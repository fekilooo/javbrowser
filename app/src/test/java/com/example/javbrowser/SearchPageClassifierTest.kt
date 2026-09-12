package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

/** Neutral, synthetic pages; no search terms, cookies or content from real users. */
class SearchPageClassifierTest {
    private val site = SearchSite("test", "Test", 0, SearchParserKind.JABLE, "test",
        { _, _ -> "https://test.invalid/search" }, setOf("test.invalid"))
    private val rules = JableSearchProvider.RULES
    private fun parse(html: String, status: Int = 200, cf: Boolean = false, header: String? = null,
                      mode: SearchMode = SearchMode.KEYWORD, path: String = "/search"): SiteSearchResult =
        SearchHtmlParser(site, rules).parse(SearchRequest("r", "TEST-001", "TEST-001", mode, listOf("test"), "test"), "a",
            SearchHttpResponse(status, "https://test.invalid" + path, html, "text/html",
                cfMitigated = header, cloudflareServed = cf))
    private val card = "<div class='video-img-box'><a href='/videos/test-001'>TEST-001</a></div>"

    @Test fun reliableChallengeHeaderWinsAtEveryHttpStatus() {
        listOf(200, 403, 503).forEach {
            val result = parse("", it, header = "challenge")
            assertEquals(SearchStatus.NEEDS_USER_ACTION, result.status)
            assertEquals("CF_CHALLENGE", result.errorCode)
            assertTrue(result.diagnostic.contains("evidence=cf-mitigated"))
        }
    }
    @Test fun challengeDomIsNotAFormatFailure() {
        listOf(200, 403, 503).forEach {
            assertEquals("CF_CHALLENGE", parse("<title>Just a moment</title><form id='challenge-form'>Verify you are human</form>", it).errorCode)
        }
    }
    @Test fun firewallBlocksAndBare403AreDistinct() {
        assertEquals("CF_BLOCKED", parse("<div id='cf-error-details'><h1>Sorry, you have been blocked</h1></div>", 403).errorCode)
        assertEquals("CF_BLOCKED", parse("<h1>Access denied</h1>", 403, cf = true).errorCode)
        assertEquals("HTTP_FORBIDDEN", parse("<h1>Forbidden</h1>", 403, cf = true).errorCode)
        assertEquals("HTTP_FORBIDDEN", parse("", 403).errorCode)
    }
    @Test fun cfBackgroundScriptsAndHeadersAreNotProofOfChallenge() {
        val body = "<main>Unknown search layout</main><script src='/cdn-cgi/challenge-platform/h/g/jsd/main.js'></script>"
        assertEquals("RESULT_CONTAINER_NOT_FOUND", parse(body, cf = true).errorCode)
        assertEquals("RESULT_CONTAINER_NOT_FOUND", parse(body + "<script src='/cdn-cgi/challenge-platform/h/g/orchestrate/jsch/v1'></script>", cf = true).errorCode)
        assertEquals(SearchStatus.SUCCESS, parse(card + body, cf = true).status)
    }
    @Test fun hiddenVerificationAndLoginWidgetsDoNotRejectThePage() {
        val hidden = "<div style='display: none'><form id='challenge-form'>Verify you are human</form><h1>請先登入</h1></div>"
        assertEquals(SearchStatus.SUCCESS, parse(card + hidden).status)
        assertEquals("RESULT_CONTAINER_NOT_FOUND", parse(hidden + "<main>Unknown layout</main>").errorCode)
    }
    @Test fun loginAndNonCfVerificationHaveTheirOwnStatus() {
        assertEquals("LOGIN_REQUIRED", parse("<main><h1>Sign in</h1></main>").errorCode)
        assertEquals("LOGIN_REQUIRED", parse("", 401).errorCode)
        assertEquals("LOGIN_REQUIRED", parse("<form>Account</form>", path = "/login").errorCode)
        assertEquals("HUMAN_VERIFICATION", parse("<h1>Verify you are human</h1>").errorCode)
    }
    @Test fun localizedChallengeIsRecognised() {
        assertEquals("CF_CHALLENGE", parse("<main><h1>正在驗證您是人類</h1></main>", cf = true).errorCode)
    }
    @Test fun explicitEmptyMessagesAreTheOnlySiteWideEmptyEvidence() {
        listOf("No results found", "Nothing found", "沒有搜尋結果", "未找到相關結果", "検索結果がありません").forEach {
            val result = parse("<main><div class='no-results'>" + it + "</div></main>")
            assertEquals(it, SearchStatus.EMPTY, result.status)
            assertEquals(it, "NO_RESULTS", result.errorCode)
        }
        assertEquals("NO_RESULTS", parse("<main><h2>沒有搜尋結果</h2></main>").errorCode)
    }
    @Test fun unrelatedOrHiddenEmptyLabelsNeverMeanNoResults() {
        listOf("<div class='empty'>empty</div>", "<div hidden><div class='no-results'>No results</div></div>",
            "<nav><div class='no-results'>No results</div></nav>", "<main><input value='No results'></main>",
            "<main><h1>Search results for No results</h1></main>",
            "<section class='comments'><div class='no-results'>No results</div></section>").forEach {
            assertEquals(it, SearchStatus.PARSE_ERROR, parse(it).status)
        }
    }
    @Test fun recommendationCardsDoNotMaskExplicitEmptySearch() {
        assertEquals("NO_RESULTS", parse("<main><div class='no-results'>No results found</div></main><section class='recommendations'>" + card + "</section>").errorCode)
    }
    @Test fun challengeTakesPrecedenceOverAnEmptyPlaceholder() {
        assertEquals("CF_CHALLENGE", parse("<div class='no-results'>No results</div>", header = "challenge").errorCode)
    }
    @Test fun unknownAndUnmatchedCodesDoNotClaimTheSiteHasNoResults() {
        assertEquals("CODE_NOT_IDENTIFIED", parse("<div class='video-img-box'><a href='/videos/sample'>Sample</a></div>", mode = SearchMode.CODE).errorCode)
        assertEquals("NO_EXACT_MATCH", parse(card.replace("TEST-001", "TEST-002").replace("test-001", "test-002"), mode = SearchMode.CODE).errorCode)
    }
    @Test fun genuineServerFailuresAreNotVerificationOrEmpty() {
        assertEquals("HTTP_503", parse("<h1>Service unavailable</h1>", 503, cf = true).errorCode)
        assertEquals("HTTP_429", parse("<h1>Too many requests</h1>", 429).errorCode)
    }
    @Test fun allNineProvidersShareChallengeAndExplicitEmptyClassification() {
        val request = SearchRequest("r", "test", "test", SearchMode.KEYWORD, listOf("test"), "test")
        listOf(MissavSearchProvider.RULES, JableSearchProvider.RULES, AvjoySearchProvider.RULES,
            PigavSearchProvider.RULES, AvtodaySearchProvider.RULES, JavhdSearchProvider.RULES,
            SevenMmTvSearchProvider.RULES, AvpleSearchProvider.RULES, WhosSearchProvider.RULES).forEach { rule ->
            val parser = SearchHtmlParser(site, rule)
            val response = SearchHttpResponse(200, "https://test.invalid/search", "<main><div class='no-results'>No results found</div></main>", "text/html")
            assertEquals("NO_RESULTS", parser.parse(request, "a", response).errorCode)
            assertEquals("CF_CHALLENGE", parser.parse(request, "a", response.copy(statusCode = 403, cfMitigated = "challenge")).errorCode)
        }
    }
}
