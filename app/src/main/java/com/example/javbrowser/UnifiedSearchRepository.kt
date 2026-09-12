package com.example.javbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Injectable scheduling allows tests without websites or Android loopers. */
class UnifiedSearchRepository(
    private val sites: List<SearchSite>,
    private val providers: Map<String, SearchProvider>,
    private val dispatch: ((() -> Unit) -> Unit),
    private val workers: ExecutorService = SearchScheduler.workers,
    private val timers: ScheduledExecutorService = SearchScheduler.timers,
    private val now: () -> Long = System::currentTimeMillis,
    private val deadlineMs: Long = 180_000L,
    private val pageDelayMs: Long = 400L
) {
    constructor(context: Context) : this(SearchSiteRegistry.sites(context),
        SearchProviderFactory.create(context), { action -> Handler(Looper.getMainLooper()).post { action() } })
    private var active: UnifiedSearchSession? = null
    private var closed = false

    fun createRequest(rawQuery: String, forceCode: Boolean = false,
                      siteIds: List<String> = sites.map { it.id },
                      runMode: SearchRunMode = SearchRunMode.DEEP,
                      inputMode: SearchInputMode? = null): SearchRequest {
        val query = inputMode?.let { SearchQueryNormalizer.normalize(rawQuery, it) }
            ?: SearchQueryNormalizer.normalize(rawQuery, forceCode)
        val selected = sites.filter { it.id in siteIds }
        return SearchRequest(UUID.randomUUID().toString(), query.raw, query.normalized, query.mode,
            selected.map { it.id }, selected.joinToString("|") { it.configurationKey },
            runMode = runMode)
    }

    fun start(request: SearchRequest, listener: SearchSnapshotListener,
              bypassCache: Boolean = false, restored: SearchSnapshot? = null): UnifiedSearchSession {
        check(!closed)
        active?.cancel()
        return UnifiedSearchSession(request, listener, restored).also {
            active = it
            it.begin(bypassCache, restored != null)
        }
    }
    fun close() { active?.cancel(); closed = true; active = null }

    inner class UnifiedSearchSession(val request: SearchRequest,
                                     private val listener: SearchSnapshotListener,
                                     restored: SearchSnapshot?) : SearchHandle {
        private val states = linkedMapOf<String, SiteSearchState>()
        private val jobs = mutableMapOf<String, SearchCancellation>()
        private val visited = mutableMapOf<String, MutableSet<String>>()
        private val failedPage = mutableMapOf<String, String?>()
        private val autoUntil = mutableMapOf<String, Int>()
        private var deadline: ScheduledFuture<*>? = null
        @Volatile override var isCancelled = false
            private set
        init {
            request.siteIds.forEach { id ->
                val previous = restored?.siteStates?.find { it.siteId == id }
                states[id] = previous?.let {
                    if (it.status.isTerminal) it else it.copy(status = SearchStatus.CANCELLED)
                } ?: SiteSearchState(id, SearchStatus.QUEUED)
                visited[id] = previous?.visitedPageTokens.orEmpty().map(SearchPagination::pageKey).toMutableSet()
                failedPage[id] = previous?.pendingPageToken
                autoUntil[id] = (previous?.loadedPages ?: 0) + SearchPagingPolicy.batchSize(id)
            }
        }
        @Synchronized internal fun begin(bypass: Boolean, restored: Boolean) {
            emit()
            if (!restored) request.siteIds.forEach { submit(it, null, false, bypass) }
        }
        @Synchronized fun snapshot(): SearchSnapshot {
            val all = states.values.toList()
            val items = SearchResultNormalizer.merge(all.flatMap { it.items }).sortedWith(
                compareBy<SearchItem> { if (it.matchType == SearchMatchType.EXACT_CODE) 0 else 1 }
                    .thenBy { item -> sites.indexOfFirst { it.id == item.siteId } }
                    .thenBy { it.sourceRank })
            return SearchSnapshot(request, all, items, all.count { it.status.isTerminal },
                request.siteIds.size, all.any { !it.status.isTerminal })
        }
        @Synchronized fun retry(siteId: String) {
            val state = states[siteId] ?: return
            if (!state.status.isTerminal || state.retryAfterUntil > now()) return
            val page = failedPage[siteId]
            autoUntil[siteId] = (if (page == null) 0 else state.loadedPages) + SearchPagingPolicy.batchSize(siteId)
            submit(siteId, page, page != null, true)
        }
        @Synchronized fun resume() {
            states.values.filter { it.status in setOf(SearchStatus.CANCELLED, SearchStatus.TIMEOUT) }.map { it.siteId }.forEach(::retry)
        }
        @Synchronized fun continuePages(siteId: String? = null) {
            states.values.toList().filter { siteId == null || it.siteId == siteId }.forEach {
                if (it.status in setOf(SearchStatus.CANCELLED, SearchStatus.TIMEOUT)) retry(it.siteId)
                else if (SearchPagingPolicy.canContinue(it)) loadMore(it.siteId)
            }
        }
        @Synchronized fun loadMore(siteId: String) {
            val state = states[siteId] ?: return
            val page = state.nextPageToken ?: return
            if (!SearchPagingPolicy.canContinue(state) || state.retryAfterUntil > now()) return
            if (SearchPagination.pageKey(page) in visited[siteId].orEmpty()) return
            autoUntil[siteId] = state.loadedPages + SearchPagingPolicy.batchSize(siteId)
            submit(siteId, page, true, false)
        }
        @Synchronized override fun cancel() {
            isCancelled = true
            deadline?.cancel(false)
            deadline = null
            jobs.values.toList().forEach { it.cancel() }
            jobs.clear()
            states.replaceAll { _, state ->
                if (state.status.isTerminal) state else state.copy(status = SearchStatus.CANCELLED, message = "已停止")
            }
            emit()
        }
        private fun armDeadline() {
            if (deadline != null) return
            deadline = timers.schedule({ dispatch {
                synchronized(this) {
                    jobs.values.toList().forEach { it.cancel() }
                    jobs.clear()
                    states.replaceAll { _, state ->
                        if (state.status.isTerminal) state else state.copy(
                            status = if (state.status == SearchStatus.RUNNING) SearchStatus.TIMEOUT else SearchStatus.CANCELLED,
                            errorCode = "SESSION_DEADLINE", message = "整體搜尋期限已到")
                    }
                    deadline = null
                    emit()
                }
            } }, deadlineMs, TimeUnit.MILLISECONDS)
        }
        @Synchronized private fun submit(id: String, page: String?, append: Boolean, bypass: Boolean) {
            if (closed || active !== this) return
            val old = states.getValue(id)
            failedPage[id] = page
            val cooldown = synchronized(cooldowns) { cooldowns[siteKey(id)] ?: 0 }
            if (cooldown > now()) {
                states[id] = old.copy(status = SearchStatus.NETWORK_ERROR, retryAfterUntil = cooldown,
                    errorCode = "HTTP_429", message = "網站暫時限制查詢，請稍後重試", pendingPageToken = page)
                emit(); return
            }
            isCancelled = false
            jobs.remove(id)?.cancel()
            val cancel = SearchCancellation()
            val attempt = UUID.randomUUID().toString()
            jobs[id] = cancel
            failedPage[id] = page
            states[id] = old.copy(status = SearchStatus.QUEUED, attemptId = attempt, errorCode = null,
                message = null, fromCache = false, pendingPageToken = page)
            armDeadline()
            emit()
            val key = listOf(id, request.configKey, request.mode, request.normalizedQuery, page, "parser-v8-pagination").toString()
            if (!bypass) cacheGet(key, now())?.let { cached ->
                dispatch { accept(id, attempt, cancel, cached.copy(requestId = request.requestId,
                    attemptId = attempt, fromCache = true), page, append, key) }
                return
            }
            val launch = {
              val future = workers.submit {
                try {
                    cancel.check()
                    dispatch {
                        synchronized(this) {
                            if (jobs[id] === cancel && !cancel.isCancelled) {
                                states[id] = states.getValue(id).copy(status = SearchStatus.RUNNING)
                                emit()
                            }
                        }
                    }
                    val result = providers[id]?.search(request, page, attempt, cancel)
                        ?: SiteSearchResult(request.requestId, id, attempt, SearchStatus.UNSUPPORTED)
                    dispatch { accept(id, attempt, cancel, result, page, append, key) }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    val result = SiteSearchResult(request.requestId, id, attempt, SearchStatus.NETWORK_ERROR,
                        errorCode = "PROVIDER_ERROR", message = "搜尋工作失敗")
                    dispatch { accept(id, attempt, cancel, result, page, append, key) }
                }
              }
              cancel.onCancel { future.cancel(true) }
              Unit
            }
            if (append && pageDelayMs > 0) {
                val delayed = timers.schedule({ if (!cancel.isCancelled) launch() }, pageDelayMs, TimeUnit.MILLISECONDS)
                cancel.onCancel { delayed.cancel(false) }
            } else launch()
        }
        @Synchronized private fun accept(id: String, attempt: String, cancel: SearchCancellation,
                                         result: SiteSearchResult, page: String?, append: Boolean, key: String) {
            if (closed || active !== this || cancel.isCancelled || jobs[id] !== cancel ||
                result.requestId != request.requestId || result.attemptId != attempt || result.siteId != id) return
            jobs.remove(id)
            val old = states.getValue(id)
            val success = result.status == SearchStatus.SUCCESS || result.status == SearchStatus.EMPTY
            if (result.retryAfterUntil > now()) synchronized(cooldowns) { cooldowns[siteKey(id)] = result.retryAfterUntil }
            if (success && !result.fromCache) cachePut(key, result, now())
            if (success && !append) visited.remove(id)
            if (success) visited.getOrPut(id) { mutableSetOf() }.add(SearchPagination.pageKey(page ?: sites.first { it.id == id }.buildSearchUrl(request.normalizedQuery, null)))
            val pages = if (success) (if (append) old.loadedPages + 1 else 1) else old.loadedPages
            val incoming = result.items.take(SearchPagingPolicy.PAGE_ITEMS).mapIndexed { index, item ->
                item.copy(sourceRank = (if (append) old.loadedPages * SearchPagingPolicy.PAGE_ITEMS else 0) + index)
            }
            val items = if (success) {
                if (append) SearchResultNormalizer.merge(old.items + incoming).take(SearchPagingPolicy.MAX_ITEMS) else incoming
            } else SearchResultNormalizer.merge(old.items + incoming).take(SearchPagingPolicy.MAX_ITEMS)
            val fingerprints = if (append) old.visitedPageFingerprints else emptyList()
            val repeated = append && success && result.pageFingerprint != null && result.pageFingerprint in fingerprints
            val repeatedUrl = result.nextPageToken?.let { SearchPagination.pageKey(it) in visited[id].orEmpty() } == true
            val next = result.nextPageToken?.takeIf { success && !repeated && !repeatedUrl }
            val pageState = when {
                !success -> old.pageState
                repeated || repeatedUrl -> SearchPageState.REPEATED_PAGE
                result.pageState == SearchPageState.PAGE_ITEM_LIMIT -> SearchPageState.PAGE_ITEM_LIMIT
                pages >= SearchPagingPolicy.MAX_PAGES && next != null -> SearchPageState.PAGE_LIMIT
                items.size >= SearchPagingPolicy.MAX_ITEMS -> SearchPageState.ITEM_LIMIT
                next != null && pages >= autoUntil.getValue(id) -> SearchPageState.AUTO_LIMIT
                next != null -> SearchPageState.HAS_NEXT
                else -> result.pageState
            }
            if (success) failedPage.remove(id)
            states[id] = SiteSearchState(id,
                if (append && success && items.isNotEmpty()) SearchStatus.SUCCESS else result.status,
                items, next, result.errorCode, result.message, attempt, result.fromCache, result.retryAfterUntil, pages,
                if (success) null else page, visited[id].orEmpty().take(SearchPagingPolicy.MAX_PAGES), result.diagnostic,
                pageState, (fingerprints + listOfNotNull(result.pageFingerprint)).distinct().take(SearchPagingPolicy.MAX_PAGES))
            // No query, full URL, body, cookies, or exception message enters logs.
            runCatching { android.util.Log.i("UnifiedSearch", "site=" + id + " status=" + result.status +
                " code=" + result.errorCode + " " + result.diagnostic) }
            if (request.runMode == SearchRunMode.DEEP && success && next != null &&
                pageState == SearchPageState.HAS_NEXT && !isCancelled) {
                submit(id, next, true, false)
                return
            }
            if (states.values.all { it.status.isTerminal }) { deadline?.cancel(false); deadline = null }
            emit()
        }
        private fun siteKey(id: String) = id + ":" + sites.find { it.id == id }?.configurationKey
        private fun emit() { dispatch { if (!closed && active === this) listener.onSnapshot(snapshot()) } }
    }
    companion object {
        private val cache = object : LinkedHashMap<String, Pair<SiteSearchResult, Long>>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<SiteSearchResult, Long>>?) = size > 100
        }
        private val cooldowns = object : LinkedHashMap<String, Long>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 100
        }
        private fun cacheGet(key: String, now: Long): SiteSearchResult? = synchronized(cache) {
            val entry = cache[key] ?: return@synchronized null
            if (entry.second <= now) { cache.remove(key); null } else entry.first
        }
        private fun cachePut(key: String, result: SiteSearchResult, now: Long) = synchronized(cache) {
            cache[key] = result to (now + if (result.status == SearchStatus.EMPTY) 120_000 else 600_000)
        }
    }
}
