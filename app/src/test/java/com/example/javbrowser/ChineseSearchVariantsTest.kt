package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class ChineseSearchVariantsTest {
    @Test fun preservesOriginalAndAddsSimplifiedChinese() {
        assertEquals(listOf("台灣", "台湾"), ChineseSearchVariants.variants("台灣"))
        assertEquals(listOf("臺灣學生寫真", "台湾学生写真"), ChineseSearchVariants.variants("臺灣學生寫真"))
    }
    @Test fun usesPhraseExceptionsAndPreservesMixedText() {
        assertEquals(listOf("乾隆與頭髮", "乾隆与头发"), ChineseSearchVariants.variants("乾隆與頭髮"))
        assertEquals(listOf("ABC-123 台灣 4K 😊", "ABC-123 台湾 4K 😊"), ChineseSearchVariants.variants("ABC-123 台灣 4K 😊"))
    }
    @Test fun doesNotDuplicateAlreadySimplifiedOrAlterCodesAndEnglish() {
        listOf("台湾", "ABC-123", "FC2-PPV-1234567", "English Keyword", "", "1234").forEach { input ->
            assertEquals(listOf(input), ChineseSearchVariants.variants(input))
        }
        assertEquals(listOf("台灣", "台湾"), ChineseSearchVariants.variants(" 台灣 "))
    }
}
