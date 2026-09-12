package com.example.javbrowser

import java.util.Locale

object SearchQueryNormalizer {
    data class Normalized(val raw: String, val normalized: String, val mode: SearchMode, val code: String? = null)
    private val fc2 = Regex("(?i)(?<![A-Z0-9])FC2[-_ ]*(?:PPV[-_ ]*)?(\\d{5,10})(?![A-Z0-9])")
    private val regular = Regex("(?i)(?<![A-Z0-9])([A-Z]{2,10})[-_ ]?(\\d{1,10}(?:-\\d{2,5})?)(?![A-Z0-9])")
    fun normalize(rawInput: String, forceCode: Boolean = false, forceKeyword: Boolean = false): Normalized {
        val raw = rawInput.trim()
        val match = fc2.matchEntire(raw) ?: regular.matchEntire(raw)
        val code = if (!forceKeyword && (match != null || forceCode)) extractCode(raw) else null
        return if (code == null) Normalized(raw, raw, SearchMode.KEYWORD)
            else Normalized(raw, code, SearchMode.CODE, code)
    }
    fun normalize(rawInput: String, mode: SearchInputMode): Normalized = when (mode) {
        SearchInputMode.AUTO -> normalize(rawInput)
        SearchInputMode.CODE -> normalize(rawInput, forceCode = true)
        SearchInputMode.KEYWORD -> normalize(rawInput, forceKeyword = true)
    }
    fun extractCode(vararg values: String): String? {
        values.forEach { value ->
            fc2.find(value)?.let { return "FC2-PPV-" + it.groupValues[1] }
            regular.find(value)?.let {
                return JavDbScraper.normalizeJavCode(it.groupValues[1].uppercase(Locale.ROOT) + "-" + it.groupValues[2])
            }
        }
        return null
    }
    fun comparableCode(value: String) = value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
}
