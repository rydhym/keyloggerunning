package com.example.inputmonitor.platform.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.example.inputmonitor.domain.backup.model.*
import java.util.concurrent.TimeUnit

/**
 * Interface defining operations for scheduling and triggering background cloud backups.
 */
interface AutoBackupScheduler {
    fun schedulePeriodicBackup(wifiOnly: Boolean)
    fun triggerImmediateBackup(wifiOnly: Boolean)
    fun cancelPeriodicBackup()
    val isManualBackupActive: Flow<Boolean> get() = flowOf(false)
    val backupRuns: Flow<List<BackupRun>> get() = flowOf(emptyList())
    fun triggerTestBackup(wifiOnly: Boolean) { triggerImmediateBackup(wifiOnly) }
    fun cancelManualBackup() {}
    suspend fun enqueueBackup(wifiOnly: Boolean, test: Boolean) {
        if (test) triggerTestBackup(wifiOnly) else triggerImmediateBackup(wifiOnly)
    }
}

/**
 * WorkManager implementation guaranteeing unique periodic scheduling (every 24 hours)
 * and single-instance manual backups without worker duplication.
 */
class WorkManagerAutoBackupScheduler(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context)
) : AutoBackupScheduler {

    companion object {
        const val UNIQUE_PERIODIC_WORK_NAME = "inputmonitor_auto_backup_periodic"
        const val UNIQUE_ONE_TIME_WORK_NAME = "inputmonitor_auto_backup_immediate"
        const val BACKUP_INTERVAL_HOURS = 24L
    }

    override val isManualBackupActive: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_ONE_TIME_WORK_NAME)
            .map { list ->
                list.any { !it.state.isFinished }
            }

    override val backupRuns: Flow<List<BackupRun>> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_ONE_TIME_WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_PERIODIC_WORK_NAME),
        observeBackupNetwork(context)
    ) { manual, automatic, network ->
        (manual + automatic).map { info ->
            val base = info.outputData.backupRun() ?: info.progress.backupRun() ?: BackupRun(
                workId = info.id.toString(),
                backupType = when {
                    info.tags.contains("backup_TEST") -> BackupType.TEST
                    info.tags.contains("backup_AUTOMATIC") || info in automatic -> BackupType.AUTOMATIC
                    else -> BackupType.MANUAL
                },
                stage = if (info.state == WorkInfo.State.SUCCEEDED) BackupStage.IDLE else BackupStage.QUEUED
            )
            mapBackupWork(info.state, base, network,
                info.constraints.requiredNetworkType == NetworkType.UNMETERED, info.runAttemptCount).copy(
                nextEligibleAt = info.nextScheduleTimeMillis.takeIf { it > 0 && it < Long.MAX_VALUE } ?: 0)
        }
    }

    override fun schedulePeriodicBackup(wifiOnly: Boolean) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            BACKUP_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .addTag("backup_AUTOMATIC")
            .setInputData(androidx.work.workDataOf(TYPE_DATA to BackupType.AUTOMATIC.name, WIFI_DATA to wifiOnly))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        // ExistingPeriodicWorkPolicy.UPDATE ensures toggling settings safely replaces the job without duplicates
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    override fun triggerImmediateBackup(wifiOnly: Boolean) {
        enqueueManual(wifiOnly, BackupType.MANUAL)
    }

    override fun triggerTestBackup(wifiOnly: Boolean) { enqueueManual(wifiOnly, BackupType.TEST) }

    override suspend fun enqueueBackup(wifiOnly: Boolean, test: Boolean) {
        val operation = enqueueManual(wifiOnly, if (test) BackupType.TEST else BackupType.MANUAL)
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            operation.result.addListener({
                try {
                    operation.result.get()
                    continuation.resumeWith(kotlin.Result.success(Unit))
                } catch (e: Exception) {
                    continuation.resumeWith(kotlin.Result.failure(e))
                }
            }, java.util.concurrent.Executor { it.run() })
        }
    }

    private fun enqueueManual(wifiOnly: Boolean, type: BackupType): androidx.work.Operation {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .addTag("backup_${type.name}")
            .setInputData(androidx.work.workDataOf(TYPE_DATA to type.name, WIFI_DATA to wifiOnly,
                QUEUED_DATA to System.currentTimeMillis()))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        // ExistingWorkPolicy.KEEP prevents duplicate concurrent manual backup jobs
        return workManager.enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest
        )
    }

    override fun cancelManualBackup() {
        workManager.cancelUniqueWork(UNIQUE_ONE_TIME_WORK_NAME)
    }

    override fun cancelPeriodicBackup() {
        workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
    }
}
