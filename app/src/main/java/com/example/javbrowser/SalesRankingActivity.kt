package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class SalesCategory { ONLINE, PHYSICAL }

data class SalesItem(
    val rank: Int?,
    val title: String,
    val actress: String = "",
    val code: String = "",
    val maker: String = "",
    val detail: String = "",
    val imageUrl: String = "",
    val linkUrl: String = ""
) {
    companion object {
        fun fromJson(o: JSONObject) = SalesItem(
            if (o.isNull("rank")) null else o.getInt("rank"), o.getString("title"), o.optString("actress"),
            o.optString("code"), o.optString("maker"), o.optString("detail"), o.optString("imageUrl"), o.optString("linkUrl"))
    }
}

data class SalesSection(
    val id: String,
    val title: String,
    val category: SalesCategory,
    val period: String,
    val note: String,
    val sourceUrl: String,
    val items: List<SalesItem>,
    val error: String = ""
)

class SalesRankingActivity : LocalizedActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var filters: LinearLayout
    private lateinit var sourceFilters: LinearLayout
    private lateinit var sourceScroll: HorizontalScrollView
    private var sections: List<SalesSection> = emptyList()
    private var selected: SalesCategory? = null
    private var selectedSource: String? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sales_ranking)
        recycler = findViewById(R.id.rv_sales)
        status = findViewById(R.id.tv_sales_status)
        progress = findViewById(R.id.progress_sales)
        filters = findViewById(R.id.sales_filters)
        sourceFilters = findViewById(R.id.sales_source_filters)
        sourceScroll = findViewById(R.id.sales_source_scroll)
        recycler.layoutManager = LinearLayoutManager(this)
        findViewById<Button>(R.id.btn_back_sales).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_refresh_sales).setOnClickListener { refresh(true) }
        buildFilters()
        SalesRankingScheduler.schedule(this)

        val cached = SalesRankingRepository.loadCache(this)
        if (cached != null) {
            sections = cached.first
            showSections()
            status.text = "已更新：${formatTime(cached.second)}（本機快取）"
            progress.visibility = View.GONE
            if (System.currentTimeMillis() - cached.second > SalesRankingRepository.CACHE_MAX_AGE) refresh(false)
        } else {
            refresh(false)
        }
    }

    private fun buildFilters() {
        filters.removeAllViews()
        listOf("全部" to null, "線上" to SalesCategory.ONLINE, "實體 DVD" to SalesCategory.PHYSICAL).forEach { (label, category) ->
            val button = Button(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.WHITE)
                isAllCaps = false
                setOnClickListener {
                    selected = category
                    selectedSource = null
                    buildFilters()
                    buildSourceFilters()
                    showSections()
                }
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (selected == category) Color.rgb(0, 137, 123) else Color.rgb(55, 55, 55)
                )
            }
            filters.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }
    }

    private fun buildSourceFilters() {
        sourceFilters.removeAllViews()
        val category = selected
        if (category == null) {
            sourceScroll.visibility = View.GONE
            return
        }
        sourceScroll.visibility = View.VISIBLE
        val choices = sections.filter { it.category == category }
        listOf("此類全部" to null).plus(choices.map { shortSourceName(it) to it.id }).forEach { (label, id) ->
            val button = Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                isAllCaps = false
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (selectedSource == id) Color.rgb(123, 31, 162) else Color.rgb(65, 65, 65)
                )
                setOnClickListener { selectedSource = id; buildSourceFilters(); showSections() }
            }
            sourceFilters.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5) })
        }
    }

    private fun shortSourceName(section: SalesSection): String = when (section.id) {
        "proclivity" -> "Proclivity-DB"
        "neoero" -> "neoero"
        "mgs" -> "MGS"
        "nobunaga" -> "信長書店"
        "tokyo" -> "東京書店"
        "kaitori" -> "買取りまっくす"
        else -> section.title.substringBefore('｜')
    }

    private fun refresh(manual: Boolean) {
        progress.visibility = View.VISIBLE
        status.text = if (manual) "正在強制更新所有來源…" else "正在取得最新排行…"
        executor.execute {
            val result = SalesRankingRepository(this).fetchAll()
            if (result.any { it.items.isNotEmpty() }) SalesRankingRepository.saveCache(this, result)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sections = result
                progress.visibility = View.GONE
                buildSourceFilters()
                showSections()
                val ok = result.count { it.items.isNotEmpty() }
                val failed = result.size - ok
                status.text = "更新完成：$ok 個來源成功" + if (failed > 0) "、$failed 個暫時無法讀取" else ""
            }
        }
    }

    private fun showSections() {
        val shown = sections.filter {
            (selected == null || it.category == selected) && (selectedSource == null || it.id == selectedSource)
        }
        recycler.adapter = SalesRankingAdapter(shown, ::openInsideApp, ::searchOnMissAv)
        recycler.scrollToPosition(0)
    }

    private fun searchOnMissAv(item: SalesItem) {
        val query = item.code.ifBlank { item.title.ifBlank { item.actress } }.trim()
        if (query.isBlank()) return
        val domainConfig = DomainConfig(AdFilterRules(this))
        openInsideApp(domainConfig.getMissAvSearchUrl(Uri.encode(query)))
    }

    private fun openInsideApp(url: String) {
        if (url.isBlank()) return
        // MainActivity 已在排行頁下方暫停；先用本機廣播讓既有 WebView 載入網址。
        val navigation = Intent(MainActivity.ACTION_LOAD_URL).apply {
            putExtra(MainActivity.EXTRA_URL, url)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .sendBroadcast(navigation)

        // 不能直接指定 MainActivity：使用者可能已透過 AppIconManager 將它停用，
        // 改用目前啟用中的 launcher alias，並把既有 MainActivity 置於排行頁上方。
        // 使用 REORDER_TO_FRONT 保留 SalesRankingActivity，返回鍵即可回到原本滾動位置。
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "找不到 APP 主頁入口", Toast.LENGTH_SHORT).show()
            return
        }
        launchIntent.putExtra(MainActivity.EXTRA_URL, url)
            .putExtra(MainActivity.EXTRA_RETURN_TO_SALES, true)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(launchIntent) }
            .onFailure {
                Toast.makeText(this, "無法開啟搜尋頁", Toast.LENGTH_SHORT).show()
            }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatTime(time: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(time))

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

