package com.example.javbrowser
import org.junit.Assert.*
import org.junit.Test
class PageUrlClipboardTest {
    @Test fun preservesQueryFragmentAndUnicodeExactly() {
        val url = "https://example.org/page?name=測試&a=1%202#section"
        assertEquals(url, PageUrlClipboard.copyableUrl(url))
    }
    @Test fun rejectsInternalPagesAndNonWebSchemes() {
        listOf(null, "", "about:blank", "data:text/html,hello", "javascript:alert(1)",
            "file:///sdcard/test", "https://javbrowser.app/").forEach { assertNull(PageUrlClipboard.copyableUrl(it)) }
    }
    @Test fun copiesExternalPageRegardlessOfSupportedSearchSites() {
        assertEquals("https://example.org/", PageUrlClipboard.copyableUrl("https://example.org/"))
    }
}
