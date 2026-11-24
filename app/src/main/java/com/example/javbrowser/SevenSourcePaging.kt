package com.example.javbrowser

/** One outstanding catalog page; failures require an explicit retry. */
class SevenSourcePaging {
    var page = 0; private set
    var hasMore = true; private set
    var loading = false; private set
    var failed = false; private set
    private var pending: Int? = null

    fun begin(reset: Boolean = false): Int? {
        if (reset) { page = 0; hasMore = true; loading = false; failed = false; pending = null }
        if (loading || !hasMore) return null
        loading = true; failed = false
        return (page + 1).also { pending = it }
    }
    fun complete(newItems: Int, sourceHasMore: Boolean) {
        val requested = pending ?: return
        page = requested; hasMore = sourceHasMore && newItems > 0
        loading = false; failed = false; pending = null
    }
    fun fail() { loading = false; failed = true; pending = null }
    fun cancel() { loading = false; pending = null }
    fun shouldLoad(lastVisible: Int, count: Int): Boolean =
        count > 0 && lastVisible >= 0 && lastVisible >= count - 7 && hasMore && !loading && !failed
}
