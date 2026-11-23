package com.example.inputmonitor.data.backup.provider

import com.example.inputmonitor.core.DispatcherProvider
import com.example.inputmonitor.data.backup.logging.BackupLogger
import com.example.inputmonitor.data.backup.security.BackupTokenStorage
import com.example.inputmonitor.domain.backup.model.BackupMetadata
import com.example.inputmonitor.domain.backup.model.BackupResult
import com.example.inputmonitor.domain.backup.repository.BackupRepository
import com.example.inputmonitor.domain.backup.repository.BackupSettingsRepository
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import com.example.inputmonitor.domain.backup.model.BackupStage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.RequestBody
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production-grade HTTPS REST/WebDAV backup provider using OkHttp.
 *
 * Security & Reliability Guarantees:
 * - Enforces HTTPS/TLS (cleartext HTTP rejected).
 * - Uses standard Android OS X.509 certificate validation.
 * - Sends RFC-4180 CSV backup directly as non-empty POST body (text/csv; charset=utf-8)
 *   compatible with Google Apps Script doPost(e) and REST endpoints.
 * - Supports automatic HTTPS redirect handling (e.g. Google Apps Script 302 -> echo).
 * - Memory-safe streaming upload directly from disk (O(1) heap).
 * - Prohibits logging of credentials, keys, or backup contents.
 */
