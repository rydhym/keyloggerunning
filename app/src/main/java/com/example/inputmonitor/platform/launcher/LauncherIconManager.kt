package com.example.inputmonitor.platform.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log

/**
 * Manages launcher icon visibility for the application.
 * 
 * This class provides a safe, user-initiated way to show/hide the launcher icon.
 * The actual application components remain visible in Android Settings > Apps.
 * 
 * Uses an activity-alias to control the launcher entry point without disabling
 * the main application or any background services.
 */
object LauncherIconManager {

    private const val TAG = "LauncherIconManager"
    
    // Component name must match android:name in AndroidManifest.xml
    // The android:name uses full package name: .InputMonitorLauncher resolves to com.example.inputmonitor.InputMonitorLauncher
    private const val LAUNCHER_ALIAS_NAME = "com.example.inputmonitor.InputMonitorLauncher"

    /**
     * Checks if the launcher icon is currently visible.
     * 
     * @param context The application context
     * @return true if the launcher icon is visible, false otherwise
     */
    fun isLauncherIconVisible(context: Context): Boolean {
        val pm = context.packageManager
        val componentName = ComponentName(context, LAUNCHER_ALIAS_NAME)
        val result = pm.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        Log.d(TAG, "isLauncherIconVisible: $result for $LAUNCHER_ALIAS_NAME")
        return result
    }

    /**
     * Hides the launcher icon.
     * 
     * The app remains fully installed and functional:
     * - Visible in Android Settings > Apps
     * - Background services continue to work
     * - Accessibility service continues to work
     * - Data storage is unaffected
     * 
     * The user can restore the icon via Settings > App Icon Visibility.
     * 
     * @param context The application context
     */
    fun hideLauncherIcon(context: Context) {
        val pm = context.packageManager
        val componentName = ComponentName(context, LAUNCHER_ALIAS_NAME)
        val currentState = pm.getComponentEnabledSetting(componentName)
        Log.d(TAG, "hideLauncherIcon: current state = $currentState, changing to DISABLED")
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0
        )
        Log.d(TAG, "hideLauncherIcon: icon hidden, killing process to refresh launcher")
        Process.killProcess(Process.myPid())
    }

    /**
     * Shows (restores) the launcher icon.
     * 
     * @param context The application context
     */
    fun showLauncherIcon(context: Context) {
        val pm = context.packageManager
        val componentName = ComponentName(context, LAUNCHER_ALIAS_NAME)
        val currentState = pm.getComponentEnabledSetting(componentName)
        Log.d(TAG, "showLauncherIcon: current state = $currentState, changing to ENABLED")
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        Log.d(TAG, "showLauncherIcon: icon shown")
    }
}
