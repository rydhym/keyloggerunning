package com.example.inputmonitor.platform.ime

import android.icu.text.Transliterator
import java.util.Locale

data class KeyboardLanguage(
    val id: String,
    val label: String,
    val transformId: String? = null,
    val ranges: List<IntRange> = emptyList()
)

object KeyboardLanguages {
    val english = KeyboardLanguage("en", "English")
    private val devanagari = listOf(0x0900..0x097f)
    private val arabic = listOf(0x0600..0x06ff)
    private val cyrillic = listOf(0x0400..0x04ff)
    private val latin = listOf(0x0061..0x007a, 0x00c0..0x024f, 0x0300..0x036f)

    val catalog: List<KeyboardLanguage> = listOf(
        english,
        KeyboardLanguage("hi", "Hindi · हिन्दी", "Latin-Devanagari", devanagari),
        KeyboardLanguage("mr", "Marathi · मराठी", "Latin-Devanagari", devanagari),
        KeyboardLanguage("ne", "Nepali · नेपाली", "Latin-Devanagari", devanagari),
        KeyboardLanguage("sa", "Sanskrit · संस्कृत", "Latin-Devanagari", devanagari),
        KeyboardLanguage("bn", "Bengali · বাংলা", "Latin-Bengali", listOf(0x0980..0x09ff)),
        KeyboardLanguage("as", "Assamese · অসমীয়া", "Latin-Bengali", listOf(0x0980..0x09ff)),
        KeyboardLanguage("pa", "Punjabi · ਪੰਜਾਬੀ", "Latin-Gurmukhi", listOf(0x0a00..0x0a7f)),
        KeyboardLanguage("gu", "Gujarati · ગુજરાતી", "Latin-Gujarati", listOf(0x0a80..0x0aff)),
        KeyboardLanguage("or", "Odia · ଓଡ଼ିଆ", "Latin-Oriya", listOf(0x0b00..0x0b7f)),
        KeyboardLanguage("ta", "Tamil · தமிழ்", "Latin-Tamil", listOf(0x0b80..0x0bff)),
        KeyboardLanguage("te", "Telugu · తెలుగు", "Latin-Telugu", listOf(0x0c00..0x0c7f)),
        KeyboardLanguage("kn", "Kannada · ಕನ್ನಡ", "Latin-Kannada", listOf(0x0c80..0x0cff)),
        KeyboardLanguage("ml", "Malayalam · മലയാളം", "Latin-Malayalam", listOf(0x0d00..0x0d7f)),
        KeyboardLanguage("ar", "Arabic · العربية", "Latin-Arabic", arabic),
        KeyboardLanguage("ur", "Urdu · اردو", "Latin-Arabic", arabic),
        KeyboardLanguage("fa", "Persian · فارسی", "Latin-Arabic", arabic),
        KeyboardLanguage("ru", "Russian · Русский", "Latin-Cyrillic", cyrillic),
        KeyboardLanguage("uk", "Ukrainian · Українська", "Latin-Cyrillic", cyrillic),
        KeyboardLanguage("el", "Greek · Ελληνικά", "Latin-Greek", listOf(0x0370..0x03ff)),
        KeyboardLanguage("hy", "Armenian · Հայերեն", "Latin-Armenian", listOf(0x0530..0x058f)),
        KeyboardLanguage("ka", "Georgian · ქართული", "Latin-Georgian", listOf(0x10a0..0x10ff)),
        KeyboardLanguage("he", "Hebrew · עברית", "Latin-Hebrew", listOf(0x0590..0x05ff)),
        KeyboardLanguage("ja-hira", "Japanese · ひらがな", "Latin-Hiragana", listOf(0x3041..0x3096)),
        KeyboardLanguage("ja-kata", "Japanese · カタカナ", "Latin-Katakana", listOf(0x30a1..0x30fa)),
        KeyboardLanguage("ko", "Korean · 한국어", "Latin-Hangul", listOf(0x3131..0x3163)),
        KeyboardLanguage("th", "Thai · ไทย", "Latin-Thai", listOf(0x0e00..0x0e7f)),
        KeyboardLanguage("am", "Amharic · አማርኛ", "Latin-Ethiopic", listOf(0x1200..0x137f)),
        KeyboardLanguage("syr", "Syriac · ܣܘܪܝܝܐ", "Latin-Syriac", listOf(0x0700..0x074f)),
        KeyboardLanguage("dv", "Dhivehi · ދިވެހި", "Latin-Thaana", listOf(0x0780..0x07bf))
    ) + listOf("es" to "Spanish · Español", "fr" to "French · Français", "de" to "German · Deutsch",
        "pt" to "Portuguese · Português", "it" to "Italian · Italiano", "tr" to "Turkish · Türkçe",
        "vi" to "Vietnamese · Tiếng Việt", "id" to "Indonesian · Bahasa Indonesia",
        "ms" to "Malay · Bahasa Melayu", "sw" to "Swahili · Kiswahili").map { (id, label) ->
        KeyboardLanguage(id, label, ranges = latin)
    }

    fun available(): List<KeyboardLanguage> {
        val availableIds = Transliterator.getAvailableIDs().asSequence().toSet()
        return catalog.filter { it.transformId == null || it.transformId in availableIds }
    }

    fun nativeCharacters(language: KeyboardLanguage): List<String> = language.ranges.flatMap { range ->
        range.filter { code ->
            Character.isLetter(code) || Character.getType(code) in setOf(
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt())
        }.map { String(Character.toChars(it)) }
    }.distinct()

    fun nativeLayout(base: KeyboardLayout, language: KeyboardLanguage, page: Int, shifted: Boolean): KeyboardLayout {
        val characters = nativeCharacters(language).chunked(24).getOrNull(page).orEmpty()
        val rows = (0..2).map { row ->
            val keys = characters.drop(row * 8).take(8).map { character ->
                val text = if (shifted) character.uppercase(Locale.forLanguageTag(language.id)) else character
                val label = if (Character.getType(text.codePointAt(0)) in setOf(
                        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt())) "◌$text" else text
                KeyModel(label, KeyAction.Character(text))
            }.toMutableList()
            if (row == 2) {
                keys.add(0, KeyModel("⇧", KeyAction.Shift, isSpecial = true))
                keys.add(KeyModel("⌫", KeyAction.Backspace, isSpecial = true))
            }
            KeyboardRow(keys)
        }
        return base.copy(rows = rows + base.rows.last())
    }

    /** Common ASCII digraphs for Indic romanization, before ICU's standard script transform. */
    fun romanInput(text: String, transformId: String): String {
        if (transformId !in setOf("Latin-Devanagari", "Latin-Bengali", "Latin-Gurmukhi", "Latin-Gujarati",
                "Latin-Oriya", "Latin-Tamil", "Latin-Telugu", "Latin-Kannada", "Latin-Malayalam")) return text
        val aliases = mapOf("chh" to "ch", "aa" to "ā", "ee" to "ī", "ii" to "ī", "oo" to "ū",
            "uu" to "ū", "sh" to "ś", "ch" to "c")
        val normalized = Regex("chh|aa|ee|ii|oo|uu|sh|ch", RegexOption.IGNORE_CASE).replace(text) {
            aliases.getValue(it.value.lowercase(Locale.ROOT))
        }
        // Ordinary Hindi/Marathi/Nepali typing uses e/o for the long vowels.
        // ICU's reversible Indic scheme otherwise maps these to short variants.
        return if (transformId == "Latin-Devanagari") Regex("[eEoO]").replace(normalized) {
            if (it.value.equals("e", ignoreCase = true)) "ē" else "ō"
        } else normalized
    }
}
