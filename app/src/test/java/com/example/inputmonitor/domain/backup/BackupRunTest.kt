package com.example.inputmonitor.domain.backup

import com.example.inputmonitor.domain.backup.model.*
import org.junit.Assert.*
import org.junit.Test

class BackupRunTest {
    @Test fun metadataRoundTripsAcrossRecreation() {
        val run = BackupRun("work-id", BackupType.TEST, BackupStage.SUCCESS, 100, 150,
            1, 58, 12742, 12742, 2, 200)
        assertEquals(listOf(run), BackupRunCodec.decode(BackupRunCodec.encode(listOf(run))))
        assertEquals(50L, run.durationMs); assertEquals(1, run.retryCount)
    }
    @Test fun invalidHistoryIsIgnoredSafely() {
        assertTrue(BackupRunCodec.decode("invalid metadata").isEmpty())
    }
    @Test fun historyUpdatesSameRunAndKeepsDifferentPeriodicRuns() {
        val first = BackupRun("periodic", startedAt = 100, stage = BackupStage.EXPORTING)
        val history = BackupRunCodec.upsert(listOf(first), first.copy(stage = BackupStage.SUCCESS))
        assertEquals(1, history.size)
        assertEquals(2, BackupRunCodec.upsert(history, first.copy(startedAt = 200)).size)
    }
    @Test fun historyIsBoundedTo100MostRecentRuns() {
        val runs = (1L..150).map { BackupRun("work", startedAt = it) }
        assertEquals(100, BackupRunCodec.decode(BackupRunCodec.encode(runs)).size)
        val updated = BackupRunCodec.upsert(runs, BackupRun("new", startedAt = 200))
        assertEquals(100, updated.size); assertEquals(200L, updated.first().startedAt)
    }
    @Test fun schemaDoesNotContainPayloadUrlOrCredentials() {
        val fields = BackupRun::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { it.contains("token") || it.contains("csv") || it.contains("payload") || it.contains("endpoint") || it.contains("key") })
    }
    @Test fun byteFormatterUsesBytesAndBinaryUnits() {
        assertEquals("842 B", formatBackupBytes(842))
        assertTrue(formatBackupBytes(12742).endsWith("KB"))
        assertTrue(formatBackupBytes(1887437).endsWith("MB"))
    }
}
