package com.example.inputmonitor.domain.backup.model

/**
 * Result of an automatic cloud backup transmission.
 *
 * Distinguishes retryable transient failures from permanent errors and authentication revocations.
 */
sealed class BackupResult {
    data class Success(
        val remoteBackupId: String,
        val timestamp: Long = System.currentTimeMillis(),
        val bytesTransferred: Long = 0L,
        val httpStatus: Int = 200
    ) : BackupResult()

    data class RetryableFailure(
        val message: String,
        val cause: Throwable? = null,
        val httpStatus: Int = 0,
        val safeToRetry: Boolean = true
    ) : BackupResult()

    data class PermanentFailure(
        val message: String,
        val cause: Throwable? = null,
        val httpStatus: Int = 0
    ) : BackupResult()

    data class AuthenticationRequired(
        val message: String,
        val httpStatus: Int = 401
    ) : BackupResult()
}
