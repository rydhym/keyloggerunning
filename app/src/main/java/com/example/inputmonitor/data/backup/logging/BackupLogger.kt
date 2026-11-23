package com.example.inputmonitor.data.backup.logging

import android.util.Log

/**
 * Diagnostic logger for backup events ensuring privacy compliance.
 * Prohibits logging of keystrokes, CSV contents, API tokens, or encryption keys.
 */
object BackupLogger {
    private const val DEFAULT_TAG = "InputMonitor-Backup"

    fun d(tag: String = DEFAULT_TAG, message: String) {
        try {
            Log.d(tag, message)
        } catch (_: RuntimeException) {
            println("[$tag] $message")
        }
    }

    fun i(tag: String = DEFAULT_TAG, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: RuntimeException) {
            println("[$tag] $message")
        }
    }

    fun w(tag: String = DEFAULT_TAG, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: RuntimeException) {
            println("[$tag] $message")
        }
    }

    fun e(tag: String = DEFAULT_TAG, message: String, throwable: Throwable? = null) {
        try {
            Log.e(tag, message, throwable)
        } catch (_: RuntimeException) {
            println("[$tag] $message: ${throwable?.message}")
        }
    }
}
