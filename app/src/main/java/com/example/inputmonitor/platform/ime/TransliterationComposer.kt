package com.example.inputmonitor.platform.ime

import android.view.inputmethod.InputConnection

/** Transient composition of only this keyboard's current word. Never reads editor text. */
internal class TransliterationComposer(private val transform: (String) -> String) {
    var roman: String = ""
        private set
    val active: Boolean get() = roman.isNotEmpty()

    fun character(text: String, connection: InputConnection?): Boolean {
        if (connection == null || text.isEmpty() || !text.all {
                it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }) return false
        if (roman.length + text.length > 64) finish(connection)
        return replace(roman + text, connection)
    }

    fun backspace(connection: InputConnection?): Boolean {
        if (!active || connection == null) return false
        return replace(roman.dropLast(1), connection)
    }

    private fun replace(value: String, connection: InputConnection): Boolean {
        val converted = if (value.isEmpty()) "" else runCatching { transform(value) }.getOrNull() ?: return false
        if (!connection.setComposingText(converted, 1)) {
            finish(connection)
            return false
        }
        roman = value
        return true
    }

    fun finish(connection: InputConnection?) {
        if (active) connection?.finishComposingText()
        reset()
    }

    fun reset() { roman = "" }
}
