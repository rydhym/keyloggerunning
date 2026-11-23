package com.example.inputmonitor.platform.backup

import androidx.work.WorkInfo
import com.example.inputmonitor.domain.backup.model.*
import org.junit.Assert.*
import org.junit.Test

class BackupWorkStateTest {
    private val connected = BackupNetwork(connected = true, metered = false, wifi = true)
    private val run = BackupRun(workId = "one", stage = BackupStage.QUEUED)
    private fun map(state: WorkInfo.State, network: BackupNetwork = connected, wifi: Boolean = true,
                    attempts: Int = 0, value: BackupRun = run) = mapBackupWork(state, value, network, wifi, attempts)

    @Test fun idleHasNoProgressOrActiveWork() {
        assertEquals(0, BackupRun().stage.percent); assertFalse(BackupRun().active)
    }
    @Test fun queuedIsNotUploading() {
        assertEquals(BackupStage.QUEUED, map(WorkInfo.State.ENQUEUED).stage)
    }
    @Test fun blockedIsQueuedWithPrerequisiteExplanation() {
        assertTrue(map(WorkInfo.State.BLOCKED).label.contains("prerequisite"))
    }
    @Test fun disconnectedWaitsForInternetEvenWhenWifiOnlyEnabled() {
        assertEquals(BackupStage.WAITING_FOR_NETWORK, map(WorkInfo.State.ENQUEUED, BackupNetwork()).stage)
    }
    @Test fun meteredWifiWaitsAndExplainsWhy() {
        val state = map(WorkInfo.State.ENQUEUED, connected.copy(metered = true))
        assertEquals(BackupStage.WAITING_FOR_WIFI, state.stage)
        assertTrue(state.label.contains("metered")); assertTrue(state.active)
    }
    @Test fun wifiOnlyOffAcceptsMeteredWifiWithoutCellular() {
        assertEquals(BackupStage.QUEUED, map(WorkInfo.State.ENQUEUED, connected.copy(metered = true), false).stage)
    }
    @Test fun wifiOnlyAcceptsOtherUnmeteredTransportsAsDocumented() {
        assertEquals(BackupStage.QUEUED, map(WorkInfo.State.ENQUEUED, connected.copy(wifi = false)).stage)
    }
    @Test fun reconnectLeavesWaitingState() {
        assertEquals(BackupStage.WAITING_FOR_NETWORK, map(WorkInfo.State.ENQUEUED, BackupNetwork()).stage)
        assertEquals(BackupStage.QUEUED, map(WorkInfo.State.ENQUEUED).stage)
    }
    @Test fun runningStartsAtPreparing() {
        assertEquals(BackupStage.PREPARING, map(WorkInfo.State.RUNNING).stage)
    }
    @Test fun runningPreservesExportUploadAndServerStages() {
        listOf(BackupStage.EXPORTING, BackupStage.PREPARED, BackupStage.UPLOADING, BackupStage.WAITING_FOR_SERVER).forEach {
            assertEquals(it, map(WorkInfo.State.RUNNING, value = run.copy(stage = it)).stage)
        }
    }
    @Test fun retriesShowNextAttemptRatherThanUploading() {
        val next = map(WorkInfo.State.ENQUEUED, attempts = 1)
        assertEquals(BackupStage.RETRYING, next.stage); assertEquals(2, next.attempt)
        assertTrue(next.label.contains("2 of 3"))
    }
    @Test fun onlySuccessfulTerminalWorkReaches100Percent() {
        assertEquals(100, map(WorkInfo.State.SUCCEEDED).stage.percent)
        assertTrue(map(WorkInfo.State.FAILED, value = run.copy(stage = BackupStage.WAITING_FOR_SERVER)).stage.percent < 100)
        assertTrue(BackupStage.entries.filter { it != BackupStage.SUCCESS }.all { it.percent < 100 })
    }
    @Test fun cancelledAndFailedAreUsableTerminalStates() {
        assertFalse(map(WorkInfo.State.CANCELLED).active); assertFalse(map(WorkInfo.State.FAILED).active)
    }
    @Test fun backgroundRestrictionHasActionableQueuedExplanation() {
        assertTrue(map(WorkInfo.State.ENQUEUED, connected.copy(backgroundRestricted = true)).label.contains("battery settings"))
    }
    @Test fun powerSavingExplainsDeferral() {
        assertTrue(map(WorkInfo.State.ENQUEUED, connected.copy(deviceIdle = true)).label.contains("power management"))
    }
    @Test fun metadataSurvivesTerminalMapping() {
        val rich = run.copy(sizeBytes = 12742, recordCount = 58, sessionCount = 1, httpStatus = 200)
        val terminal = map(WorkInfo.State.SUCCEEDED, value = rich)
        assertEquals(12742L, terminal.sizeBytes); assertEquals(58L, terminal.recordCount)
    }
}
