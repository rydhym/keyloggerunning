package com.example.inputmonitor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.inputmonitor.domain.backup.model.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun backupDate(time: Long): String = if (time <= 0) "Never" else
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(time))

@Composable
internal fun BackupStatusCard(
    uiState: SettingsUiState,
    onAutomaticBackupToggle: (Boolean) -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit,
    onTriggerBackupNow: () -> Unit,
    onSendTestBackup: () -> Unit,
    onTestConnection: () -> Unit,
    onCancel: () -> Unit,
    onRequestConnectBackup: () -> Unit,
    onRequestDisconnectBackup: () -> Unit
) {
    val settings = uiState.backupSettings
    val run = uiState.backupRun
    var selected by remember { mutableStateOf<BackupRun?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var showDestination by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val destination = if (runCatching { java.net.URI(settings.providerEndpoint.orEmpty()).host }
        .getOrNull() == "script.google.com") "Google Apps Script" else "Custom HTTPS Endpoint"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CLOUD BACKUP", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("DESTINATION", style = MaterialTheme.typography.labelMedium)
            Text(if (settings.isConfigured) destination else "Not configured")
            if (settings.isConfigured) {
                Text(uiState.connectionStatus, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showDestination = true }) { Text("View destination details") }
            }
            HorizontalDivider()
            Text("BACKUP STATUS", style = MaterialTheme.typography.labelMedium)
            Text(if (run.backupType == BackupType.TEST && run.stage == BackupStage.SUCCESS)
                "✓ Test backup uploaded successfully" else run.label)
            if (run.stage != BackupStage.IDLE) {
                LinearProgressIndicator(progress = { run.stage.percent / 100f }, modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Backup progress, ${run.stage.percent} percent, ${run.stage.label}"
                    liveRegion = LiveRegionMode.Polite
                })
                Text("${run.stage.percent}% · Stage-based progress", style = MaterialTheme.typography.bodySmall)
            }
            if (run.sizeBytes > 0) {
                Text("${run.sessionCount} sessions · ${run.recordCount} records · ${formatBackupBytes(run.sizeBytes)}")
                if (run.uploadSizeBytes != run.sizeBytes) Text("Upload size: ${formatBackupBytes(run.uploadSizeBytes)}")
            }
            if (run.completedAt > 0 && run.startedAt > 0) Text(String.format(Locale.getDefault(), "Duration: %.2f sec", run.durationMs / 1000.0))
            if (run.failureReason.isNotBlank()) Text(run.failureReason, color = MaterialTheme.colorScheme.error)
            if (run.attempt > 1) Text("Attempt ${run.attempt} of ${run.maxAttempts}")
            if (run.workId.isNotEmpty()) TextButton(onClick = { selected = run }) { Text("Show details") }
            if (uiState.isManualBackupRunning) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Text("Cancellation stops local work. A request already sent may still complete at the destination.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Last successful backup: ${backupDate(settings.lastSuccessfulBackupAt ?: 0)}")
            val lastAttempt = uiState.backupHistory.firstOrNull()
            Text("Last attempt: ${backupDate(settings.lastAttemptAt ?: 0)}")
            lastAttempt?.let { Text("${it.backupType.name.lowercase().replaceFirstChar(Char::uppercase)} · ${it.stage.label}", style = MaterialTheme.typography.bodySmall) }
            if (!settings.isConfigured) {
                Button(onClick = onRequestConnectBackup) { Text("Connect Cloud Destination") }
            } else {
                Button(onClick = onTriggerBackupNow, enabled = !uiState.isManualBackupRunning,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (run.stage == BackupStage.FAILED) "Try Again" else "Back Up Now")
                }
                OutlinedButton(onClick = onSendTestBackup, enabled = !uiState.isManualBackupRunning) { Text("Send Test Backup") }
                OutlinedButton(onClick = onTestConnection, enabled = !uiState.isTestingConnection) {
                    Text(if (uiState.isTestingConnection) "Testing connection…" else "Test Connection")
                }
                Text("Test Connection checks GET reachability only. Send Test Backup uses synthetic data through the real POST pipeline.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRequestDisconnectBackup) { Text("Disconnect") }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic Daily Backup", fontWeight = FontWeight.Medium)
                    Text("Runs approximately every 24 hours when Android permits.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.enabled, onCheckedChange = onAutomaticBackupToggle)
            }
            val automatic = uiState.backupHistory.firstOrNull { it.backupType == BackupType.AUTOMATIC && it.stage == BackupStage.SUCCESS }
            Text("Last automatic backup: ${backupDate(automatic?.completedAt ?: 0)}", style = MaterialTheme.typography.bodySmall)
            if (settings.enabled) {
                val scheduled = uiState.automaticBackupRun
                Text("Automatic schedule: ${scheduled?.label ?: "Waiting for schedule information"}", style = MaterialTheme.typography.bodySmall)
                Text("Next eligible backup: ${scheduled?.nextEligibleAt?.takeIf { it > 0 }?.let { "approximately ${backupDate(it)}" } ?: "when Android permits"}. Network, battery, or system restrictions may defer it.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi Only", fontWeight = FontWeight.Medium)
                    Text("Applies to manual, test, and automatic backups. Uses Android's unmetered-network rule: metered Wi-Fi waits, and other unmetered networks also qualify. No cellular connection is required.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.wifiOnly, onCheckedChange = onWifiOnlyToggle, enabled = !uiState.isManualBackupRunning)
            }
            HorizontalDivider()
            Text("BACKUP HISTORY", fontWeight = FontWeight.Bold)
            val history = uiState.backupHistory.filter { !it.active }
            if (history.isEmpty()) Text("No completed backups yet")
            history.take(if (showAll) 100 else 3).forEach { item ->
                Column(Modifier.fillMaxWidth().clickable { selected = item }.padding(vertical = 12.dp)) {
                    Text("${item.stage.label} · ${backupDate(item.completedAt)}")
                    Text("${item.backupType.name} · ${item.recordCount} records · ${formatBackupBytes(item.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                    if (item.httpStatus > 0) Text("HTTP ${item.httpStatus}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (history.size > 3) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "View All") }
            Text("History keeps the latest 100 attempts and stores metadata only. CSV is sent over HTTPS; the exported CSV file itself is not encrypted.", style = MaterialTheme.typography.bodySmall)
        }
    }
    selected?.let { item ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Backup Details") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.stage.label)
                Text("Type: ${item.backupType}")
                Text("Started: ${backupDate(item.startedAt)}")
                Text("Completed: ${backupDate(item.completedAt)}")
                Text("Duration: ${item.durationMs} ms")
                Text("Sessions: ${item.sessionCount} · Records: ${item.recordCount}")
                Text("Export size: ${formatBackupBytes(item.sizeBytes)}")
                Text("Upload size: ${formatBackupBytes(item.uploadSizeBytes)}")
                Text("HTTP status: ${item.httpStatus.takeIf { it > 0 } ?: "Not received"}")
                Text("Retries: ${item.retryCount}")
                Text("Work ID: ${item.workId}")
                if (item.failureReason.isNotBlank()) Text(item.failureReason)
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
    if (showDestination) AlertDialog(onDismissRequest = { showDestination = false },
        title = { Text(destination) }, text = { Text("HTTPS destination configured. Credentials and URL query values are hidden.") },
        confirmButton = { TextButton(onClick = { showDestination = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = {
            clipboard.setText(AnnotatedString(settings.providerEndpoint.orEmpty()))
        }) { Text("Copy URL") } })
}
