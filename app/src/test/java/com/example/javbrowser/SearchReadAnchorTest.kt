package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SearchReadAnchorTest {
    private fun group(id: String) = SearchResultGroup(id, id, "Title", null, emptyList())

    @Test fun anchorResolvesByStableGroupIdAfterListChanges() {
        val anchor = SearchReadAnchor("code:TEST001", 42)
        assertEquals(1, SearchReadAnchorResolver.resolve(listOf(group("code:NEW"), group("code:TEST001")), anchor))
        assertNull(SearchReadAnchorResolver.resolve(listOf(group("code:OTHER")), anchor))
    }

    @Test fun zeroOffsetIsValid() {
        assertEquals(0, SearchReadAnchorResolver.resolve(listOf(group("same")), SearchReadAnchor("same", 0)))
    }
}
