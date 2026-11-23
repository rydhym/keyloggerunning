package com.example.inputmonitor.platform.ime

import android.view.inputmethod.EditorInfo
import com.example.inputmonitor.domain.privacy.EditorContext
import com.example.inputmonitor.domain.privacy.FieldCategory
import com.example.inputmonitor.platform.ime.analysis.EditorInfoAnalyzer
import com.example.inputmonitor.platform.ime.analysis.EditorInfoAnalyzerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates keyboard state, layout switches, shift/caps-lock state machine,
 * and context-specific input type adaptation (Architecture.md §26, §29, §30).
 */
class KeyboardController(
    private val editorInfoAnalyzer: EditorInfoAnalyzer = EditorInfoAnalyzerImpl()
) {

    private var _shiftState = ShiftState.OFF
    val shiftState: ShiftState
        get() = _shiftState

    private var _layoutType = KeyboardLayoutType.QWERTY
    val layoutType: KeyboardLayoutType
        get() = _layoutType

    private var _enterLabel = "↵"
    var language: KeyboardLanguage = KeyboardLanguages.english
        private set
    var nativeMode: Boolean = false
        private set
    var languagePage: Int = 0
        private set
    var editorAllowsLanguage: Boolean = false
        private set

    fun selectLanguage(value: KeyboardLanguage, native: Boolean) {
        language = value
        nativeMode = native
        languagePage = 0
        if (editorAllowsLanguage) {
            _layoutType = KeyboardLayoutType.QWERTY
            _shiftState = ShiftState.OFF
            lastShiftTapTime = 0L
        }
        refreshLayout()
    }

    fun nextLanguagePage() {
        val count = ((KeyboardLanguages.nativeCharacters(language).size + 23) / 24).coerceAtLeast(1)
        languagePage = (languagePage + 1) % count
        refreshLayout()
    }
    val enterLabel: String
        get() = _enterLabel

    private var _currentEditorContext: EditorContext? = null
    val currentEditorContext: EditorContext?
        get() = _currentEditorContext

    private var lastShiftTapTime: Long = 0L

    private val _currentLayout = MutableStateFlow(createCurrentLayout())
    val currentLayout: StateFlow<KeyboardLayout> = _currentLayout.asStateFlow()

    /**
     * Handles Shift key press with double-tap Caps Lock detection:
     * - Double tap (< 350ms): Locks in CAPS_LOCK
     * - Single tap from OFF: ON_ONCE
     * - Single tap from ON_ONCE or CAPS_LOCK: OFF
     */
    fun toggleShift() {
        val now = System.currentTimeMillis()
        val isDoubleTap = lastShiftTapTime > 0L && (now - lastShiftTapTime) <= 350L

        _shiftState = if (isDoubleTap) {
            ShiftState.CAPS_LOCK
        } else {
            when (_shiftState) {
                ShiftState.OFF -> ShiftState.ON_ONCE
                ShiftState.ON_ONCE -> ShiftState.OFF
                ShiftState.CAPS_LOCK -> ShiftState.OFF
            }
        }
        lastShiftTapTime = if (isDoubleTap) 0L else now
        refreshLayout()
    }

    /**
     * Sets a specific shift state directly.
     */
    fun setShift(state: ShiftState) {
        _shiftState = state
        refreshLayout()
    }

    /**
     * Called after a character has been typed.
     * If Shift was in ON_ONCE mode, resets it back to OFF.
     */
    fun onCharacterTyped() {
        if (_shiftState == ShiftState.ON_ONCE) {
            _shiftState = ShiftState.OFF
            refreshLayout()
        }
    }

    /**
     * Switches the active keyboard layout type (QWERTY, SYMBOLS, SYMBOLS_MORE, NUMERIC, PHONE).
     */
    fun switchLayout(type: KeyboardLayoutType) {
        _layoutType = type
        refreshLayout()
    }

    /**
     * Updates editor info to configure appropriate layouts (Email, URI, Numeric, Phone)
     * and action key labels (Go, Search, Send, Next, Done, etc.) via [EditorInfoAnalyzer].
     */
    fun updateEditorInfo(editorInfo: EditorInfo?) {
        editorAllowsLanguage = SuggestionPolicy.allows(editorInfo?.inputType ?: 0, editorInfo?.imeOptions ?: 0)
        val context = editorInfoAnalyzer.analyze(editorInfo)
        _currentEditorContext = context

        _layoutType = when (context.fieldCategory) {
            FieldCategory.PHONE -> KeyboardLayoutType.PHONE
            FieldCategory.NUMBER, FieldCategory.NUMERIC_PASSWORD -> KeyboardLayoutType.NUMERIC
            FieldCategory.EMAIL -> KeyboardLayoutType.EMAIL
            FieldCategory.URL -> KeyboardLayoutType.URI
            else -> KeyboardLayoutType.QWERTY
        }
        _enterLabel = context.imeActionLabel
        refreshLayout()
    }

    /**
     * Recreates and emits the updated layout reflecting current shift, action, and layout states.
     */
    private fun refreshLayout() {
        _currentLayout.value = createCurrentLayout()
    }

    private fun createCurrentLayout(): KeyboardLayout {
        val base = when (_layoutType) {
            KeyboardLayoutType.QWERTY,
            KeyboardLayoutType.EMAIL,
            KeyboardLayoutType.URI -> {
                KeyboardLayout.createQwertyLayout(
                    shiftState = _shiftState,
                    enterLabel = _enterLabel,
                    type = _layoutType
                )
            }
            KeyboardLayoutType.SYMBOLS -> {
                KeyboardLayout.createSymbolsLayout(enterLabel = _enterLabel)
            }
            KeyboardLayoutType.SYMBOLS_MORE -> {
                KeyboardLayout.createSymbolsMoreLayout(enterLabel = _enterLabel)
            }
            KeyboardLayoutType.NUMERIC -> {
                KeyboardLayout.createNumericLayout(enterLabel = _enterLabel)
            }
            KeyboardLayoutType.PHONE -> {
                KeyboardLayout.createPhoneLayout(enterLabel = _enterLabel)
            }
        }
        return if (editorAllowsLanguage && nativeMode && language.ranges.isNotEmpty() &&
            _layoutType == KeyboardLayoutType.QWERTY) {
            KeyboardLanguages.nativeLayout(base, language, languagePage, _shiftState.isShifted)
        } else base
    }
}
