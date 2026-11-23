package com.example.inputmonitor.data.backup.provider

import com.example.inputmonitor.core.DispatcherProvider
import com.example.inputmonitor.data.backup.security.BackupTokenStorage
import com.example.inputmonitor.domain.backup.model.BackupMetadata
import com.example.inputmonitor.domain.backup.model.BackupResult
import com.example.inputmonitor.domain.backup.model.BackupSettings
import com.example.inputmonitor.domain.backup.repository.BackupSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HttpBackupProviderTest {
    @Test fun uploadStagesComeFromBodyWriteAndNeverClaimSuccessBeforeResponse() = runBlocking {
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = mockServer.url("/upload").toString())
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val stages = java.util.Collections.synchronizedList(mutableListOf<com.example.inputmonitor.domain.backup.model.BackupStage>())
        val metadata = BackupMetadata(tempFile.name, tempFile.length(), 1, backupId = "stable-test-id")
        val result = provider.uploadBackup(tempFile, metadata) { stages.add(it) }
        assertTrue(result is BackupResult.Success)
        assertEquals(listOf(com.example.inputmonitor.domain.backup.model.BackupStage.UPLOADING,
            com.example.inputmonitor.domain.backup.model.BackupStage.WAITING_FOR_SERVER), stages)
        val request = mockServer.takeRequest()
        assertEquals("stable-test-id", request.getHeader("Idempotency-Key"))
        assertEquals(tempFile.length(), request.bodySize)
    }

    @Test fun timeoutAfterSendingBodyIsNotSafeToRetryAndDoesNotReplayPost() = runBlocking {
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = mockServer.url("/upload").toString())
        mockServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val shortClient = HttpBackupProvider.defaultClient().newBuilder()
            .callTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val shortProvider = HttpBackupProvider(fakeSettingsRepository, fakeTokenStorage, testDispatchers, shortClient)
        val result = shortProvider.uploadBackup(tempFile, BackupMetadata(tempFile.name, tempFile.length(), 1))
        assertTrue(result is BackupResult.RetryableFailure)
        assertTrue((result as BackupResult.RetryableFailure).message.contains("timed out"))
        assertTrue(!result.safeToRetry)
        assertEquals(1, mockServer.requestCount)
    }

    @Test fun cancellationCancelsOutstandingHttpCall() = runBlocking {
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = mockServer.url("/upload").toString())
        mockServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val task = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            provider.uploadBackup(tempFile, BackupMetadata(tempFile.name, tempFile.length(), 1))
        }
        assertTrue(mockServer.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS) != null)
        kotlinx.coroutines.withTimeout(2000) { task.cancel(); task.join() }
        assertTrue(task.isCancelled)
        assertEquals(1, mockServer.requestCount)
    }

    @Test fun scriptErrorsDoNotLeakResponseContents() = runBlocking {
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = mockServer.url("/upload").toString())
        mockServer.enqueue(MockResponse().setBody("{\"error\":\"secret-token-and-private-content\"}"))
        val result = provider.uploadBackup(tempFile, BackupMetadata(tempFile.name, tempFile.length(), 1))
        assertTrue(result is BackupResult.PermanentFailure)
        assertTrue(!(result as BackupResult.PermanentFailure).message.contains("secret-token"))
    }

    @Test fun defaultTransportHasTotalTimeoutAndNoSilentReplay() {
        val client = HttpBackupProvider.defaultClient()
        assertTrue(client.callTimeoutMillis > 0)
        assertTrue(!client.retryOnConnectionFailure)
        assertTrue(!client.followSslRedirects)
    }

    private val testDispatcher = Dispatchers.Unconfined
    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private class FakeTokenStorage : BackupTokenStorage {
        var token: String? = null
        override suspend fun saveAuthToken(token: String) { this.token = token }
        override suspend fun getAuthToken(): String? = token
        override suspend fun clearAuthToken() { token = null }
        override suspend fun hasAuthToken(): Boolean = token != null
    }

    private class FakeSettingsRepository : BackupSettingsRepository {
        var settings = BackupSettings()
        override val backupSettings: Flow<BackupSettings> get() = flowOf(settings)
        override suspend fun getBackupSettings(): BackupSettings = settings
        override suspend fun setBackupEnabled(enabled: Boolean) { settings = settings.copy(enabled = enabled) }
        override suspend fun setWifiOnly(wifiOnly: Boolean) { settings = settings.copy(wifiOnly = wifiOnly) }
        override suspend fun setProviderConfig(providerId: String, endpoint: String?) {
            settings = settings.copy(providerId = providerId, providerEndpoint = endpoint)
        }
        override suspend fun recordBackupAttempt(timestamp: Long) {}
        override suspend fun recordBackupSuccess(timestamp: Long, remoteBackupId: String?) {}
        override suspend fun recordBackupFailure(reason: String, authRequired: Boolean) {}
        override suspend fun clearAuthenticationRequired() {}
        override suspend fun resetBackupSettings() {}
    }

    private lateinit var mockServer: MockWebServer
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private lateinit var fakeSettingsRepository: FakeSettingsRepository
    private lateinit var provider: HttpBackupProvider
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        fakeTokenStorage = FakeTokenStorage()
        fakeSettingsRepository = FakeSettingsRepository()

        provider = HttpBackupProvider(
            backupSettingsRepository = fakeSettingsRepository,
            tokenStorage = fakeTokenStorage,
            dispatchers = testDispatchers,
            okHttpClient = HttpBackupProvider.defaultClient()
        )

        val tempDir = Files.createTempDirectory("http_test").toFile()
        tempFile = File(tempDir, "test_backup.csv").apply {
            writeText("col1,col2\nval1,val2\n")
        }
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        tempFile.delete()
        tempFile.parentFile?.delete()
    }

    // --- Test 1, 2, 3: POST method, non-empty body, Content-Type, and CSV data integrity ---

    @Test
    fun uploadBackup_test123_sendsPostWithCsvBodyAndCorrectContentTypeAndDataIntegrity() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Backup-Id", "cloud_id_9999")
                .setBody("{\"status\":\"ok\"}")
        )

        val expectedContent = "timestamp,event_type,event_data\n1620000000,KEY_DOWN,test_input\n"
        tempFile.writeText(expectedContent)

        val serverUrl = mockServer.url("/api/upload").toString()
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

        val metadata = BackupMetadata(
            fileName = tempFile.name,
            fileSize = tempFile.length(),
            eventCount = 1L,
            timestamp = 1620000000L
        )

        val result = provider.uploadBackup(file = tempFile, metadata = metadata)

        assertTrue("Upload should succeed", result is BackupResult.Success)
        val success = result as BackupResult.Success
        assertEquals("cloud_id_9999", success.remoteBackupId)

        val recordedRequest = mockServer.takeRequest()

        // Test 1: Method is POST and body is non-empty
        assertEquals("POST", recordedRequest.method)
        assertTrue("Request body must be non-empty", recordedRequest.bodySize > 0)

        // Test 2: Content-Type is text/csv; charset=utf-8
        val contentType = recordedRequest.getHeader("Content-Type")
        assertEquals("text/csv; charset=utf-8", contentType)

        // Test 3: Data integrity - content received matches CSV file exactly
        val bodyContent = recordedRequest.body.readUtf8()
        assertEquals(expectedContent, bodyContent)

        // Metadata headers preserved
        assertEquals(tempFile.name, recordedRequest.getHeader("X-Backup-Filename"))
        assertEquals("1", recordedRequest.getHeader("X-Backup-Event-Count"))
        assertEquals("1620000000", recordedRequest.getHeader("X-Backup-Timestamp"))
    }

    // --- Test 4: Optional authentication ---

    @Test
    fun uploadBackup_test4_includesAuthorizationWhenConfigured() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}")
        )

        fakeTokenStorage.token = "secret-token-xyz"
        val serverUrl = mockServer.url("/api/upload").toString()
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

        val metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        val result = provider.uploadBackup(file = tempFile, metadata = metadata)

        assertTrue(result is BackupResult.Success)
        val recordedRequest = mockServer.takeRequest()
        assertEquals("Bearer secret-token-xyz", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun uploadBackup_test4_omitsAuthorizationWhenNotConfigured() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}")
        )

        fakeTokenStorage.token = null
        val serverUrl = mockServer.url("/api/upload").toString()
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

        val metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        val result = provider.uploadBackup(file = tempFile, metadata = metadata)

        assertTrue(result is BackupResult.Success)
        val recordedRequest = mockServer.takeRequest()
        assertNull("Authorization header should be omitted when token is null", recordedRequest.getHeader("Authorization"))
    }

    // --- Test 5: HTTP success ---

    @Test
    fun uploadBackup_test5_http2xxReportsSuccess() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("Success")
        )

        val serverUrl = mockServer.url("/macros/s/exec").toString()
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

        val metadata = BackupMetadata(tempFile.name, tempFile.length(), 5L, timestamp = 1700000000L)
        val result = provider.uploadBackup(file = tempFile, metadata = metadata)

        assertTrue(result is BackupResult.Success)
        val success = result as BackupResult.Success
        assertEquals(tempFile.length(), success.bytesTransferred)
        assertEquals("backup_1700000000", success.remoteBackupId)
    }

    // --- Test 6: HTTP failure ---

    @Test
    fun uploadBackup_test6_http401ReportsAuthenticationRequired() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized")
        )

        fakeSettingsRepository.settings = BackupSettings(
            providerEndpoint = mockServer.url("/api/upload").toString()
        )

        val result = provider.uploadBackup(
            file = tempFile,
            metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        )

        assertTrue(result is BackupResult.AuthenticationRequired)
    }

    @Test
    fun uploadBackup_test6_http403ReportsAuthenticationRequired() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )

        fakeSettingsRepository.settings = BackupSettings(
            providerEndpoint = mockServer.url("/api/upload").toString()
        )

        val result = provider.uploadBackup(
            file = tempFile,
            metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        )

        assertTrue(result is BackupResult.AuthenticationRequired)
    }

    @Test
    fun uploadBackup_test6_http500ReportsRetryableFailure() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        fakeSettingsRepository.settings = BackupSettings(
            providerEndpoint = mockServer.url("/api/upload").toString()
        )

        val result = provider.uploadBackup(
            file = tempFile,
            metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        )

        assertTrue(result is BackupResult.RetryableFailure)
    }

    @Test
    fun uploadBackup_test6_http400ReportsPermanentFailure() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("Bad Request")
        )

        fakeSettingsRepository.settings = BackupSettings(
            providerEndpoint = mockServer.url("/api/upload").toString()
        )

        val result = provider.uploadBackup(
            file = tempFile,
            metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        )

        assertTrue(result is BackupResult.PermanentFailure)
    }

    // --- Test 7: Redirect handling (e.g. Google Apps Script 302 -> echo) ---

    @Test
    fun uploadBackup_test7_handles302RedirectSuccessfully() = runBlocking {
        val echoUrl = mockServer.url("/echo?user_content_key=xyz").toString()

        // Google Apps Script responds with 302 Found redirecting to echo
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", echoUrl)
        )

        // Echo endpoint responds with 200 OK
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Backup-Id", "gas_backup_001")
                .setBody("{\"status\":\"success\",\"message\":\"Backup received\"}")
        )

        val serverUrl = mockServer.url("/macros/s/exec").toString()
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

        val metadata = BackupMetadata(tempFile.name, tempFile.length(), 3L)
        val result = provider.uploadBackup(file = tempFile, metadata = metadata)

        assertTrue("Upload through redirect should succeed", result is BackupResult.Success)
        val success = result as BackupResult.Success
        assertEquals("gas_backup_001", success.remoteBackupId)

        // Verify request 1 was POST to /macros/s/exec with CSV body
        val req1 = mockServer.takeRequest()
        assertEquals("POST", req1.method)
        assertEquals("/macros/s/exec", req1.path)
        assertTrue(req1.bodySize > 0)

        // Verify request 2 followed the redirect to /echo
        val req2 = mockServer.takeRequest()
        assertEquals("GET", req2.method)
        assertEquals("/echo?user_content_key=xyz", req2.path)
    }

    // --- Test 8: Empty dataset handling and prevention of bodyless POST ---

    @Test
    fun uploadBackup_test8_empty0ByteFileAbortsWithoutIssuingBodylessPost() = runBlocking {
        val emptyFile = File(tempFile.parentFile, "empty_backup.csv").apply {
            writeBytes(ByteArray(0))
        }

        try {
            val serverUrl = mockServer.url("/api/upload").toString()
            fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

            val metadata = BackupMetadata(emptyFile.name, 0L, 0L)
            val result = provider.uploadBackup(file = emptyFile, metadata = metadata)

            assertTrue("Empty 0-byte file must be rejected", result is BackupResult.PermanentFailure)
            val failure = result as BackupResult.PermanentFailure
            assertTrue(failure.message.contains("empty (0 bytes)"))

            // Verify no request was sent over the wire
            assertEquals(0, mockServer.requestCount)
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun uploadBackup_test8_missingFileAbortsWithPermanentFailure() = runBlocking {
        val nonExistentFile = File(tempFile.parentFile, "non_existent.csv")

        val serverUrl = mockServer.url("/api/upload").toString()
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

        val metadata = BackupMetadata(nonExistentFile.name, 0L, 0L)
        val result = provider.uploadBackup(file = nonExistentFile, metadata = metadata)

        assertTrue(result is BackupResult.PermanentFailure)
        val failure = result as BackupResult.PermanentFailure
        assertTrue(failure.message.contains("does not exist"))
        assertEquals(0, mockServer.requestCount)
    }

    // --- Additional Guard: Explicit error in 200 response body ---

    @Test
    fun uploadBackup_detectsDiagnosticErrorInResponseBodyEvenIf200() = runBlocking {
        val scriptErrorOutput = """
            FAILED STAGE:
            validating request

            ERROR:
            Error: POST data is missing
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(scriptErrorOutput)
        )

        val serverUrl = mockServer.url("/macros/s/exec").toString()
        fakeSettingsRepository.settings = BackupSettings(providerEndpoint = serverUrl)

        val metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        val result = provider.uploadBackup(file = tempFile, metadata = metadata)

        assertTrue("Script diagnostic error must be reported as PermanentFailure", result is BackupResult.PermanentFailure)
        val failure = result as BackupResult.PermanentFailure
        assertTrue(failure.message.contains("processing error"))
    }

    // --- Security Check: Cleartext prohibition ---

    @Test
    fun uploadBackup_rejectsCleartextNonLocalUrl() = runBlocking {
        fakeSettingsRepository.settings = BackupSettings(
            providerEndpoint = "http://remote-insecure-server.com/api/backup"
        )

        val result = provider.uploadBackup(
            file = tempFile,
            metadata = BackupMetadata(tempFile.name, tempFile.length(), 1L)
        )

        assertTrue(result is BackupResult.PermanentFailure)
        val failure = result as BackupResult.PermanentFailure
        assertTrue(failure.message.contains("Cleartext HTTP is prohibited"))
    }

    // --- Test Connection ---

    @Test
    fun testConnection_successOn200() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val result = provider.testConnection(mockServer.url("/test").toString(), "token")
        assertTrue(result is BackupResult.Success)

        val req = mockServer.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("Bearer token", req.getHeader("Authorization"))
    }

    @Test
    fun testConnection_successOn405MethodNotAllowed() = runBlocking {
        // Many POST-only endpoints respond with 405 to GET
        mockServer.enqueue(MockResponse().setResponseCode(405))

        val result = provider.testConnection(mockServer.url("/test").toString(), null)
        assertTrue(result is BackupResult.Success)
    }

    @Test
    fun testConnection_failsOn401() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(401))

        val result = provider.testConnection(mockServer.url("/test").toString(), "bad_token")
        assertTrue(result is BackupResult.AuthenticationRequired)
    }
}
