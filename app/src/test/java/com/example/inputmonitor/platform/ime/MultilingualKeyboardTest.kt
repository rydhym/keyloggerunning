package com.example.inputmonitor.platform.ime

import android.view.inputmethod.InputConnection
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class MultilingualKeyboardTest {
    @Test fun everyLanguageSelectionReturnsFromSymbolsToUsableLetterKeys() {
        val controller = KeyboardController()
        controller.updateEditorInfo(android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        })
        KeyboardLanguages.catalog.forEach { language ->
            controller.switchLayout(KeyboardLayoutType.SYMBOLS)
            controller.selectLanguage(language, native = true)
            val layout = controller.currentLayout.value
            assertEquals(language.id, KeyboardLayoutType.QWERTY, layout.type)
            assertEquals(4, layout.rows.size)
            val keys = layout.rows.flatMap { it.keys }
            assertTrue(keys.any { it.action is KeyAction.Character })
            assertTrue(keys.any { it.action == KeyAction.Backspace })
            assertTrue(keys.any { it.action == KeyAction.Space })
        }
    }

    @Test fun languageSelectionResetsOldNativePageAndShift() {
        val controller = KeyboardController()
        controller.updateEditorInfo(android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        })
        controller.selectLanguage(KeyboardLanguages.catalog.first { it.id == "hi" }, true)
        controller.nextLanguagePage()
        controller.setShift(ShiftState.CAPS_LOCK)
        controller.selectLanguage(KeyboardLanguages.catalog.first { it.id == "ta" }, true)
        assertEquals(0, controller.languagePage)
        assertEquals(ShiftState.OFF, controller.shiftState)
        assertTrue(controller.currentLayout.value.rows[0].keys.any {
            (it.action as? KeyAction.Character)?.text?.any { character -> character.code in 0x0b80..0x0bff } == true
        })
    }

    @Test fun languageSelectionPreservesNumericPasswordLayoutAndReopensInNormalText() {
        val controller = KeyboardController()
        controller.updateEditorInfo(android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        })
        controller.selectLanguage(KeyboardLanguages.catalog.first { it.id == "hi" }, true)
        assertEquals(KeyboardLayoutType.NUMERIC, controller.currentLayout.value.type)
        assertFalse(controller.editorAllowsLanguage)
        controller.updateEditorInfo(android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        })
        assertEquals(KeyboardLayoutType.QWERTY, controller.currentLayout.value.type)
        assertTrue(controller.editorAllowsLanguage)
        assertEquals("hi", controller.language.id)
    }

    private class Editor {
        val compositions = mutableListOf<String>()
        var accept = true
        var finishes = 0
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "setComposingText" -> { compositions.add(args!![0].toString()); accept }
                "finishComposingText" -> { finishes++; true }
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as InputConnection
    }

    @Test fun compositionReplacesWholeWordAndBackspaceRecomputesIt() {
        val editor = Editor()
        val composer = TransliterationComposer { "[$it]" }
        assertTrue(composer.character("s", editor.connection))
        assertTrue(composer.character("h", editor.connection))
        assertTrue(composer.backspace(editor.connection))
        assertEquals(listOf("[s]", "[sh]", "[s]"), editor.compositions)
        assertEquals("s", composer.roman)
    }

    @Test fun boundaryCommitsOnceAndNextWordStartsClean() {
        val editor = Editor()
        val composer = TransliterationComposer { it.uppercase() }
        composer.character("hello", editor.connection)
        assertFalse(composer.character(" ", editor.connection))
        composer.finish(editor.connection)
        composer.finish(editor.connection)
        assertEquals(1, editor.finishes)
        composer.character("next", editor.connection)
        assertEquals("NEXT", editor.compositions.last())
    }

    @Test fun resetDoesNotWriteToNewEditor() {
        val first = Editor()
        val second = Editor()
        val composer = TransliterationComposer { it }
        composer.character("old", first.connection)
        composer.reset()
        assertFalse(composer.backspace(second.connection))
        composer.character("new", second.connection)
        assertEquals(listOf("new"), second.compositions)
    }

    @Test fun rejectedCompositionFallsBackWithoutKeepingStaleBuffer() {
        val editor = Editor().apply { accept = false }
        val composer = TransliterationComposer { it }
        assertFalse(composer.character("a", editor.connection))
        assertFalse(composer.active)
        assertFalse(composer.character("a", null))
    }

    @Test fun punctuationAndNativeTextRemainAvailableToNormalInputPath() {
        val editor = Editor()
        val composer = TransliterationComposer { it }
        listOf("!", "1", "ह", "🙂").forEach { assertFalse(composer.character(it, editor.connection)) }
        assertTrue(editor.compositions.isEmpty())
    }

    @Test fun catalogHasUniqueProfilesAndCoreIndianLanguages() {
        val ids = KeyboardLanguages.catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("en", "hi", "mr", "bn", "pa", "ta", "te", "kn", "ml", "gu", "or")))
    }

    @Test fun indicDigraphsConvertWithoutChangingUnrelatedScripts() {
        assertEquals("śānti", KeyboardLanguages.romanInput("shaanti", "Latin-Devanagari"))
        assertEquals("shaanti", KeyboardLanguages.romanInput("shaanti", "Latin-Greek"))
        assertEquals("namastē", KeyboardLanguages.romanInput("namaste", "Latin-Devanagari"))
    }

    @Test fun nativePagesExposeAllCharactersAndKeepSpaceAndDelete() {
        val language = KeyboardLanguages.catalog.first { it.id == "hi" }
        val characters = KeyboardLanguages.nativeCharacters(language)
        val base = KeyboardLayout.createQwertyLayout()
        val pages = characters.chunked(24).indices.map { KeyboardLanguages.nativeLayout(base, language, it, false) }
        val letters = pages.flatMap { it.rows.dropLast(1) }.flatMap { it.keys }
            .mapNotNull { (it.action as? KeyAction.Character)?.text }
        assertEquals(characters, letters)
        assertTrue(pages.all { it.rows.flatMap { row -> row.keys }.any { key -> key.action == KeyAction.Backspace } })
        assertTrue(pages.all { it.rows.last().keys.any { key -> key.action == KeyAction.Space } })
    }
}
