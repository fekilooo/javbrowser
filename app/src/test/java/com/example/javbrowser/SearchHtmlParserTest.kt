package com.example.javbrowser
import org.junit.Assert.*
import org.junit.Test

class SearchHtmlParserTest {
    private val site = SearchSite("test", "Test", 0, SearchParserKind.JABLE, "test",
        { q, _ -> "https://test.invalid/search?q=" + q }, setOf("test.invalid"))
    private fun request(mode: SearchMode = SearchMode.KEYWORD) =
        SearchRequest("request", "TEST-001", "TEST-001", mode, listOf("test"), "test")
    private fun response(body: String, status: Int = 200) =
        SearchHttpResponse(status, "https://test.invalid/search?q=test", body, "text/html")
    private fun parse(body: String, status: Int = 200) =
        SearchHtmlParser(site, JableSearchProvider.RULES).parse(request(), "attempt", response(body, status))
    private fun fixture(name: String) = javaClass.getResource("/search/$name.html")!!.readText(Charsets.UTF_8)
    private val rules = listOf("missav" to MissavSearchProvider.RULES, "jable" to JableSearchProvider.RULES,
        "avjoy" to AvjoySearchProvider.RULES, "pigav" to PigavSearchProvider.RULES,
        "avtoday" to AvtodaySearchProvider.RULES, "javhd" to JavhdSearchProvider.RULES,
        "sevenmmtv" to SevenMmTvSearchProvider.RULES, "avple" to AvpleSearchProvider.RULES,
        "whos" to WhosSearchProvider.RULES)

    @Test fun allNineStructuralContractsParseResultsAndExplicitEmptyStates() {
        rules.forEach { (name, rule) ->
            val parser = SearchHtmlParser(site, rule)
            val result = parser.parse(request(SearchMode.CODE), "attempt", response(fixture(name + "-results")))
            assertEquals(name, SearchStatus.SUCCESS, result.status)
            assertEquals(name, 1, result.items.size)
            assertEquals(name, "TEST-001", result.items.single().title)
            assertEquals(name, "https://test.invalid/cover.png", result.items.single().coverUrl)
            assertTrue(name, result.items.single().canonicalUrl.contains("id=17"))
            assertFalse(name, result.items.single().canonicalUrl.contains("utm_"))
            assertEquals(name, SearchStatus.EMPTY, parser.parse(request(), "attempt",
                response(fixture(name + "-empty"))).status)
        }
    }
    @Test fun recordsWhichParsingStageFailed() {
        assertEquals("RESULT_CONTAINER_NOT_FOUND", parse("<main>unknown layout</main>").errorCode)
        assertEquals("DETAIL_LINKS_REJECTED", parse("<div class='video-img-box'><a href='https://ads.invalid/videos/x'>x</a></div>").errorCode)
        assertEquals("CARD_DATA_MISSING", parse("<div class='video-img-box'><a href='/videos/x'></a></div>").errorCode)
    }
    @Test fun verificationHttpAndUnknownPagesAreNotEmptyResults() {
        assertEquals(SearchStatus.NEEDS_USER_ACTION, parse("<title>Just a moment</title>").status)
        assertEquals(SearchStatus.NEEDS_USER_ACTION, parse("", 403).status)
        assertEquals("HTTP_404", parse("", 404).errorCode)
        assertEquals("HTTP_429", parse("", 429).errorCode)
        assertEquals(SearchStatus.PARSE_ERROR, parse("<div class='empty'>empty unrelated widget</div>").status)
    }
    @Test fun embeddedLoginWidgetDoesNotRejectValidCards() {
        val body = fixture("jable-results") + "<aside style='display:none'>請先登入</aside>"
        assertEquals(SearchStatus.SUCCESS, parse(body).status)
    }
    @Test fun exactCodeDoesNotAcceptLongerNumbersOrQueryInSearchBox() {
        val body = "<input value='TEST-001'><div class='video-img-box'><a href='/videos/test-0012'>TEST-0012</a></div>"
        val result = SearchHtmlParser(site, JableSearchProvider.RULES).parse(request(SearchMode.CODE), "attempt", response(body))
        assertEquals(SearchStatus.EMPTY, result.status)
    }
    @Test fun diagnosticsExcludeContentAndFullUrls() {
        val result = parse(fixture("jable-results"))
        assertFalse(result.diagnostic.contains("TEST-001"))
        assertFalse(result.diagnostic.contains("?q="))
        assertTrue(result.diagnostic.contains("selected="))
    }

