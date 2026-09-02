package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 近3日新着頁面
 *
 * 快取策略：
 *   - 爬完後存入 SharedPreferences（JSON + 爬取日期）
 *   - 下次開啟若日期相同 → 直接讀快取，不重新爬
 *   - 換日（手機日期改變）或使用者按「↺ 更新」→ 重新爬
 *
 * 每頁流程：
 *   1. 載入頁面，等 5 秒讓 Vue 渲染完
 *   2. 點擊「本日の新着」開關（每頁都要點，因為換頁後 Vue 重置狀態）
 *   3. 再等 3 秒讓過濾後的列表載入
 *   4. JS 抓取影片列表，用日期篩選近3天的條目
 *   5. 若末筆仍在3天內且未達4頁上限 → 載下一頁，重複
 */
class TodayNewActivity : LocalizedActivity() {

    data class VideoItem(
        val url: String,
        val thumb: String,
        val title: String,
        val date: String,
        val javCode: String
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var favoritesManager: FavoritesManager
    private var hiddenWebView: WebView? = null

    private val allItems = mutableListOf<VideoItem>()
    private var currentPage = 0
    private val MAX_PAGES = 4
    private val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

    // SharedPreferences 快取 keys
    private val PREFS_NAME  = "today_new_prefs"
    private val KEY_DATA    = "cached_data"
    private val KEY_DATE    = "cached_date"         // 格式："2026-04-08"
    private val todayKey get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                     .format(Calendar.getInstance().time)

    // ── 生命週期 ─────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_today_new)

        recyclerView = findViewById(R.id.rv_today)
        progressBar  = findViewById(R.id.progress_today)
        tvStatus     = findViewById(R.id.tv_today_status)
        favoritesManager = FavoritesManager(this)

        findViewById<Button>(R.id.btn_back_today).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_refresh_today).setOnClickListener { startScraping() }

