package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultRankerTest {
    private val request = SearchRequest("r", "ABC-001", "ABC-001", SearchMode.CODE, listOf("a", "b"), "config")
    private fun item(id: String, site: String, title: String, rank: Int) =
        SearchItem(id, site, title, "https://$site.invalid/$id", "https://$site.invalid/$id", code = null,
            matchType = SearchMatchType.KEYWORD, sourceRank = rank)

    @Test fun relevanceKeepsExactCodeBeforeKeywordResults() {
        val exact = item("exact", "b", "other", 9).copy(matchType = SearchMatchType.EXACT_CODE)
        val keyword = item("keyword", "a", "ABC-001 extra", 0)
        assertEquals(listOf(exact, keyword), SearchResultRanker.sort(listOf(keyword, exact), request, listOf("a", "b"), SearchSortMode.RELEVANCE))
    }

    @Test fun sourcePreferenceIsDeterministicAndUsesOriginalRankAsTieBreaker() {
        val a = item("a", "a", "a", 2)
        val b = item("b", "b", "b", 1)
        assertEquals(listOf(a, b), SearchResultRanker.sort(listOf(b, a), request, listOf("a", "b"), SearchSortMode.SOURCE_PREFERENCE))
    }

    @Test fun originalModeRetainsArrivalOrderForEqualSourceRank() {
        val first = item("first", "a", "z", 0)
        val second = item("second", "a", "a", 0)
        assertEquals(listOf(first, second), SearchResultRanker.sort(listOf(first, second), request, listOf("a"), SearchSortMode.ORIGINAL))
    }
}
