package com.example.inputmonitor.platform.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.inputmonitor.MonitoringApplication
import com.example.inputmonitor.data.backup.file.BackupFileManager
import com.example.inputmonitor.data.backup.file.BackupFileResult
import com.example.inputmonitor.data.backup.logging.BackupLogger
import com.example.inputmonitor.data.backup.security.BackupTokenStorage
import com.example.inputmonitor.domain.backup.model.*
import com.example.inputmonitor.domain.backup.repository.BackupRepository
import com.example.inputmonitor.domain.backup.repository.BackupSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import java.io.File

/** Existing CSV -> repository -> HTTPS pipeline, with persisted metadata and bounded retries. */
class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    companion object {
        private val uploadMutex = Mutex()
        private const val TAG = "BACKUP_WORKER"
    }
    var dependenciesOverride: Dependencies? = null
    data class Dependencies(
        val backupFileManager: BackupFileManager,
        val backupRepository: BackupRepository,
        val backupSettingsRepository: BackupSettingsRepository,
        val tokenStorage: BackupTokenStorage
    )
    private fun resolveDependencies(): Dependencies {
        dependenciesOverride?.let { return it }
        val container = (applicationContext as MonitoringApplication).container
        return Dependencies(container.backupFileManager, container.backupRepository,
            container.backupSettingsRepository, container.backupTokenStorage)
    }

    override suspend fun doWork(): Result {
        val deps = try { resolveDependencies() } catch (_: Exception) { return Result.failure() }
        val type = runCatching { BackupType.valueOf(inputData.getString(TYPE_DATA) ?: "AUTOMATIC") }
            .getOrDefault(BackupType.AUTOMATIC)
        val settings = deps.backupSettingsRepository.getBackupSettings()
        if (type == BackupType.AUTOMATIC && !settings.enabled) return Result.success()
        // One in-process upload at a time, including overlapping automatic/manual jobs.
        uploadMutex.lock()
        try { return executeBackup(deps, type) } finally { uploadMutex.unlock() }
    }

    private suspend fun executeBackup(deps: Dependencies, type: BackupType): Result = coroutineScope {
        val previous = deps.backupSettingsRepository.backupHistory.firstOrNullRun(id.toString())
        var run = BackupRun(workId = id.toString(), backupType = type, stage = BackupStage.PREPARING,
            startedAt = previous?.takeIf { it.active }?.startedAt ?: System.currentTimeMillis(), attempt = runAttemptCount + 1)
        var file: File? = null
        suspend fun publish(stage: BackupStage, reason: String = "") {
            run = run.copy(stage = stage, failureReason = reason)
            deps.backupSettingsRepository.saveBackupRun(run)
            setProgress(run.toWorkData())
            BackupLogger.i(TAG, "stage=${stage.name} attempt=${run.attempt}")
        }
        suspend fun terminal(stage: BackupStage, reason: String = "", http: Int = 0): Result {
            run = run.copy(stage = stage, completedAt = System.currentTimeMillis(), failureReason = reason, httpStatus = http)
            deps.backupSettingsRepository.saveBackupRun(run)
            return if (stage == BackupStage.SUCCESS) Result.success(run.toWorkData()) else Result.failure(run.toWorkData())
        }
        suspend fun fail(reason: String, http: Int = 0, retry: Boolean = false, auth: Boolean = false): Result {
            deps.backupSettingsRepository.recordBackupFailure(reason, auth)
            if (retry && run.attempt < run.maxAttempts) {
                publish(BackupStage.RETRYING, reason)
                return Result.retry()
            }
            return terminal(BackupStage.FAILED, reason, http)
        }
        try {
            publish(BackupStage.PREPARING)
            deps.backupSettingsRepository.recordBackupAttempt(run.startedAt)
            if (runAttemptCount > 0 && previous?.stage in setOf(BackupStage.UPLOADING, BackupStage.WAITING_FOR_SERVER, BackupStage.CANCELLED)) {
                return@coroutineScope fail("A previous upload was interrupted. Delivery is uncertain; verify at the destination before trying again.")
            }
            val settings = deps.backupSettingsRepository.getBackupSettings()
            if (!settings.isConfigured) return@coroutineScope fail("Backup endpoint is not configured")
            deps.backupFileManager.deleteStaleBackups()
            publish(BackupStage.EXPORTING)
            val prepared = withTimeout(120_000L) {
                if (type == BackupType.TEST) deps.backupFileManager.createTestBackupFile()
                else deps.backupFileManager.createBackupFile()
            }
            if (prepared !is BackupFileResult.Success) return@coroutineScope fail("Could not generate backup CSV")
            file = prepared.file
            run = run.copy(sessionCount = prepared.sessionCount, recordCount = prepared.eventCount,
                sizeBytes = prepared.file.length(), uploadSizeBytes = prepared.file.length())
            BackupLogger.i("BACKUP_EXPORT", "sessions=${run.sessionCount} records=${run.recordCount} bytes=${run.sizeBytes}")
            publish(BackupStage.PREPARED)
            publish(BackupStage.UPLOADING)
            val metadata = BackupMetadata(prepared.file.name, run.sizeBytes, run.recordCount, run.startedAt,
                "${run.workId}-${run.startedAt}")
            // Channel serializes transport stage callbacks without writing from OkHttp's thread.
            val stages = kotlinx.coroutines.channels.Channel<BackupStage>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val observer = launch { for (stage in stages) publish(stage) }
            val result = try {
                withTimeout(130_000L) {
                    deps.backupRepository.uploadBackup(prepared.file, metadata) { stages.trySend(it) }
                }
            } finally { stages.close(); observer.join() }
            when (result) {
                is BackupResult.Success -> {
                    // A synthetic POST never advances the timestamp of a real data backup.
                    if (type != BackupType.TEST) deps.backupSettingsRepository.recordBackupSuccess(result.timestamp, result.remoteBackupId)
                    terminal(BackupStage.SUCCESS, http = result.httpStatus)
                }
                is BackupResult.AuthenticationRequired -> fail(result.message, result.httpStatus, auth = true)
                is BackupResult.PermanentFailure -> fail(result.message, result.httpStatus)
                is BackupResult.RetryableFailure -> fail(result.message, result.httpStatus, retry = result.safeToRetry)
            }
        } catch (e: TimeoutCancellationException) {
            fail("Backup timed out. Verify destination delivery before trying again.")
        } catch (e: CancellationException) {
            // WorkManager owns cancellation. Persist what happened without swallowing cancellation.
            withContext(NonCancellable) {
                run = run.copy(stage = BackupStage.CANCELLED, completedAt = System.currentTimeMillis(),
                    failureReason = "Cancelled or stopped by Android. An upload already sent may still be processed by the destination.")
                deps.backupSettingsRepository.saveBackupRun(run)
            }
            throw e
        } catch (_: Exception) {
            fail("Backup could not complete. Check the destination and try again.")
        } finally {
            file?.let { deps.backupFileManager.deleteBackupFile(it) }
        }
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<List<BackupRun>>.firstOrNullRun(id: String): BackupRun? =
    first().firstOrNull { it.workId == id }
