package com.example.javbrowser

enum class SearchSortMode {
    RELEVANCE,
    SOURCE_PREFERENCE,
    ORIGINAL
}

/** Deterministic, local-only ordering. It never performs a detail-page request. */
object SearchResultRanker {
    fun sort(items: List<SearchItem>, request: SearchRequest, sourceOrder: List<String>, mode: SearchSortMode): List<SearchItem> {
        val query = request.normalizedQuery.trim().lowercase()
        val sourceRanks = sourceOrder.withIndex().associate { it.value to it.index }
        return items.withIndex().sortedWith(compareBy<IndexedValue<SearchItem>> {
            when (mode) {
                SearchSortMode.RELEVANCE -> relevance(it.value, query)
                SearchSortMode.SOURCE_PREFERENCE -> sourceRanks[it.value.siteId] ?: Int.MAX_VALUE
                SearchSortMode.ORIGINAL -> 0
            }
        }.thenBy {
            when (mode) {
                SearchSortMode.RELEVANCE -> sourceRanks[it.value.siteId] ?: Int.MAX_VALUE
                SearchSortMode.SOURCE_PREFERENCE, SearchSortMode.ORIGINAL -> it.value.sourceRank
            }
        }.thenBy { it.index }).map { it.value }
    }

    private fun relevance(item: SearchItem, query: String): Int {
        if (item.matchType == SearchMatchType.EXACT_CODE) return 0
        val title = item.title.lowercase()
        if (query.isNotBlank() && title == query) return 1
        if (query.isNotBlank() && title.contains(query)) return 2
        val tokens = query.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isNotEmpty() && tokens.all(title::contains)) return 3
        return 4
    }
}
