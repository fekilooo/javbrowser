package com.example.javbrowser

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 九個來源共用的搜尋與結果頁。 */
class UnifiedSearchActivity : LocalizedActivity(), SearchSnapshotListener {
    companion object {
        const val EXTRA_QUERY = "unified_search_query"
        const val EXTRA_FORCE_CODE = "unified_search_force_code"
    }

    private lateinit var queryEdit: EditText
    private lateinit var searchButton: Button
    private lateinit var stopButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var siteChooser: LinearLayout
    private lateinit var siteStatuses: LinearLayout
    private lateinit var sourceSpinner: Spinner
    private lateinit var resultsView: RecyclerView
    private lateinit var adapter: UnifiedSearchAdapter
    private lateinit var repository: UnifiedSearchRepository
    private lateinit var sites: List<SearchSite>
    private val siteChecks = LinkedHashMap<String, CheckBox>()
    private var session: UnifiedSearchRepository.UnifiedSearchSession? = null
    private var lastSnapshot: SearchSnapshot? = null
    private var forceCodeForNextSearch = false
    private var selectedSource = ""
    private var activeRequestId: String? = null
    private var restoredListState: android.os.Parcelable? = null
    private var panelState = SearchPanelState()
    private lateinit var savedSearchStore: SavedSearchStore
    private var savedSearches = emptyList<SavedSearchInfo>()
    private var savedSearchesReady = false
    private var savingSearch = false
    private var savedReadSerial = 0
    private var openedSavedAt: Long? = null
    private var openedSavedKey: String? = null
    private var runMode = SearchRunMode.BROWSE
    private var queryMode = SearchInputMode.AUTO
    private var sortMode = SearchSortMode.RELEVANCE
    private var preserveSessionOnStop = false
    private var renderedSiteStates: List<SiteSearchState>? = null
    private var readAnchor: SearchReadAnchor? = null
    private var restoredAnchor: SearchReadAnchor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unified_search)

        repository = UnifiedSearchRepository(this)
        sites = SearchSiteRegistry.sites(this)
        savedSearchStore = SavedSearchStore(java.io.File(noBackupFilesDir, "saved_searches"))
        openedSavedAt = savedInstanceState?.getLong("opened_saved_at", 0L)?.takeIf { it > 0 }
        openedSavedKey = savedInstanceState?.getString("opened_saved_key")
        queryEdit = findViewById(R.id.et_unified_query)
        searchButton = findViewById(R.id.btn_unified_search)
        stopButton = findViewById(R.id.btn_unified_stop)
        progress = findViewById(R.id.progress_unified)
        statusText = findViewById(R.id.tv_unified_status)
        siteChooser = findViewById(R.id.unified_site_chooser)
        siteStatuses = findViewById(R.id.unified_site_statuses)
        sourceSpinner = findViewById(R.id.spinner_unified_source)
        panelState = SearchPanelState(
            savedInstanceState?.getBoolean("controls_collapsed") ?: false,
            savedInstanceState?.getBoolean("controls_collapse_handled") ?: false
        )
        runMode = if (savedInstanceState?.getString("run_mode") == SearchRunMode.DEEP.name)
            SearchRunMode.DEEP else SearchRunMode.BROWSE
        queryMode = runCatching { SearchInputMode.valueOf(savedInstanceState?.getString("query_mode").orEmpty()) }
            .getOrDefault(SearchInputMode.AUTO)
        sortMode = runCatching { SearchSortMode.valueOf(savedInstanceState?.getString("sort_mode").orEmpty()) }
            .getOrDefault(SearchSortMode.RELEVANCE)
        findViewById<Button>(R.id.btn_unified_run_mode).setOnClickListener {
            runMode = if (runMode == SearchRunMode.BROWSE) SearchRunMode.DEEP else SearchRunMode.BROWSE
            renderPanel()
        }
        findViewById<Button>(R.id.btn_unified_query_mode).setOnClickListener {
            queryMode = when (queryMode) {
                SearchInputMode.AUTO -> SearchInputMode.CODE
                SearchInputMode.CODE -> SearchInputMode.KEYWORD
                SearchInputMode.KEYWORD -> SearchInputMode.AUTO
            }
            renderPanel()
        }
        findViewById<Button>(R.id.btn_unified_sort_mode).setOnClickListener {
            sortMode = when (sortMode) {
                SearchSortMode.RELEVANCE -> SearchSortMode.SOURCE_PREFERENCE
                SearchSortMode.SOURCE_PREFERENCE -> SearchSortMode.ORIGINAL
                SearchSortMode.ORIGINAL -> SearchSortMode.RELEVANCE
            }
            renderItems(lastSnapshot)
            renderPanel()
        }
        findViewById<Button>(R.id.btn_unified_toggle_controls).setOnClickListener {
            panelState = panelState.toggle()
            if (panelState.collapsed) hideSearchKeyboard()
            renderPanel()
        }
        renderPanel()

        adapter = UnifiedSearchAdapter(sites.associate { it.id to it.displayName },
            onOpen = { item ->
                rememberReadAnchor()
                preserveSessionOnStop = true
                BrowserNavigator.open(this, item.detailUrl)
            },
            onShowSources = { group ->
                rememberReadAnchor()
                UnifiedSearchSourceSheet.show(this, group, sites.associate { it.id to it.displayName }) { item ->
                    preserveSessionOnStop = true
                    BrowserNavigator.open(this, item.detailUrl)
                }
            })
        resultsView = findViewById<RecyclerView>(R.id.rv_unified_results).apply {
            layoutManager = LinearLayoutManager(this@UnifiedSearchActivity)
            adapter = this@UnifiedSearchActivity.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) this@UnifiedSearchActivity.adapter.setOrderFrozen(true)
                }
            })
        }
        adapter.onListCommitted = { restoreReadAnchorIfNeeded() }
        loadLocalMetadata()

        setupSiteChooser()
        setupSourceFilter()
        findViewById<Button>(R.id.btn_unified_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_unified_select_all).setOnClickListener {
            siteChecks.values.forEach { it.isChecked = true }
        }
        findViewById<Button>(R.id.btn_unified_clear_sites).setOnClickListener {
            siteChecks.values.forEach { it.isChecked = false }
        }
        searchButton.setOnClickListener { startSearch() }
        findViewById<Button>(R.id.btn_unified_save_search).setOnClickListener { saveCurrentSearch() }
        findViewById<Button>(R.id.btn_unified_saved_searches).setOnClickListener { showSavedSearches() }
        stopButton.setOnClickListener { session?.cancel() }
        findViewById<Button>(R.id.btn_unified_copy_diagnostics).setOnClickListener {
            copyDiagnostics(lastSnapshot?.siteStates.orEmpty())
        }
        findViewById<Button>(R.id.btn_unified_resume).setOnClickListener { session?.resume() }
        findViewById<Button>(R.id.btn_unified_continue_pages).setOnClickListener {
            session?.continuePages(selectedSource.takeIf { it.isNotBlank() })
        }
        queryEdit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                startSearch()
                true
            } else false
        }

        queryEdit.setText(savedInstanceState?.getString("query") ?: intent.getStringExtra(EXTRA_QUERY).orEmpty())
        forceCodeForNextSearch = intent.getBooleanExtra(EXTRA_FORCE_CODE, false)
        if (forceCodeForNextSearch) queryMode = SearchInputMode.CODE
        if (savedInstanceState != null) {
            val selected = savedInstanceState.getStringArrayList("sites").orEmpty()
            siteChecks.forEach { (id, check) -> check.isChecked = id in selected }
            sourceSpinner.setSelection(savedInstanceState.getInt("source", 0))
            restoredListState = savedInstanceState.getParcelable("scroll")
            restoredAnchor = savedInstanceState.getString("anchor_group")?.let {
                SearchReadAnchor(it, savedInstanceState.getInt("anchor_offset", 0))
            }
            val previous = SearchSessionStore.get(savedInstanceState.getString("session"))
            if (previous != null) {
                activeRequestId = previous.request.requestId
                session = repository.start(previous.request, this, restored = previous)
            } else {
                panelState = SearchPanelState()
                renderPanel()
                statusText.text = tr("搜尋狀態已清除，請重新搜尋", "Search state was cleared. Search again.")
                openedSavedKey?.let { openSavedSearch(it) }
            }
        }
        refreshSavedSearches {
            if (savedInstanceState == null && queryEdit.text.toString().isNotBlank() && lastSnapshot == null) startSearch(false)
        }
    }

    private fun setupSiteChooser() {
        sites.forEach { site ->
            val check = CheckBox(this).apply {
                text = site.displayName
                tag = site.id
                isChecked = true
                textSize = 11f
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.rgb(187, 134, 252)
                )
                setPadding(0, 0, dp(6), 0)
            }
            siteChecks[site.id] = check
            siteChooser.addView(check, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
            ))
        }
    }

    private fun loadLocalMetadata() {
        SavedSearchStore.io.execute {
            val index = runCatching {
                SearchLocalMetadataIndex.from(FavoritesManager(this).getFavorites(), DownloadRepository.list(this))
            }.getOrDefault(SearchLocalMetadataIndex.EMPTY)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) adapter.setLocalMetadata(index)
            }
        }
    }

    private fun setupSourceFilter() {
        val labels = listOf(tr("全部來源", "All sources")) + sites.map { it.displayName }
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedSource = if (position == 0) "" else sites[position - 1].id
                renderItems(lastSnapshot)
                renderPanel()
            }
        }
    }

    private fun startSearch(bypassCache: Boolean = true, offerSaved: Boolean = true) {
        if (!savedSearchesReady) {
            Toast.makeText(this, tr("正在讀取已存搜尋，請稍候", "Loading saved searches; please wait"), Toast.LENGTH_SHORT).show()
            return
        }
        savedReadSerial++
        val raw = queryEdit.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, tr("請輸入番號或關鍵字", "Enter a code or keyword"), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = siteChecks.filterValues { it.isChecked }.keys.toList()
        if (selected.isEmpty()) {
            Toast.makeText(this, tr("至少選擇一個網站", "Select at least one site"), Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = SearchQueryNormalizer.normalize(raw, queryMode)
        val key = SavedSearchCodec.key(normalized.normalized, normalized.mode)
        val existing = savedSearches.firstOrNull { it.key == key }
        if (offerSaved && existing != null && openedSavedKey != key) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(tr("找到已存搜尋", "Saved search found"))
                .setMessage(savedSearchLabel(existing) + "\n" + tr("可直接開啟上次結果，或重新搜尋最新資料。", "Open previous results or run a fresh search."))
                .setPositiveButton(tr("開啟已存結果", "Open saved")) { _, _ -> openSavedSearch(existing.key) }
                .setNeutralButton(tr("重新搜尋", "Search again")) { _, _ -> startSearch(bypassCache, false) }
                .setNegativeButton(tr("取消", "Cancel"), null).show()
            return
        }

        session?.cancel()
        SearchSessionStore.remove(activeRequestId)
        repository.close()
        repository = UnifiedSearchRepository(this)
        lastSnapshot = null
        renderedSiteStates = null
        adapter.setOrderFrozen(false)
        readAnchor = null
        restoredAnchor = null
        openedSavedAt = null
        openedSavedKey = null
        panelState = SearchPanelState()
        renderPanel()
        adapter.submitItems(emptyList())
        siteStatuses.removeAllViews()
        val request = repository.createRequest(raw, queryMode == SearchInputMode.CODE, selected, runMode, queryMode)
        forceCodeForNextSearch = false
        activeRequestId = request.requestId
        session = repository.start(request, this, bypassCache)
        hideSearchKeyboard()
    }

    override fun onSnapshot(snapshot: SearchSnapshot) {
        if (snapshot.request.requestId != activeRequestId || isDestroyed) return
        lastSnapshot = snapshot
        SearchSessionStore.put(snapshot)
        val running = snapshot.isRunning
        progress.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        stopButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        searchButton.isEnabled = true
        searchButton.text = tr("重新搜尋", "Search again")
        val wasCollapsed = panelState.collapsed
        panelState = panelState.onResults(snapshot.items.isNotEmpty())
        if (!wasCollapsed && panelState.collapsed) hideSearchKeyboard()
        renderPanel()
        renderItems(snapshot)
        if (renderedSiteStates != snapshot.siteStates) {
            renderSiteStatuses(snapshot)
            renderedSiteStates = snapshot.siteStates
        }
    }

    private fun hideSearchKeyboard() {
        queryEdit.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(queryEdit.windowToken, 0)
    }

    private fun renderPanel() {
        val currentKey = lastSnapshot?.request?.let { SavedSearchCodec.key(it.normalizedQuery, it.mode) }
        findViewById<Button>(R.id.btn_unified_save_search).apply {
            isEnabled = savedSearchesReady && !savingSearch && lastSnapshot?.items?.isNotEmpty() == true
            text = if (savedSearches.any { it.key == currentKey }) tr("更新儲存", "Update saved") else tr("儲存搜尋", "Save search")
        }
        findViewById<Button>(R.id.btn_unified_saved_searches).apply {
            isEnabled = savedSearchesReady && !savingSearch
            text = tr("已存搜尋（${savedSearches.size}）", "Saved (${savedSearches.size})")
        }
        findViewById<Button>(R.id.btn_unified_run_mode).apply {
            text = if (runMode == SearchRunMode.BROWSE)
                tr("流暢瀏覽 · 首頁優先", "Smooth browse · first page first")
            else tr("深入搜尋 · 自動續頁", "Deep search · auto next pages")
            contentDescription = text
        }
        findViewById<Button>(R.id.btn_unified_query_mode).apply {
            text = when (queryMode) {
                SearchInputMode.AUTO -> tr("查詢模式：自動辨識", "Query mode: Auto")
                SearchInputMode.CODE -> tr("查詢模式：精確番號", "Query mode: Exact code")
                SearchInputMode.KEYWORD -> tr("查詢模式：關鍵字", "Query mode: Keyword")
            }
            contentDescription = text
        }
        findViewById<Button>(R.id.btn_unified_sort_mode).apply {
            text = when (sortMode) {
                SearchSortMode.RELEVANCE -> tr("排序：相關性", "Sort: Relevance")
                SearchSortMode.SOURCE_PREFERENCE -> tr("排序：來源偏好", "Sort: Source preference")
                SearchSortMode.ORIGINAL -> tr("排序：原站順序", "Sort: Original order")
            }
            contentDescription = text
        }
        findViewById<TextView>(R.id.tv_unified_saved_notice).apply {
            visibility = if (openedSavedAt != null) android.view.View.VISIBLE else android.view.View.GONE
            text = openedSavedAt?.let { tr("載入儲存時間：", "Loaded save from: ") + savedTime(it) +
                tr(" · 含非即時資料；更新後請再按儲存", " · Includes saved data; save again after updating") }.orEmpty()
        }
        val resumable = lastSnapshot?.siteStates.orEmpty().filter { selectedSource.isBlank() || it.siteId == selectedSource }
            .filter { SearchPagingPolicy.canContinue(it) || it.status in setOf(SearchStatus.CANCELLED, SearchStatus.TIMEOUT) }
        findViewById<Button>(R.id.btn_unified_continue_pages).apply {
            visibility = if (resumable.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            isEnabled = lastSnapshot?.isRunning != true
            text = tr("繼續抓取後續頁（${resumable.size} 站）", "Continue next pages (${resumable.size} sites)")
        }
        val visibility = if (panelState.collapsed) android.view.View.GONE else android.view.View.VISIBLE
        listOf(
            R.id.btn_unified_run_mode,
            R.id.unified_query_controls,
            R.id.btn_unified_query_mode,
            R.id.btn_unified_sort_mode,
            R.id.unified_selection_controls,
            R.id.unified_site_chooser_scroll,
            R.id.btn_unified_save_search,
            R.id.btn_unified_saved_searches,
            R.id.tv_unified_saved_notice,
            R.id.btn_unified_copy_diagnostics,
            R.id.unified_site_status_scroll
        ).forEach { findViewById<android.view.View>(it).visibility = visibility }
        findViewById<Button>(R.id.btn_unified_resume).visibility =
            if (!panelState.collapsed && lastSnapshot?.siteStates?.any { it.status == SearchStatus.CANCELLED } == true)
                android.view.View.VISIBLE else android.view.View.GONE
        findViewById<Button>(R.id.btn_unified_toggle_controls).apply {
            text = if (panelState.collapsed) tr("展開設定與站點 ▾", "Show controls ▾")
                else tr("收合設定與站點 ▴", "Hide controls ▴")
            contentDescription = text
        }
        statusText.text = lastSnapshot?.let {
            val visibleItems = it.items.filter { item -> selectedSource.isBlank() || item.siteId == selectedSource }
            if (panelState.collapsed) SearchStatusPresentation.compactSummary(it, LanguageManager.isEnglish(this), visibleItems)
            else SearchStatusPresentation.summary(it, LanguageManager.isEnglish(this), visibleItems)
        } ?: tr("輸入內容後按搜尋", "Enter a query and search")
    }

    private fun renderItems(snapshot: SearchSnapshot?) {
        val items = snapshot?.let {
            SearchResultRanker.sort(it.items, it.request, sites.map(SearchSite::id), sortMode)
        }.orEmpty().filter { selectedSource.isBlank() || it.siteId == selectedSource }
        adapter.submitItems(items)
        findViewById<TextView>(R.id.tv_unified_empty).apply {
            text = SearchStatusPresentation.emptyMessage(snapshot, selectedSource, LanguageManager.isEnglish(this@UnifiedSearchActivity))
            visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (items.isNotEmpty()) restoredListState?.let {
            resultsView.layoutManager?.onRestoreInstanceState(it)
            restoredListState = null
        }
    }

    private fun renderSiteStatuses(snapshot: SearchSnapshot) {
        siteStatuses.removeAllViews()
        val english = LanguageManager.isEnglish(this)
        snapshot.siteStates.forEach { state ->
            val site = sites.firstOrNull { it.id == state.siteId } ?: return@forEach
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(android.graphics.Color.rgb(32, 32, 32))
            }
            val title = site.displayName + " · " + SearchStatusPresentation.label(state, english)
            val detail = listOf(SearchStatusPresentation.description(state, english), SearchStatusPresentation.pagination(state, english))
                .filter { it.isNotBlank() }.joinToString("\n")
            card.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(when (state.status) {
                    SearchStatus.SUCCESS -> android.graphics.Color.rgb(129, 199, 132)
                    SearchStatus.NEEDS_USER_ACTION -> android.graphics.Color.rgb(255, 204, 128)
                    else -> android.graphics.Color.WHITE
                })
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@UnifiedSearchActivity)
                        .setTitle(site.displayName)
                        .setMessage(title + "\n" + detail + "\n\n" + state.errorCode.orEmpty() + "\n" + state.diagnostic)
                        .setPositiveButton(tr("複製診斷", "Copy diagnostics")) { _, _ -> copyDiagnostics(listOf(state)) }
                        .setNegativeButton(tr("返回", "Back"), null).show()
                }
            })
            if (detail.isNotBlank()) card.addView(TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(android.graphics.Color.LTGRAY)
                setPadding(0, dp(4), 0, dp(4))
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun action(label: String, click: () -> Unit) {
                actions.addView(Button(this).apply {
                    text = label
                    textSize = 12f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { click() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            action(tr("原始搜尋頁", "Original page")) {
                val url = site.buildSearchUrl(snapshot.request.normalizedQuery, state.pendingPageToken)
                preserveSessionOnStop = true
                startActivityForResult(android.content.Intent(this, SearchSourceActivity::class.java)
                    .putExtra(SearchSourceActivity.EXTRA_SITE, site.id)
                    .putExtra(SearchSourceActivity.EXTRA_URL, url)
                    .putExtra(SearchSourceActivity.EXTRA_REQUEST, snapshot.request.requestId), 410)
            }
            if (state.status.isTerminal) action(tr("重試此站", "Retry site")) { retrySite(state.siteId) }
            if (SearchPagingPolicy.canContinue(state)) {
                action(tr("載入更多", "Load more")) { session?.loadMore(state.siteId) }
            }
            card.addView(actions)
            siteStatuses.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun savedTime(time: Long): String = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))

    private fun savedSearchLabel(info: SavedSearchInfo): String = info.query + "\n" + savedTime(info.savedAt) +
        tr(" · ${info.groups} 組／${info.links} 連結", " · ${info.groups} groups / ${info.links} links")

    private fun refreshSavedSearches(after: () -> Unit = {}) {
        SavedSearchStore.io.execute {
            val loaded = runCatching { savedSearchStore.list() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loaded.onSuccess { savedSearches = it }.onFailure {
                    Toast.makeText(this, tr("無法讀取已存搜尋清單", "Unable to read saved searches"), Toast.LENGTH_SHORT).show()
                }
                savedSearchesReady = true
                renderPanel()
                after()
            }
        }
    }

    private fun saveCurrentSearch() {
        val snapshot = lastSnapshot?.takeIf { it.items.isNotEmpty() } ?: return
        val key = SavedSearchCodec.key(snapshot.request.normalizedQuery, snapshot.request.mode)
        val existing = savedSearches.firstOrNull { it.key == key }
        val message = snapshot.request.rawQuery + "\n" + tr(
            "儲存目前已取得的結果到本機。未完成的頁面不會被當作已完成；封面圖片仍可能需要網路。",
            "Save currently loaded results on this device. Unfinished pages stay unfinished; cover images may still need a network connection.") +
            if (existing != null) "\n\n" + tr("將更新這個關鍵字原有的儲存紀錄。", "This replaces the existing save for this query.") else ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr(if (existing != null) "更新已存搜尋" else "儲存搜尋", if (existing != null) "Update saved search" else "Save search"))
            .setMessage(message)
            .setPositiveButton(tr("儲存", "Save")) { _, _ ->
                val toSave = lastSnapshot?.takeIf { it.request.requestId == snapshot.request.requestId } ?: snapshot
                savingSearch = true
                renderPanel()
                SavedSearchStore.io.execute {
                    val result = runCatching { savedSearchStore.save(toSave) }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        savingSearch = false
                        val notice = if (result.isSuccess) tr("已儲存搜尋結果", "Search results saved")
                            else if (result.exceptionOrNull()?.message == "SAVED_SEARCH_LIMIT")
                                tr("最多儲存 20 筆搜尋，請先刪除不需要的紀錄", "Up to 20 saved searches; delete an unused entry first")
                            else tr("儲存失敗，原有紀錄保留；請確認可用空間或縮小搜尋範圍", "Save failed; the previous record is retained. Check storage or narrow the search.")
                        Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
                        refreshSavedSearches()
                    }
                }
            }.setNegativeButton(tr("取消", "Cancel"), null).show()
    }

    private fun showSavedSearches() {
        if (savedSearches.isEmpty()) {
            Toast.makeText(this, tr("尚未儲存搜尋；有結果後按「儲存搜尋」", "No saved searches yet. Save one after results load."), Toast.LENGTH_LONG).show()
            return
        }
        val entries = savedSearches.toList()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr("已存搜尋（本機）", "Saved searches (on this device)"))
            .setItems(entries.map(::savedSearchLabel).toTypedArray()) { _, position ->
                val entry = entries[position]
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(entry.query)
                    .setItems(arrayOf(tr("開啟已存結果", "Open saved results"), tr("重新搜尋最新資料", "Search for fresh results"), tr("刪除這筆搜尋", "Delete this save"))) { _, action ->
                        when (action) {
                            0 -> openSavedSearch(entry.key)
                            1 -> openSavedSearch(entry.key, refresh = true)
                            2 -> confirmDeleteSavedSearch(entry)
                        }
                    }.setNegativeButton(tr("取消", "Cancel"), null).show()
            }.setNegativeButton(tr("返回", "Back"), null).show()
    }

    private fun confirmDeleteSavedSearch(info: SavedSearchInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tr("刪除已存搜尋？", "Delete saved search?"))
            .setMessage(info.query + "\n" + tr("只刪除這筆本機搜尋紀錄，不影響原有書籤。刪除後無法復原。", "Only this local search save is deleted; bookmarks are unaffected. This cannot be undone."))
            .setPositiveButton(tr("刪除", "Delete")) { _, _ ->
                SavedSearchStore.io.execute {
                    val result = runCatching { savedSearchStore.delete(info.key) }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        Toast.makeText(this, if (result.isSuccess) tr("已刪除這筆搜尋紀錄", "Saved search deleted") else tr("刪除失敗，請重試", "Delete failed; try again"), Toast.LENGTH_SHORT).show()
                        refreshSavedSearches()
                    }
                }
            }.setNegativeButton(tr("取消", "Cancel"), null).show()
    }

    private fun openSavedSearch(key: String, refresh: Boolean = false) {
        val readSerial = ++savedReadSerial
        SavedSearchStore.io.execute {
            val result = runCatching { savedSearchStore.load(key) }
            runOnUiThread {
                if (isDestroyed || isFinishing || readSerial != savedReadSerial) return@runOnUiThread
                val record = result.getOrNull()
                if (record == null) {
                    Toast.makeText(this, tr("無法讀取這筆搜尋；可從已存清單刪除後重新儲存", "Unable to read this save; delete it from the saved list and save again"), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val ids = record.snapshot.request.siteIds.filter { id -> sites.any { it.id == id } }
                if (ids.isEmpty()) return@runOnUiThread
                queryEdit.setText(if (record.info.mode == SearchMode.CODE) record.snapshot.request.normalizedQuery else record.info.query)
                siteChecks.forEach { (id, check) -> check.isChecked = id in ids }
                queryMode = if (record.info.mode == SearchMode.CODE) SearchInputMode.CODE else SearchInputMode.KEYWORD
                forceCodeForNextSearch = false
                selectedSource = ""
                sourceSpinner.setSelection(0)
                if (refresh) { startSearch(true, false); return@runOnUiThread }
                session?.cancel()
                SearchSessionStore.remove(activeRequestId)
                repository.close()
                repository = UnifiedSearchRepository(this)
                val selected = sites.filter { it.id in ids }
                val request = record.snapshot.request.copy(
                    configKey = selected.joinToString("|") { it.configurationKey },
                    siteIds = ids,
                    runMode = runMode
                )
                val states = record.snapshot.siteStates.filter { it.siteId in ids }.map { state ->
                    val site = selected.first { it.id == state.siteId }
                    val next = state.nextPageToken?.takeIf { SearchResultNormalizer.validUrl(it, site) }
                    val pending = state.pendingPageToken?.takeIf { SearchResultNormalizer.validUrl(it, site) }
                    val stalePaging = next != state.nextPageToken || pending != state.pendingPageToken
                    state.copy(nextPageToken = next, pendingPageToken = pending,
                        pageState = if (stalePaging) SearchPageState.UNKNOWN else state.pageState)
                }
                val snapshot = record.snapshot.copy(request = request, siteStates = states,
                    items = SearchResultNormalizer.merge(states.flatMap { it.items }), completedSites = ids.size, selectedSites = ids.size, isRunning = false)
                activeRequestId = request.requestId
                openedSavedKey = record.info.key
                openedSavedAt = record.info.savedAt
                panelState = SearchPanelState(collapsed = snapshot.items.isNotEmpty(), automaticCollapseHandled = true)
                restoredListState = null
                lastSnapshot = snapshot
                session = repository.start(request, this, restored = snapshot)
                forceCodeForNextSearch = false
                hideSearchKeyboard()
                findViewById<RecyclerView>(R.id.rv_unified_results).scrollToPosition(0)
            }
        }
    }

    private fun retrySite(siteId: String) {
        val state = lastSnapshot?.siteStates?.firstOrNull { it.siteId == siteId } ?: return
        val remaining = state.retryAfterUntil - System.currentTimeMillis()
        if (remaining > 0) {
            val seconds = remaining / 1000 + 1
            Toast.makeText(this, tr("請於 " + seconds + " 秒後重試", "Retry in " + seconds + " seconds"), Toast.LENGTH_SHORT).show()
        } else session?.retry(siteId)
    }

    private fun rememberReadAnchor() {
        val manager = resultsView.layoutManager as? LinearLayoutManager ?: return
        val position = manager.findFirstVisibleItemPosition()
        val group = adapter.groupAt(position) ?: return
        readAnchor = SearchReadAnchor(group.stableId, resultsView.getChildAt(0)?.top ?: 0)
    }

    private fun restoreReadAnchorIfNeeded() {
        val anchor = restoredAnchor ?: return
        val position = adapter.positionOf(anchor.groupId)
        if (position < 0) return
        resultsView.post {
            (resultsView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, anchor.offsetPx)
            readAnchor = anchor
            restoredAnchor = null
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 410 && resultCode == RESULT_OK && data != null &&
            data.getStringExtra(SearchSourceActivity.EXTRA_REQUEST) == activeRequestId &&
            data.getBooleanExtra(SearchSourceActivity.EXTRA_RETRY, false)) {
            data.getStringExtra(SearchSourceActivity.EXTRA_SITE)?.let(::retrySite)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        savedReadSerial++
        if (::adapter.isInitialized) adapter.close()
        session?.cancel()
        repository.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Returning from an internal detail/original-page Activity is not background cancellation.
        preserveSessionOnStop = false
    }
    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)

    private fun copyDiagnostics(states: List<SiteSearchState>) {
        val report = buildString {
            append("UnifiedSearch diagnostic v6\n")
            append("time=").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())).append('\n')
            append("android=").append(android.os.Build.VERSION.SDK_INT).append('\n')
            append("Query, URLs, page content and cookies are excluded.\n")
            states.forEach { state ->
                append("site=").append(state.siteId).append(" status=").append(state.status)
                append(" code=").append(state.errorCode ?: "NONE")
                append(" items=").append(state.items.size).append(" cache=").append(state.fromCache)
                append(" pages=").append(state.loadedPages).append(" paging=").append(state.pageState)
                append(" hasNext=").append(state.nextPageToken != null)
                append(' ').append(state.diagnostic).append('\n')
            }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Search diagnostics", report))
        Toast.makeText(this, tr("已複製診斷資訊（不含搜尋內容）", "Diagnostics copied (query excluded)"), Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        session?.snapshot()?.let(SearchSessionStore::put)
        outState.putString("session", activeRequestId)
        outState.putString("query", queryEdit.text.toString())
        outState.putString("opened_saved_key", openedSavedKey)
        outState.putLong("opened_saved_at", openedSavedAt ?: 0L)
        outState.putBoolean("controls_collapsed", panelState.collapsed)
        outState.putBoolean("controls_collapse_handled", panelState.automaticCollapseHandled)
        outState.putStringArrayList("sites", ArrayList(siteChecks.filterValues { it.isChecked }.keys))
        outState.putInt("source", sourceSpinner.selectedItemPosition)
        outState.putString("run_mode", runMode.name)
        outState.putString("query_mode", queryMode.name)
        outState.putString("sort_mode", sortMode.name)
        outState.putParcelable("scroll", findViewById<RecyclerView>(R.id.rv_unified_results).layoutManager?.onSaveInstanceState())
        rememberReadAnchor()
        outState.putString("anchor_group", readAnchor?.groupId)
        outState.putInt("anchor_offset", readAnchor?.offsetPx ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (!isChangingConfigurations && !preserveSessionOnStop) session?.cancel()
        session?.snapshot()?.let(SearchSessionStore::put)
        super.onStop()
    }
}