    @Test fun articleHeaderIsNotMistakenForGlobalPageHeader() {
        val parser = SearchHtmlParser(site, JavhdSearchProvider.RULES)
        val card = "<article><header><h2><a href='/video/test-001/'>TEST-001</a></h2></header></article>"
        assertEquals(SearchStatus.SUCCESS, parser.parse(request(), "a", response(card)).status)
        assertEquals(SearchStatus.PARSE_ERROR, parser.parse(request(), "a", response("<header>" + card + "</header>")).status)
    }

    @Test fun avjoySupportsNumericIdAndSlugWithoutAllowingArbitraryRoutes() {
        val parser = SearchHtmlParser(site, AvjoySearchProvider.RULES)
        val body = "<div class='content-row'><div class='content-info'><a href='/video/12345/test-001'><span class='content-title'>TEST-001</span></a></div></div>"
        assertEquals(SearchStatus.SUCCESS, parser.parse(request(SearchMode.CODE), "a", response(body)).status)
        assertEquals(SearchStatus.PARSE_ERROR, parser.parse(request(), "a", response(body.replace("/12345/", "/category/"))).status)
    }

    @Test fun linksWithTheirOwnTitlesDoNotRequireGuessedCardClasses() {
        listOf(AvpleSearchProvider.RULES to "/video/", WhosSearchProvider.RULES to "/videos/").forEach { (rules, route) ->
            val parser = SearchHtmlParser(site, rules)
            val body = "<div class='unknown-framework-wrapper'><a href='" + route + "test-001' title='TEST-001'><img data-src='/cover.png'></a></div>"
            val result = parser.parse(request(SearchMode.CODE), "a", response(body))
            assertEquals(SearchStatus.SUCCESS, result.status)
            assertEquals("TEST-001", result.items.single().title)
        }
    }

    @Test fun boundedFallbackNeverBorrowsANeighbouringCardsTitle() {
        val parser = SearchHtmlParser(site, AvpleSearchProvider.RULES)
        val body = "<section><a href='/video/test-001'></a><a href='/video/test-002'>TEST-002</a></section>"
        val result = parser.parse(request(SearchMode.CODE), "a", response(body))
        assertEquals(SearchStatus.EMPTY, result.status)
        assertTrue(result.items.isEmpty())
    }

    @Test fun javhdAcceptsBothReportedChineseDetailRoutes() {
        val parser = SearchHtmlParser(site, JavhdSearchProvider.RULES)
        listOf("/zh/video/test-001/", "/zh/variant/video/test-001/").forEach { route ->
            val body = "<article><a href='" + route + "'>TEST-001</a></article>"
            assertEquals(route, SearchStatus.SUCCESS, parser.parse(request(SearchMode.CODE), "a", response(body)).status)
        }
        assertFalse(JavhdSearchProvider.RULES.detailPath.matches("/zh/category/test-001/"))
    }

    @Test fun missavTwoSegmentRouteStillRejectsNavigationPages() {
        val parser = SearchHtmlParser(site, MissavSearchProvider.RULES)
        val body = "<div class='thumbnail'><a href='/route42/test-001'>TEST-001</a></div>"
        assertEquals(SearchStatus.SUCCESS, parser.parse(request(SearchMode.CODE), "a", response(body)).status)
        listOf("/search/test-001", "/category/test-001", "/actresses/test-001", "/route42/not-a-code").forEach {
            assertFalse(it, MissavSearchProvider.RULES.detailPath.matches(it))
        }
    }

    @Test fun missingContainerDiagnosticsRevealOnlyRedactedRouteCounts() {
        val parser = SearchHtmlParser(site, JableSearchProvider.RULES)
        val result = parser.parse(request(), "a", response("<main><a href='/videos/123456?private=secret'>Private title</a></main>"))
        assertEquals("RESULT_CONTAINER_NOT_FOUND", result.errorCode)
        assertTrue(result.diagnostic.contains("knownDetailLinks=1"))
        assertTrue(result.diagnostic.contains("/videos/:id=1"))
        assertFalse(result.diagnostic.contains("123456"))
        assertFalse(result.diagnostic.contains("secret"))
        assertFalse(result.diagnostic.contains("Private title"))
    }
}
