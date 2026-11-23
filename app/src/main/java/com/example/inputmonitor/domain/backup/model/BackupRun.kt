package com.example.inputmonitor.domain.backup.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.Locale

enum class BackupStage(val percent: Int, val label: String) {
    IDLE(0, "Ready to back up"), QUEUED(5, "Backup queued"),
    WAITING_FOR_NETWORK(5, "Waiting for internet connection"),
    WAITING_FOR_WIFI(5, "Waiting for unmetered Wi-Fi"),
    PREPARING(15, "Preparing backup…"), EXPORTING(30, "Exporting interaction data…"),
    PREPARED(45, "Backup ready"), UPLOADING(60, "Uploading backup…"),
    WAITING_FOR_SERVER(90, "Upload sent · Waiting for destination…"),
    RETRYING(5, "Retrying backup…"), SUCCESS(100, "✓ Backup completed successfully"),
    FAILED(0, "⚠ Backup failed"), CANCELLED(0, "Backup cancelled")
}

enum class BackupType { MANUAL, AUTOMATIC, TEST }

/** Metadata only. Never add payload, destination URLs, identifiers from responses, or credentials. */
data class BackupRun(
    val workId: String = "",
    val backupType: BackupType = BackupType.MANUAL,
    val stage: BackupStage = BackupStage.IDLE,
    val startedAt: Long = 0,
    val completedAt: Long = 0,
    val sessionCount: Int = 0,
    val recordCount: Long = 0,
    val sizeBytes: Long = 0,
    val uploadSizeBytes: Long = 0,
    val attempt: Int = 1,
    val httpStatus: Int = 0,
    val failureReason: String = "",
    val statusMessage: String = "",
    val nextEligibleAt: Long = 0
) {
    val id: String get() = "$workId:$startedAt"
    val maxAttempts: Int get() = 3
    val durationMs: Long get() = if (completedAt > 0 && startedAt > 0) (completedAt - startedAt).coerceAtLeast(0) else 0
    val active: Boolean get() = stage !in setOf(BackupStage.IDLE, BackupStage.SUCCESS, BackupStage.FAILED, BackupStage.CANCELLED)
    val retryCount: Int get() = (attempt - 1).coerceAtLeast(0)
    val label: String get() = statusMessage.ifBlank { stage.label }
}

fun formatBackupBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Bounded, versioned metadata serialization; works on Android and in JVM tests. */
object BackupRunCodec {
    fun encode(runs: List<BackupRun>): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(1)
            val bounded = runs.take(100)
            out.writeInt(bounded.size)
            bounded.forEach { run ->
                out.writeUTF(run.workId); out.writeUTF(run.backupType.name); out.writeUTF(run.stage.name)
                out.writeLong(run.startedAt); out.writeLong(run.completedAt)
                out.writeInt(run.sessionCount); out.writeLong(run.recordCount)
                out.writeLong(run.sizeBytes); out.writeLong(run.uploadSizeBytes)
                out.writeInt(run.attempt); out.writeInt(run.httpStatus)
                out.writeUTF(run.failureReason.take(512)); out.writeUTF(run.statusMessage.take(512))
                out.writeLong(run.nextEligibleAt)
            }
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }

    fun decode(value: String?): List<BackupRun> = runCatching {
        if (value.isNullOrEmpty()) return emptyList()
        require(value.length <= 300_000)
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
            require(input.readInt() == 1)
            val count = input.readInt().also { require(it in 0..100) }
            List(count) {
                BackupRun(input.readUTF(), BackupType.valueOf(input.readUTF()), BackupStage.valueOf(input.readUTF()),
                    input.readLong(), input.readLong(), input.readInt(), input.readLong(), input.readLong(),
                    input.readLong(), input.readInt(), input.readInt(), input.readUTF(), input.readUTF(), input.readLong())
            }
        }
    }.getOrDefault(emptyList())

    fun upsert(runs: List<BackupRun>, run: BackupRun): List<BackupRun> =
        (runs.filterNot { it.id == run.id } + run).sortedByDescending { it.startedAt }.take(100)
}
