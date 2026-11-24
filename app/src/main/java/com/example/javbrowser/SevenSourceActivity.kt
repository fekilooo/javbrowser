package com.example.javbrowser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.Executors

/** Source poster grid and detail preview, sharing existing bookmarks, history and fullscreen playback. */
class SevenSourceActivity : LocalizedActivity() {
    companion object {
        const val EXTRA_SOURCE = "seven_source"
        const val EXTRA_VIDEO = "seven_video"
        fun open(context: android.content.Context, source: String, video: String? = null) {
            context.startActivity(Intent(context, SevenSourceActivity::class.java)
                .putExtra(EXTRA_SOURCE, source).putExtra(EXTRA_VIDEO, video)
                .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }
    private lateinit var source: SevenSourceProtocol.Source
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private lateinit var next: TextView
    private lateinit var adapter: SevenSourceGridAdapter
    private lateinit var listView: RecyclerView
    private val catalogControls = mutableListOf<View>()
    private lateinit var queryInput: EditText
    private val workers = Executors.newSingleThreadExecutor()
    private val client = SevenSourceClient()
    private var job: SearchCancellation? = null
    private var generation = 0
    private val paging = SevenSourcePaging()
    private val page get() = paging.page
    private var category = "recent"
    private var query = ""
    private var videoId: String? = null
    private var currentVideo: SevenSourceProtocol.Video? = null
    private var selectedServer: String? = null
    private var items = emptyList<SevenSourceProtocol.Video>()
    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun text(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        source = runCatching { SevenSourceProtocol.source(intent.getStringExtra(EXTRA_SOURCE).orEmpty()) }.getOrElse { finish(); return }
        category = state?.getString("category") ?: "recent"
        query = state?.getString("query").orEmpty()
        videoId = state?.getString("video") ?: intent.getStringExtra(EXTRA_VIDEO)
        adapter = SevenSourceGridAdapter { showDetail(it.id) }
        listView = RecyclerView(this).apply {
            id = R.id.rv_seven_source_grid
            layoutManager = GridLayoutManager(this@SevenSourceActivity, 3)
            adapter = this@SevenSourceActivity.adapter
            clipToPadding = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy >= 0) maybeLoadNextPage()
                }
            })
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val header = layoutInflater.inflate(R.layout.view_browser_navigation, root, false)
        root.addView(header)
        header.findViewById<Button>(R.id.btn_home).apply {
            contentDescription = tr("首頁", "Home")
            setOnClickListener { BrowserNavigator.openHome(this@SevenSourceActivity); finish() }
        }
        header.findViewById<Button>(R.id.btn_add_favorite).setOnClickListener { toggleBookmark() }
        header.findViewById<Button>(R.id.btn_view_favorites).apply {
            contentDescription = tr("書籤", "Bookmarks")
            setOnClickListener { startActivity(Intent(this@SevenSourceActivity, FavoritesActivity::class.java)) }
        }
        header.findViewById<Button>(R.id.btn_downloads).apply {
            contentDescription = tr("下載管理", "Downloads")
            setOnClickListener { startActivity(Intent(this@SevenSourceActivity, DownloadsActivity::class.java)) }
        }
        header.findViewById<Button>(R.id.btn_settings).apply {
            contentDescription = tr("設定", "Settings")
            setOnClickListener { startActivity(Intent(this@SevenSourceActivity, SettingsActivity::class.java)) }
        }
        header.findViewById<Button>(R.id.btn_play).setOnClickListener { currentVideo?.let { playVideo(it, selectedServer) } }
        root.addView(text(source.name, 20f))
        val search = layoutInflater.inflate(R.layout.view_browser_search, root, false)
        queryInput = search.findViewById<EditText>(R.id.et_unified_query).apply {
            setText(query); hint = tr("搜尋影片", "Search videos")
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        }
        search.findViewById<Button>(R.id.btn_unified_search).apply {
            text = tr("搜尋", "Search")
            setOnClickListener { search() }
        }
        root.addView(search)
        catalogControls.add(search)
        val categoryHint = text(tr("分類（左右滑動）", "Categories (swipe horizontally)"), 12f)
        root.addView(categoryHint)
        catalogControls.add(categoryHint)
        val categories = ChipGroup(this).apply {
            isSingleLine = true
            isSingleSelection = true
            isSelectionRequired = true
            source.categories.forEach { entry ->
                addView(Chip(this@SevenSourceActivity).apply {
                    id = View.generateViewId()
                    text = SevenSourceProtocol.categoryLabel(entry, LanguageManager.isEnglish(this@SevenSourceActivity))
                    tag = entry.first
                    isCheckable = true
                    isChecked = entry.first == category
                    setOnClickListener {
                        if (category != entry.first) {
                            category = entry.first; query = ""; queryInput.setText(""); videoId = null; loadPage(true)
                        }
                    }
                })
            }
        }
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(categories)
        }
        root.addView(categoryScroll)
        catalogControls.add(categoryScroll)
        status = text("")
        root.addView(status)
        content = FrameLayout(this).apply { setPadding(dp(6), 0, dp(6), 0) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        next = text("", 13f).apply { gravity = android.view.Gravity.CENTER; visibility = View.GONE }
        root.addView(next)
        setContentView(root)
        reload()
    }
    private fun search() {
        query = queryInput.text.toString().trim(); videoId = null; loadPage(true)
    }
    private fun reload() {
        videoId?.let { showDetail(it); return }
        if (items.isEmpty()) loadPage(true) else {
            status.text = tr("第 $page 頁 · ${items.size} 部影片", "Page $page · ${items.size} videos")
            showList()
        }
    }
    private fun <T> execute(work: (SearchCancellation) -> T, complete: (T) -> Unit, retry: () -> Unit,
        failed: () -> Unit = {}) {
        job?.cancel()
        val serial = ++generation
        val cancellation = SearchCancellation().also { job = it }
        status.visibility = View.VISIBLE
        status.text = tr("載入中…", "Loading…"); status.setOnClickListener(null); next.isEnabled = false
        workers.execute {
            try {
                val result = work(cancellation)
                runOnUiThread {
                    if (serial == generation && !cancellation.isCancelled && !isDestroyed) {
                        next.isEnabled = true; complete(result)
                    }
                }
            } catch (_: InterruptedException) { }
            catch (error: Exception) {
                runOnUiThread {
                    if (serial == generation && !cancellation.isCancelled && !isDestroyed) {
                        status.text = (error.message ?: tr("來源無法回應", "Source unavailable")) + tr("\n點此重試", "\nTap to retry")
                        status.setOnClickListener { retry() }; next.isEnabled = true
                        failed()
                    }
                }
            }
        }
    }
    private fun showList() {
        currentVideo = null
        updateNavigation()
        catalogControls.forEach { it.visibility = View.VISIBLE }
        status.visibility = View.VISIBLE
        if (listView.parent != content) {
            content.removeAllViews()
            content.addView(listView)
        }
        adapter.submitList(items) { listView.post { maybeLoadNextPage() } }
        updatePagingFooter()
    }
    private fun maybeLoadNextPage() {
        if (videoId != null || isDestroyed || isFinishing || listView.parent != content ||
            adapter.itemCount != items.size ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        val manager = listView.layoutManager as GridLayoutManager
        if (paging.shouldLoad(manager.findLastVisibleItemPosition(), adapter.itemCount)) loadPage(false)
    }
    private fun updatePagingFooter() {
        next.setOnClickListener(null)
        next.visibility = when {
            videoId != null -> View.GONE
            paging.loading && items.isNotEmpty() -> View.VISIBLE
            paging.failed -> View.VISIBLE
            !paging.hasMore && items.isNotEmpty() -> View.VISIBLE
            else -> View.GONE
        }
        next.text = when {
            paging.loading -> tr("正在載入下一頁…", "Loading the next page…")
            paging.failed -> tr("載入失敗，點此重試", "Load failed. Tap to retry")
            !paging.hasMore -> tr("已顯示全部影片", "All videos loaded")
            else -> ""
        }
        if (paging.failed) next.setOnClickListener { loadPage(page == 0) }
    }
    private fun loadPage(reset: Boolean) {
        val requested = paging.begin(reset) ?: return
        videoId = null
        if (reset) { items = emptyList(); showList(); listView.scrollToPosition(0) }
        updatePagingFooter()
        execute({ client.page(source, category, requested, query, it) }, { result ->
            val previousCount = items.size
            items = (items + result.videos).distinctBy { it.stableId }
            paging.complete(items.size - previousCount, result.hasMore)
            showList()
            status.text = if (items.isEmpty()) tr("沒有符合的影片", "No matching videos") else tr("第 $page 頁 · ${items.size} 部影片", "Page $page · ${items.size} videos")
        }, { loadPage(reset) }, { paging.fail(); updatePagingFooter() })
    }
    private fun showDetail(id: String) {
        paging.cancel()
        videoId = id; content.removeAllViews(); next.visibility = View.GONE
        currentVideo = null
        updateNavigation()
        catalogControls.forEach { it.visibility = View.GONE }
        execute({ client.detail(source, id, null, it) }, { detail ->
            renderDetail(detail.video)
        }, { showDetail(id) })
    }
    private fun renderDetail(video: SevenSourceProtocol.Video) {
        videoId = video.id
        currentVideo = video
        selectedServer = null
        updateNavigation()
        catalogControls.forEach { it.visibility = View.GONE }
        status.text = ""
        status.visibility = View.GONE
        val panel = layoutInflater.inflate(R.layout.view_seven_source_detail, content, false)
        val cover = panel.findViewById<ImageView>(R.id.iv_seven_detail_cover)
        Glide.with(this).load(video.cover).into(cover)
        panel.findViewById<TextView>(R.id.tv_seven_detail_title).text = video.title
        panel.findViewById<TextView>(R.id.tv_seven_detail_metadata).text =
            listOf(video.duration, video.category, video.date).filter { it.isNotBlank() }.joinToString(" · ")
        panel.findViewById<TextView>(R.id.tv_seven_detail_description_label).text = tr("影片簡介", "About this video")
        panel.findViewById<TextView>(R.id.tv_seven_detail_description).text =
            video.description.ifBlank { tr("來源未提供簡介", "No description provided") }
        val serverContainer = panel.findViewById<FrameLayout>(R.id.seven_server_selection)
        if (source.id == "theporny") serverContainer.addView(Spinner(this).apply {
            val servers = listOf(null, "hd1", "hd2", "hd3", "default1")
            adapter = ArrayAdapter(this@SevenSourceActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(tr("自動選線", "Automatic"), "HD 1", "HD 2", "HD 3", tr("免費線路", "Free server")))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedServer = servers[position] }
            }
        }) else serverContainer.visibility = View.GONE
        val play = panel.findViewById<ImageButton>(R.id.btn_seven_detail_play)
        play.contentDescription = tr("全螢幕播放", "Play fullscreen") + " · " + video.title
        play.setOnClickListener { playVideo(video, selectedServer) }
        panel.findViewById<View>(R.id.seven_playback_preview).setOnClickListener { playVideo(video, selectedServer) }
        content.removeAllViews()
        content.addView(ScrollView(this).apply { addView(panel) })
    }
    private fun playVideo(video: SevenSourceProtocol.Video, server: String?) {
        PlaybackHistoryRepository.record(this, video.pageUrl, video.title)
        PlaybackHistoryRepository.updateArtwork(this, video.pageUrl, video.cover, source.origin + "/favicon.ico")
        startActivity(Intent(this, FullscreenInternalPlayerActivity::class.java)
            .putExtra(FullscreenInternalPlayerActivity.EXTRA_SEVEN_SOURCE, source.id)
            .putExtra(FullscreenInternalPlayerActivity.EXTRA_SEVEN_VIDEO, video.id)
            .putExtra(FullscreenInternalPlayerActivity.EXTRA_SEVEN_SERVER, server)
            .putExtra(FullscreenInternalPlayerActivity.EXTRA_PAGE_URL, video.pageUrl)
            .putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, video.title))
    }
    private fun updateNavigation() {
        val url = currentVideo?.pageUrl ?: (source.origin + "/")
        val saved = FavoritesManager(this).getFavorites().any { it.url == url }
        findViewById<Button>(R.id.btn_add_favorite).apply {
            text = if (saved) "♥" else "♡"
            isEnabled = videoId == null || currentVideo != null
            contentDescription = if (saved) tr("移除書籤", "Remove bookmark") else tr("加入書籤", "Add bookmark")
        }
        findViewById<Button>(R.id.btn_play).visibility = if (currentVideo != null) View.VISIBLE else View.GONE
    }
    private fun toggleBookmark() {
        if (videoId != null && currentVideo == null) return
        val video = currentVideo
        val url = video?.pageUrl ?: (source.origin + "/")
        val manager = FavoritesManager(this)
        if (manager.getFavorites().any { it.url == url }) {
            manager.removeFavorite(url)
            Toast.makeText(this, tr("已從書籤移除", "Bookmark removed"), Toast.LENGTH_SHORT).show()
        } else {
            manager.addFavorite(video?.title ?: source.name, url, video?.cover)
            Toast.makeText(this, tr("已加入書籤", "Bookmark added"), Toast.LENGTH_SHORT).show()
        }
        updateNavigation()
    }
    private fun back() {
        if (videoId != null && items.isNotEmpty()) {
            job?.cancel(); generation++; videoId = null; status.text = ""; showList()
        } else finish()
    }
    @Deprecated("Deprecated in Java") override fun onBackPressed() = back()
    override fun onResume() {
        super.onResume()
        if (::source.isInitialized) updateNavigation()
        if (::source.isInitialized && ::content.isInitialized && job?.isCancelled == true) reload()
    }
    override fun onStop() { job?.cancel(); paging.cancel(); generation++; super.onStop() }
    override fun onDestroy() { job?.cancel(); workers.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("category", category); outState.putString("query", query); outState.putString("video", videoId)
        super.onSaveInstanceState(outState)
    }
}