        // 有當日快取 → 直接顯示，否則爬取
        val cached = loadCache()
        if (cached != null) {
            tvStatus.text = "近3日新着  ${cached.size} 件（快取）"
            progressBar.visibility = View.GONE
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = TodayAdapter(cached)
        } else {
            startScraping()
        }
    }

    // ── 快取讀寫 ─────────────────────────────────────────────────────────────────

    /** 讀快取：今天有資料才回傳，否則 null */
    private fun loadCache(): List<VideoItem>? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDate = prefs.getString(KEY_DATE, null) ?: return null
        if (savedDate != todayKey) return null                          // 已換日
        val json = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VideoItem(o.optString("url"), o.optString("thumb"),
                          o.optString("title"), o.optString("date"), o.optString("javCode"))
            }.filter { it.url.isNotEmpty() }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    /** 寫快取 */
    private fun saveCache(items: List<VideoItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("url",     item.url)
                put("thumb",   item.thumb)
                put("title",   item.title)
                put("date",    item.date)
                put("javCode", item.javCode)
            })
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATA, arr.toString())
            .putString(KEY_DATE, todayKey)
            .apply()
    }

    // ── 爬取流程 ─────────────────────────────────────────────────────────────────

    private fun startScraping() {
        // 清除舊結果
        allItems.clear()
        recyclerView.adapter = null
        hiddenWebView?.destroy()
        initWebView()
        loadPage(1)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val wv = WebView(this)
        hiddenWebView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.contains("javtrailers.com/ja/videos") != true) return
                tvStatus.text = "第 $currentPage 頁 — 渲染中（5秒）..."

                handler.postDelayed({
                    // 每頁都重新點開關（Vue 換頁後狀態重置）
                    tvStatus.text = "第 $currentPage 頁 — 點擊本日の新着..."
                    view?.evaluateJavascript("""
                        (function() {
                            var sw = document.getElementById('flexSwitchCheckDefault');
                            if (sw && !sw.checked) sw.click();
                        })();
                    """.trimIndent(), null)

                    // 等 3 秒讓過濾後列表載入
                    handler.postDelayed({ extractPage(view) }, 3000)
                }, 5000)
            }
        }
    }

    private fun loadPage(page: Int) {
        handler.removeCallbacksAndMessages(null)
        currentPage = page
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "載入第 $page 頁..."
        val url = if (page == 1) "https://javtrailers.com/ja/videos"
                  else "https://javtrailers.com/ja/videos?page=$page"
        hiddenWebView?.loadUrl(url)
    }

    private fun extractPage(view: WebView?) {
        tvStatus.text = "第 $currentPage 頁 — 擷取資料..."
        view?.evaluateJavascript("""
            (function() {
                var items = [];
                var links = document.querySelectorAll('section.videos-list .video-link');
                links.forEach(function(link) {
                    var href = link.getAttribute('href') || '';
                    var img = link.querySelector('.video-image');
                    var titleEl = link.querySelector('.vid-title');
                    var dateEl  = link.querySelector('small.text-muted');
                    var alt = img ? (img.getAttribute('alt') || '') : '';
                    var javCode = alt.replace(/ jav${'$'}/i, '').trim().toUpperCase();
                    var thumb = '';
                    if (img) {
                        var s = img.getAttribute('src') || '';
                        var raw = s.startsWith('data:') ? (img.getAttribute('data-src') || '') : s;
                        thumb = raw.replace('ps.jpg', 'pl.jpg');
                    }
                    if (href) items.push({
                        url: 'https://javtrailers.com' + href,
                        thumb: thumb,
                        title: titleEl ? titleEl.textContent.trim() : '',
                        date:  dateEl  ? dateEl.textContent.trim()  : '',
                        javCode: javCode
                    });
                });
                return JSON.stringify(items);
            })();
        """.trimIndent()) { result ->
            handler.post { handlePageResult(result) }
        }
    }

    private fun handlePageResult(rawResult: String?) {
        val json = try {
            JSONTokener(rawResult ?: "\"[]\"").nextValue() as? String ?: "[]"
        } catch (e: Exception) { "[]" }

        val pageItems = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VideoItem(o.optString("url"), o.optString("thumb"),
                          o.optString("title"), o.optString("date"), o.optString("javCode"))
            }.filter { it.url.isNotEmpty() }
        } catch (e: Exception) { emptyList() }

        val now = System.currentTimeMillis()
        allItems.addAll(pageItems.filter { item ->
            try { val d = dateFmt.parse(item.date); d != null && (now - d.time) <= THREE_DAYS_MS }
            catch (e: Exception) { false }
        })

        val lastIsRecent = pageItems.lastOrNull()?.date?.let { dateStr ->
            try { val d = dateFmt.parse(dateStr); d != null && (now - d.time) <= THREE_DAYS_MS }
            catch (e: Exception) { false }
        } ?: false

        if (lastIsRecent && currentPage < MAX_PAGES && pageItems.isNotEmpty()) {
            loadPage(currentPage + 1)
        } else {
            showFinalResults()
        }
    }

    private fun showFinalResults() {
        progressBar.visibility = View.GONE
        hiddenWebView?.destroy()
        hiddenWebView = null

        if (allItems.isEmpty()) {
            tvStatus.text = "⚠ 近3日內無資料（網路較慢可點↺重試）"
        } else {
            saveCache(allItems)                                         // 存快取
            tvStatus.text = "近3日新着  ${allItems.size} 件"
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = TodayAdapter(allItems.toList())
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────────────

    inner class TodayAdapter(private val items: List<VideoItem>) :
        RecyclerView.Adapter<TodayAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView  = view.findViewById(R.id.iv_today_thumb)
            val tvTitle: TextView   = view.findViewById(R.id.tv_today_title)
            val tvMeta: TextView    = view.findViewById(R.id.tv_today_meta)
            val btnBookmark: Button = view.findViewById(R.id.btn_today_bookmark)
            val btnCrossSearch: Button = view.findViewById(R.id.btn_today_cross_search)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_today_new, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvMeta.text  = buildString {
                if (item.javCode.isNotEmpty()) append(item.javCode)
                if (item.javCode.isNotEmpty() && item.date.isNotEmpty()) append("  ")
                if (item.date.isNotEmpty()) append(item.date)
            }

            Glide.with(holder.ivThumb.context)
                .load(item.thumb.ifEmpty { null })
                .placeholder(android.R.color.darker_gray)
                .error(android.R.color.darker_gray)
                .into(holder.ivThumb)

            refreshBookmarkBtn(holder.btnBookmark, item)

            holder.btnBookmark.setOnClickListener {
                val saved = favoritesManager.getFavorites().any { it.url == item.url }
                if (saved) favoritesManager.removeFavorite(item.url)
                else favoritesManager.addFavorite(item.title, item.url, item.thumb, item.javCode)
                refreshBookmarkBtn(holder.btnBookmark, item)
            }

            holder.btnCrossSearch.setOnClickListener {
                val code = item.javCode.ifBlank { CrossSiteSearchUi.extractCode(item.title + " " + item.url) }
                CrossSiteSearchUi.show(this@TodayNewActivity, code)
            }

            holder.itemView.setOnClickListener {
                android.util.Log.d("TODAY_DEBUG", "click item url=${item.url} code=${item.javCode} title=${item.title}")
                startActivity(Intent(this@TodayNewActivity, TodayContentActivity::class.java).apply {
                    putExtra(TodayContentActivity.EXTRA_URL, item.url)
                    putExtra(TodayContentActivity.EXTRA_TITLE, item.title)
                    putExtra(TodayContentActivity.EXTRA_THUMB, item.thumb)
                    putExtra(TodayContentActivity.EXTRA_JAV_CODE, item.javCode)
                })
            }
        }

        private fun refreshBookmarkBtn(btn: Button, item: VideoItem) {
            val saved = favoritesManager.getFavorites().any { it.url == item.url }
            btn.text = if (saved) "✅" else "📚"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(if (saved) "#7B1FA2" else "#333333"))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hiddenWebView?.destroy()
        hiddenWebView = null
        super.onDestroy()
    }
}
