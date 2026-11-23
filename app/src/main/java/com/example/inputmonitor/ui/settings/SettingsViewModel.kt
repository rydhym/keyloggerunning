package com.example.inputmonitor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.inputmonitor.core.DispatcherProvider
import com.example.inputmonitor.data.backup.security.BackupTokenStorage
import com.example.inputmonitor.domain.backup.model.BackupResult
import com.example.inputmonitor.domain.backup.model.BackupSettings
import com.example.inputmonitor.domain.backup.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import com.example.inputmonitor.domain.backup.repository.BackupRepository
import com.example.inputmonitor.domain.backup.repository.BackupSettingsRepository
import com.example.inputmonitor.domain.model.MonitoringSession
import com.example.inputmonitor.domain.repository.EventRepository
import com.example.inputmonitor.domain.repository.PreferencesRepository
import com.example.inputmonitor.domain.repository.SessionRepository
import com.example.inputmonitor.domain.usecase.DeleteAllApplicationDataUseCase
import com.example.inputmonitor.domain.usecase.DeleteAllSessionsUseCase
import com.example.inputmonitor.domain.usecase.DeleteSessionUseCase
import com.example.inputmonitor.platform.backup.AutoBackupScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel orchestrating user settings, logging preferences, destructive data management,
 * and user-consented automatic daily cloud backup (SRS.md §55, Architecture.md §50).
 */
