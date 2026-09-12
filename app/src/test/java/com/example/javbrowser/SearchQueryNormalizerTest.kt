package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryNormalizerTest {
    @Test
    fun preservesLeadingZerosInCodeMode() {
        val result = SearchQueryNormalizer.normalize("LULU-095")
        assertEquals(SearchMode.CODE, result.mode)
        assertEquals("LULU-095", result.normalized)
    }

    @Test
    fun normalizesFc2Variants() {
        val result = SearchQueryNormalizer.normalize("FC2 PPV-123456")
        assertEquals(SearchMode.CODE, result.mode)
        assertEquals("FC2-PPV-123456", result.normalized)
    }

    @Test
    fun mixedTextRemainsKeyword() {
        val result = SearchQueryNormalizer.normalize("ABP-123 中文字幕")
        assertEquals(SearchMode.KEYWORD, result.mode)
        assertEquals("ABP-123 中文字幕", result.normalized)
    }

    @Test
    fun extractsCodeFromTitleText() {
        assertEquals("ABP-123", SearchQueryNormalizer.extractCode("作品 ABP-123 中文字幕"))
        assertNull(SearchQueryNormalizer.extractCode("沒有番號的標題"))
    }

    @Test
    fun explicitKeywordModeCanOverrideAnApparentCode() {
        val result = SearchQueryNormalizer.normalize("ABC-123", SearchInputMode.KEYWORD)
        assertEquals(SearchMode.KEYWORD, result.mode)
        assertEquals("ABC-123", result.normalized)
    }

    @Test
    fun explicitCodeModeAndAutoModeRemainDistinctChoices() {
        assertEquals(SearchMode.CODE, SearchQueryNormalizer.normalize("ABC-123", SearchInputMode.CODE).mode)
        assertEquals(SearchMode.CODE, SearchQueryNormalizer.normalize("ABC-123", SearchInputMode.AUTO).mode)
        assertEquals(SearchMode.KEYWORD, SearchQueryNormalizer.normalize("a phrase", SearchInputMode.AUTO).mode)
    }
}