class HttpBackupProvider(
    private val backupSettingsRepository: BackupSettingsRepository,
    private val tokenStorage: BackupTokenStorage,
    private val dispatchers: DispatcherProvider,
    private val okHttpClient: OkHttpClient = defaultClient()
) : BackupRepository {

    companion object {
        private const val TAG = "HttpBackupProvider"
        const val MIME_TYPE_CSV = "text/csv; charset=utf-8"

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .callTimeout(120, TimeUnit.SECONDS)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    override suspend fun uploadBackup(file: File, metadata: BackupMetadata): BackupResult =
        uploadBackup(file, metadata) {}

    override suspend fun uploadBackup(file: File, metadata: BackupMetadata,
        onStage: (BackupStage) -> Unit): BackupResult = withContext(dispatchers.io) {
        BackupLogger.i(TAG, "Cloud backup started")

        val settings = backupSettingsRepository.getBackupSettings()
        val endpoint = settings.providerEndpoint

        if (endpoint.isNullOrBlank()) {
            val msg = "Backup provider endpoint is not configured"
            BackupLogger.w(TAG, msg)
            return@withContext BackupResult.PermanentFailure(msg)
        }

        if (!endpoint.startsWith("https://", ignoreCase = true) && !isLocalTestHost(endpoint)) {
            val msg = "Cleartext HTTP is prohibited. Destination URL must use HTTPS."
            BackupLogger.w(TAG, msg)
            return@withContext BackupResult.PermanentFailure(msg)
        }

        if (!file.exists()) {
            val msg = "Backup file does not exist: ${file.name}"
            BackupLogger.w(TAG, msg)
            return@withContext BackupResult.PermanentFailure(msg)
        }

        val fileLength = file.length()
        if (fileLength == 0L) {
            val msg = "Backup payload is empty (0 bytes). Upload aborted to avoid bodyless POST."
            BackupLogger.w(TAG, msg)
            return@withContext BackupResult.PermanentFailure(msg)
        }

        BackupLogger.i(TAG, "Backup payload generated: $fileLength bytes")

        val token = tokenStorage.getAuthToken()

        val mediaType = MIME_TYPE_CSV.toMediaType()
        var bodyStarted = false
        val delegate = file.asRequestBody(mediaType)
        val requestBody = object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            // Never replay a POST body automatically after an ambiguous transport failure.
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                bodyStarted = true
                onStage(BackupStage.UPLOADING)
                delegate.writeTo(sink)
                sink.flush()
                onStage(BackupStage.WAITING_FOR_SERVER)
            }
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("User-Agent", "InputMonitor-AutoBackup/1.0")
            .header("Content-Type", MIME_TYPE_CSV)
            .header("X-Backup-Filename", metadata.fileName)
            .header("X-Backup-Event-Count", metadata.eventCount.toString())
            .header("X-Backup-Timestamp", metadata.timestamp.toString())
            .header("Idempotency-Key", metadata.backupId)

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        BackupLogger.i(TAG, "Sending HTTPS POST")

        try {
            val response = awaitResponse(requestBuilder.build())
            response.use { res ->
                val code = res.code
                BackupLogger.i(TAG, "Response status: $code")

                when {
                    res.isSuccessful -> {
                        val bodyString = try {
                            res.body?.string()?.trim() ?: ""
                        } catch (_: Exception) {
                            throw IOException("Could not read destination response")
                        }

                        if (isExplicitScriptError(bodyString)) {
                            BackupLogger.w(TAG, "Destination reported an application error")
                            return@withContext BackupResult.PermanentFailure("Destination reported a backup processing error", httpStatus = code)
                        }

                        val remoteId = res.header("X-Backup-Id")
                            ?: res.header("ETag")?.trim('"')
                            ?: extractRemoteIdFromBody(bodyString)
                            ?: "backup_${metadata.timestamp}"

                        BackupLogger.i(TAG, "Cloud backup completed")
                        BackupResult.Success(
                            remoteBackupId = remoteId,
                            timestamp = System.currentTimeMillis(),
                            bytesTransferred = fileLength,
                            httpStatus = code
                        )
                    }
                    code == 401 || code == 403 -> {
                        BackupLogger.w(TAG, "Destination rejected authentication (HTTP $code)")
                        BackupResult.AuthenticationRequired("Authentication failed. Check the API key or bearer token.", code)
                    }
                    code == 408 || code == 429 || code in 500..599 -> {
                        BackupLogger.w(TAG, "Upload failed status=$code")
                        BackupResult.RetryableFailure(httpFailureMessage(code), httpStatus = code, safeToRetry = false)
                    }
                    else -> {
                        BackupLogger.w(TAG, "Upload rejected status=$code")
                        BackupResult.PermanentFailure(httpFailureMessage(code), httpStatus = code)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            BackupLogger.w(TAG, "Secure connection failed")
            BackupResult.PermanentFailure("Secure connection failed. The destination's HTTPS certificate could not be verified.")
        } catch (e: java.io.InterruptedIOException) {
            BackupLogger.w(TAG, "Upload timed out")
            BackupResult.RetryableFailure("Backup timed out. The destination did not respond in time." +
                if (bodyStarted) " Delivery is uncertain; verify before trying again." else "", safeToRetry = !bodyStarted)
        } catch (e: IOException) {
            BackupLogger.w(TAG, "Network upload failed")
            BackupResult.RetryableFailure("Network connection failed." +
                if (bodyStarted) " Delivery is uncertain; verify before trying again." else " Connect to the internet and try again.", safeToRetry = !bodyStarted)
        } catch (t: Throwable) {
            BackupLogger.w(TAG, "Unexpected upload failure")
            BackupResult.PermanentFailure("Backup could not be uploaded.")
        }
    }

    override suspend fun testConnection(endpoint: String, token: String?): BackupResult = withContext(dispatchers.io) {
        if (endpoint.isBlank()) {
            return@withContext BackupResult.PermanentFailure("Endpoint URL cannot be empty")
        }

        if (!endpoint.startsWith("https://", ignoreCase = true) && !isLocalTestHost(endpoint)) {
            return@withContext BackupResult.PermanentFailure("Cleartext HTTP is prohibited. Destination URL must use HTTPS.")
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "InputMonitor-AutoBackup/1.0")

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
            val response = awaitResponse(requestBuilder.build())
            response.use { res ->
                val code = res.code
                when {
                    res.isSuccessful || code == 405 -> {
                        // 200..299 or 405 Method Not Allowed confirms reachable & endpoint responds
                        BackupResult.Success("connection_verified")
                    }
                    code == 401 || code == 403 -> {
                        BackupResult.AuthenticationRequired("Authentication failed (HTTP $code)")
                    }
                    code in 500..599 || code == 408 || code == 429 -> {
                        BackupResult.RetryableFailure("Server transient error (HTTP $code)")
                    }
                    else -> {
                        BackupResult.PermanentFailure("Connection test failed (HTTP $code): ${res.message}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            BackupResult.RetryableFailure("Unable to connect securely to the destination.")
        } catch (t: Throwable) {
            BackupResult.PermanentFailure("Failed to test destination connection.")
        }
    }

    private suspend fun awaitResponse(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = okHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun httpFailureMessage(code: Int): String = when (code) {
        404 -> "Backup destination not found. Check the configured destination."
        408 -> "Backup timed out. The destination did not respond in time."
        429 -> "Too many requests. The backup service requested a retry later."
        in 500..599 -> "Backup server unavailable. The destination returned HTTP $code."
        else -> "Backup destination rejected the upload (HTTP $code)."
    }

    private fun isLocalTestHost(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1")
    }

    private fun isExplicitScriptError(body: String): Boolean {
        if (body.isEmpty()) return false
        val lower = body.lowercase()
        return lower.contains("failed stage:") ||
                lower.contains("post data is missing") ||
                lower.contains("\"status\":\"error\"") ||
                lower.contains("\"status\": \"error\"") ||
                lower.contains("\"result\":\"error\"") ||
                lower.contains("\"result\": \"error\"") ||
                lower.startsWith("{\"error\":") ||
                lower.startsWith("{\"error\" :")
    }

    private fun extractRemoteIdFromBody(body: String): String? {
        if (body.isEmpty()) return null
        val idRegex = Regex("\"(?:backup_id|id)\"\\s*:\\s*\"([^\"]+)\"")
        return idRegex.find(body)?.groupValues?.get(1)
    }
}