class SettingsViewModel(
    private val sessionRepository: SessionRepository,
    private val eventRepository: EventRepository,
    private val preferencesRepository: PreferencesRepository,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val deleteAllSessionsUseCase: DeleteAllSessionsUseCase,
    private val deleteAllApplicationDataUseCase: DeleteAllApplicationDataUseCase,
    private val backupSettingsRepository: BackupSettingsRepository,
    private val backupTokenStorage: BackupTokenStorage,
    private val backupRepository: BackupRepository,
    private val autoBackupScheduler: AutoBackupScheduler,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _dialogState = MutableStateFlow(DialogState())
    private val _feedbackState = MutableStateFlow(FeedbackState())
    private val _isTestingConnection = MutableStateFlow(false)
    private val _connectionStatus = MutableStateFlow("Not tested")
    private val enqueueGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    private val modalAndFeedbackFlow = combine(
        _dialogState,
        _feedbackState,
        _isTestingConnection
    ) { dialog, feedback, isTesting ->
        ModalAndFeedback(dialog, feedback, isTesting)
    }

    private data class LoggingPreferences(
        val keyboard: Boolean,
        val accessibility: Boolean
    )

    private val loggingPrefsFlow = combine(
        preferencesRepository.isKeyboardLoggingEnabled,
        preferencesRepository.isAccessibilityLoggingEnabled
    ) { kb, acc ->
        LoggingPreferences(kb, acc)
    }

    private val totalEventsCountFlow = eventRepository.observeEventCounts(null)
        .map { it.total.toInt() }

    private data class BackupUiModel(
        val settings: BackupSettings,
        val isManualBackupRunning: Boolean,
        val run: BackupRun = BackupRun(),
        val history: List<BackupRun> = emptyList(),
        val connectionStatus: String = "Not tested",
        val automaticRun: BackupRun? = null
    )

    private val backupStateFlow = combine(
        backupSettingsRepository.backupSettings,
        autoBackupScheduler.isManualBackupActive,
        autoBackupScheduler.backupRuns,
        backupSettingsRepository.backupHistory,
        _connectionStatus
    ) { settings, isManualRunning, work, history, connection ->
        val enriched = work.map { live ->
            val saved = history.firstOrNull { it.workId == live.workId }
            if (live.startedAt > 0 || saved == null) live else saved.copy(stage = live.stage, statusMessage = live.statusMessage,
                nextEligibleAt = live.nextEligibleAt)
        }
        val activeManual = enriched.firstOrNull { it.active && it.backupType != BackupType.AUTOMATIC }
        val activeAutomatic = enriched.firstOrNull { it.active && it.backupType == BackupType.AUTOMATIC &&
            it.stage !in setOf(BackupStage.QUEUED, BackupStage.WAITING_FOR_NETWORK, BackupStage.WAITING_FOR_WIFI) }
        val last = (enriched.filter { !it.active } + history).maxByOrNull { it.completedAt }
        BackupUiModel(settings, isManualRunning, activeManual ?: activeAutomatic ?: last ?: BackupRun(), history, connection,
            enriched.firstOrNull { it.backupType == BackupType.AUTOMATIC })
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        sessionRepository.observeAllSessions(),
        loggingPrefsFlow,
        totalEventsCountFlow,
        modalAndFeedbackFlow,
        backupStateFlow
    ) { sessions, loggingPrefs, eventsCount, modalAndFeedback, backupState ->
        SettingsUiState(
            sessions = sessions,
            totalEventsCount = eventsCount,
            isKeyboardLoggingEnabled = loggingPrefs.keyboard,
            isAccessibilityLoggingEnabled = loggingPrefs.accessibility,
            isLoading = false,
            isOperating = modalAndFeedback.feedback.isOperating,
            pendingDeleteSession = modalAndFeedback.dialog.pendingDeleteSession,
            showDeleteAllSessionsDialog = modalAndFeedback.dialog.showDeleteAllSessionsDialog,
            showDeleteAllDataDialog = modalAndFeedback.dialog.showDeleteAllDataDialog,
            userMessage = modalAndFeedback.feedback.userMessage,
            error = modalAndFeedback.feedback.error,
            backupSettings = backupState.settings,
            showConnectBackupDialog = modalAndFeedback.dialog.showConnectBackupDialog,
            showDisconnectBackupDialog = modalAndFeedback.dialog.showDisconnectBackupDialog,
            isTestingConnection = modalAndFeedback.isTesting,
            isManualBackupRunning = backupState.isManualBackupRunning,
            backupRun = backupState.run,
            backupHistory = backupState.history,
            connectionStatus = backupState.connectionStatus,
            automaticBackupRun = backupState.automaticRun
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(isLoading = true)
    )

    init {
        // Queued jobs can be cancelled without ever entering a worker. Reconcile those
        // terminal WorkInfo records on reattachment as well as during this screen's lifetime.
        viewModelScope.launch(dispatchers.io) {
            autoBackupScheduler.backupRuns.collect { work ->
                val history = backupSettingsRepository.backupHistory.first()
                work.filter { it.backupType != BackupType.AUTOMATIC &&
                    it.stage in setOf(BackupStage.CANCELLED, BackupStage.FAILED) }.forEach { terminal ->
                    val saved = history.firstOrNull { it.workId == terminal.workId }
                    if (saved == null || saved.active) {
                        backupSettingsRepository.saveBackupRun((saved ?: terminal).copy(
                            stage = terminal.stage, completedAt = System.currentTimeMillis(), statusMessage = "",
                            failureReason = saved?.failureReason?.ifBlank { null }
                                ?: if (terminal.stage == BackupStage.CANCELLED) "Backup cancelled. A request already sent may still complete."
                                else "Android ended this backup before it reported a result."))
                    }
                }
            }
        }
    }

    fun setKeyboardLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.setKeyboardLoggingEnabled(enabled)
        }
    }

    fun setAccessibilityLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferencesRepository.setAccessibilityLoggingEnabled(enabled)
        }
    }

    // --- Automatic Daily Cloud Backup Actions ---

    fun setAutomaticBackupEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            val settings = backupSettingsRepository.getBackupSettings()
            if (enabled && !settings.isConfigured) {
                // If attempting to enable without a configured destination, open connect dialog
                _dialogState.value = _dialogState.value.copy(showConnectBackupDialog = true)
                _feedbackState.value = _feedbackState.value.copy(
                    error = "Please connect a cloud destination before enabling automatic backup."
                )
                return@launch
            }

            backupSettingsRepository.setBackupEnabled(enabled)
            if (enabled) {
                autoBackupScheduler.schedulePeriodicBackup(settings.wifiOnly)
                _feedbackState.value = _feedbackState.value.copy(
                    userMessage = "Automatic Daily Backup enabled. Runs approximately every 24 hours."
                )
            } else {
                autoBackupScheduler.cancelPeriodicBackup()
                _feedbackState.value = _feedbackState.value.copy(
                    userMessage = "Automatic Daily Backup disabled."
                )
            }
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            backupSettingsRepository.setWifiOnly(wifiOnly)
            val settings = backupSettingsRepository.getBackupSettings()
            if (settings.enabled) {
                autoBackupScheduler.schedulePeriodicBackup(wifiOnly)
            }
        }
    }

    fun triggerBackupNow() = enqueueBackup(false)
    fun sendTestBackup() = enqueueBackup(true)

    private fun enqueueBackup(test: Boolean) {
        if (uiState.value.isManualBackupRunning || !enqueueGuard.compareAndSet(false, true)) return
        com.example.inputmonitor.data.backup.logging.BackupLogger.i("BACKUP_UI", if (test) "test_requested" else "manual_requested")
        viewModelScope.launch(dispatchers.io) {
          try {
            val settings = backupSettingsRepository.getBackupSettings()
            if (!settings.isConfigured) {
                _feedbackState.value = _feedbackState.value.copy(
                    error = "Cannot back up: destination is not configured."
                )
                return@launch
            }
            autoBackupScheduler.enqueueBackup(settings.wifiOnly, test)
            com.example.inputmonitor.data.backup.logging.BackupLogger.i("BACKUP_WORK", "enqueued")
            _feedbackState.value = _feedbackState.value.copy(
                userMessage = "Backup queued. Android will start it when constraints permit."
            )
          } catch (e: CancellationException) { throw e
          } catch (_: Exception) {
            _feedbackState.value = _feedbackState.value.copy(error = "Could not queue backup. Try again.")
          } finally { enqueueGuard.set(false) }
        }
    }

    fun cancelManualBackup() {
        autoBackupScheduler.cancelManualBackup()
        viewModelScope.launch(dispatchers.io) {
            val current = uiState.value.backupRun
            if (current.backupType != BackupType.AUTOMATIC && current.workId.isNotEmpty()) {
                backupSettingsRepository.saveBackupRun(current.copy(stage = BackupStage.CANCELLED,
                    completedAt = System.currentTimeMillis(), statusMessage = "",
                    failureReason = "Cancelled. An upload already sent may still be processed by the destination."))
            }
        }
    }

    fun testBackupConnection() {
        if (_isTestingConnection.value) return
        _isTestingConnection.value = true
        viewModelScope.launch(dispatchers.io) {
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                val settings = backupSettingsRepository.getBackupSettings()
                val result = backupRepository.testConnection(settings.providerEndpoint.orEmpty(), backupTokenStorage.getAuthToken())
                _connectionStatus.value = if (result is BackupResult.Success)
                    "✓ Destination reachable · ${android.os.SystemClock.elapsedRealtime() - start} ms (GET only)"
                    else "⚠ Destination connection test failed"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _connectionStatus.value = "⚠ Destination connection test failed"
            } finally { _isTestingConnection.value = false }
        }
    }

    fun showConnectBackupDialog(show: Boolean) {
        _dialogState.value = _dialogState.value.copy(showConnectBackupDialog = show)
    }

    fun showDisconnectBackupDialog(show: Boolean) {
        _dialogState.value = _dialogState.value.copy(showDisconnectBackupDialog = show)
    }

    fun connectBackup(endpoint: String, token: String?) {
        val trimmedEndpoint = endpoint.trim()
        val trimmedToken = token?.trim()?.ifBlank { null }

        if (!trimmedEndpoint.startsWith("https://", ignoreCase = true) &&
            !trimmedEndpoint.startsWith("http://localhost", ignoreCase = true) &&
            !trimmedEndpoint.startsWith("http://127.0.0.1", ignoreCase = true)
        ) {
            _feedbackState.value = _feedbackState.value.copy(
                error = "Security Error: Destination URL must use secure HTTPS."
            )
            return
        }

        _isTestingConnection.value = true
        viewModelScope.launch(dispatchers.io) {
            try {
                val testResult = backupRepository.testConnection(trimmedEndpoint, trimmedToken)
                _isTestingConnection.value = false

                when (testResult) {
                    is BackupResult.Success -> {
                        if (trimmedToken != null) {
                            backupTokenStorage.saveAuthToken(trimmedToken)
                        } else {
                            backupTokenStorage.clearAuthToken()
                        }
                        backupSettingsRepository.setProviderConfig("https_rest", trimmedEndpoint)
                        backupSettingsRepository.clearAuthenticationRequired()
                        _dialogState.value = _dialogState.value.copy(showConnectBackupDialog = false)
                        _feedbackState.value = _feedbackState.value.copy(
                            userMessage = "Cloud destination connected successfully."
                        )
                    }
                    is BackupResult.AuthenticationRequired -> {
                        _feedbackState.value = _feedbackState.value.copy(
                            error = "Authentication failed: Please verify your access token or credentials."
                        )
                    }
                    is BackupResult.RetryableFailure -> {
                        _feedbackState.value = _feedbackState.value.copy(
                            error = "Connection failed: ${testResult.message}"
                        )
                    }
                    is BackupResult.PermanentFailure -> {
                        _feedbackState.value = _feedbackState.value.copy(
                            error = "Connection rejected: ${testResult.message}"
                        )
                    }
                }
            } catch (t: CancellationException) { throw t
            } catch (t: Throwable) {
                _isTestingConnection.value = false
                _feedbackState.value = _feedbackState.value.copy(
                    error = "Failed to connect securely to destination."
                )
            }
        }
    }

    fun disconnectBackup() {
        viewModelScope.launch(dispatchers.io) {
            autoBackupScheduler.cancelManualBackup()
            autoBackupScheduler.cancelPeriodicBackup()
            backupSettingsRepository.setBackupEnabled(false)
            backupSettingsRepository.setProviderConfig("https_rest", null)
            backupTokenStorage.clearAuthToken()
            _dialogState.value = _dialogState.value.copy(showDisconnectBackupDialog = false)
            _feedbackState.value = _feedbackState.value.copy(
                userMessage = "Cloud backup destination disconnected."
            )
        }
    }

    // --- Session & Data Deletion Actions ---

    fun requestDeleteSession(session: MonitoringSession) {
        _dialogState.value = _dialogState.value.copy(pendingDeleteSession = session)
    }

    fun dismissDeleteSessionDialog() {
        _dialogState.value = _dialogState.value.copy(pendingDeleteSession = null)
    }

    fun confirmDeleteSession() {
        val session = _dialogState.value.pendingDeleteSession ?: return
        _dialogState.value = _dialogState.value.copy(pendingDeleteSession = null)
        _feedbackState.value = _feedbackState.value.copy(isOperating = true, error = null)

        viewModelScope.launch(dispatchers.io) {
            deleteSessionUseCase(session.id)
                .onSuccess {
                    _feedbackState.value = FeedbackState(
                        isOperating = false,
                        userMessage = "Session \"${session.name}\" and its events were deleted."
                    )
                }
                .onFailure { t ->
                    _feedbackState.value = FeedbackState(
                        isOperating = false,
                        error = t.message ?: "Failed to delete session."
                    )
                }
        }
    }

    fun requestDeleteAllSessions() {
        _dialogState.value = _dialogState.value.copy(showDeleteAllSessionsDialog = true)
    }

    fun dismissDeleteAllSessionsDialog() {
        _dialogState.value = _dialogState.value.copy(showDeleteAllSessionsDialog = false)
    }

    fun confirmDeleteAllSessions() {
        _dialogState.value = _dialogState.value.copy(showDeleteAllSessionsDialog = false)
        _feedbackState.value = _feedbackState.value.copy(isOperating = true, error = null)

        viewModelScope.launch(dispatchers.io) {
            deleteAllSessionsUseCase()
                .onSuccess {
                    _feedbackState.value = FeedbackState(
                        isOperating = false,
                        userMessage = "All sessions and interaction events were deleted."
                    )
                }
                .onFailure { t ->
                    _feedbackState.value = FeedbackState(
                        isOperating = false,
                        error = t.message ?: "Failed to delete all sessions."
                    )
                }
        }
    }

    fun requestDeleteAllData() {
        _dialogState.value = _dialogState.value.copy(showDeleteAllDataDialog = true)
    }

    fun dismissDeleteAllDataDialog() {
        _dialogState.value = _dialogState.value.copy(showDeleteAllDataDialog = false)
    }

    fun confirmDeleteAllData() {
        _dialogState.value = _dialogState.value.copy(showDeleteAllDataDialog = false)
        _feedbackState.value = _feedbackState.value.copy(isOperating = true, error = null)

        viewModelScope.launch(dispatchers.io) {
            autoBackupScheduler.cancelManualBackup()
            autoBackupScheduler.cancelPeriodicBackup()
            backupTokenStorage.clearAuthToken()
            backupSettingsRepository.resetBackupSettings()

            deleteAllApplicationDataUseCase()
                .onSuccess {
                    _feedbackState.value = FeedbackState(
                        isOperating = false,
                        userMessage = "All application data, sessions, and backup settings have been permanently deleted."
                    )
                }
                .onFailure { t ->
                    _feedbackState.value = FeedbackState(
                        isOperating = false,
                        error = t.message ?: "Failed to purge application data."
                    )
                }
        }
    }

    fun dismissUserMessage() {
        _feedbackState.value = _feedbackState.value.copy(userMessage = null)
    }

    fun dismissError() {
        _feedbackState.value = _feedbackState.value.copy(error = null)
    }

    private data class DialogState(
        val pendingDeleteSession: MonitoringSession? = null,
        val showDeleteAllSessionsDialog: Boolean = false,
        val showDeleteAllDataDialog: Boolean = false,
        val showConnectBackupDialog: Boolean = false,
        val showDisconnectBackupDialog: Boolean = false
    )

    private data class FeedbackState(
        val isOperating: Boolean = false,
        val userMessage: String? = null,
        val error: String? = null
    )

    private data class ModalAndFeedback(
        val dialog: DialogState,
        val feedback: FeedbackState,
        val isTesting: Boolean
    )

    companion object {
        fun provideFactory(
            sessionRepository: SessionRepository,
            eventRepository: EventRepository,
            preferencesRepository: PreferencesRepository,
            deleteSessionUseCase: DeleteSessionUseCase,
            deleteAllSessionsUseCase: DeleteAllSessionsUseCase,
            deleteAllApplicationDataUseCase: DeleteAllApplicationDataUseCase,
            backupSettingsRepository: BackupSettingsRepository,
            backupTokenStorage: BackupTokenStorage,
            backupRepository: BackupRepository,
            autoBackupScheduler: AutoBackupScheduler,
            dispatchers: DispatcherProvider
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    sessionRepository = sessionRepository,
                    eventRepository = eventRepository,
                    preferencesRepository = preferencesRepository,
                    deleteSessionUseCase = deleteSessionUseCase,
                    deleteAllSessionsUseCase = deleteAllSessionsUseCase,
                    deleteAllApplicationDataUseCase = deleteAllApplicationDataUseCase,
                    backupSettingsRepository = backupSettingsRepository,
                    backupTokenStorage = backupTokenStorage,
                    backupRepository = backupRepository,
                    autoBackupScheduler = autoBackupScheduler,
                    dispatchers = dispatchers
                ) as T
            }
        }
    }
}