private class SalesRankingAdapter(
    sections: List<SalesSection>,
    private val openUrl: (String) -> Unit,
    private val searchItem: (SalesItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private data class Header(val section: SalesSection)
    private data class Row(val section: SalesSection, val item: SalesItem)
    private val rows = buildList<Any> {
        sections.forEach { section ->
            add(Header(section))
            section.items.forEach { add(Row(section, it)) }
            if (section.items.isEmpty()) add(Row(section, SalesItem(null, "此來源目前無可顯示資料", detail = section.error)))
        }
    }

    override fun getItemViewType(position: Int) = if (rows[position] is Header) 0 else 1
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderHolder(inflater.inflate(R.layout.item_sales_source_header, parent, false))
        else ItemHolder(inflater.inflate(R.layout.item_sales_ranking, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Header -> (holder as HeaderHolder).bind(row.section, openUrl)
            is Row -> (holder as ItemHolder).bind(row.item, searchItem)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tv_source_title)
        private val period: TextView = view.findViewById(R.id.tv_source_period)
        private val note: TextView = view.findViewById(R.id.tv_source_note)
        fun bind(section: SalesSection, openUrl: (String) -> Unit) {
            title.text = section.title
            period.text = section.period
            note.text = section.note + if (section.error.isNotBlank()) "\n⚠ ${section.error}" else ""
            itemView.setOnClickListener { openUrl(section.sourceUrl) }
        }
    }

    private class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val rank: TextView = view.findViewById(R.id.tv_sales_rank)
        private val cover: ImageView = view.findViewById(R.id.iv_sales_cover)
        private val title: TextView = view.findViewById(R.id.tv_sales_title)
        private val meta: TextView = view.findViewById(R.id.tv_sales_meta)
        private val detail: TextView = view.findViewById(R.id.tv_sales_detail)
        fun bind(item: SalesItem, searchItem: (SalesItem) -> Unit) {
            rank.text = item.rank?.let { "#$it" } ?: "•"
            title.text = item.title
            val parts = mutableListOf<String>()
            if (item.actress.isNotBlank()) parts += "出演：${item.actress}"
            if (item.code.isNotBlank()) parts += "番號：${item.code}"
            if (item.maker.isNotBlank()) parts += "片商：${item.maker}"
            meta.text = parts.joinToString("\n")
            meta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            detail.text = item.detail
            detail.visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
            cover.visibility = if (item.imageUrl.isBlank()) View.GONE else View.VISIBLE
            if (item.imageUrl.isNotBlank()) Glide.with(cover).load(item.imageUrl).centerCrop().into(cover) else Glide.with(cover).clear(cover)
            itemView.setOnClickListener { searchItem(item) }
        }
    }
}

class SalesRankingRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchAll(): List<SalesSection> = listOf(
        source("proclivity", "Proclivity-DB｜FANZA 女優月榜", SalesCategory.ONLINE,
            "FANZA 官方月間女優排名的歷史保存；不是公開的精確銷售套數。", PROCLIVITY, ::parseProclivity),
        source("neoero", "neoero｜FANZA 作品趨勢", SalesCategory.ONLINE,
            "依 FANZA 每日名次進行的獨自集計，適合觀察當月熱門作品。", NEOERO, ::parseNeoero),
        source("mgs", "MGS｜作品月榜", SalesCategory.ONLINE,
            "MGS 海外可能限制存取；使用會連回 MGS 商品頁的公開月榜整理。", MGS_ARCHIVE, ::parseMgs),
        source("nobunaga", "信長書店｜關西實體 DVD 月榜", SalesCategory.PHYSICAL,
            "關西大型實體通路。官方文章有時只以文字公開 Top 20 中的部分名次。", NOBUNAGA, ::parseNobunaga),
        source("tokyo", "東京書店｜廣島實體 DVD 月榜", SalesCategory.PHYSICAL,
            "廣島地方實體通路的每月店頭銷售實績。", TOKYO, ::parseTokyo),
        source("kaitori", "買取りまっくす｜店頭銷售榜", SalesCategory.PHYSICAL,
            "關西／西日本各分店的公開店頭排行；來源未標示精確統計期間。", KAITORI, ::parseKaitori)
    )

    private fun source(
        id: String, title: String, category: SalesCategory, note: String, url: String,
        parser: (String) -> Pair<String, List<SalesItem>>
    ): SalesSection = try {
        val (period, items) = parser(url)
        SalesSection(id, title, category, period, note, url, items)
    } catch (e: Exception) {
        SalesSection(id, title, category, "等待下次更新", note, url, emptyList(), cleanError(e))
    }

    private fun get(url: String, cookie: String = ""): Document {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        }
    }

    private fun parseProclivity(url: String): Pair<String, List<SalesItem>> {
        val doc = get(url)
        val period = doc.selectFirst("p:matches(\\d{4}年\\d{1,2}月度ランキング)")?.text()
            ?.let { Regex("(\\d{4}年\\d{1,2}月度)").find(it)?.value } ?: "最新月榜"
        val items = doc.select("table.ranking-table tbody tr").mapNotNull { row ->
            val cells = row.select("td")
            val rank = cells.getOrNull(0)?.text()?.toIntOrNull() ?: return@mapNotNull null
            val actress = cells.getOrNull(1)?.text()?.trim().orEmpty()
            SalesItem(rank, actress, actress = actress,
                detail = "上月變化：${cells.getOrNull(2)?.text().orEmpty()}　歷史最高：${cells.getOrNull(3)?.text().orEmpty()}",
                linkUrl = cells.getOrNull(1)?.selectFirst("a")?.absUrl("href").orEmpty())
        }.take(100)
        require(items.isNotEmpty()) { "找不到榜單欄位，來源可能已改版" }
        return period to items
    }

    private fun parseNeoero(url: String): Pair<String, List<SalesItem>> {
        val home = get(url)
        val link = home.select("a[href]").firstOrNull {
            it.text().contains("セクシービデオランキング") && it.attr("href").contains("/article/")
        }?.absUrl("href") ?: error("找不到最新作品榜連結")
        val doc = get(link)
        val heading = doc.selectFirst("h1")?.text().orEmpty()
        val period = Regex("\\d{4}年\\d{1,2}月").find(heading)?.value ?: "最新月榜"
        val items = doc.select("li.video-item").mapIndexedNotNull { index, row ->
            val title = row.selectFirst(".title-text")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapIndexedNotNull null
            val rank = Regex("\\d+").find(row.selectFirst(".ranking-badge")?.text().orEmpty())?.value?.toIntOrNull() ?: index + 1
            val actress = row.selectFirst(".info .name")?.text()?.replace("出演：", "")?.trim().orEmpty()
            val product = row.select("a[href]").firstOrNull { it.absUrl("href").contains("mgstage.com") || it.absUrl("href").contains("dmm.co.jp") }
            val code = extractCode(row.select("a[href], iframe[src]").joinToString(" ") {
                it.absUrl(if (it.hasAttr("href")) "href" else "src")
            })
            SalesItem(rank, title, actress, code = code, imageUrl = row.selectFirst(".thumbnail img")?.absUrl("src").orEmpty(),
                linkUrl = product?.absUrl("href").takeUnless { it.isNullOrBlank() } ?: link)
        }.take(20)
        require(items.isNotEmpty()) { "找不到作品列，來源可能已改版" }
        return (period + if (heading.contains("暫定")) "（暫定）" else "") to items
    }

    private fun parseMgs(url: String): Pair<String, List<SalesItem>> {
        val archive = get(url)
        val link = archive.select("a[href]").firstOrNull {
            it.text().contains("MGS動画 月間ランキング")
        }?.absUrl("href") ?: error("找不到最新 MGS 月榜")
        val doc = get(link)
        val heading = doc.selectFirst("h1")?.text().orEmpty()
        val period = Regex("\\d{4}年\\d{2}月").find(heading)?.value ?: "最新月榜"
        val items = doc.select("table.product-data-table tbody tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 4) return@mapNotNull null
            val rank = Regex("(\\d+)位").find(cells[1].text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val a = cells[1].selectFirst("a[href]")
            SalesItem(rank, a?.text()?.trim().orEmpty(), actress = cells[2].text(), code = cells[3].text(),
                maker = cells.getOrNull(4)?.text().orEmpty(), detail = cells.getOrNull(6)?.text().orEmpty(),
                imageUrl = cells[0].selectFirst("img")?.absUrl("src").orEmpty(), linkUrl = a?.absUrl("href").orEmpty())
        }.take(50)
        require(items.isNotEmpty()) { "找不到 MGS 作品列" }
        return period to items
    }

    private fun parseNobunaga(url: String): Pair<String, List<SalesItem>> {
        val archive = get(url)
        val link = archive.select("a[href]").firstOrNull { it.text().contains("信長書店グループDVDランキング") }
            ?.absUrl("href") ?: error("找不到最新信長書店月榜")
        val doc = get(link)
        val heading = doc.selectFirst("h1")?.text().orEmpty()
        val period = Regex("\\d{1,2}月発表").find(heading)?.value ?: "最新月榜"
        val items = mutableListOf<SalesItem>()
        doc.select(".entrybody h1, .entrybody h2, article h1").forEach { marker ->
            val rank = Regex("第[ 　]*([0-9０-９]+)位").find(marker.text())?.groupValues?.get(1)?.toAsciiDigits()?.toIntOrNull() ?: return@forEach
            var cursor: Element? = marker.nextElementSibling()
            var title = ""
            var image = ""
            var info = ""
            repeat(6) {
                if (cursor != null) {
                    if (title.isBlank() && cursor!!.tagName() in listOf("h1", "h2", "h3")) title = cursor!!.text()
                    if (image.isBlank()) image = cursor!!.selectFirst("img")?.absUrl("src").orEmpty()
                    if (cursor!!.tagName() == "p") info += " " + cursor!!.text()
                    cursor = cursor!!.nextElementSibling()
                }
            }
            if (title.isNotBlank()) {
                val maker = Regex("メーカー[：:]\\s*([^　 ]+)").find(info)?.groupValues?.get(1).orEmpty()
                val code = Regex("品番[：:]\\s*([A-Za-z0-9-]+)").find(info)?.groupValues?.get(1).orEmpty()
                items += SalesItem(rank, title, code = code, maker = maker, imageUrl = image, linkUrl = link)
            }
        }
        require(items.isNotEmpty()) { "找不到信長書店名次" }
        return period to items.distinctBy { it.rank }.sortedBy { it.rank }
    }

    private fun parseTokyo(url: String): Pair<String, List<SalesItem>> {
        val doc = get(url, "AUTH_TYOKYOSYOTEN_LEPTON=1")
        val items = doc.select("#sec1 .rows .list").mapIndexedNotNull { index, row ->
            val title = row.selectFirst(".body .title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapIndexedNotNull null
            val fields = mutableMapOf<String, String>()
            row.select(".body .info dl").forEach { dl -> fields[dl.selectFirst("dt")?.text().orEmpty()] = dl.selectFirst("dd")?.text().orEmpty() }
            val rankText = row.selectFirst(".ranking")?.text().orEmpty()
            val rank = Regex("\\d+").find(rankText)?.value?.toIntOrNull() ?: index + 1
            val imageUrl = row.selectFirst(".img > img")?.absUrl("src").orEmpty()
            SalesItem(rank, title, actress = fields["出演者"].orEmpty(), code = extractCode(imageUrl), maker = fields["メーカー"].orEmpty(),
                detail = fields["発売日"].orEmpty(), imageUrl = row.selectFirst(".img > img")?.absUrl("src").orEmpty(), linkUrl = url)
        }.take(20)
        require(items.isNotEmpty()) { "年齡確認未通過或榜單結構已改版" }
        return "最新月榜" to items
    }

    private fun parseKaitori(url: String): Pair<String, List<SalesItem>> {
        val doc = get(url)
        val items = doc.select("#list tbody tr").mapNotNull { row ->
            val rank = row.select("td.rank img[alt]").mapNotNull { Regex("(\\d+)位").find(it.attr("alt"))?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()
                ?: return@mapNotNull null
            val cover = row.selectFirst("td.photo img[src*=week_ranking]") ?: return@mapNotNull null
            val raw = cover.attr("alt").replace(Regex("width=.*$"), "").trim()
            val link = row.selectFirst("td.txt h3 a[href]")?.absUrl("href").orEmpty()
            val detail = row.selectFirst("td.txt")?.ownText()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            SalesItem(rank, raw.ifBlank { "第 ${rank} 名作品" }, code = extractCode(link), detail = detail,
                imageUrl = cover.absUrl("src"), linkUrl = link)
        }.distinctBy { it.rank }.sortedBy { it.rank }.take(10)
        require(items.isNotEmpty()) { "找不到店頭排行榜" }
        return "目前公開榜（來源未標期間）" to items
    }

    private fun cleanError(e: Exception): String = (e.message ?: e.javaClass.simpleName).take(120)

    private fun extractCode(text: String): String {
        val raw = Regex("(?:cid=|product_detail/)([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
            ?: Regex("/([A-Za-z]{2,10})[-_]?([0-9]{3,5})(?:ps)?\\.", RegexOption.IGNORE_CASE)
                .find(text)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
            ?: return ""
        val cleaned = raw.removePrefix("1").removeSuffix("ps").removeSuffix("PS")
        val parts = Regex("([A-Za-z]+)[-_]?(\\d{3,5})").find(cleaned) ?: return cleaned.uppercase()
        return "${parts.groupValues[1].uppercase()}-${parts.groupValues[2]}"
    }

    companion object {
        const val CACHE_MAX_AGE = 24L * 60 * 60 * 1000
        private const val PREFS = "sales_ranking_cache"
        private const val KEY_DATA = "data"
        private const val KEY_TIME = "time"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        private const val PROCLIVITY = "https://proclivity-db.com/"
        private const val NEOERO = "https://neoero.net/"
        private const val MGS_ARCHIVE = "https://shiroutowiki.work/ranking/"
        private const val NOBUNAGA = "https://www.e-nobunaga.com/blog/dvd/"
        private const val TOKYO = "https://tyokyosyoten-lepton.com/adult_dvd_ranking/"
        private const val KAITORI = "http://www.kaitorimax.com/ranking/ranking.html"

        fun saveCache(context: Context, sections: List<SalesSection>) {
            val arr = JSONArray()
            sections.forEach { section ->
                arr.put(JSONObject().apply {
                    put("id", section.id); put("title", section.title); put("category", section.category.name)
                    put("period", section.period); put("note", section.note); put("sourceUrl", section.sourceUrl); put("error", section.error)
                    put("items", JSONArray().apply { section.items.forEach { item -> put(item.toJson()) } })
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, arr.toString()).putLong(KEY_TIME, System.currentTimeMillis()).apply()
        }

        fun loadCache(context: Context): Pair<List<SalesSection>, Long>? {
            return try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val time = prefs.getLong(KEY_TIME, 0L)
                val raw = prefs.getString(KEY_DATA, null) ?: return null
                val arr = JSONArray(raw)
                val sections = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val itemArray = o.getJSONArray("items")
                    SalesSection(o.getString("id"), o.getString("title"), SalesCategory.valueOf(o.getString("category")),
                        o.getString("period"), o.getString("note"), o.getString("sourceUrl"),
                        (0 until itemArray.length()).map { SalesItem.fromJson(itemArray.getJSONObject(it)) }, o.optString("error"))
                }
                sections to time
            } catch (_: Exception) { null }
        }

        private fun SalesItem.toJson() = JSONObject().apply {
            put("rank", rank ?: JSONObject.NULL); put("title", title); put("actress", actress); put("code", code)
            put("maker", maker); put("detail", detail); put("imageUrl", imageUrl); put("linkUrl", linkUrl)
        }

    }
}

private fun String.toAsciiDigits(): String = map { c -> if (c in '０'..'９') ('0'.code + c.code - '０'.code).toChar() else c }.joinToString("")

class SalesRankingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val sections = SalesRankingRepository(applicationContext).fetchAll()
            if (sections.any { it.items.isNotEmpty() }) {
                SalesRankingRepository.saveCache(applicationContext, sections)
                Result.success()
            } else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
}

object SalesRankingScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<SalesRankingWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sales-ranking-daily", ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
}
