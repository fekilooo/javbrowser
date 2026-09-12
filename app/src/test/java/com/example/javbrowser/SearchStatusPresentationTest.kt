package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SearchStatusPresentationTest {
    @Test fun missingNextLinkIsVisibleEvenWhenThePanelIsCollapsed() {
        val data = snapshot(state(SearchStatus.SUCCESS).copy(loadedPages = 1, pageState = SearchPageState.NEXT_NOT_FOUND))
        assertTrue(SearchStatusPresentation.compactSummary(data, false).contains("未確認抓全"))
        assertTrue(SearchStatusPresentation.pagination(data.siteStates.single(), false).contains("未保證已抓全"))
    }
    @Test fun compactStatusKeepsCfAndOtherFailuresVisible() {
        val data = snapshot(state(SearchStatus.NEEDS_USER_ACTION, "CF_CHALLENGE"),
            state(SearchStatus.PARSE_ERROR, "RESULT_CONTAINER_NOT_FOUND", "other"))
        val message = SearchStatusPresentation.compactSummary(data, false)
        assertTrue(message.contains("CF 驗證／封鎖 1 站"))
        assertTrue(message.contains("其他待處理 1 站"))
        assertEquals(2, message.lines().size)
    }
    private fun state(status: SearchStatus, code: String? = null, id: String = "test") =
        SiteSearchState(id, status, errorCode = code)
    private fun snapshot(vararg states: SiteSearchState) = SearchSnapshot(
        SearchRequest("r", "test", "test", SearchMode.KEYWORD, states.map { it.siteId }, "test"),
        states.toList(), states.flatMap { it.items }, states.count { it.status.isTerminal }, states.size,
        states.any { !it.status.isTerminal })

    @Test fun cfLabelsAreExplicitAndDoNotSayFormatError() {
        assertEquals("CF 人機驗證", SearchStatusPresentation.label(state(SearchStatus.NEEDS_USER_ACTION, "CF_CHALLENGE"), false))
        assertEquals("CF 防火牆封鎖", SearchStatusPresentation.label(state(SearchStatus.NEEDS_USER_ACTION, "CF_BLOCKED"), false))
        assertEquals("網站拒絕存取（403）", SearchStatusPresentation.label(state(SearchStatus.NEEDS_USER_ACTION, "HTTP_FORBIDDEN"), false))
    }
    @Test fun mixedFailuresAndEmptySitesNeverClaimAllSitesAreEmpty() {
        val data = snapshot(state(SearchStatus.EMPTY, "NO_RESULTS"), state(SearchStatus.NEEDS_USER_ACTION, "CF_CHALLENGE", "other"))
        assertTrue(SearchStatusPresentation.emptyMessage(data, "", false).contains("不能判定"))
        assertFalse(SearchStatusPresentation.emptyMessage(data, "", false).contains("皆明確回報"))
        assertTrue(SearchStatusPresentation.summary(data, false).contains("CF 驗證／封鎖 1 站"))
    }
    @Test fun explicitEmptySitesShowNoSearchResults() {
        val data = snapshot(state(SearchStatus.EMPTY, "NO_RESULTS"), state(SearchStatus.EMPTY, "NO_RESULTS", "other"))
        assertEquals("已選網站皆明確回報沒有搜尋結果", SearchStatusPresentation.emptyMessage(data, "", false))
    }
    @Test fun codeMismatchAndParseFailureAreDifferentFromSiteEmpty() {
        assertEquals("沒有相同番號", SearchStatusPresentation.label(state(SearchStatus.EMPTY, "NO_EXACT_MATCH"), false))
        assertTrue(SearchStatusPresentation.emptyMessage(snapshot(state(SearchStatus.PARSE_ERROR, "RESULT_CONTAINER_NOT_FOUND")), "", false).contains("尚無法判定"))
    }
    @Test fun sourceFilterShowsOnlyThatSitesState() {
        val data = snapshot(state(SearchStatus.EMPTY, "NO_RESULTS"), state(SearchStatus.NEEDS_USER_ACTION, "CF_BLOCKED", "other"))
        val message = SearchStatusPresentation.emptyMessage(data, "other", false)
        assertTrue(message.startsWith("CF 防火牆封鎖"))
        assertFalse(message.contains("皆明確回報"))
    }
    @Test fun pendingAndStoppedSearchesAreNotEmpty() {
        assertTrue(SearchStatusPresentation.emptyMessage(snapshot(state(SearchStatus.RUNNING)), "", false).contains("仍在進行"))
        assertTrue(SearchStatusPresentation.emptyMessage(snapshot(state(SearchStatus.CANCELLED)), "", false).contains("不代表"))
    }
    @Test fun previousResultsSurviveLaterPageRestrictionsInPresentation() {
        val item = SearchItem("i", "test", "Test", "https://test.invalid/item", "https://test.invalid/item")
        val data = state(SearchStatus.NEEDS_USER_ACTION, "CF_CHALLENGE").copy(items = listOf(item))
        assertTrue(SearchStatusPresentation.description(data, false).contains("已保留先前取得的 1 筆"))
        assertEquals("", SearchStatusPresentation.emptyMessage(snapshot(data), "", false))
        val exhausted = data.copy(status = SearchStatus.SUCCESS, errorCode = "NO_EXACT_MATCH")
        assertEquals("找到 1 筆", SearchStatusPresentation.label(exhausted, false))
    }
}
