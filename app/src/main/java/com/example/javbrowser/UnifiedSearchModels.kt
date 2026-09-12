package com.example.javbrowser

/** 統一搜尋的查詢模式。 */
enum class SearchMode {
    CODE,
    KEYWORD
}

/** Foreground browsing stops after the first page; deep search follows safe next-page evidence. */
enum class SearchRunMode {
    BROWSE,
    DEEP
}

/** User-requested query interpretation; the effective SearchMode remains in SearchRequest. */
enum class SearchInputMode {
    AUTO,
    CODE,
    KEYWORD
}

/** 每個網站一次搜尋的生命週期狀態。 */
enum class SearchStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    EMPTY,
    NEEDS_USER_ACTION,
    TIMEOUT,
    NETWORK_ERROR,
    PARSE_ERROR,
    UNSUPPORTED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this != QUEUED && this != RUNNING
}

data class SearchRequest(
    val requestId: String,
    val rawQuery: String,
    val normalizedQuery: String,
    val mode: SearchMode,
    val siteIds: List<String>,
    val configKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    val runMode: SearchRunMode = SearchRunMode.DEEP
)

enum class SearchMatchType {
    EXACT_CODE,
    KEYWORD,
    UNKNOWN
}

data class SearchItem(
    val stableId: String,
    val siteId: String,
    val title: String,
    val detailUrl: String,
    val canonicalUrl: String,
    val coverUrl: String? = null,
    val code: String? = null,
    val variantTags: List<String> = emptyList(),
    val matchType: SearchMatchType = SearchMatchType.UNKNOWN,
    val sourceRank: Int = 0
)

data class SiteSearchResult(
    val requestId: String,
    val siteId: String,
    val attemptId: String,
    val status: SearchStatus,
    val items: List<SearchItem> = emptyList(),
    val nextPageToken: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    val fromCache: Boolean = false,
    val retryAfterUntil: Long = 0L,
    val diagnostic: String = "",
    val pageState: SearchPageState = SearchPageState.UNKNOWN,
    val pageFingerprint: String? = null
)

data class SiteSearchState(
    val siteId: String,
    val status: SearchStatus,
    val items: List<SearchItem> = emptyList(),
    val nextPageToken: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val attemptId: String? = null,
    val fromCache: Boolean = false,
    val retryAfterUntil: Long = 0L,
    val loadedPages: Int = 0,
    val pendingPageToken: String? = null,
    val visitedPageTokens: List<String> = emptyList(),
    val diagnostic: String = "",
    val pageState: SearchPageState = SearchPageState.UNKNOWN,
    val visitedPageFingerprints: List<String> = emptyList()
)

data class SearchSnapshot(
    val request: SearchRequest,
    val siteStates: List<SiteSearchState>,
    val items: List<SearchItem>,
    val completedSites: Int,
    val selectedSites: Int,
    val isRunning: Boolean
) {
    val successfulSites: Int
        get() = siteStates.count { it.status == SearchStatus.SUCCESS }
}

interface SearchHandle {
    fun cancel()
    val isCancelled: Boolean
}

interface SearchSnapshotListener {
    fun onSnapshot(snapshot: SearchSnapshot)
}
