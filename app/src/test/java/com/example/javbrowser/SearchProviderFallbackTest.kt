package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

/** No real site data or network: verify the complete HTTP-to-rendered-page decision path. */
class SearchProviderFallbackTest {
    private val site = SearchSite("test", "Test", 0, SearchParserKind.PIGAV, "test",
        { _, page -> page ?: "https://test.invalid/search?q=test" }, setOf("test.invalid"))
    private val request = SearchRequest("r", "TEST-001", "TEST-001", SearchMode.KEYWORD, listOf("test"), "test")
    private val shell = "<html><head><base href='/'><script src='/main.js'></script></head><body><my-app>Loading</my-app></body></html>"
    private val card = "<my-app><div class='unknown-wrapper'><a href='/w/neutral-id'><img data-src='/cover.png'></a><span class='video-name'>TEST-001</span></div></my-app>"
    private fun response(html: String, status: Int = 200) = SearchHttpResponse(status, site.buildSearchUrl("test", null), html, "text/html")

    @Test fun pigavLoadsDynamicShellThenParsesCardsWithoutGuessedClasses() {
        var rendered = 0
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES, { _, _ -> response(shell) }, { url, _ ->
            assertEquals(site.buildSearchUrl("test", null), url)
            rendered++
            response(card).copy(rendered = true)
        })
        val result = provider.search(request, attemptId = "a")
        assertEquals(1, rendered)
        assertEquals(SearchStatus.SUCCESS, result.status)
        assertEquals("TEST-001", result.items.single().title)
        assertEquals("https://test.invalid/cover.png", result.items.single().coverUrl)
        assertTrue(result.diagnostic.contains("phase=WebView"))
    }
    @Test fun unresolvedRenderedShellIsNotReportedAsWrongFormatOrEmpty() {
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES, { _, _ -> response(shell) }, { _, _ -> response(shell).copy(rendered = true) })
        val result = provider.search(request)
        assertEquals(SearchStatus.TIMEOUT, result.status)
        assertEquals("DYNAMIC_RESULTS_NOT_READY", result.errorCode)
        assertTrue(result.diagnostic.contains("appShell=true"))
        assertTrue(result.diagnostic.contains("rendered=true"))
    }
    @Test fun challengeIsNotSentToAnAutomaticBrowserAttempt() {
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES,
            { _, _ -> response("", 403).copy(cfMitigated = "challenge") }, { _, _ -> error("Must await manual verification") })
        val result = provider.search(request)
        assertEquals(SearchStatus.NEEDS_USER_ACTION, result.status)
        assertEquals("CF_CHALLENGE", result.errorCode)
    }
    @Test fun challengeEncounteredDuringRenderingKeepsItsTrueStatus() {
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES,
            { _, _ -> response(shell) }, { _, _ -> response("", 403).copy(cfMitigated = "challenge", rendered = true) })
        assertEquals("CF_CHALLENGE", provider.search(request).errorCode)
    }
    @Test fun explicitEmptyAndSuccessfulHttpResultsDoNotNeedRendering() {
        listOf(card to SearchStatus.SUCCESS, "<main><div class='no-results'>No results found</div></main>" to SearchStatus.EMPTY).forEach { (html, status) ->
            var calls = 0
            val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES,
                { _, _ -> response(html) }, { _, _ -> calls++; response("") })
            assertEquals(status, provider.search(request).status)
            assertEquals(0, calls)
        }
    }
    @Test fun avtodayAcceptsReportedPrefixedRoutesAfterManualVerification() {
        listOf("/cht/video/test-001", "/en/video/test-001", "/video/test-001", "/videos/test-001").forEach { path ->
            val provider = SiteHtmlSearchProvider(site, AvtodaySearchProvider.RULES,
                { _, _ -> response("<div class='thumbnail'><a href='" + path + "'>TEST-001</a></div>") },
                { _, _ -> error("HTTP result should already parse") })
            assertEquals(path, SearchStatus.SUCCESS, provider.search(request).status)
        }
    }
    @Test fun avtodayStillRejectsOffsiteAdsAndNavigation() {
        val html = "<div class='thumbnail'><a href='https://ad.invalid/cht/video/ad'>Ad</a><a href='/cht/category/sample'>Category</a><a href='/cht/video/test-001'>TEST-001</a></div>"
        val parsed = SearchHtmlParser(site, AvtodaySearchProvider.RULES).parse(request, "a", response(html))
        assertEquals(1, parsed.items.size)
        assertTrue(parsed.diagnostic.contains("rejectedHost=1"))
        assertTrue(parsed.diagnostic.contains("rejectedPath=1"))
    }
    @Test fun cancelledHttpDoesNotStartRendering() {
        val cancellation = SearchCancellation()
        var renderCalls = 0
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES,
            { _, token -> token.cancel(); response(shell) }, { _, _ -> renderCalls++; response(card) })
        try {
            provider.search(request, cancellation = cancellation)
            fail("Expected cancellation")
        } catch (_: InterruptedException) { assertEquals(0, renderCalls) }
    }
    @Test fun noResultMessageAfterRenderingIsRecognised() {
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES,
            { _, _ -> response(shell) }, { _, _ -> response("<my-app><main><h2>No results found</h2></main></my-app>").copy(rendered = true) })
        assertEquals("NO_RESULTS", provider.search(request).errorCode)
    }
    @Test fun sameHostRedirectKeepsTheFinalSearchAddressForRendering() {
        val finalUrl = "https://test.invalid/search?search=test&searchTarget=local"
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES,
            { _, _ -> response(shell).copy(finalUrl = finalUrl) }, { url, _ -> assertEquals(finalUrl, url); response(card) })
        assertEquals(SearchStatus.SUCCESS, provider.search(request).status)
    }
    @Test fun pigavDoesNotBorrowAnotherCardsTitleOrAcceptOffsiteLinks() {
        val html = "<main><a href='/w/empty'></a><a href='/w/valid'>TEST-001</a><a href='https://ad.invalid/w/ad'>Ad</a></main>"
        val result = SearchHtmlParser(site, PigavSearchProvider.RULES).parse(request, "a", response(html))
        assertEquals(1, result.items.size)
        assertEquals("https://test.invalid/w/valid", result.items.single().detailUrl)
    }
    @Test fun unapprovedFinalHostCannotBeLoadedByFallback() {
        var calls = 0
        val provider = SiteHtmlSearchProvider(site, PigavSearchProvider.RULES,
            { _, _ -> response(shell).copy(finalUrl = "https://unapproved.invalid/search") },
            { _, _ -> calls++; response(card) })
        assertEquals("UNEXPECTED_HOST", provider.search(request).errorCode)
        assertEquals(0, calls)
    }
}
