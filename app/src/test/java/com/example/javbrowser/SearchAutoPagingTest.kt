package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SearchAutoPagingTest {
    private class Harness(val id: String = "missav", val responder: (Int, SearchRequest, String) -> SiteSearchResult) : AutoCloseable {
        val calls = CopyOnWriteArrayList<Int>()
        val snapshots = LinkedBlockingQueue<SearchSnapshot>()
        val site = SearchSite(id, "Test", 0, SearchParserKind.MISSAV, UUID.randomUUID().toString(),
            { _, page -> page ?: "https://test.invalid/search?q=neutral" }, setOf("test.invalid"))
        val workers = Executors.newFixedThreadPool(2)
        val timers = Executors.newSingleThreadScheduledExecutor()
        val provider = object : SearchProvider {
            override val site = this@Harness.site
            override fun search(request: SearchRequest, pageToken: String?, attemptId: String, cancellation: SearchCancellation): SiteSearchResult {
                val page = pageToken?.substringAfter("page=", "1")?.substringBefore('&')?.toIntOrNull() ?: 1
                calls.add(page)
                return responder(page, request, attemptId)
            }
        }
        val repo = UnifiedSearchRepository(listOf(site), mapOf(id to provider), { it() }, workers, timers, pageDelayMs = 0)
        val request = repo.createRequest("neutral")
        val listener = object : SearchSnapshotListener { override fun onSnapshot(snapshot: SearchSnapshot) { snapshots.add(snapshot) } }
        lateinit var session: UnifiedSearchRepository.UnifiedSearchSession
        fun start(restored: SearchSnapshot? = null, requestOverride: SearchRequest = request) {
            session = repo.start(requestOverride, listener, true, restored)
        }
        fun settled(): SearchSnapshot {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < until) {
                val next = snapshots.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!next.isRunning && next.siteStates.all { it.status.isTerminal }) return next
            }
            error("Search did not settle")
        }
        override fun close() { repo.close(); workers.shutdownNow(); timers.shutdownNow() }
    }
    private fun page(n: Int, request: SearchRequest, attempt: String, last: Int = 3, fingerprint: String = "page-$n", match: Boolean = true): SiteSearchResult {
        val site = request.siteIds.single()
        val url = "https://test.invalid/videos/item-$n"
        val items = if (match) listOf(SearchItem("$site:$n", site, "TEST-001", url, url, code = "TEST-001")) else emptyList()
        return SiteSearchResult(request.requestId, site, attempt, if (match) SearchStatus.SUCCESS else SearchStatus.EMPTY,
            items, if (n < last) "https://test.invalid/search?q=neutral&page=${n + 1}" else null,
            errorCode = if (match) null else "NO_EXACT_MATCH",
            pageState = if (n < last) SearchPageState.HAS_NEXT else SearchPageState.END_REPORTED, pageFingerprint = fingerprint)
    }

    @Test fun savedDiskSnapshotDoesNotFetchUntilUserContinues() {
        Harness { n, r, a -> page(n, r, a, last = 11) }.use { original ->
            original.start()
            val bytes = SavedSearchCodec.encode(original.settled(), 123L)
            Harness { n, r, a -> page(n, r, a, last = 11) }.use { restored ->
                restored.start(SavedSearchCodec.decode(bytes).snapshot.copy(request = restored.request))
                assertEquals(10, restored.settled().items.size)
                assertTrue(restored.calls.isEmpty())
                restored.session.continuePages()
                assertEquals(11, restored.settled().items.size)
                assertEquals(listOf(11), restored.calls.toList())
            }
        }
    }

    @Test fun bothPrimarySitesAutomaticallyFetchThroughTheLastPageAndGroupLinks() {
        listOf("missav", "jable").forEach { id ->
            Harness(id) { n, r, a -> page(n, r, a) }.use { h ->
                h.start()
                val result = h.settled()
                assertEquals(listOf(1, 2, 3), h.calls.toList())
                assertEquals(3, result.siteStates.single().loadedPages)
                assertEquals(SearchPageState.END_REPORTED, result.siteStates.single().pageState)
                assertEquals(3, SearchResultGrouping.group(result.items).single().links.size)
            }
        }
    }

    @Test fun smoothBrowseReturnsFirstPageAndWaitsForExplicitLoadMore() {
        Harness { n, r, a -> page(n, r, a, last = 3) }.use { h ->
            h.start(requestOverride = h.request.copy(runMode = SearchRunMode.BROWSE))
            val first = h.settled()
            assertEquals(listOf(1), h.calls.toList())
            assertEquals(1, first.siteStates.single().loadedPages)
            assertEquals(SearchPageState.HAS_NEXT, first.siteStates.single().pageState)
            h.session.loadMore(h.site.id)
            val second = h.settled()
            assertEquals(listOf(1, 2), h.calls.toList())
            assertEquals(2, second.siteStates.single().loadedPages)
        }
    }
    @Test fun primaryBatchPausesAtTenAndContinueStartsAtElevenNotPageOne() {
        Harness { n, r, a -> page(n, r, a, last = 12) }.use { h ->
            h.start()
            val paused = h.settled().siteStates.single()
            assertEquals(10, paused.loadedPages)
            assertEquals(SearchPageState.AUTO_LIMIT, paused.pageState)
            assertTrue(SearchPagingPolicy.canContinue(paused))
            h.session.continuePages()
            assertEquals(12, h.settled().items.size)
            assertEquals((1..12).toList(), h.calls.toList())
        }
    }
    @Test fun otherSitesPauseAtFiveWithoutDiscardingTheirNextUrl() {
        Harness("other") { n, r, a -> page(n, r, a, last = 7) }.use { h ->
            h.start()
            val state = h.settled().siteStates.single()
            assertEquals(5, state.loadedPages)
            assertTrue(state.nextPageToken!!.endsWith("page=6"))
        }
    }
    @Test fun aPageWithoutExactMatchesDoesNotStopLaterPages() {
        Harness { n, r, a -> page(n, r, a, match = n == 3) }.use { h ->
            h.start()
            val result = h.settled()
            assertEquals(listOf(1, 2, 3), h.calls.toList())
            assertEquals(1, result.items.size)
        }
    }
    @Test fun duplicatePageContentStopsEvenWhenUrlKeepsIncreasing() {
        Harness { n, r, a -> page(n, r, a, last = 50, fingerprint = "same").copy(items = page(1, r, a).items) }.use { h ->
            h.start()
            val result = h.settled()
            assertEquals(listOf(1, 2), h.calls.toList())
            assertEquals(SearchPageState.REPEATED_PAGE, result.siteStates.single().pageState)
            assertEquals(1, result.items.size)
        }
    }
    @Test fun cfOnPageTwoPreservesPageOneAndRetryResumesTheFailedPage() {
        var verified = false
        Harness { n, r, a ->
            if (n == 2 && !verified) SiteSearchResult(r.requestId, r.siteIds.single(), a, SearchStatus.NEEDS_USER_ACTION, errorCode = "CF_CHALLENGE")
            else page(n, r, a)
        }.use { h ->
            h.start()
            val blocked = h.settled()
            assertEquals(1, blocked.items.size)
            assertEquals(1, blocked.siteStates.single().loadedPages)
            assertTrue(blocked.siteStates.single().pendingPageToken!!.endsWith("page=2"))
            verified = true
            h.session.retry("missav")
            assertEquals(3, h.settled().items.size)
            assertEquals(listOf(1, 2, 2, 3), h.calls.toList())
        }
    }
    @Test fun rateLimitDoesNotAutomaticallyHammerTheNextPage() {
        Harness { n, r, a ->
            if (n == 2) SiteSearchResult(r.requestId, r.siteIds.single(), a, SearchStatus.NETWORK_ERROR,
                errorCode = "HTTP_429", retryAfterUntil = System.currentTimeMillis() + 60_000)
            else page(n, r, a)
        }.use { h ->
            h.start()
            val stopped = h.settled()
            h.session.retry("missav")
            assertEquals(listOf(1, 2), h.calls.toList())
            assertEquals(1, stopped.items.size)
        }
    }
    @Test fun cancellationRejectsLateResultsFromTheNextPage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness { n, r, a ->
            if (n == 2) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {} }
            page(n, r, a)
        }.use { h ->
            h.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            h.session.cancel()
            release.countDown()
            val stopped = h.settled()
            assertEquals(SearchStatus.CANCELLED, stopped.siteStates.single().status)
            assertEquals(1, stopped.items.size)
            assertEquals(1, h.session.snapshot().items.size)
        }
    }
    @Test fun restoredBatchKeepsItsNextPageAndPreviousGroups() {
        Harness { n, r, a -> page(n, r, a, last = 11) }.use { first ->
            first.start()
            val saved = first.settled()
            Harness { n, r, a -> page(n, r, a, last = 11) }.use { restored ->
                restored.start(saved.copy(request = restored.request))
                restored.settled()
                restored.session.continuePages()
                assertEquals(11, restored.settled().items.size)
                assertEquals(listOf(11), restored.calls.toList())
            }
        }
    }
    @Test fun hardPageLimitIsVisibleAndKeepsTheUnfetchedNextUrl() {
        Harness { n, r, a -> page(n, r, a, last = 120) }.use { h ->
            val state = SiteSearchState("missav", SearchStatus.SUCCESS, nextPageToken = "https://test.invalid/search?q=neutral&page=100",
                loadedPages = 99, pageState = SearchPageState.AUTO_LIMIT)
            h.start(SearchSnapshot(h.request, listOf(state), emptyList(), 1, 1, false))
            h.settled()
            h.session.continuePages()
            val limited = h.settled().siteStates.single()
            assertEquals(SearchPageState.PAGE_LIMIT, limited.pageState)
            assertNotNull(limited.nextPageToken)
            assertFalse(SearchPagingPolicy.canContinue(limited))
            assertTrue(SearchStatusPresentation.pagination(limited, false).contains("尚未抓全"))
        }
    }
    @Test fun cycleToAnAlreadyVisitedUrlStopsWithoutRefetchingIt() {
        Harness { n, r, a -> page(n, r, a).copy(nextPageToken = if (n == 2) "https://test.invalid/search?q=neutral" else "https://test.invalid/search?q=neutral&page=2") }.use { h ->
            h.start()
            assertEquals(SearchPageState.REPEATED_PAGE, h.settled().siteStates.single().pageState)
            assertEquals(listOf(1, 2), h.calls.toList())
        }
    }
    @Test fun partiallyRetrievedItemsAreKeptWhenPaginationRenderingHitsCf() {
        Harness { n, r, a ->
            if (n == 2) page(n, r, a).copy(status = SearchStatus.NEEDS_USER_ACTION, errorCode = "CF_CHALLENGE", nextPageToken = null)
            else page(n, r, a)
        }.use { h ->
            h.start()
            val result = h.settled()
            assertEquals(2, result.items.size)
            assertEquals(SearchStatus.NEEDS_USER_ACTION, result.siteStates.single().status)
            assertTrue(result.siteStates.single().pendingPageToken!!.endsWith("page=2"))
        }
    }
}
