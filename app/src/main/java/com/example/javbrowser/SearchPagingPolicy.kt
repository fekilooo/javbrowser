package com.example.javbrowser

object SearchPagingPolicy {
    const val MAX_PAGES = 100
    const val MAX_ITEMS = 2000
    const val PAGE_ITEMS = 200
    fun batchSize(siteId: String) = if (siteId in setOf(SearchSiteIds.MISSAV, SearchSiteIds.JABLE)) 10 else 5
    fun canContinue(state: SiteSearchState): Boolean = state.status.isTerminal &&
        !state.nextPageToken.isNullOrBlank() && state.loadedPages < MAX_PAGES && state.items.size < MAX_ITEMS &&
        state.pageState !in setOf(SearchPageState.REPEATED_PAGE, SearchPageState.PAGE_ITEM_LIMIT)
}
