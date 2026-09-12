package com.example.javbrowser

/** User-facing state is tested separately from networking and Android views. */
object SearchStatusPresentation {
    private fun text(en: Boolean, zh: String, english: String) = if (en) english else zh
    fun pagination(state: SiteSearchState, en: Boolean): String {
        if (state.loadedPages == 0) return ""
        val prefix = text(en, "已抓 ${state.loadedPages} 頁 · ", "${state.loadedPages} pages loaded · ")
        if (!state.status.isTerminal) return prefix + text(en, "正在接續搜尋", "Fetching next page")
        if (state.status !in setOf(SearchStatus.SUCCESS, SearchStatus.EMPTY))
            return prefix + text(en, "後續頁未完成，已有結果保留", "Later page unfinished; results retained")
        return prefix + when (state.pageState) {
            SearchPageState.HAS_NEXT, SearchPageState.AUTO_LIMIT -> text(en, "還有下一頁，可繼續抓取", "More pages available; continue loading")
            SearchPageState.END_REPORTED -> text(en, "原站顯示已到末頁", "Site indicates the last page")
            SearchPageState.NEXT_NOT_FOUND, SearchPageState.UNKNOWN -> text(en, "未發現下一頁連結，未保證已抓全", "No next link found; completeness unconfirmed")
            SearchPageState.UNSUPPORTED -> text(en, "發現未支援的分頁控制，請查看原始頁", "Unsupported pagination control; check original page")
            SearchPageState.REPEATED_PAGE -> text(en, "網站回傳重複頁，已停止以避免循環", "Repeated page detected; stopped to avoid a loop")
            SearchPageState.PAGE_LIMIT -> text(en, "達 ${SearchPagingPolicy.MAX_PAGES} 頁安全上限，尚未抓全", "${SearchPagingPolicy.MAX_PAGES}-page safety limit reached; incomplete")
            SearchPageState.ITEM_LIMIT -> text(en, "達 ${SearchPagingPolicy.MAX_ITEMS} 筆安全上限，尚未抓全", "${SearchPagingPolicy.MAX_ITEMS}-result safety limit reached; incomplete")
            SearchPageState.PAGE_ITEM_LIMIT -> text(en, "本頁超過 ${SearchPagingPolicy.PAGE_ITEMS} 筆，部分資料未匯入，請查看原始頁", "This page exceeds ${SearchPagingPolicy.PAGE_ITEMS} results; some were not imported. Check original page")
        }
    }
    private fun unconfirmedPages(snapshot: SearchSnapshot) = snapshot.siteStates.count {
        it.loadedPages > 0 && it.status.isTerminal && it.pageState in setOf(SearchPageState.NEXT_NOT_FOUND,
            SearchPageState.UNKNOWN, SearchPageState.UNSUPPORTED, SearchPageState.REPEATED_PAGE,
            SearchPageState.PAGE_LIMIT, SearchPageState.ITEM_LIMIT, SearchPageState.PAGE_ITEM_LIMIT)
    }
    private fun groupedCount(items: List<SearchItem>, en: Boolean): String {
        val groups = SearchResultGrouping.group(items)
        val links = groups.sumOf { it.links.size }
        return text(en, "${groups.size} 組 · $links 個連結", "${groups.size} groups · $links links")
    }
    fun compactSummary(snapshot: SearchSnapshot, en: Boolean, visibleItems: List<SearchItem> = snapshot.items): String {
        val base = groupedCount(visibleItems, en) + text(en, " · ${snapshot.completedSites}/${snapshot.selectedSites} 站已回報",
            " · ${snapshot.completedSites}/${snapshot.selectedSites} sites reported")
        val cf = snapshot.siteStates.count { it.errorCode in setOf("CF_CHALLENGE", "CF_BLOCKED") }
        val other = snapshot.siteStates.count { it.status in setOf(SearchStatus.NEEDS_USER_ACTION,
            SearchStatus.NETWORK_ERROR, SearchStatus.PARSE_ERROR, SearchStatus.TIMEOUT,
            SearchStatus.UNSUPPORTED, SearchStatus.CANCELLED) } - cf
        val warnings = buildList {
            if (cf > 0) add(text(en, "CF 驗證／封鎖 $cf 站", "$cf CF challenges/blocks"))
            if (other > 0) add(text(en, "其他待處理 $other 站", "$other sites need attention"))
            val more = snapshot.siteStates.count(SearchPagingPolicy::canContinue)
            if (more > 0) add(text(en, "$more 站仍有後續頁", "$more sites have more pages"))
            val unknown = unconfirmedPages(snapshot)
            if (unknown > 0) add(text(en, "$unknown 站未確認抓全", "$unknown sites not confirmed complete"))
        }
        return (if (snapshot.isRunning) text(en, "搜尋中 · ", "Searching · ") else "") + base +
            if (warnings.isEmpty()) "" else "\n" + warnings.joinToString(" · ")
    }
    fun label(state: SiteSearchState, en: Boolean): String = when (state.errorCode.takeUnless { state.status == SearchStatus.SUCCESS }) {
        "CF_CHALLENGE" -> text(en, "CF 人機驗證", "CF verification")
        "CF_BLOCKED" -> text(en, "CF 防火牆封鎖", "CF firewall block")
        "HTTP_FORBIDDEN" -> text(en, "網站拒絕存取（403）", "Access denied (403)")
        "LOGIN_REQUIRED" -> text(en, "需要登入", "Sign-in required")
        "HUMAN_VERIFICATION" -> text(en, "需要人機驗證", "Human verification")
        "NO_EXACT_MATCH" -> text(en, "沒有相同番號", "No exact code match")
        else -> when (state.status) {
            SearchStatus.QUEUED -> text(en, "排隊中", "Queued")
            SearchStatus.RUNNING -> text(en, "搜尋中", "Searching")
            SearchStatus.SUCCESS -> text(en, "找到 ${state.items.size} 筆", "${state.items.size} results")
            SearchStatus.EMPTY -> text(en, "沒有搜尋結果", "No search results")
            SearchStatus.NEEDS_USER_ACTION -> text(en, "需要手動處理", "Action required")
            SearchStatus.TIMEOUT -> text(en, "連線或載入逾時", "Connection or loading timeout")
            SearchStatus.NETWORK_ERROR -> text(en, "連線失敗", "Connection failed")
            SearchStatus.PARSE_ERROR -> text(en, "未能讀取搜尋結果", "Unable to read search results")
            SearchStatus.UNSUPPORTED -> text(en, "尚未支援", "Unsupported")
            SearchStatus.CANCELLED -> text(en, "已停止", "Stopped")
        }
    }
    fun description(state: SiteSearchState, en: Boolean): String {
        if (state.items.isNotEmpty() && state.errorCode != null) return text(en,
            "已保留先前取得的 " + state.items.size + " 筆。最近查詢頁面：",
            "Kept " + state.items.size + " previous results. Latest page: ") +
            (if (en) SearchFailure.en(state.errorCode) else SearchFailure.zh(state.errorCode))
        state.errorCode?.let { return if (en) SearchFailure.en(it) else SearchFailure.zh(it) }
        return when (state.status) {
            SearchStatus.EMPTY -> text(en, "原始搜尋頁明確表示沒有搜尋結果", "The original page explicitly reports no results")
            SearchStatus.PARSE_ERROR -> text(en, "尚無法判定有無結果，請檢視原始搜尋頁", "Results remain unknown; view the original page")
            SearchStatus.CANCELLED -> text(en, "未完成的搜尋已停止，不代表原站沒有結果", "Search stopped before completion; this does not mean no results")
            SearchStatus.SUCCESS -> text(en, "目前已載入的結果；可開啟原始頁面核對", "Currently loaded results; compare with the original page")
            else -> ""
        }
    }
    fun summary(snapshot: SearchSnapshot, en: Boolean, visibleItems: List<SearchItem> = snapshot.items): String {
        val base = text(en, "本輪已回報 ${snapshot.completedSites}/${snapshot.selectedSites} 站 · ",
            "This batch reported ${snapshot.completedSites}/${snapshot.selectedSites} sites · ") + groupedCount(visibleItems, en)
        val cf = snapshot.siteStates.count { it.errorCode in setOf("CF_CHALLENGE", "CF_BLOCKED") }
        val action = snapshot.siteStates.count { it.status == SearchStatus.NEEDS_USER_ACTION } - cf
        val empty = snapshot.siteStates.count { it.status == SearchStatus.EMPTY }
        val failed = snapshot.siteStates.count { it.status in setOf(SearchStatus.NETWORK_ERROR, SearchStatus.PARSE_ERROR, SearchStatus.TIMEOUT, SearchStatus.UNSUPPORTED) }
        return buildList {
            add(if (snapshot.isRunning) text(en, "搜尋中 · ", "Searching · ") + base else base)
            if (cf > 0) add(text(en, "CF 驗證／封鎖 $cf 站", "$cf CF challenges/blocks"))
            if (action > 0) add(text(en, "其他限制 $action 站", "$action other access restrictions"))
            if (empty > 0) add(text(en, "無符合結果 $empty 站", "$empty sites without matches"))
            if (failed > 0) add(text(en, "未能取得 $failed 站", "$failed sites unavailable"))
            val more = snapshot.siteStates.count(SearchPagingPolicy::canContinue)
            if (more > 0) add(text(en, "$more 站仍有下一頁，可繼續抓取", "$more sites have more pages; continue loading"))
            val unknown = unconfirmedPages(snapshot)
            if (unknown > 0) add(text(en, "$unknown 站未確認抓全，請展開查看分頁狀態", "$unknown sites not confirmed complete; check pagination status"))
        }.joinToString("\n")
    }
    fun emptyMessage(snapshot: SearchSnapshot?, source: String, en: Boolean): String {
        if (snapshot == null) return text(en, "輸入內容後按搜尋", "Enter a query and search")
        val states = snapshot.siteStates.filter { source.isBlank() || it.siteId == source }
        if (states.any { it.items.isNotEmpty() }) return ""
        if (states.isEmpty()) return text(en, "本次沒有選擇這個網站", "This site was not selected")
        if (states.any { !it.status.isTerminal }) return text(en, "搜尋仍在進行，尚未取得可顯示的結果", "Search is in progress; no results loaded yet")
        if (states.size == 1) return label(states.single(), en) + "\n" + description(states.single(), en)
        if (states.all { it.status == SearchStatus.EMPTY }) return if (states.any { it.errorCode == "NO_EXACT_MATCH" })
            text(en, "已載入的結果沒有符合項目；部分原站有結果但番號不同，可檢視原始搜尋頁", "No matches on loaded pages; some sites returned different codes. View the original pages.")
            else text(en, "已選網站皆明確回報沒有搜尋結果", "All selected sites explicitly reported no search results")
        return text(en, "尚未取得可顯示的結果。部分站點有驗證、連線問題或尚未讀取成功；不能判定全部網站沒有結果。請檢視各站狀態與原始搜尋頁。",
            "No results loaded. Some sites require verification, failed to connect, or could not be read. This does not mean all sites have no results. Check each status and original page.")
    }
}
