package com.example.inputmonitor.platform.backup

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.work.Data
import androidx.work.WorkInfo
import com.example.inputmonitor.domain.backup.model.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal const val RUN_DATA = "backup_run_v1"
internal const val TYPE_DATA = "backup_type"
internal const val WIFI_DATA = "backup_wifi_only"
internal const val QUEUED_DATA = "backup_queued_at"

internal fun BackupRun.toWorkData(): Data = Data.Builder()
    .putString(RUN_DATA, BackupRunCodec.encode(listOf(this))).build()
internal fun Data.backupRun(): BackupRun? = BackupRunCodec.decode(getString(RUN_DATA)).firstOrNull()

data class BackupNetwork(
    val connected: Boolean = false,
    val metered: Boolean = true,
    val wifi: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val powerSave: Boolean = false,
    val deviceIdle: Boolean = false
)

internal fun observeBackupNetwork(context: Context) = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    fun snapshot(): BackupNetwork {
        val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork)
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        return BackupNetwork(
            connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = cm?.isActiveNetworkMetered != false,
            wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            backgroundRestricted = activity?.isBackgroundRestricted == true,
            powerSave = power?.isPowerSaveMode == true,
            deviceIdle = power?.isDeviceIdleMode == true
        )
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(snapshot()) }
        override fun onLost(network: Network) { trySend(snapshot()) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(snapshot()) }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) { trySend(snapshot()) }
    }
    val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) { trySend(snapshot()) }
    }
    val filter = android.content.IntentFilter().apply {
        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
    }
    androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter,
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    cm?.registerDefaultNetworkCallback(callback)
    trySend(snapshot())
    awaitClose { cm?.unregisterNetworkCallback(callback); context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

/** Pure mapping, also used by tests; ENQUEUED does not mean the upload has started. */
fun mapBackupWork(state: WorkInfo.State, run: BackupRun, network: BackupNetwork,
                  wifiOnly: Boolean, attempts: Int): BackupRun {
    val stage = when (state) {
        WorkInfo.State.SUCCEEDED -> if (run.stage == BackupStage.IDLE) BackupStage.IDLE else BackupStage.SUCCESS
        WorkInfo.State.FAILED -> BackupStage.FAILED
        WorkInfo.State.CANCELLED -> BackupStage.CANCELLED
        WorkInfo.State.RUNNING -> if (run.stage in setOf(BackupStage.IDLE, BackupStage.QUEUED, BackupStage.RETRYING))
            BackupStage.PREPARING else run.stage
        WorkInfo.State.BLOCKED -> BackupStage.QUEUED
        WorkInfo.State.ENQUEUED -> when {
            !network.connected -> BackupStage.WAITING_FOR_NETWORK
            wifiOnly && network.metered -> BackupStage.WAITING_FOR_WIFI
            attempts > 0 -> BackupStage.RETRYING
            else -> BackupStage.QUEUED
        }
    }
    val explanation = when {
        stage == BackupStage.WAITING_FOR_NETWORK -> "Backup will start automatically when an internet connection becomes available."
        stage == BackupStage.WAITING_FOR_WIFI -> if (network.wifi)
            "Android considers this Wi-Fi connection metered. Wi-Fi Only is enabled; waiting for an eligible unmetered connection."
            else "Wi-Fi Only is enabled. Backup will start automatically when an eligible unmetered connection becomes available."
        stage == BackupStage.RETRYING -> "Temporary failure. Waiting for WorkManager retry; attempt ${attempts + 1} of 3."
        state == WorkInfo.State.BLOCKED -> "Waiting for prerequisite work."
        stage == BackupStage.QUEUED && network.backgroundRestricted -> "Android reports background restrictions. Check this app's battery settings if work stays queued."
        stage == BackupStage.QUEUED && (network.powerSave || network.deviceIdle) -> "Android power management may defer this job. It will run when Android permits."
        stage == BackupStage.QUEUED -> "Waiting for Android to schedule this job. Automatic jobs also wait if battery is low."
        else -> ""
    }
    return run.copy(stage = stage, attempt = maxOf(run.attempt, attempts + if (state == WorkInfo.State.ENQUEUED) 1 else 0),
        statusMessage = if (explanation.isEmpty()) "" else "${stage.label}\n$explanation")
}
