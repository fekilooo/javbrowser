package com.example.javbrowser

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SearchPaginationTest {
    private fun site(kind: SearchParserKind = SearchParserKind.MISSAV) = SearchSite("test", "Test", 0, kind, "test", { _, _ -> "https://test.invalid/search/key" }, setOf("test.invalid"))
    private fun detect(html: String, url: String = "https://test.invalid/search/key", kind: SearchParserKind = SearchParserKind.MISSAV) =
        SearchPagination.detect(Jsoup.parse(html, url), url, site(kind))

    @Test fun missavRelNextAndAriaLabelsWorkWithEncodedQueries() {
        val url = "https://test.invalid/search/%E6%B8%AC%E8%A9%A6"
        assertEquals(url + "?page=2", detect("<a rel='next' href='?page=2'>Next</a>", url).next)
        assertEquals(url + "?page=2", detect("<nav><a aria-label='Next »' href='?page=2'>→</a></nav>", url).next)
    }
    @Test fun numericPagerChoosesNearestForwardPageNotFirstOrLastNumber() {
        val result = detect("<nav class='pagination'><a href='?page=1'>1</a><a href='?page=9'>9</a><a href='?page=3'>3</a><a href='?page=2'>2</a></nav>", "https://test.invalid/search/key?page=2")
        assertEquals("https://test.invalid/search/key?page=3", result.next)
    }
    @Test fun numberOutsidePaginationIsNotTreatedAsNextPage() {
        assertNull(detect("<main><a href='?page=2'>2</a></main>").next)
    }
    @Test fun jableKvsNextAndNumericButtonsBuildTheObservedBlockRequest() {
        val html = "<div class='pagination'><a data-action='ajax' data-block-id='list_videos_search' data-parameters='sort_by:post_date;from_videos:2'>下一頁</a></div>"
        val result = detect(html, "https://test.invalid/search/key/", SearchParserKind.JABLE)
        assertEquals("https://test.invalid/search/key/?mode=async&function=get_block&block_id=list_videos_search&from_videos=2", result.next)
        assertEquals(result.next, detect(html.replace("下一頁", "2"), "https://test.invalid/search/key/", SearchParserKind.JABLE).next)
    }
    @Test fun jableFragmentCanReuseCurrentBlockIdForNextPage() {
        val url = "https://test.invalid/search/key/?mode=async&function=get_block&block_id=list_videos_search&from_videos=2"
        val next = detect("<a class='next' data-parameters='from_videos:3'>Next</a>", url, SearchParserKind.JABLE).next
        assertTrue(next!!.contains("from_videos=3"))
        assertTrue(next.contains("block_id=list_videos_search"))
    }
    @Test fun disabledNextIsAnExplicitEndButMissingLinkIsNotProofOfCompleteness() {
        assertEquals(SearchPageState.END_REPORTED, detect("<span aria-disabled='true' aria-label='Next »'>Next</span>").state)
        assertEquals(SearchPageState.NEXT_NOT_FOUND, detect("<main>Results</main>").state)
        assertEquals(SearchPageState.UNSUPPORTED, detect("<button class='next'>Next</button>").state)
    }
    @Test fun rejectsDifferentQueriesPathsHostsBackwardCountersAndJavascript() {
        listOf("https://other.invalid/search/key?page=2", "/search/another?page=2", "?q=another&page=2", "?page=1", "javascript:nextPage()", "?page=-2").forEach {
            assertNull(it, detect("<a rel='next' href='" + it + "'>Next</a>").next)
        }
    }
    @Test fun pathPaginationAndHtmlCountersKeepSearchRoute() {
        assertEquals("https://test.invalid/search/key/page/2/", detect("<a rel='next' href='/search/key/page/2/'>Next</a>", "https://test.invalid/search/key/").next)
        assertEquals("https://test.invalid/zh/search/key/2.html", detect("<a rel='next' href='2.html'>Next</a>", "https://test.invalid/zh/search/key/1.html").next)
    }
    @Test fun numericSearchTermIsNotConfusedWithAPathPageCounter() {
        assertEquals("https://test.invalid/search/123/2/", detect("<a rel='next' href='/search/123/2/'>Next</a>", "https://test.invalid/search/123/", SearchParserKind.JABLE).next)
        assertEquals("https://test.invalid/search/key/3/", detect("<a rel='next' href='/search/key/3/'>Next</a>", "https://test.invalid/search/key/2/", SearchParserKind.JABLE).next)
    }
    @Test fun hiddenControlsAndTrackingOnlyChangesDoNotCreatePages() {
        assertNull(detect("<div hidden><a rel='next' href='?page=2'>Next</a></div>").next)
        assertNull(detect("<a rel='next' href='?_=$123'>Next</a>").next)
    }
    @Test fun pageIdentityIgnoresParameterOrderFragmentAndTracking() {
        assertEquals(SearchPagination.pageKey("https://test.invalid/search/key?page=2&q=x"),
            SearchPagination.pageKey("https://test.invalid/search/key?q=x&page=2&utm_source=other&_=4#next"))
    }
    @Test fun parsingDoesNotDiscardAFullPageAfterThirtyResults() {
        val html = (1..48).joinToString("") { "<div class='video-img-box'><a href='/videos/item-$it'>Neutral $it</a></div>" } + "<a rel='next' href='?page=2'>Next</a>"
        val request = SearchRequest("r", "neutral", "neutral", SearchMode.KEYWORD, listOf("test"), "config")
        val result = SearchHtmlParser(site(SearchParserKind.JABLE), JableSearchProvider.RULES).parse(request, "a", SearchHttpResponse(200, "https://test.invalid/search/key", html, "text/html"))
        assertEquals(48, result.items.size)
        assertEquals(SearchPageState.HAS_NEXT, result.pageState)
        assertNotNull(result.pageFingerprint)
    }
    @Test fun oversizedPageIsExplicitlyMarkedIncomplete() {
        val html = (1..205).joinToString("") { "<div class='video-img-box'><a href='/videos/item-$it'>Neutral $it</a></div>" }
        val request = SearchRequest("r", "neutral", "neutral", SearchMode.KEYWORD, listOf("test"), "config")
        val result = SearchHtmlParser(site(SearchParserKind.JABLE), JableSearchProvider.RULES).parse(request, "a", SearchHttpResponse(200, "https://test.invalid/search/key", html, "text/html"))
        assertEquals(200, result.items.size)
        assertEquals(SearchPageState.PAGE_ITEM_LIMIT, result.pageState)
    }
}
