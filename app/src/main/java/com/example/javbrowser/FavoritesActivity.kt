package com.example.javbrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FavoritesActivity : LocalizedActivity() {

    private data class SourceFilter(
        val key: String,
        val label: String,
        val hostKeywords: List<String>
    )

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: android.widget.EditText
    private lateinit var btnHome: android.widget.Button
    private lateinit var btnFilter: android.widget.Button
    private lateinit var btnSort: android.widget.Button

    private enum class SortMode {
        DEFAULT,
        RATING_DESC, RATING_ASC,
        DATE_DESC,   DATE_ASC,
        VOTES_DESC,  VOTES_ASC
    }
    private var sortMode = SortMode.DEFAULT
    private lateinit var llActiveFilter: LinearLayout
    private lateinit var llFilterChips: com.google.android.material.chip.ChipGroup
    private lateinit var btnClearAllFilters: android.widget.Button
    private var filterBarGestureDetector: GestureDetector? = null
    private lateinit var llFilterActors: LinearLayout
    private lateinit var llFilterMaleActors: LinearLayout
    private lateinit var llFilterGenres: LinearLayout
    private lateinit var llFilterRatings: LinearLayout
    private lateinit var llFilterVotes: LinearLayout
    private lateinit var llFilterDates: LinearLayout
    private lateinit var llFilterYears: LinearLayout
    private lateinit var llFilterCustom: LinearLayout
    private lateinit var etFemaleSearch: android.widget.EditText
    private lateinit var etMaleSearch: android.widget.EditText
    private lateinit var btnTagEditMode: android.widget.Button
    private var isFemaleExpanded = false
    private var isMaleExpanded = false
    private var isYearExpanded = false
    private var isTagEditMode = false
    private var lastBuildResult: List<FavoriteItem> = emptyList()
    private val customLabels = mutableSetOf<String>()
    private lateinit var adapter: FavoritesAdapter
    private lateinit var favoritesManager: FavoritesManager
    private val fc2DomainConfig by lazy { DomainConfig(AdFilterRules(this)) }
    private var allFavorites: List<FavoriteItem> = emptyList()
    private var localVideosByCode: Map<String, List<LocalVideoFile>> = emptyMap()
    private var localStripchatRecordingsByUsername: Map<String, List<LocalVideoFile>> = emptyMap()
    private val localScanExecutor = Executors.newSingleThreadExecutor()
    private val isLocalScanRunning = AtomicBoolean(false)
    private val stripchatStatusExecutor = Executors.newFixedThreadPool(3)
    private val stripchatStatusGeneration = AtomicInteger(0)
    private val stripchatStatuses = ConcurrentHashMap<String, StripchatLiveStatus>()
    private val stripchatInfos = ConcurrentHashMap<String, StripchatBookmarkInfo>()
    private val stripchatCacheLock = Any()
    private val stripchatThumbnailHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stripchatThumbnailRefreshToken = System.currentTimeMillis()
    private val stripchatThumbnailRefreshRunnable = object : Runnable {
        override fun run() {
            refreshVisibleStripchatThumbnails()
            stripchatThumbnailHandler.postDelayed(this, STRIPCHAT_THUMBNAIL_REFRESH_MS)
        }
    }
    private val stripchatStatusClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private enum class StripchatLiveStatus { CHECKING, LIVE, OFFLINE, UNKNOWN }
    private data class StripchatBookmarkInfo(
        val status: StripchatLiveStatus,
        val checkedAt: Long,
        val roomStatus: String = "",
        val topic: String = "",
        val offlineStatus: String = "",
        val isHd: Boolean = false,
        val languages: List<String> = emptyList(),
        val scheduleDay: String = "",
        val scheduleStartSeconds: Int = -1,
        val previewUrl: String = "",
        val previewUrlThumbBig: String = "",
        val avatarUrlThumb: String = "",
        val snapshotTimestamp: Long = 0L,
        val modelId: Long = 0L
    )

    // Active filter state – map of type → set of selected values
    // Types: "actor", "genre", "rating"
    // AND across types, OR within same type
    private val activeFilters = mutableMapOf<String, MutableSet<String>>()
    private val sourceFilters = listOf(
        SourceFilter("pornhub", "Pornhub", listOf("pornhub.com", "phncdn.com")),
        SourceFilter("stripchat", "Stripchat", listOf("stripchat.com")),
        SourceFilter("xvideos", "XVideos", listOf("xvideos.com"))
    )

    // 批量刪除選擇模式
    private var isSelectionMode = false
    private val selectedUrls = mutableSetOf<String>()
    private lateinit var llSelectionBar: LinearLayout
    private lateinit var btnSelectCancel: android.widget.Button
    private lateinit var btnSelectAll: android.widget.Button
    private lateinit var btnDeleteSelected: android.widget.Button
    private lateinit var btnDeleteMode: android.widget.Button

    private val enrichReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadFavorites()
        }
    }

    companion object {
        const val ACTION_FAVORITE_ENRICHED = "com.example.javbrowser.FAVORITE_ENRICHED"
        private const val STRIPCHAT_CACHE_PREFS = "stripchat_live_status_cache"
        private const val STRIPCHAT_CACHE_KEY = "entries_v4"
        private const val STRIPCHAT_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val STRIPCHAT_THUMBNAIL_REFRESH_MS = 60 * 1000L

        val GENRE_CATEGORIES = linkedMapOf(
            "主題" to setOf("淫亂真實","出軌","強姦","亂倫","溫泉","女同性戀","企畫","戀腿癖","獵豔","偷窺","洗澡","其他戀物癖","處女","性愛","學校作品","妄想","M男","跳舞","戀物癖","戀乳癖","惡作劇","運動","倒追","女同接吻","美容院","奴隸","白天出軌","流汗","性騷擾","情侶","爛醉如泥的","魔鬼系","處男","殘忍畫面","性感的","曬黑","雙性人","全裸","正太控","觸手","正常","奇異的","蠻橫嬌羞","性轉換·女體化","男同性戀","韓國","形象俱樂部","友誼","亞洲","暗黑系","天賦","被外國人幹","刺青紋身","黑白配","絕頂高潮","純欲","經歷告白","濕身"),
            "角色" to setOf("高中女生","美少女","已婚婦女","藝人","姐姐","各種職業","蕩婦","母親","辣妹","妓女","新娘，年輕妻子","女教師","白人","婆婆","女大學生","偶像","明星臉","大小姐","秘書","護士","角色扮演者","賽車女郎","家教","黑人演員","妹妹","寡婦","女醫生","老闆娘，女主人","女主播","其他學生","模特兒","格鬥家","展場女孩","禮儀小姐","女檢察官","講師","服務生","伴侶","車掌小姐","女兒","年輕女孩","公主","童年朋友","飛特族","亞洲女演員","痴漢","御宅族","老太婆","老年男性","拉拉隊","媽媽的朋友","養女","女王"),
            "服裝" to setOf("眼鏡","角色扮演","內衣","制服","水手服","泳裝","和服，喪服","連褲襪","女傭","運動短褲","女戰士","校服","制服外套","裸體圍裙","女忍者","身體意識","OL","貓耳女","短裙","學校泳裝","迷你裙","浴衣","猥褻穿著","緊身衣","娃娃","蘿莉角色扮演","女裝人妖","絲襪、過膝襪","泡泡襪","空中小姐","旗袍","兔女郎","女祭司","動畫人物","迷你裙警察","修女","COSPLAY服飾","高跟鞋","靴子"),
            "體型" to setOf("熟女","巨乳","蘿莉塔","無毛","美臀","苗條","美乳","巨大陰莖","胖女人","平胸","素人","高挑","孕婦","大屁股","瘦小身型","變性者","肌肉","超乳","美腳","多毛"),
            "行爲" to setOf("乳交","中出","多P","69","淫語","女上位","自慰","顏射","潮吹","口交","舔陰","肛門・肛交","手指插入","手淫","深喉","放尿","足交","按摩","吞精","母乳","濫交","接吻","拳交","飲尿","騎乗位","排便","食糞","剃毛","二穴同入","兩女一男","兩男兩女","兩男一女","打屁股","約會","不穿內褲","不穿胸罩","後入","瑜伽·健身","白眼失神","搔癢"),
            "玩法" to setOf("凌辱","捆綁","緊縛","輪姦","玩具","SM","戶外","乳液","羞恥","女優按摩棒","拘束","調教","立即口交","跳蛋","監禁","按摩棒","插入異物","灌腸","藥物","露出","汽車性愛","催眠","鴨嘴","糞便","脫衣","子宮頸","導尿","蒙面・面罩","唾液敷面","乳釘、穿孔、乳環","口球","輔助自慰","夫妻交換","假陽具","鼻鉤","蠟燭","站立後入"),
            "類別" to setOf("單體作品","首次亮相","故事集","經典","戀愛","VR","感謝祭","給女性觀眾","無碼流出","4K","無碼破解","綜藝","精選綜合","國外進口","4小時以上作品","戲劇","成人電影","介紹影片")
        )
    }

    private val systemGenreSet: Set<String>
        get() = GENRE_CATEGORIES.values.flatten().toSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(R.layout.activity_favorites)

        favoritesManager = FavoritesManager(this)

        drawerLayout = findViewById(R.id.drawer_layout)
        recyclerView = findViewById(R.id.rv_favorites)
        tvEmpty = findViewById(R.id.tv_empty)
        etSearch = findViewById(R.id.et_search)
        btnHome = findViewById(R.id.btn_home)
        btnFilter = findViewById(R.id.btn_filter)
        btnSort = findViewById(R.id.btn_sort)
        llActiveFilter = findViewById(R.id.ll_active_filter)
        llFilterChips = findViewById(R.id.ll_filter_chips)
        btnClearAllFilters = findViewById(R.id.btn_clear_all_filters)
        llFilterActors = findViewById(R.id.ll_filter_actors)
        llFilterMaleActors = findViewById(R.id.ll_filter_male_actors)
        llFilterGenres = findViewById(R.id.ll_filter_genres)
        llFilterRatings = findViewById(R.id.ll_filter_ratings)
        llFilterVotes = findViewById(R.id.ll_filter_votes)
        llFilterDates = findViewById(R.id.ll_filter_dates)
        llFilterYears = findViewById(R.id.ll_filter_years)
        llFilterCustom = findViewById(R.id.ll_filter_custom)
        etFemaleSearch = findViewById(R.id.et_female_search)
        etMaleSearch = findViewById(R.id.et_male_search)
        btnTagEditMode = findViewById(R.id.btn_tag_edit_mode)
        llSelectionBar = findViewById(R.id.ll_selection_bar)
        btnSelectCancel = findViewById(R.id.btn_select_cancel)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
        btnDeleteMode = findViewById(R.id.btn_delete_mode)
        loadCustomLabels()

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = FavoritesAdapter(mutableListOf()) { item ->
            // 廣播 URL 給 MainActivity
            val broadcastIntent = Intent(MainActivity.ACTION_LOAD_URL).apply {
                putExtra(MainActivity.EXTRA_URL, item.url)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
            // 關閉書籤頁，立即回到 MainActivity（狀態已在 onPause 存好）
            finish()
        }
        recyclerView.adapter = adapter

        setupSearch()
        FooterHelper.setup(this)
        setupHomeButton()
        setupFilterButton()
        setupSortButton()
        setupTagEditMode()
        setupDeleteMode()
        restoreUiState()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(enrichReceiver, IntentFilter(ACTION_FAVORITE_ENRICHED))
        loadFavorites()
        scanLocalVideos()
        refreshStripchatLiveStatuses()
        stripchatThumbnailHandler.removeCallbacks(stripchatThumbnailRefreshRunnable)
        stripchatThumbnailHandler.postDelayed(
            stripchatThumbnailRefreshRunnable,
            STRIPCHAT_THUMBNAIL_REFRESH_MS
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(enrichReceiver)
        stripchatThumbnailHandler.removeCallbacks(stripchatThumbnailRefreshRunnable)
        saveUiState()
    }

    override fun onDestroy() {
        localScanExecutor.shutdownNow()
        stripchatThumbnailHandler.removeCallbacks(stripchatThumbnailRefreshRunnable)
        stripchatStatusGeneration.incrementAndGet()
        stripchatStatusExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun saveUiState() {
        val prefs = getSharedPreferences("fav_ui_state", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("search", etSearch.text.toString())
        editor.putString("sort", sortMode.name)
        // 儲存所有 activeFilters
        editor.putStringSet("filter_types", activeFilters.keys.toSet())
        activeFilters.forEach { (type, values) ->
            editor.putStringSet("filter_$type", values)
        }
        // 儲存捲動位置
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        editor.putInt("scroll", layoutManager?.findFirstVisibleItemPosition() ?: 0)
        editor.apply()
    }

    private fun restoreUiState() {
        val prefs = getSharedPreferences("fav_ui_state", Context.MODE_PRIVATE)
        // 還原排序
        val sortName = prefs.getString("sort", SortMode.DEFAULT.name)
        sortMode = try { SortMode.valueOf(sortName!!) } catch (e: Exception) { SortMode.DEFAULT }
        btnSort.text = when (sortMode) {
            SortMode.DEFAULT     -> "↕"
            SortMode.RATING_DESC -> "★↓"
            SortMode.RATING_ASC  -> "★↑"
            SortMode.DATE_DESC   -> "📅↓"
            SortMode.DATE_ASC    -> "📅↑"
            SortMode.VOTES_DESC  -> "👥↓"
            SortMode.VOTES_ASC   -> "👥↑"
        }
        // 還原篩選
        activeFilters.clear()
        prefs.getStringSet("filter_types", emptySet())?.forEach { type ->
            val values = prefs.getStringSet("filter_$type", emptySet()) ?: emptySet()
            if (values.isNotEmpty()) activeFilters[type] = values.toMutableSet()
        }
        // 還原搜尋（設定文字但不觸發 TextWatcher，最後才一次 applyFilters）
        val searchText = prefs.getString("search", "") ?: ""
        etSearch.removeTextChangedListener(searchWatcher)
        etSearch.setText(searchText)
        etSearch.addTextChangedListener(searchWatcher)
        // 還原捲動位置（等 adapter 更新後再捲）
        pendingScrollPos = prefs.getInt("scroll", 0)
    }

    private var pendingScrollPos = 0
    private lateinit var searchWatcher: android.text.TextWatcher

    private fun setupHomeButton() {
        btnHome.setOnClickListener { finish() }
    }

    private fun setupDeleteMode() {
        btnDeleteMode.setOnClickListener {
            drawerLayout.closeDrawers()
            enterSelectionMode()
        }
        btnSelectCancel.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener {
            if (selectedUrls.size == adapter.itemCount) {
                selectedUrls.clear()
            } else {
                selectedUrls.clear()
                adapter.getAllUrls().forEach { selectedUrls.add(it) }
            }
            adapter.notifyDataSetChanged()
            updateSelectionBar()
        }
        btnDeleteSelected.setOnClickListener { deleteSelected() }
    }

    private fun setupTagEditMode() {
        btnTagEditMode.setOnClickListener {
            isTagEditMode = !isTagEditMode
            updateTagEditModeButton()
            adapter.notifyDataSetChanged()
            drawerLayout.closeDrawers()
        }
        updateTagEditModeButton()
    }

    private fun updateTagEditModeButton() {
        btnTagEditMode.text = if (isTagEditMode) "✓" else "✎"
        btnTagEditMode.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (isTagEditMode) "#2E7D32" else "#00838F")
        )
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        selectedUrls.clear()
        llSelectionBar.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedUrls.clear()
        llSelectionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionBar() {
        val total = adapter.itemCount
        btnSelectAll.text = if (total > 0 && selectedUrls.size == total) "取消全選" else "全選"
        btnDeleteSelected.text = "🗑️ 刪除(${selectedUrls.size})"
    }

    private fun deleteSelected() {
        if (selectedUrls.isEmpty()) {
            android.widget.Toast.makeText(this, "請先勾選書籤", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("確認刪除")
            .setMessage("確定要刪除 ${selectedUrls.size} 個書籤？")
            .setPositiveButton("刪除") { _, _ ->
                selectedUrls.forEach { url -> favoritesManager.removeFavorite(url) }
                exitSelectionMode()
                loadFavorites()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupSortButton() {
        btnSort.setOnClickListener {
            sortMode = when (sortMode) {
                SortMode.DEFAULT     -> SortMode.RATING_DESC
                SortMode.RATING_DESC -> SortMode.RATING_ASC
                SortMode.RATING_ASC  -> SortMode.DATE_DESC
                SortMode.DATE_DESC   -> SortMode.DATE_ASC
                SortMode.DATE_ASC    -> SortMode.VOTES_DESC
                SortMode.VOTES_DESC  -> SortMode.VOTES_ASC
                SortMode.VOTES_ASC   -> SortMode.DEFAULT
            }
            btnSort.text = when (sortMode) {
                SortMode.DEFAULT     -> "↕"
                SortMode.RATING_DESC -> "★↓"
                SortMode.RATING_ASC  -> "★↑"
                SortMode.DATE_DESC   -> "📅↓"
                SortMode.DATE_ASC    -> "📅↑"
                SortMode.VOTES_DESC  -> "👥↓"
                SortMode.VOTES_ASC   -> "👥↑"
            }
            applyFilters()
        }
    }

    private fun setupFilterButton() {
        btnFilter.setOnClickListener {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }
        btnClearAllFilters.setOnClickListener {
            clearFilter()
            etSearch.setText("")
            sortMode = SortMode.DEFAULT
            btnSort.text = "↕"
            drawerLayout.closeDrawers()
        }
        filterBarGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val startEvent = e1 ?: return false
                val loc = IntArray(2)
                llActiveFilter.getLocationOnScreen(loc)
                val barTop = loc[1]
                val barBottom = barTop + llActiveFilter.height
                if (startEvent.rawY < barTop || startEvent.rawY > barBottom) return false
                if (velocityX > 400) {
                    drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        filterBarGestureDetector?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun setupSearch() {
        searchWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        etSearch.addTextChangedListener(searchWatcher)

        etFemaleSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isFemaleExpanded = false
                buildFilterDrawer(lastBuildResult)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        etMaleSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isMaleExpanded = false
                buildFilterDrawer(lastBuildResult)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun loadFavorites() {
        allFavorites = favoritesManager.getFavorites()
        applyFilters()
        if (pendingScrollPos > 0) {
            recyclerView.post {
                (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pendingScrollPos, 0)
                pendingScrollPos = 0
            }
        }
    }

    private fun scanLocalVideos() {
        if (!isLocalScanRunning.compareAndSet(false, true)) return
        localScanExecutor.execute {
            try {
                val files = LocalVideoRepository.scan(this)
                val indexed = files.mapNotNull { file ->
                    file.javCode?.let(LocalVideoRepository::normalizeCode)?.let { code -> code to file }
                }.groupBy({ it.first }, { it.second })
                val stripchatIndexed = files.mapNotNull { file ->
                    parseStripchatRecordingName(file.fileName)?.let { recording ->
                        recording.usernameKey to file
                    }
                }.groupBy({ it.first }, { it.second }).mapValues { (_, recordings) ->
                    recordings.sortedByDescending(::stripchatRecordingTimestamp)
                }
                if (!isDestroyed) {
                    runOnUiThread {
                        localVideosByCode = indexed
                        localStripchatRecordingsByUsername = stripchatIndexed
                        applyFilters()
                    }
                }
            } finally {
                isLocalScanRunning.set(false)
            }
        }
    }

    private fun localVideosFor(item: FavoriteItem): List<LocalVideoFile> {
        if (isStripchatUrl(item.url)) {
            val usernameKey = stripchatUsername(item.url)?.let(::normalizeStripchatUsername)
                ?: return emptyList()
            return localStripchatRecordingsByUsername[usernameKey].orEmpty()
        }
        val code = LocalVideoRepository.normalizeCode(item.javCode)
            ?: LocalVideoRepository.extractJavCode(item.title)
        return code?.let(localVideosByCode::get).orEmpty()
    }

    private data class StripchatRecordingName(
        val usernameKey: String,
        val recordedAt: Long,
    )

    private fun parseStripchatRecordingName(fileName: String): StripchatRecordingName? {
        val match = Regex(
            "^Stripchat-(.+)-(\\d{8})-(\\d{6})\\.(?:mp4|webm)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(fileName) ?: return null
        val recordedAt = try {
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).apply {
                isLenient = false
            }.parse("${match.groupValues[2]}-${match.groupValues[3]}")?.time
        } catch (_: Exception) {
            null
        } ?: return null
        return StripchatRecordingName(
            usernameKey = normalizeStripchatUsername(match.groupValues[1]),
            recordedAt = recordedAt,
        )
    }

    private fun normalizeStripchatUsername(value: String): String =
        value.trim().lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9_-]"), "_")

    private fun stripchatRecordingTimestamp(file: LocalVideoFile): Long =
        parseStripchatRecordingName(file.fileName)?.recordedAt ?: file.modifiedAt

    private fun stripchatRecordingLabel(file: LocalVideoFile): String {
        val timestamp = stripchatRecordingTimestamp(file)
        return java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }

    private fun isStripchatUrl(url: String): Boolean =
        url.contains("stripchat.com", ignoreCase = true)

    private fun fc2CodeFor(item: FavoriteItem): String? =
        JavDbScraper.extractFc2Code(item.url, item.title, item.javCode.orEmpty())

    private fun isFc2Favorite(item: FavoriteItem): Boolean = fc2CodeFor(item) != null

    private fun stripchatUsername(url: String): String? = try {
        java.net.URI(url).path.orEmpty().trim('/').substringBefore('/').takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** 每次進入書籤頁先用 5 分鐘快取，只重新查詢已過期的帳號。 */
    private fun refreshStripchatLiveStatuses() {
        val targets = allFavorites.filter { isStripchatUrl(it.url) }
            .distinctBy { it.url.lowercase() }
        val generation = stripchatStatusGeneration.incrementAndGet()
        val now = System.currentTimeMillis()
        val staleTargets = mutableListOf<FavoriteItem>()
        stripchatStatuses.clear()
        stripchatInfos.clear()
        targets.forEach { item ->
            val cached = readStripchatStatusCache(item.url)
            if (cached != null && now - cached.checkedAt < STRIPCHAT_CACHE_TTL_MS) {
                stripchatStatuses[item.url] = cached.status
                stripchatInfos[item.url] = cached
                android.util.Log.i("STRIPCHAT_STATUS", "${stripchatUsername(item.url)}=${cached.status} (cache)")
            } else {
                // 過期時仍先顯示上一次結果，背景靜默更新；沒有舊資料才顯示查詢中。
                stripchatStatuses[item.url] = cached?.status ?: StripchatLiveStatus.CHECKING
                if (cached != null) stripchatInfos[item.url] = cached
                staleTargets.add(item)
            }
        }
        if (targets.isNotEmpty()) applyFilters()

        staleTargets.forEach { item ->
            stripchatStatusExecutor.execute {
                val fetched = fetchStripchatLiveStatus(item.url)
                if (generation != stripchatStatusGeneration.get() || isDestroyed) return@execute
                val previous = readStripchatStatusCache(item.url)
                val result = if (fetched.status == StripchatLiveStatus.UNKNOWN && previous != null) previous else fetched
                if (fetched.status == StripchatLiveStatus.LIVE || fetched.status == StripchatLiveStatus.OFFLINE) {
                    writeStripchatStatusCache(item.url, fetched)
                }
                stripchatStatuses[item.url] = result.status
                stripchatInfos[item.url] = result
                android.util.Log.i("STRIPCHAT_STATUS", "${stripchatUsername(item.url)}=${result.status}")
                runOnUiThread { refreshStripchatStatusUi(item.url) }
            }
        }
    }

    private fun forceRefreshStripchatStatus(url: String) {
        val generation = stripchatStatusGeneration.get()
        stripchatStatuses[url] = StripchatLiveStatus.CHECKING
        adapter.notifyUrlChanged(url)
        stripchatStatusExecutor.execute {
            val fetched = fetchStripchatLiveStatus(url)
            if (generation != stripchatStatusGeneration.get() || isDestroyed) return@execute
            val previous = readStripchatStatusCache(url)
            val result = if (fetched.status == StripchatLiveStatus.UNKNOWN && previous != null) previous else fetched
            if (fetched.status == StripchatLiveStatus.LIVE || fetched.status == StripchatLiveStatus.OFFLINE) {
                writeStripchatStatusCache(url, fetched)
            }
            stripchatStatuses[url] = result.status
            stripchatInfos[url] = result
            stripchatThumbnailRefreshToken = System.currentTimeMillis()
            android.util.Log.i("STRIPCHAT_STATUS", "${stripchatUsername(url)}=${result.status} (manual)")
            runOnUiThread { refreshStripchatStatusUi(url) }
        }
    }

    /** 每分鐘只重載 RecyclerView 畫面內正在 LIVE 的縮圖，不重新查詢狀態 API。 */
    private fun refreshVisibleStripchatThumbnails() {
        if (isFinishing || isDestroyed) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        stripchatThumbnailRefreshToken = System.currentTimeMillis()
        val refreshed = adapter.refreshLiveThumbnails(first, last)
        if (refreshed > 0) {
            android.util.Log.i(
                "STRIPCHAT_THUMBNAIL",
                "refreshed=$refreshed token=$stripchatThumbnailRefreshToken"
            )
        }
    }

    private fun refreshStripchatStatusUi(url: String) {
        if (activeFilters["stripchat_live"]?.isNotEmpty() == true) {
            applyFilters()
        } else {
            adapter.notifyUrlChanged(url)
            buildFilterDrawer(lastBuildResult)
        }
    }

    private fun readStripchatStatusCache(url: String): StripchatBookmarkInfo? =
        synchronized(stripchatCacheLock) {
            try {
                val raw = getSharedPreferences(STRIPCHAT_CACHE_PREFS, Context.MODE_PRIVATE)
                    .getString(STRIPCHAT_CACHE_KEY, "{}") ?: "{}"
                val obj = JSONObject(raw).optJSONObject(url) ?: return@synchronized null
                val status = runCatching {
                    StripchatLiveStatus.valueOf(obj.optString("status"))
                }.getOrNull()?.takeIf {
                    it == StripchatLiveStatus.LIVE || it == StripchatLiveStatus.OFFLINE
                } ?: return@synchronized null
                val languageArray = obj.optJSONArray("languages")
                val languages = buildList {
                    if (languageArray != null) {
                        for (index in 0 until languageArray.length()) add(languageArray.optString(index))
                    }
                }
                StripchatBookmarkInfo(
                    status = status,
                    checkedAt = obj.optLong("checkedAt", 0L),
                    roomStatus = obj.optString("roomStatus"),
                    topic = obj.optString("topic"),
                    offlineStatus = obj.optString("offlineStatus"),
                    isHd = obj.optBoolean("isHd", false),
                    languages = languages,
                    scheduleDay = obj.optString("scheduleDay"),
                    scheduleStartSeconds = obj.optInt("scheduleStartSeconds", -1),
                    previewUrl = obj.optString("previewUrl"),
                    previewUrlThumbBig = obj.optString("previewUrlThumbBig"),
                    avatarUrlThumb = obj.optString("avatarUrlThumb"),
                    snapshotTimestamp = obj.optLong("snapshotTimestamp", 0L),
                    modelId = obj.optLong("modelId", 0L)
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun writeStripchatStatusCache(url: String, info: StripchatBookmarkInfo) {
        synchronized(stripchatCacheLock) {
            val prefs = getSharedPreferences(STRIPCHAT_CACHE_PREFS, Context.MODE_PRIVATE)
            val root = try {
                JSONObject(prefs.getString(STRIPCHAT_CACHE_KEY, "{}") ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
            root.put(url, JSONObject().apply {
                put("status", info.status.name)
                put("checkedAt", info.checkedAt)
                put("roomStatus", info.roomStatus)
                put("topic", info.topic)
                put("offlineStatus", info.offlineStatus)
                put("isHd", info.isHd)
                put("languages", org.json.JSONArray(info.languages))
                put("scheduleDay", info.scheduleDay)
                put("scheduleStartSeconds", info.scheduleStartSeconds)
                put("previewUrl", info.previewUrl)
                put("previewUrlThumbBig", info.previewUrlThumbBig)
                put("avatarUrlThumb", info.avatarUrlThumb)
                put("snapshotTimestamp", info.snapshotTimestamp)
                put("modelId", info.modelId)
            })
            prefs.edit().putString(STRIPCHAT_CACHE_KEY, root.toString()).apply()
        }
    }

    private fun fetchStripchatLiveStatus(modelUrl: String): StripchatBookmarkInfo {
        val checkedAt = System.currentTimeMillis()
        val username = stripchatUsername(modelUrl)
            ?: return StripchatBookmarkInfo(StripchatLiveStatus.UNKNOWN, checkedAt)
        val cookie = android.webkit.CookieManager.getInstance()
            .getCookie("https://stripchat.com/").orEmpty()
        val cachedModelId = readStripchatStatusCache(modelUrl)?.modelId ?: 0L

        return try {
            val snapshot = StripchatStatusApi.fetchSnapshot(
                client = stripchatStatusClient,
                username = username,
                referer = modelUrl,
                cookie = cookie,
                knownModelId = cachedModelId,
            ) ?: return StripchatBookmarkInfo(StripchatLiveStatus.UNKNOWN, checkedAt)
            val root = snapshot.root
            val user = root.optJSONObject("user")?.optJSONObject("user")
                ?: return StripchatBookmarkInfo(StripchatLiveStatus.UNKNOWN, checkedAt)
                val status = when {
                    user.optBoolean("isLive", false) -> StripchatLiveStatus.LIVE
                    user.has("isLive") -> StripchatLiveStatus.OFFLINE
                    user.optBoolean("isOnline", false) -> StripchatLiveStatus.LIVE
                    user.optString("status").equals("off", ignoreCase = true) -> StripchatLiveStatus.OFFLINE
                    else -> StripchatLiveStatus.UNKNOWN
                }
                val cam = root.optJSONObject("cam")
                val nearest = cam?.optJSONObject("broadcastSchedule")?.optJSONObject("nearest")
                val period = nearest?.optJSONArray("period")
                val languageArray = user.optJSONArray("languages")
                val languages = buildList {
                    if (languageArray != null) {
                        for (index in 0 until languageArray.length()) add(languageArray.optString(index))
                    }
                }
                StripchatBookmarkInfo(
                    status = status,
                    checkedAt = checkedAt,
                    roomStatus = user.optString("status"),
                    topic = cam?.optString("topic").orEmpty(),
                    offlineStatus = user.optString("offlineStatus"),
                    isHd = user.optBoolean("isHd", false),
                    languages = languages,
                    scheduleDay = nearest?.optString("day").orEmpty(),
                    scheduleStartSeconds = period?.optInt(0, -1) ?: -1,
                    previewUrl = user.optString("previewUrl"),
                    previewUrlThumbBig = user.optString("previewUrlThumbBig"),
                    avatarUrlThumb = user.optString("avatarUrlThumb"),
                    snapshotTimestamp = user.optLong("snapshotTimestamp", 0L),
                    modelId = snapshot.modelId.takeIf { it > 0L } ?: user.optLong("id", 0L)
                )
        } catch (e: Exception) {
            android.util.Log.w("STRIPCHAT_STATUS", "$username lookup failed", e)
            StripchatBookmarkInfo(StripchatLiveStatus.UNKNOWN, checkedAt)
        }
    }

    private fun stripchatRoomStatusLabel(status: String): String? = when (status.lowercase()) {
        "public" -> LanguageManager.text(this, "公開直播", "Public")
        "private" -> LanguageManager.text(this, "私人直播中", "Private")
        "p2p" -> LanguageManager.text(this, "私人連線中", "One-to-one")
        "group", "groupshow" -> LanguageManager.text(this, "群組直播中", "Group show")
        "away", "idle" -> LanguageManager.text(this, "暫時離開", "Away")
        else -> null
    }

    private fun formatStripchatLanguages(languages: List<String>): String {
        val labels = languages.mapNotNull { code ->
            when (code.lowercase()) {
                "zt", "zh", "zh-cn", "zh-tw" -> LanguageManager.text(this, "中文", "Chinese")
                "en" -> LanguageManager.text(this, "英文", "English")
                "ja", "jp" -> LanguageManager.text(this, "日文", "Japanese")
                "ko", "kr" -> LanguageManager.text(this, "韓文", "Korean")
                "es" -> LanguageManager.text(this, "西班牙文", "Spanish")
                "fr" -> LanguageManager.text(this, "法文", "French")
                "de" -> LanguageManager.text(this, "德文", "German")
                "ru" -> LanguageManager.text(this, "俄文", "Russian")
                else -> code.uppercase().takeIf { it.isNotBlank() }
            }
        }.distinct()
        return labels.joinToString("/")
    }

    private fun formatStripchatSchedule(info: StripchatBookmarkInfo): String? {
        if (info.scheduleDay.isBlank() || info.scheduleStartSeconds < 0) return null
        val day = when (info.scheduleDay.lowercase()) {
            "mon" -> LanguageManager.text(this, "週一", "Mon")
            "tue" -> LanguageManager.text(this, "週二", "Tue")
            "wed" -> LanguageManager.text(this, "週三", "Wed")
            "thu" -> LanguageManager.text(this, "週四", "Thu")
            "fri" -> LanguageManager.text(this, "週五", "Fri")
            "sat" -> LanguageManager.text(this, "週六", "Sat")
            "sun" -> LanguageManager.text(this, "週日", "Sun")
            else -> info.scheduleDay
        }
        val hour = info.scheduleStartSeconds / 3600
        val minute = (info.scheduleStartSeconds % 3600) / 60
        val time = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        return LanguageManager.text(this, "預定 $day $time", "Scheduled $day $time")
    }

    private fun formatStripchatCheckedAt(checkedAt: Long): String {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(checkedAt))
        return LanguageManager.text(this, "$time 更新", "Updated $time")
    }

    private fun openLocalVideo(files: List<LocalVideoFile>) {
        if (files.isEmpty()) return
        if (files.size == 1) {
            launchLocalVideo(files.first())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("選擇本地影片")
            .setItems(files.map { it.fileName }.toTypedArray()) { _, index ->
                launchLocalVideo(files[index])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun enrichFc2Favorite(item: FavoriteItem, canonicalCode: String) {
        MissavScraper(this, fc2DomainConfig).scrapeByCode(canonicalCode) { result ->
            runOnUiThread {
                if (result != null) {
                    favoritesManager.updateFavoriteDetail(
                        item.url,
                        JavVideoDetail(
                            code = canonicalCode,
                            title = result.title.removePrefix(canonicalCode).trim(),
                            coverUrl = result.coverUrl,
                            date = result.releaseDate,
                            duration = "",
                            maker = result.maker,
                            series = result.series,
                            rating = "",
                            genres = result.genres,
                            actors = result.actors,
                            detailUrl = result.pageUrl
                        )
                    )
                    LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(Intent(ACTION_FAVORITE_ENRICHED))
                    android.widget.Toast.makeText(
                        this,
                        LanguageManager.text(this, "已從 MISSAV 更新資料", "Metadata updated from MISSAV"),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    adapter.notifyUrlChanged(item.url)
                    android.widget.Toast.makeText(
                        this,
                        LanguageManager.text(
                            this,
                            "MISSAV 找不到 $canonicalCode，已保留原網站資料",
                            "$canonicalCode was not found on MISSAV; original data was kept"
                        ),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun launchLocalVideo(file: LocalVideoFile) {
        LocalVideoPlayback.openExternal(this, file.uri)
    }

    /**
     * 依「目前篩選結果集」重建側邊選單。
     * 已選中的選項永遠顯示；未選中且在結果集中 count=0 的選項隱藏。
     * 這樣選了某個標籤後，不可能有交集的選項自動消失。
     */
    private fun buildFilterDrawer(currentResult: List<FavoriteItem> = allFavorites) {
        lastBuildResult = currentResult
        // 統計目前結果集的 count
        val femaleCount = mutableMapOf<String, Int>()
        val maleCount   = mutableMapOf<String, Int>()
        val genreCount  = mutableMapOf<String, Int>()
        val yearCount   = mutableMapOf<String, Int>()
        val customTagCount = mutableMapOf<String, Int>()
        currentResult.forEach { item ->
            item.actors.forEach { a ->
                if (a.endsWith("♂")) maleCount[a] = (maleCount[a] ?: 0) + 1
                else femaleCount[a] = (femaleCount[a] ?: 0) + 1
            }
            mergedGenres(item).forEach { g -> genreCount[g] = (genreCount[g] ?: 0) + 1 }
            visibleCustomTags(item).forEach { tag -> customTagCount[tag] = (customTagCount[tag] ?: 0) + 1 }
            val yr = item.releaseDate?.take(4)
            if (!yr.isNullOrEmpty() && yr.all { it.isDigit() }) yearCount[yr] = (yearCount[yr] ?: 0) + 1
        }

        // 演員（女）：從 allFavorites 取全集，但 count=0 且未選中的隱藏
        val allFemale = mutableMapOf<String, Int>()
        val allMale   = mutableMapOf<String, Int>()
        allFavorites.forEach { item ->
            item.actors.forEach { a ->
                if (a.endsWith("♂")) allMale[a] = (allMale[a] ?: 0) + 1
                else allFemale[a] = (allFemale[a] ?: 0) + 1
            }
        }

        // ── 自訂標籤區段 ────────────────────────────────────────────────
        llFilterCustom.removeAllViews()
        sourceFilters.forEach { source ->
            val count = allFavorites.count { matchesSourceFilter(it, source.key) }
            val isActive = activeFilters["source"]?.contains(source.key) == true
            if (count > 0 || isActive) {
                llFilterCustom.addView(
                    makeFilterChip("${source.label} ($count)", "#5D4037", "#EFEBE9", "source", source.key) {
                        setFilter("source", source.key)
                    }
                )
            }
        }
        val fc2Count = allFavorites.count(::isFc2Favorite)
        val fc2FilterActive = activeFilters["fc2"]?.contains("fc2") == true
        if (fc2Count > 0 || fc2FilterActive) {
            llFilterCustom.addView(
                makeFilterChip("FC2 ($fc2Count)", "#6A1B9A", "#F3E5F5", "fc2", "fc2") {
                    setFilter("fc2", "fc2")
                }
            )
        }
        val liveCount = allFavorites.count {
            stripchatStatuses[it.url] == StripchatLiveStatus.LIVE
        }
        val liveFilterActive = activeFilters["stripchat_live"]?.contains("live") == true
        if (liveCount > 0 || liveFilterActive) {
            llFilterCustom.addView(
                makeFilterChip(
                    LanguageManager.text(this, "● LIVE中 ($liveCount)", "● LIVE ($liveCount)"),
                    "#B71C1C",
                    "#FFEBEE",
                    "stripchat_live",
                    "live"
                ) { setFilter("stripchat_live", "live") }
            )
        }
        val localCount = allFavorites.count { localVideosFor(it).isNotEmpty() }
        llFilterCustom.addView(
            makeFilterChip(
                LanguageManager.text(this, "本地影片 ($localCount)", "Local Videos ($localCount)"),
                "#1B5E20",
                "#E8F5E9",
                "local",
                "available"
            ) { setFilter("local", "available") }
        )
        val allCustomTags = linkedSetOf<String>().apply {
            addAll(customLabels.sorted())
            allFavorites.flatMapTo(this) { visibleCustomTags(it) }
        }
        allCustomTags.filterNot { it.equals("FC2", ignoreCase = true) }.forEach { label ->
            val count = customTagCount[label] ?: 0
            val isActive = activeFilters["custom"]?.contains(label) == true
            if (count <= 0 && !isActive) return@forEach
            val chip = makeFilterChip("$label ($count)", "#006064", "#E0F7FA", "custom", label) {
                setFilter("custom", label)
            }
            chip.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("移除常用標籤「$label」？")
                    .setPositiveButton("刪除") { _, _ ->
                        customLabels.remove(label)
                        saveCustomLabels()
                        updateActiveFilterBar()
                        buildFilterDrawer()
                        applyFilters()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            llFilterCustom.addView(chip)
        }
        llFilterCustom.addView(TextView(this).apply {
            text = "+ 新增標籤"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#006064"))
            setPadding(48, 16, 48, 16)
            setOnClickListener { showAddCustomLabelDialog() }
        })

        // 加入日期區段
        llFilterDates.removeAllViews()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfToday = cal.timeInMillis
        val startOfYesterday = startOfToday - 86_400_000L
        val startOfDayBefore = startOfToday - 172_800_000L
        listOf(
            Triple("今日書籤", "today",     allFavorites.count { it.addedAt >= startOfToday }),
            Triple("昨日書籤", "yesterday", allFavorites.count { it.addedAt in startOfYesterday until startOfToday }),
            Triple("前天書籤", "dayBefore", allFavorites.count { it.addedAt in startOfDayBefore until startOfYesterday })
        ).forEach { (label, value, count) ->
            val isActive = activeFilters["date"]?.contains(value) == true
            if (count > 0 || isActive) {
                llFilterDates.addView(makeFilterChip("$label ($count)", "#4527A0", "#EDE7F6", "date", value) {
                    setFilter("date", value)
                })
            }
        }
        if (llFilterDates.childCount == 0) llFilterDates.addView(makeNoDataLabel("最近無新書籤"))

        // 上市年份
        val allYears = mutableMapOf<String, Int>()
        allFavorites.forEach { item ->
            val yr = item.releaseDate?.take(4)
            if (!yr.isNullOrEmpty() && yr.all { it.isDigit() }) allYears[yr] = (allYears[yr] ?: 0) + 1
        }
        llFilterYears.removeAllViews()
        val visibleYears = allYears.keys.filter { yr ->
            (yearCount[yr] ?: 0) > 0 || activeFilters["year"]?.contains(yr) == true
        }.sortedDescending()
        val yearLimit = 8
        val showAllYears = isYearExpanded || visibleYears.size <= yearLimit
        val yearsToShow = if (showAllYears) visibleYears else visibleYears.take(yearLimit)
        yearsToShow.forEach { yr ->
            val count = yearCount[yr] ?: 0
            llFilterYears.addView(makeFilterChip("$yr ($count)", "#4527A0", "#EDE7F6", "year", yr) {
                setFilter("year", yr)
            })
        }
        if (!showAllYears) {
            val moreCount = visibleYears.size - yearLimit
            llFilterYears.addView(TextView(this).apply {
                text = LanguageManager.text(this@FavoritesActivity, "更多 ($moreCount) ▼", "More ($moreCount) ▼")
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#4527A0"))
                setPadding(48, 16, 48, 16)
                setOnClickListener { isYearExpanded = true; buildFilterDrawer(lastBuildResult) }
            })
        } else if (isYearExpanded && visibleYears.size > yearLimit) {
            llFilterYears.addView(TextView(this).apply {
                text = LanguageManager.text(this@FavoritesActivity, "收起 ▲", "Collapse ▲")
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#4527A0"))
                setPadding(48, 16, 48, 16)
                setOnClickListener { isYearExpanded = false; buildFilterDrawer(lastBuildResult) }
            })
        }
        if (llFilterYears.childCount == 0) llFilterYears.addView(makeNoDataLabel("尚無年份資料"))

        // 女優（含搜尋 + 更多按鈕）
        llFilterActors.removeAllViews()
        val femaleSearch = etFemaleSearch.text.toString().trim().lowercase()
        val femaleChips = allFemale.keys
            .filter { actor ->
                val count = femaleCount[actor] ?: 0
                val isActive = activeFilters["actor"]?.contains(actor) == true
                (count > 0 || isActive) && (femaleSearch.isEmpty() || actor.lowercase().contains(femaleSearch))
            }
            .sortedByDescending { allFemale[it] }
        val femaleLimit = 15
        val showAllFemale = isFemaleExpanded || femaleChips.size <= femaleLimit
        val femaleVisible = if (showAllFemale) femaleChips else femaleChips.take(femaleLimit)
        femaleVisible.forEach { actor ->
            val count = femaleCount[actor] ?: 0
            llFilterActors.addView(makeFilterChip("$actor ($count)", "#C2185B", "#FCE4EC", "actor", actor) {
                setFilter("actor", actor)
            })
        }
        if (!showAllFemale) {
            val moreCount = femaleChips.size - femaleLimit
            llFilterActors.addView(TextView(this).apply {
                text = "更多 ($moreCount) ▼"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#C2185B"))
                setPadding(48, 16, 48, 16)
                setOnClickListener { isFemaleExpanded = true; buildFilterDrawer(lastBuildResult) }
            })
        } else if (isFemaleExpanded && femaleChips.size > femaleLimit) {
            llFilterActors.addView(TextView(this).apply {
                text = "收起 ▲"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#C2185B"))
                setPadding(48, 16, 48, 16)
                setOnClickListener { isFemaleExpanded = false; buildFilterDrawer(lastBuildResult) }
            })
        }
        if (llFilterActors.childCount == 0) llFilterActors.addView(makeNoDataLabel(LanguageManager.text(this, "尚無女優資料", "No actress data")))

        // 男優（含搜尋 + 更多按鈕）
        llFilterMaleActors.removeAllViews()
        val maleSearch = etMaleSearch.text.toString().trim().lowercase()
        val maleChips = allMale.keys
            .filter { actor ->
                val count = maleCount[actor] ?: 0
                val isActive = activeFilters["actor"]?.contains(actor) == true
                (count > 0 || isActive) && (maleSearch.isEmpty() || actor.lowercase().contains(maleSearch))
            }
            .sortedByDescending { allMale[it] }
        val maleLimit = 10
        val showAllMale = isMaleExpanded || maleChips.size <= maleLimit
        val maleVisible = if (showAllMale) maleChips else maleChips.take(maleLimit)
        maleVisible.forEach { actor ->
            val count = maleCount[actor] ?: 0
            llFilterMaleActors.addView(makeFilterChip("$actor ($count)", "#1565C0", "#E3F2FD", "actor", actor) {
                setFilter("actor", actor)
            })
        }
        if (!showAllMale) {
            val moreCount = maleChips.size - maleLimit
            llFilterMaleActors.addView(TextView(this).apply {
                text = LanguageManager.text(this@FavoritesActivity, "更多 ($moreCount) ▼", "More ($moreCount) ▼")
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#1565C0"))
                setPadding(48, 16, 48, 16)
                setOnClickListener { isMaleExpanded = true; buildFilterDrawer(lastBuildResult) }
            })
        } else if (isMaleExpanded && maleChips.size > maleLimit) {
            llFilterMaleActors.addView(TextView(this).apply {
                text = LanguageManager.text(this@FavoritesActivity, "收起 ▲", "Collapse ▲")
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#1565C0"))
                setPadding(48, 16, 48, 16)
                setOnClickListener { isMaleExpanded = false; buildFilterDrawer(lastBuildResult) }
            })
        }
        if (llFilterMaleActors.childCount == 0) llFilterMaleActors.addView(makeNoDataLabel(LanguageManager.text(this, "尚無男優資料", "No actor data")))

        // 評分：靜態選項，count=0 且未選中則隱藏
        llFilterRatings.removeAllViews()
        listOf("4~5★" to "4", "3~4★" to "3", "2~3★" to "2").forEach { (label, value) ->
            val isActive = activeFilters["rating"]?.contains(value) == true
            val count = currentResult.count { item ->
                val score = parseRatingScore(item.rating)
                score != null && score >= (value.toDouble()) && score < (value.toDouble() + 1.0)
            }
            if (count > 0 || isActive) {
                llFilterRatings.addView(makeFilterChip("$label ($count)", "#F57F17", "#FFF9C4", "rating", value) {
                    setFilter("rating", value)
                })
            }
        }

        // 評價人數：靜態選項，count=0 且未選中則隱藏
        llFilterVotes.removeAllViews()
        listOf(
            LanguageManager.text(this, "1000人以上", "1000+ ratings") to "1000",
            LanguageManager.text(this, "100~1000人", "100–1000 ratings") to "100",
            LanguageManager.text(this, "0~100人", "0–100 ratings") to "0"
        ).forEach { (label, value) ->
            val isActive = activeFilters["votes"]?.contains(value) == true
            val count = currentResult.count { item ->
                val c = parseRatingCount(item.rating)
                when (value) {
                    "1000" -> c != null && c >= 1000
                    "100"  -> c != null && c in 100..999
                    else   -> c == null || c < 100
                }
            }
            if (count > 0 || isActive) {
                llFilterVotes.addView(makeFilterChip("$label ($count)", "#00695C", "#E0F2F1", "votes", value) {
                    setFilter("votes", value)
                })
            }
        }

        // 類別 — 分子類別顯示
        val allGenres = mutableMapOf<String, Int>()
        allFavorites.forEach { item -> mergedGenres(item).forEach { g -> allGenres[g] = (allGenres[g] ?: 0) + 1 } }
        llFilterGenres.removeAllViews()
        val categorizedGenres = mutableSetOf<String>()
        GENRE_CATEGORIES.forEach { (catName, catGenres) ->
            categorizedGenres.addAll(catGenres)
            val visibleInCat = catGenres.filter { g ->
                (genreCount[g] ?: 0) > 0 || activeFilters["genre"]?.contains(g) == true
            }.sortedByDescending { genreCount[it] ?: 0 }
            if (visibleInCat.isEmpty()) return@forEach
            llFilterGenres.addView(TextView(this).apply {
                text = LanguageManager.genre(this@FavoritesActivity, catName)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#455A64"))
                setBackgroundColor(android.graphics.Color.parseColor("#ECEFF1"))
                setPadding(16, 6, 16, 6)
            })
            visibleInCat.forEach { genre ->
                val count = genreCount[genre] ?: 0
                llFilterGenres.addView(makeFilterChip("${LanguageManager.genre(this, genre)} ($count)", "#1565C0", "#E3F2FD", "genre", genre) {
                    setFilter("genre", genre)
                })
            }
        }
        // 未分類的標籤
        val uncategorized = allGenres.keys.filter { g ->
            g !in categorizedGenres && ((genreCount[g] ?: 0) > 0 || activeFilters["genre"]?.contains(g) == true)
        }.sortedByDescending { genreCount[it] ?: 0 }
        if (uncategorized.isNotEmpty()) {
            llFilterGenres.addView(TextView(this).apply {
                text = LanguageManager.genre(this@FavoritesActivity, "其他")
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#455A64"))
                setBackgroundColor(android.graphics.Color.parseColor("#ECEFF1"))
                setPadding(16, 6, 16, 6)
            })
            uncategorized.forEach { genre ->
                val count = genreCount[genre] ?: 0
                llFilterGenres.addView(makeFilterChip("${LanguageManager.genre(this, genre)} ($count)", "#1565C0", "#E3F2FD", "genre", genre) {
                    setFilter("genre", genre)
                })
            }
        }
        if (llFilterGenres.childCount == 0) llFilterGenres.addView(makeNoDataLabel(LanguageManager.text(this, "尚無類別資料", "No genre data")))
    }

    private fun makeFilterChip(
        label: String,
        textColor: String,
        bgColor: String,
        filterType: String,
        filterValue: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor(textColor))
            setPadding(48, 16, 48, 16)
            setOnClickListener { onClick() }
            val isActive = activeFilters[filterType]?.contains(filterValue) == true
            if (isActive) {
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(android.graphics.Color.parseColor(bgColor))
            } else {
                setTypeface(null, Typeface.NORMAL)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    /** Extract the numeric score from strings like "4.25, by 100 users" or "4.25分, 由100人評價" */
    private fun parseRatingScore(rating: String?): Double? {
        if (rating.isNullOrEmpty()) return null
        return Regex("(\\d+\\.?\\d*)").find(rating)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Extract vote count from strings like "4.39分,由587人評價" → 587 */
    private fun parseRatingCount(rating: String?): Int? {
        if (rating.isNullOrEmpty()) return null
        return Regex("由(\\d+)人").find(rating)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d+)\\s*人").find(rating)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun makeNoDataLabel(msg: String): TextView {
        return TextView(this).apply {
            text = msg
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            setPadding(48, 16, 48, 16)
        }
    }

    private fun mergedGenres(item: FavoriteItem): List<String> {
        val merged = linkedSetOf<String>()
        item.genres.forEach { merged.add(it) }
        item.customTags.filter { isSystemGenre(it) }.forEach { merged.add(it) }
        return merged.toList()
    }

    private fun visibleCustomTags(item: FavoriteItem): List<String> =
        item.customTags.filterNot(::isSystemGenre)

    private fun matchesSourceFilter(item: FavoriteItem, sourceKey: String): Boolean {
        val source = sourceFilters.firstOrNull { it.key == sourceKey } ?: return false
        val url = item.url.lowercase()
        return source.hostKeywords.any { keyword -> url.contains(keyword) }
    }

    private fun isSystemGenre(tag: String): Boolean =
        systemGenreSet.any { it.equals(tag.trim(), ignoreCase = true) }

    // ── 自訂標籤 ─────────────────────────────────────────────────────────────

    private fun loadCustomLabels() {
        val prefs = getSharedPreferences("fav_ui_state", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("custom_labels", null)
        customLabels.clear()
        if (saved != null) {
            customLabels.addAll(saved)
        } else {
            customLabels.addAll(listOf("FC2", "LUXU"))
            saveCustomLabels()
        }
    }

    private fun saveCustomLabels() {
        getSharedPreferences("fav_ui_state", Context.MODE_PRIVATE)
            .edit().putStringSet("custom_labels", customLabels.toSet()).apply()
    }

    private fun showAddCustomLabelDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "例如：FC2、LUXU、S1..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            setPadding(64, 32, 64, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("新增自訂標籤")
            .setView(editText)
            .setPositiveButton("新增") { _, _ ->
                val label = editText.text.toString().trim().uppercase()
                if (label.isNotEmpty() && !customLabels.contains(label) && !isSystemGenre(label)) {
                    customLabels.add(label)
                    saveCustomLabels()
                    buildFilterDrawer()
                    applyFilters()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setFilter(type: String, value: String) {
        val set = activeFilters.getOrPut(type) { mutableSetOf() }
        if (set.contains(value)) {
            set.remove(value)
            if (set.isEmpty()) activeFilters.remove(type)
        } else {
            set.add(value)
        }
        etSearch.setText("")
        updateActiveFilterBar()
        buildFilterDrawer()
        applyFilters()
    }

    private fun clearFilter() {
        activeFilters.clear()
        updateActiveFilterBar()
        buildFilterDrawer()
        applyFilters()
    }

    private fun removeFilter(type: String, value: String) {
        activeFilters[type]?.remove(value)
        if (activeFilters[type]?.isEmpty() == true) activeFilters.remove(type)
        updateActiveFilterBar()
        buildFilterDrawer()
        applyFilters()
    }

    private fun updateActiveFilterBar() {
        data class ChipEntry(val label: String, val type: String, val raw: String)
        val chips = mutableListOf<ChipEntry>()
        activeFilters["actor"]?.forEach { chips.add(ChipEntry(it, "actor", it)) }
        activeFilters["genre"]?.forEach { chips.add(ChipEntry(LanguageManager.genre(this, it), "genre", it)) }
        activeFilters["rating"]?.forEach { v ->
            chips.add(ChipEntry("${v}~${v.toInt() + 1}★", "rating", v))
        }
        activeFilters["votes"]?.forEach { v ->
            val label = when (v) {
                "1000" -> LanguageManager.text(this, "1000人以上", "1000+ ratings")
                "100" -> LanguageManager.text(this, "100~1000人", "100–1000 ratings")
                else -> LanguageManager.text(this, "0~100人", "0–100 ratings")
            }
            chips.add(ChipEntry(label, "votes", v))
        }
        activeFilters["date"]?.forEach { v ->
            val label = when (v) {
                "today" -> LanguageManager.text(this, "今日書籤", "Added today")
                "yesterday" -> LanguageManager.text(this, "昨日書籤", "Added yesterday")
                else -> LanguageManager.text(this, "前天書籤", "Added two days ago")
            }
            chips.add(ChipEntry(label, "date", v))
        }
        activeFilters["year"]?.forEach { v -> chips.add(ChipEntry("${v}年", "year", v)) }
        activeFilters["source"]?.forEach { v ->
            val label = sourceFilters.firstOrNull { it.key == v }?.label ?: v
            chips.add(ChipEntry(label, "source", v))
        }
        activeFilters["custom"]?.forEach { v -> chips.add(ChipEntry("[$v]", "custom", v)) }
        activeFilters["local"]?.forEach {
            chips.add(ChipEntry(LanguageManager.text(this, "有本地影片", "Has Local Video"), "local", it))
        }
        activeFilters["stripchat_live"]?.forEach {
            chips.add(ChipEntry(LanguageManager.text(this, "● LIVE中", "● LIVE"), "stripchat_live", it))
        }
        activeFilters["fc2"]?.forEach {
            chips.add(ChipEntry("FC2", "fc2", it))
        }

        llFilterChips.removeAllViews()
        if (chips.isEmpty()) {
            llActiveFilter.visibility = View.GONE
            return
        }
        llActiveFilter.visibility = View.VISIBLE
        chips.forEach { entry ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = entry.label
                isCloseIconVisible = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0"))
                setTextColor(Color.WHITE)
                closeIconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#90CAF9"))
                textSize = 12f
                setOnCloseIconClickListener { removeFilter(entry.type, entry.raw) }
                isClickable = false
                isFocusable = false
            }
            llFilterChips.addView(chip)
        }
    }

    /** Search text + active filters combined.
     *  Within same type: OR (any selected value matches).
     *  Across types: AND (all active types must match). */
    private fun applyFilters() {
        val query = etSearch.text.toString()
        var result = allFavorites

        // AND across types
        activeFilters.forEach { (type, values) ->
            if (values.isEmpty()) return@forEach
            result = when (type) {
                "actor" -> result.filter { item ->
                    values.all { v -> item.actors.any { it.equals(v, ignoreCase = true) } }
                }
                "genre" -> result.filter { item ->
                    values.all { v -> mergedGenres(item).any { it.equals(v, ignoreCase = true) } }
                }
                "rating" -> result.filter { item ->
                    val score = parseRatingScore(item.rating)
                    score != null && values.any { v ->
                        val min = v.toDoubleOrNull() ?: 0.0
                        score >= min && score < min + 1.0
                    }
                }
                "votes" -> result.filter { item ->
                    val count = parseRatingCount(item.rating)
                    values.any { v ->
                        when (v) {
                            "1000" -> count != null && count >= 1000
                            "100"  -> count != null && count in 100..999
                            else   -> count == null || count < 100
                        }
                    }
                }
                "year" -> result.filter { item ->
                    val yr = item.releaseDate?.take(4) ?: ""
                    values.any { v -> yr == v }
                }
                "custom" -> result.filter { item ->
                    values.all { v -> visibleCustomTags(item).any { it.equals(v, ignoreCase = true) } }
                }
                "source" -> result.filter { item ->
                    values.any { v -> matchesSourceFilter(item, v) }
                }
                "local" -> result.filter { item -> localVideosFor(item).isNotEmpty() }
                "stripchat_live" -> result.filter { item ->
                    stripchatStatuses[item.url] == StripchatLiveStatus.LIVE
                }
                "fc2" -> result.filter(::isFc2Favorite)
                "date" -> {
                    val dc = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val todayMs     = dc.timeInMillis
                    val yesterdayMs = todayMs - 86_400_000L
                    val dayBeforeMs = todayMs - 172_800_000L
                    result.filter { item ->
                        values.any { v ->
                            when (v) {
                                "today"     -> item.addedAt >= todayMs
                                "yesterday" -> item.addedAt in yesterdayMs until todayMs
                                "dayBefore" -> item.addedAt in dayBeforeMs until yesterdayMs
                                else        -> false
                            }
                        }
                    }
                }
                else -> result
            }
        }

        // Apply search query on top
        if (query.isNotEmpty()) {
            result = result.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.url.contains(query, ignoreCase = true) ||
                (it.javCode?.contains(query, ignoreCase = true) == true) ||
                it.actors.any { a -> a.contains(query, ignoreCase = true) } ||
                mergedGenres(it).any { g -> g.contains(query, ignoreCase = true) } ||
                visibleCustomTags(it).any { t -> t.contains(query, ignoreCase = true) } ||
                it.note.contains(query, ignoreCase = true) ||
                (it.maker?.contains(query, ignoreCase = true) == true) ||
                (it.series?.contains(query, ignoreCase = true) == true) ||
                stripchatInfos[it.url]?.let { info ->
                    info.topic.contains(query, ignoreCase = true) ||
                        info.offlineStatus.contains(query, ignoreCase = true) ||
                        info.languages.any { language -> language.contains(query, ignoreCase = true) }
                } == true
            }
        }

        result = when (sortMode) {
            SortMode.RATING_DESC -> result.sortedByDescending { parseRatingScore(it.rating) ?: -1.0 }
            SortMode.RATING_ASC  -> result.sortedBy { parseRatingScore(it.rating) ?: Double.MAX_VALUE }
            SortMode.DATE_DESC   -> result.sortedByDescending { it.releaseDate ?: "" }
            SortMode.DATE_ASC    -> result.sortedBy { it.releaseDate ?: "9999" }
            SortMode.VOTES_DESC  -> result.sortedByDescending { parseRatingCount(it.rating) ?: -1 }
            SortMode.VOTES_ASC   -> result.sortedBy { parseRatingCount(it.rating) ?: Int.MAX_VALUE }
            SortMode.DEFAULT     -> result.sortedByDescending { it.addedAt }
        }

        adapter.updateList(result)
        updateActiveFilterBar()
        buildFilterDrawer(result)
        if (result.isEmpty()) {
            tvEmpty.text = if (query.isEmpty() && activeFilters.isEmpty()) "No favorites yet" else "找不到符合的收藏"
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun showEditFavoriteDialog(item: FavoriteItem) {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((density * 18).toInt(), (density * 8).toInt(), (density * 18).toInt(), 0)
        }
        val helper = TextView(this).apply {
            text = LanguageManager.text(
                this@FavoritesActivity,
                "自訂類別可用逗號、頓號、分號或換行分隔",
                "Separate custom tags with commas, semicolons, or line breaks"
            )
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
        }
        val tagInput = android.widget.EditText(this).apply {
            hint = LanguageManager.text(this@FavoritesActivity, "例如：收藏, 已看, 劇情好", "Example: Favorite, Watched, Great plot")
            setText(item.customTags.joinToString(", "))
            minLines = 2
            maxLines = 3
        }
        val noteTitle = TextView(this).apply {
            text = LanguageManager.text(this@FavoritesActivity, "心得 / 備註", "Notes")
            textSize = 12f
            setTextColor(Color.parseColor("#6D4C41"))
            setPadding(0, (density * 12).toInt(), 0, (density * 6).toInt())
        }
        val noteInput = android.widget.EditText(this).apply {
            hint = LanguageManager.text(this@FavoritesActivity, "寫下你的心得或備註", "Write your notes or comments")
            setText(item.note)
            minLines = 4
            maxLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        container.addView(helper)
        container.addView(tagInput)
        container.addView(noteTitle)
        container.addView(noteInput)

        AlertDialog.Builder(this)
            .setTitle(LanguageManager.text(this, "編輯自訂類別與心得", "Edit custom tags and notes"))
            .setView(container)
            .setPositiveButton(LanguageManager.text(this, "儲存", "Save")) { _, _ ->
                val tags = parseCustomTags(tagInput.text?.toString().orEmpty())
                val note = noteInput.text?.toString().orEmpty().trim()
                customLabels.addAll(tags.filterNot(::isSystemGenre))
                saveCustomLabels()
                favoritesManager.updateFavoriteUserData(item.url, tags, note)
                loadFavorites()
            }
            .setNeutralButton(LanguageManager.text(this, "清空", "Clear")) { _, _ ->
                favoritesManager.updateFavoriteUserData(item.url, emptyList(), "")
                loadFavorites()
            }
            .setNegativeButton(LanguageManager.text(this, "取消", "Cancel"), null)
            .show()
    }

    private fun parseCustomTags(input: String): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        input.split(',', '，', '、', ';', '；', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { tag ->
                val key = tag.lowercase()
                if (seen.add(key)) result.add(tag)
            }
        return result
    }

    inner class FavoritesAdapter(
        private var items: MutableList<FavoriteItem>,
        private val onItemClick: (FavoriteItem) -> Unit
    ) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_title)
            val tvUrl: TextView = view.findViewById(R.id.tv_url)
            val ivThumbnailBackground: android.widget.ImageView =
                view.findViewById(R.id.iv_thumbnail_background)
            val ivThumbnail: android.widget.ImageView = view.findViewById(R.id.iv_thumbnail)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
            val tvActors: TextView = view.findViewById(R.id.tv_actors)
            val tvMeta: TextView = view.findViewById(R.id.tv_meta)
            val tvGenres: TextView = view.findViewById(R.id.tv_genres)
            val tvLiveStatus: TextView = view.findViewById(R.id.tv_live_status)
            val tvStripchatDetails: TextView = view.findViewById(R.id.tv_stripchat_details)
            val tvCustomTags: TextView = view.findViewById(R.id.tv_custom_tags)
            val tvNote: TextView = view.findViewById(R.id.tv_note)
            val cbSelect: android.widget.CheckBox = view.findViewById(R.id.cb_select)
            val llPlatformChips: LinearLayout = view.findViewById(R.id.ll_platform_chips)
            val llStripchatRecordings: LinearLayout = view.findViewById(R.id.ll_stripchat_recordings)
            val hsvChips: android.widget.HorizontalScrollView = view.findViewById(R.id.hsv_chips)
            val chipJable: TextView = view.findViewById(R.id.chip_jable)
            val chip7mmtv: TextView = view.findViewById(R.id.chip_7mmtv)
            val chipAvple: TextView = view.findViewById(R.id.chip_avple)
            val chipWhos: TextView = view.findViewById(R.id.chip_whos)
            val chipPigav: TextView = view.findViewById(R.id.chip_pigav)
            val chipAvToday: TextView = view.findViewById(R.id.chip_avtoday)
            val chipMissav: TextView = view.findViewById(R.id.chip_missav)
            val chipMissavUncensored: TextView = view.findViewById(R.id.chip_missav_uncensored)
            val chipMissavChinese: TextView = view.findViewById(R.id.chip_missav_chinese)
            val chipAvjoy: TextView = view.findViewById(R.id.chip_avjoy)
            val chipAvjoyChinese: TextView = view.findViewById(R.id.chip_avjoy_chinese)
            val chipJavhd: TextView = view.findViewById(R.id.chip_javhd)
            val chipJavhdUncensored: TextView = view.findViewById(R.id.chip_javhd_uncensored)
            val chipJavhdChinese: TextView = view.findViewById(R.id.chip_javhd_chinese)
            val btnRetryCrosssite: TextView = view.findViewById(R.id.btn_retry_crosssite)
            val btnLocalPlay: android.widget.Button = view.findViewById(R.id.btn_local_play)
            val btnEditFavorite: android.widget.Button = view.findViewById(R.id.btn_edit_favorite)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorite, parent, false)
            LanguageManager.translateViewTree(parent.context, view)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val fc2Code = fc2CodeFor(item)
            holder.tvTitle.text = item.title
            bindStripchatLiveStatus(holder, item)
            holder.btnEditFavorite.visibility = if (isTagEditMode) View.VISIBLE else View.GONE
            holder.btnEditFavorite.setOnClickListener { showEditFavoriteDialog(item) }
            val localFiles = localVideosFor(item)
            val isStripchatItem = isStripchatUrl(item.url)
            holder.btnLocalPlay.visibility =
                if (!isStripchatItem && localFiles.isNotEmpty()) View.VISIBLE else View.GONE
            holder.btnLocalPlay.text = if (localFiles.size > 1) {
                "▶ ${localFiles.size}"
            } else {
                LanguageManager.text(this@FavoritesActivity, "▶ 本地", "▶ Local")
            }
            holder.btnLocalPlay.setOnClickListener {
                openLocalVideo(localFiles)
            }

            holder.llStripchatRecordings.removeAllViews()
            if (isStripchatItem && localFiles.isNotEmpty()) {
                localFiles.sortedByDescending(::stripchatRecordingTimestamp).forEach { file ->
                    val recordingLink = TextView(holder.itemView.context).apply {
                        text = "▶ ${stripchatRecordingLabel(file)}"
                        textSize = 10f
                        setTextColor(Color.parseColor("#5E35B1"))
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, 5, 0, 5)
                        isClickable = true
                        isFocusable = true
                        background = android.util.TypedValue().let { typedValue ->
                            context.theme.resolveAttribute(
                                android.R.attr.selectableItemBackground,
                                typedValue,
                                true,
                            )
                            androidx.core.content.ContextCompat.getDrawable(context, typedValue.resourceId)
                        }
                        setOnClickListener { launchLocalVideo(file) }
                    }
                    holder.llStripchatRecordings.addView(recordingLink)
                }
                holder.llStripchatRecordings.visibility = View.VISIBLE
            } else {
                holder.llStripchatRecordings.visibility = View.GONE
            }

            // 防止 chips 水平滑動觸發 ItemTouchHelper 刪除手勢
            holder.hsvChips.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE ->
                        recyclerView.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        recyclerView.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            val domain = extractDomain(item.url)
            holder.tvUrl.text = LanguageManager.text(this@FavoritesActivity, "來自: $domain", "From: $domain")

            val customTags = visibleCustomTags(item)
            if (customTags.isNotEmpty()) {
                holder.tvCustomTags.text = customTags.joinToString("  ") { "#$it" }
                holder.tvCustomTags.visibility = View.VISIBLE
                holder.tvCustomTags.setOnClickListener {
                    val tags = customTags.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
                        .setTitle("篩選自訂類別")
                        .setItems(tags) { _, which ->
                            setFilter("custom", tags[which])
                            drawerLayout.closeDrawers()
                        }
                        .show()
                }
            } else {
                holder.tvCustomTags.visibility = View.GONE
                holder.tvCustomTags.setOnClickListener(null)
            }

            if (item.note.isNotBlank()) {
                holder.tvNote.text = "📝 ${item.note.trim().replace('\n', ' ')}"
                holder.tvNote.visibility = View.VISIBLE
            } else {
                holder.tvNote.visibility = View.GONE
            }

            val stripchatInfo = stripchatInfos[item.url]
            val doppioMinuteTimestamp = (stripchatThumbnailRefreshToken / 1000L / 60L) * 60L
            val doppioThumbnail = stripchatInfo?.takeIf {
                it.status == StripchatLiveStatus.LIVE && it.modelId > 0L
            }?.let { info ->
                "https://img.doppiocdn.org/thumbs/$doppioMinuteTimestamp/${info.modelId}"
            }
            val dynamicStripchatThumbnail = stripchatInfo?.let { info ->
                when (info.status) {
                    StripchatLiveStatus.LIVE -> doppioThumbnail
                        ?: info.previewUrlThumbBig.ifBlank { info.previewUrl }
                    StripchatLiveStatus.OFFLINE -> info.avatarUrlThumb
                    else -> ""
                }
            }?.takeIf { it.isNotBlank() }
            val thumbnailToLoad = dynamicStripchatThumbnail ?: item.thumbnailUrl

            holder.ivThumbnail.scaleType = if (isStripchatItem) {
                android.widget.ImageView.ScaleType.FIT_CENTER
            } else {
                android.widget.ImageView.ScaleType.CENTER_CROP
            }
            holder.ivThumbnailBackground.visibility =
                if (isStripchatItem && !thumbnailToLoad.isNullOrEmpty()) View.VISIBLE else View.GONE
            com.bumptech.glide.Glide.with(holder.itemView.context)
                .clear(holder.ivThumbnailBackground)

            if (!thumbnailToLoad.isNullOrEmpty()) {
                val glide = com.bumptech.glide.Glide.with(holder.itemView.context)
                val request = glide.load(thumbnailToLoad)
                    .placeholder(android.R.color.darker_gray)
                if (dynamicStripchatThumbnail != null && stripchatInfo != null) {
                    val version = if (stripchatInfo.status == StripchatLiveStatus.LIVE) {
                        "${stripchatInfo.snapshotTimestamp}:$stripchatThumbnailRefreshToken"
                    } else {
                        stripchatInfo.avatarUrlThumb
                    }
                    request.signature(com.bumptech.glide.signature.ObjectKey(version))
                    if (doppioThumbnail != null) {
                        val staticPreview = stripchatInfo.previewUrlThumbBig.ifBlank { stripchatInfo.previewUrl }
                        if (staticPreview.isNotBlank()) {
                            val staticFallback = glide.load(staticPreview)
                                .signature(com.bumptech.glide.signature.ObjectKey(stripchatInfo.snapshotTimestamp))
                            if (!item.thumbnailUrl.isNullOrEmpty()) {
                                staticFallback.error(glide.load(item.thumbnailUrl))
                            } else {
                                staticFallback.error(android.R.drawable.ic_menu_gallery)
                            }
                            request.error(staticFallback)
                        } else if (!item.thumbnailUrl.isNullOrEmpty()) {
                            request.error(glide.load(item.thumbnailUrl))
                        } else {
                            request.error(android.R.drawable.ic_menu_gallery)
                        }
                    } else if (!item.thumbnailUrl.isNullOrEmpty()) {
                        request.error(glide.load(item.thumbnailUrl))
                    } else {
                        request.error(android.R.drawable.ic_menu_gallery)
                    }
                } else {
                    request.error(android.R.drawable.ic_menu_gallery)
                }
                if (isStripchatItem) {
                    request.clone()
                        .centerCrop()
                        .into(holder.ivThumbnailBackground)
                }
                request.into(holder.ivThumbnail)
            } else {
                val iconRes = when {
                    item.url.contains("missav") -> android.R.drawable.ic_menu_camera
                    item.url.contains("jable") -> android.R.drawable.ic_menu_gallery
                    item.url.contains("rou.video") || item.url.contains("rouva") -> android.R.drawable.ic_menu_view
                    else -> android.R.drawable.ic_menu_gallery
                }
                holder.ivThumbnail.setImageResource(iconRes)
            }

            // 解析番號（優先 javCode，其次從 JavTrailers/JavDB URL 解析，最後從標題解析）
            val resolvedJavCode: String? = if (isStripchatUrl(item.url)) null else fc2Code
                ?: item.javCode?.takeIf { it.isNotEmpty() }
                ?: run {
                    val m = Regex("javtrailers\\.com/[^/]+/video/([a-z]+)(\\d+)", RegexOption.IGNORE_CASE)
                        .find(item.url)
                    if (m != null) {
                        val p = m.groupValues[1].uppercase()
                        val n = m.groupValues[2].trimStart('0').ifEmpty { "0" }
                        "$p-$n"
                    } else {
                        // JavDB search URL: ?q=DVEH-082
                        val q = Regex("[?&]q=([A-Za-z]+-\\d+)", RegexOption.IGNORE_CASE).find(item.url)
                        q?.groupValues?.get(1)?.uppercase()
                            // 從標題萃取番號（例：「IPX-123 美少女...」或「SSIS456 女優...」）
                            ?: Regex("\\b([A-Za-z]{2,6})-?(\\d{3,6})\\b").find(item.title)
                                ?.let { mr ->
                                    val p = mr.groupValues[1].uppercase()
                                    val n = mr.groupValues[2].trimStart('0').ifEmpty { "0" }
                                    "$p-$n"
                                }
                    }
                }

            // 點封面 → 開 gallery 檢視器（封面圖 + swiper 圖）
            val galleryAll = buildList<String> {
                if (!item.thumbnailUrl.isNullOrEmpty()) add(item.thumbnailUrl!!)
                addAll(item.galleryImages)
            }
            android.util.Log.e("GALLERY_DEBUG",
                "bind: title=${item.title} thumb=${item.thumbnailUrl} gallery=${item.galleryImages.size} code=$resolvedJavCode")
            if (item.galleryImages.isNotEmpty())
                android.util.Log.e("GALLERY_DEBUG", "gallery[0]=${item.galleryImages[0]}")
            when {
                isStripchatItem -> {
                    // Stripchat 封面代表主播入口，不套用番號作品的圖片檢視器。
                    // 交由卡片本身處理，選擇模式下仍可正常勾選。
                    holder.ivThumbnail.setOnClickListener {
                        holder.itemView.performClick()
                    }
                }
                item.galleryImages.isNotEmpty() -> {
                    // 已有 gallery：直接開圖庫
                    holder.ivThumbnail.setOnClickListener {
                        android.util.Log.e("GALLERY_DEBUG", "thumb clicked → showGalleryDialog imgs=${galleryAll.size}")
                        showGalleryDialog(holder.itemView.context, galleryAll, item.url,
                            javCode = resolvedJavCode, hasExtraGallery = true,
                            trailerUrl = item.trailerUrl)
                    }
                }
                resolvedJavCode != null && fc2Code == null -> {
                    // 無 gallery（只有封面或完全沒圖）但有番號 → 先開 dialog，再 in-dialog 抓取
                    holder.ivThumbnail.setOnClickListener {
                        android.util.Log.e("GALLERY_DEBUG", "thumb clicked → open dialog + auto-scrape $resolvedJavCode")
                        val autoScrapeCode = resolvedJavCode.takeIf {
                            !favoritesManager.hasGalleryLookupAttempted(item.url)
                        }
                        showGalleryDialog(holder.itemView.context, galleryAll, item.url,
                            javCode = resolvedJavCode, hasExtraGallery = false,
                            trailerUrl = item.trailerUrl, autoScrapeCode = autoScrapeCode)
                    }
                }
                galleryAll.isNotEmpty() -> {
                    // 有封面但沒法解析番號：開圖庫，讓 in-dialog 🔄 可手動輸入
                    holder.ivThumbnail.setOnClickListener {
                        showGalleryDialog(holder.itemView.context, galleryAll, item.url,
                            javCode = null, hasExtraGallery = false, trailerUrl = item.trailerUrl)
                    }
                }
                else -> {
                    android.util.Log.e("GALLERY_DEBUG", "thumb click = null (no thumb, no code)")
                    holder.ivThumbnail.setOnClickListener(null)
                }
            }

            if (item.isEnriched) {
                if (item.actors.isNotEmpty()) {
                    holder.tvActors.text = LanguageManager.text(
                        this@FavoritesActivity,
                        "演員: ${item.actors.joinToString("、")}",
                        "Cast: ${item.actors.joinToString(", ")}"
                    )
                    holder.tvActors.visibility = View.VISIBLE
                    holder.tvActors.setOnClickListener {
                        val names = item.actors.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
                            .setTitle("篩選演員")
                            .setItems(names) { _, which ->
                                setFilter("actor", names[which])
                                drawerLayout.closeDrawers()
                            }
                            .show()
                    }
                } else {
                    holder.tvActors.visibility = View.GONE
                    holder.tvActors.setOnClickListener(null)
                }

                val metaParts = mutableListOf<String>()
                if (!item.releaseDate.isNullOrEmpty()) metaParts.add(item.releaseDate)
                if (!item.rating.isNullOrEmpty()) metaParts.add("★ ${item.rating}")
                // 顯示 JavDB 來源，長按可校正
                val srcDisplay = item.javdbUrl
                    ?.removePrefix("https://")?.removePrefix("http://")
                    ?.let { "📍 $it" }
                if (srcDisplay != null) metaParts.add(srcDisplay)
                if (metaParts.isNotEmpty()) {
                    holder.tvMeta.text = metaParts.joinToString("  |  ")
                    holder.tvMeta.visibility = View.VISIBLE
                } else {
                    holder.tvMeta.visibility = View.GONE
                }
                // 長按 tvMeta → 校正 JavDB 來源 URL
                holder.tvMeta.setOnLongClickListener {
                    val ctx = holder.itemView.context
                    val et = android.widget.EditText(ctx).apply {
                        setText(item.javdbUrl ?: "https://javdb.com/v/")
                        hint = "https://javdb.com/v/xxxxx"
                        setSingleLine(true)
                        setSelection(text.length)
                    }
                    val pad = (resources.displayMetrics.density * 16).toInt()
                    val wrap = android.widget.FrameLayout(ctx).apply {
                        setPadding(pad, pad / 2, pad, 0)
                        addView(et)
                    }
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle("校正 JavDB 來源")
                        .setMessage("貼上正確的 JavDB 影片頁面 URL，重新補全資料：")
                        .setView(wrap)
                        .setPositiveButton("重新補全") { _, _ ->
                            val newUrl = et.text.toString().trim()
                            if (newUrl.contains("javdb.com")) {
                                val code = item.javCode ?: JavDbScraper.extractJavCode(item.title) ?: ""
                                holder.tvMeta.text = "⏳ 補全中..."
                                JavDbWebViewScraper(this@FavoritesActivity)
                                    .enrichFromUrl(code, newUrl) { detail ->
                                        runOnUiThread {
                                            if (detail != null) {
                                                favoritesManager.updateFavoriteDetail(item.url, detail)
                                                LocalBroadcastManager.getInstance(this@FavoritesActivity)
                                                    .sendBroadcast(Intent(ACTION_FAVORITE_ENRICHED))
                                            } else {
                                                android.widget.Toast.makeText(
                                                    this@FavoritesActivity,
                                                    "❌ 無法從該 URL 取得資料", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                            }
                        }
                        .setNeutralButton("在瀏覽器開啟") { _, _ ->
                            val url = item.javdbUrl ?: return@setNeutralButton
                            val broadcastIntent = Intent(MainActivity.ACTION_LOAD_URL).apply {
                                putExtra(MainActivity.EXTRA_URL, url)
                            }
                            LocalBroadcastManager.getInstance(this@FavoritesActivity)
                                .sendBroadcast(broadcastIntent)
                            finish()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
                if (fc2Code != null) holder.tvMeta.setOnLongClickListener(null)

                val genresForDisplay = mergedGenres(item)
                if (genresForDisplay.isNotEmpty()) {
                    holder.tvGenres.text = genresForDisplay.joinToString("  ") {
                        LanguageManager.genre(this@FavoritesActivity, it)
                    }
                    holder.tvGenres.visibility = View.VISIBLE
                    holder.tvGenres.setOnClickListener {
                        val genres = genresForDisplay.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
                            .setTitle("篩選類別")
                            .setItems(genres) { _, which ->
                                setFilter("genre", genres[which])
                                drawerLayout.closeDrawers()
                            }
                            .show()
                    }
                } else {
                    holder.tvGenres.visibility = View.GONE
                    holder.tvGenres.setOnClickListener(null)
                }
            } else {
                val enrichCode = if (isStripchatUrl(item.url) || fc2Code != null) null else
                    item.javCode ?: JavDbScraper.extractJavCode(item.title)
                holder.tvMeta.visibility = View.GONE
                val genresForDisplay = mergedGenres(item)
                if (genresForDisplay.isNotEmpty()) {
                    holder.tvGenres.text = genresForDisplay.joinToString("  ") {
                        LanguageManager.genre(this@FavoritesActivity, it)
                    }
                    holder.tvGenres.visibility = View.VISIBLE
                    holder.tvGenres.setOnClickListener {
                        val genres = genresForDisplay.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
                            .setTitle("篩選類別")
                            .setItems(genres) { _, which ->
                                setFilter("genre", genres[which])
                                drawerLayout.closeDrawers()
                            }
                            .show()
                    }
                } else {
                    holder.tvGenres.visibility = View.GONE
                    holder.tvGenres.setOnClickListener(null)
                }
                if (fc2Code != null) {
                    holder.tvActors.text = LanguageManager.text(
                        this@FavoritesActivity,
                        "📋 點此從 MISSAV 補全資料",
                        "📋 Fetch metadata from MISSAV"
                    )
                    holder.tvActors.visibility = View.VISIBLE
                    holder.tvActors.isClickable = true
                    holder.tvActors.setOnClickListener {
                        holder.tvActors.text = LanguageManager.text(
                            this@FavoritesActivity,
                            "⏳ 查詢 MISSAV 中...",
                            "⏳ Checking MISSAV..."
                        )
                        holder.tvActors.isClickable = false
                        enrichFc2Favorite(item, fc2Code)
                    }
                } else if (enrichCode == null) {
                    // 標題無番號，無法查詢，直接隱藏
                    holder.tvActors.visibility = View.GONE
                    holder.tvActors.setOnClickListener(null)
                } else {
                holder.tvActors.text = "📋 點此從 JavDB 補全資料"
                holder.tvActors.visibility = View.VISIBLE
                holder.tvActors.isClickable = true
                holder.tvActors.setOnClickListener {
                    holder.tvActors.text = "⏳ 查詢 JavDB 中..."
                    holder.tvActors.isClickable = false
                    JavDbWebViewScraper(this@FavoritesActivity).enrichFavorite(enrichCode) { detail ->
                        runOnUiThread {
                            if (detail != null) {
                                favoritesManager.updateFavoriteDetail(item.url, detail)
                                LocalBroadcastManager.getInstance(this@FavoritesActivity)
                                    .sendBroadcast(Intent(ACTION_FAVORITE_ENRICHED))
                            } else {
                                // 背景撈失敗（CF 未過或其他原因），開手動 WebView dialog
                                showJavDbManualWebViewDialog(item, enrichCode)
                            }
                        }
                    }
                }
                } // end enrichCode != null
            }

            // === 跨站平台 chips ===
            // 將書籤本身的 URL 也納入顯示（其平台 chip 作為「已選」狀態，不加點擊）
            val sourceKey = CrossSiteChecker.keyFromUrl(item.url)
            // 建立完整的「key → url」對應（包含書籤本身）
            val allPlatformUrls = mutableMapOf<String, String>()
            allPlatformUrls.putAll(item.relatedUrls)
            if (sourceKey != null) allPlatformUrls[sourceKey] = item.url

            fun bindChip(chip: TextView, key: String) {
                val url = allPlatformUrls[key]
                if (url != null) {
                    chip.visibility = View.VISIBLE
                    chip.alpha = 1f
                    chip.setOnClickListener {
                        val broadcastIntent = Intent(MainActivity.ACTION_LOAD_URL).apply {
                            putExtra(MainActivity.EXTRA_URL, url)
                        }
                        LocalBroadcastManager.getInstance(this@FavoritesActivity).sendBroadcast(broadcastIntent)
                        finish()
                    }
                } else {
                    chip.visibility = View.GONE
                    chip.setOnClickListener(null)
                }
            }

            bindChip(holder.chipJable, CrossSiteChecker.KEY_JABLE)
            bindChip(holder.chip7mmtv, CrossSiteChecker.KEY_7MMTV)
            bindChip(holder.chipAvple, CrossSiteChecker.KEY_AVPLE)
            bindChip(holder.chipWhos, CrossSiteChecker.KEY_WHOS)
            bindChip(holder.chipPigav, CrossSiteChecker.KEY_PIGAV)
            bindChip(holder.chipAvToday, CrossSiteChecker.KEY_AVTODAY)
            bindChip(holder.chipMissav, CrossSiteChecker.KEY_MISSAV)
            bindChip(holder.chipMissavUncensored, CrossSiteChecker.KEY_MISSAV_UNCENSORED)
            bindChip(holder.chipMissavChinese, CrossSiteChecker.KEY_MISSAV_CHINESE)
            bindChip(holder.chipAvjoy, CrossSiteChecker.KEY_AVJOY)
            bindChip(holder.chipAvjoyChinese, CrossSiteChecker.KEY_AVJOY_CHINESE)
            bindChip(holder.chipJavhd, CrossSiteChecker.KEY_JAVHD)
            bindChip(holder.chipJavhdUncensored, CrossSiteChecker.KEY_JAVHD_UNCENSORED)
            bindChip(holder.chipJavhdChinese, CrossSiteChecker.KEY_JAVHD_CHINESE)

            // 重試按鈕：已 enriched 但 relatedUrls 全空（查詢失敗 / 被擋）
            val hasCode = !isStripchatUrl(item.url) &&
                (item.javCode != null || JavDbScraper.extractJavCode(item.title) != null)
            val showRetry = item.isEnriched && hasCode
            if (showRetry) {
                holder.btnRetryCrosssite.visibility = View.VISIBLE
                holder.btnRetryCrosssite.text = "↺"
                holder.btnRetryCrosssite.isClickable = true
                holder.btnRetryCrosssite.setOnClickListener {
                    val code = item.javCode ?: JavDbScraper.extractJavCode(item.title) ?: return@setOnClickListener
                    holder.btnRetryCrosssite.text = "⋯"
                    holder.btnRetryCrosssite.isClickable = false
                    CrossSiteChecker.checkAll(this@FavoritesActivity, code, item.url) { found ->
                        if (found.isNotEmpty()) {
                            favoritesManager.updateRelatedUrls(item.url, found)
                            LocalBroadcastManager.getInstance(this@FavoritesActivity)
                                .sendBroadcast(Intent(ACTION_FAVORITE_ENRICHED))
                        } else {
                            // 仍無結果，恢復按鈕
                            holder.btnRetryCrosssite.text = "↺"
                            holder.btnRetryCrosssite.isClickable = true
                        }
                    }
                }
            } else {
                holder.btnRetryCrosssite.visibility = View.GONE
                holder.btnRetryCrosssite.setOnClickListener(null)
            }

            // chip 列：有 chip 或有重試按鈕都顯示
            if (isStripchatItem) {
                holder.hsvChips.visibility = View.GONE
                holder.btnRetryCrosssite.visibility = View.GONE
                holder.llPlatformChips.visibility =
                    if (localFiles.isNotEmpty()) View.VISIBLE else View.GONE
            } else {
                holder.hsvChips.visibility = View.VISIBLE
                holder.llPlatformChips.visibility =
                    if (allPlatformUrls.isNotEmpty() || showRetry) View.VISIBLE else View.GONE
            }

            // 選擇模式：顯示 checkbox、點擊切換勾選；一般模式：正常開啟
            val cardView = holder.itemView as? androidx.cardview.widget.CardView
            holder.cbSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = selectedUrls.contains(item.url)
            cardView?.setCardBackgroundColor(
                if (isSelectionMode && selectedUrls.contains(item.url))
                    android.graphics.Color.parseColor("#E3F2FD")
                else
                    android.graphics.Color.WHITE
            )
            holder.itemView.setOnClickListener {
                if (isSelectionMode) {
                    if (selectedUrls.contains(item.url)) {
                        selectedUrls.remove(item.url)
                        holder.cbSelect.isChecked = false
                        cardView?.setCardBackgroundColor(android.graphics.Color.WHITE)
                    } else {
                        selectedUrls.add(item.url)
                        holder.cbSelect.isChecked = true
                        cardView?.setCardBackgroundColor(android.graphics.Color.parseColor("#E3F2FD"))
                    }
                    updateSelectionBar()
                } else {
                    onItemClick(item)
                }
            }

            // (舊的單圖 PhotoView 已移除，改用 showGalleryDialog 統一處理)
        }

        /** 刪除指定位置，回傳被刪除的項目（供復原使用） */
        fun deleteAt(position: Int): FavoriteItem {
            val item = items[position]
            favoritesManager.removeFavorite(item.url)
            items.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, items.size)
            if (items.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            }
            allFavorites = favoritesManager.getFavorites()
            buildFilterDrawer()
            return item
        }

        override fun getItemCount() = items.size

        fun getAllUrls(): List<String> = items.map { it.url }

        fun updateList(newItems: List<FavoriteItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun notifyUrlChanged(url: String) {
            val index = items.indexOfFirst { it.url == url }
            if (index >= 0) notifyItemChanged(index)
        }

        fun refreshLiveThumbnails(firstVisible: Int, lastVisible: Int): Int {
            if (items.isEmpty()) return 0
            val start = firstVisible.coerceAtLeast(0)
            val end = lastVisible.coerceAtMost(items.lastIndex)
            if (start > end) return 0
            var refreshed = 0
            for (position in start..end) {
                val item = items[position]
                val info = stripchatInfos[item.url]
                if (info?.status == StripchatLiveStatus.LIVE &&
                    (info.modelId > 0L || info.previewUrlThumbBig.isNotBlank() || info.previewUrl.isNotBlank())) {
                    notifyItemChanged(position)
                    refreshed++
                }
            }
            return refreshed
        }

        private fun bindStripchatLiveStatus(holder: ViewHolder, item: FavoriteItem) {
            if (!isStripchatUrl(item.url)) {
                holder.tvLiveStatus.visibility = View.GONE
                holder.tvLiveStatus.setOnClickListener(null)
                holder.tvStripchatDetails.visibility = View.GONE
                return
            }
            val status = stripchatStatuses[item.url] ?: StripchatLiveStatus.CHECKING
            holder.tvLiveStatus.text = when (status) {
                StripchatLiveStatus.CHECKING -> LanguageManager.text(this@FavoritesActivity, "查詢中…", "CHECKING…")
                StripchatLiveStatus.LIVE -> LanguageManager.text(this@FavoritesActivity, "● LIVE中", "● LIVE")
                StripchatLiveStatus.OFFLINE -> LanguageManager.text(this@FavoritesActivity, "下播中", "OFFLINE")
                StripchatLiveStatus.UNKNOWN -> LanguageManager.text(this@FavoritesActivity, "狀態未知", "UNKNOWN")
            }
            val color = when (status) {
                StripchatLiveStatus.LIVE -> "#D32F2F"
                StripchatLiveStatus.OFFLINE -> "#757575"
                StripchatLiveStatus.CHECKING -> "#F57C00"
                StripchatLiveStatus.UNKNOWN -> "#9E9E9E"
            }
            holder.tvLiveStatus.background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.parseColor(color))
            }
            holder.tvLiveStatus.contentDescription = LanguageManager.text(
                this@FavoritesActivity,
                "Stripchat 狀態；點擊立即更新",
                "Stripchat status; tap to refresh"
            )
            holder.tvLiveStatus.setOnClickListener { forceRefreshStripchatStatus(item.url) }
            holder.tvLiveStatus.visibility = View.VISIBLE

            val info = stripchatInfos[item.url]
            if (info == null) {
                holder.tvStripchatDetails.visibility = View.GONE
            } else {
                val detailLines = mutableListOf<String>()
                val badges = mutableListOf<String>()
                stripchatRoomStatusLabel(info.roomStatus)?.let(badges::add)
                if (info.isHd) badges.add("HD")
                if (badges.isNotEmpty()) detailLines.add(badges.joinToString(" · "))

                val message = if (info.status == StripchatLiveStatus.LIVE) info.topic else info.offlineStatus
                message.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
                    ?.let { detailLines.add(it) }

                val footer = mutableListOf<String>()
                formatStripchatLanguages(info.languages).takeIf { it.isNotEmpty() }?.let(footer::add)
                formatStripchatSchedule(info)?.let(footer::add)
                footer.add(formatStripchatCheckedAt(info.checkedAt))
                detailLines.add(footer.joinToString(" · "))

                holder.tvStripchatDetails.text = detailLines.joinToString("\n")
                holder.tvStripchatDetails.visibility = View.VISIBLE
            }
        }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun sourceBadgeLabel(url: String): String = when {
        url.contains("pornhub", ignoreCase = true) -> "PH"
        url.contains("stripchat", ignoreCase = true) -> "SC"
        url.contains("xvideos", ignoreCase = true) -> "XV"
        url.contains("rou.video", ignoreCase = true) || url.contains("rouva", ignoreCase = true) -> "ROU"
        url.contains("missav", ignoreCase = true) -> "MISSAV"
        url.contains("jable", ignoreCase = true) -> "JABLE"
        url.contains("7mmtv", ignoreCase = true) || url.contains("7tv", ignoreCase = true) -> "7MMTV"
        url.contains("avple", ignoreCase = true) -> "AVPLE"
        url.contains("whos.tv", ignoreCase = true) -> "WHOS"
        url.contains("pigav", ignoreCase = true) -> "PIGAV"
        url.contains("avtoday", ignoreCase = true) -> "AVT"
        url.contains("avjoy", ignoreCase = true) -> "AVJOY"
        else -> "OPEN"
    }
    }

    // ── 共用：觸發 JavTrailers 重新抓取 Gallery ────────────────────────────────

    private fun triggerRescrapeGallery(
        bookmarkUrl: String,
        javCode: String,
        triggerView: android.view.View? = null,
        fallbackImages: List<String> = emptyList(),
        fallbackTrailerUrl: String? = null
    ) {
        JavTrailersScraper(this).scrape(javCode) { result ->
            runOnUiThread {
                if (result != null && result.galleryImages.isNotEmpty()) {
                    // 一次更新 gallery + trailerUrl
                    val list = favoritesManager.getFavorites().toMutableList()
                    val idx = list.indexOfFirst { it.url == bookmarkUrl }
                    if (idx >= 0) {
                        list[idx] = list[idx].copy(
                            thumbnailUrl = list[idx].thumbnailUrl.takeIf { !it.isNullOrEmpty() } ?: result.coverUrl,
                            galleryImages = result.galleryImages,
                            trailerUrl = result.trailerUrl.ifEmpty { null }
                        )
                        favoritesManager.saveFavoritesPublic(list)
                    }
                    android.widget.Toast.makeText(
                        this, "抓到 ${result.galleryImages.size} 張圖${if (result.trailerUrl.isNotEmpty()) " + 預告片" else ""}！",
                        android.widget.Toast.LENGTH_SHORT).show()
                    loadFavorites()
                } else {
                    android.widget.Toast.makeText(
                        this, "❌ JavTrailers 找不到 $javCode 的圖片", android.widget.Toast.LENGTH_SHORT).show()
                    // 抓取失敗：若有封面圖則直接開圖庫，不讓用戶卡住
                    if (fallbackImages.isNotEmpty()) {
                        showGalleryDialog(this, fallbackImages, bookmarkUrl,
                            javCode = javCode, hasExtraGallery = false,
                            trailerUrl = fallbackTrailerUrl)
                    }
                }
            }
        }
    }

    // ── JavDB 可見 WebView 查詢對話框 ─────────────────────────────────────────
    // ── Gallery 全螢幕檢視器 ───────────────────────────────────────────────────

    /** 從番號推導 JavTrailers HLS 預告片候選 URL（方案A fallback）
     *  有些番號用不補零 (XVSR-851 → xvsr851mmb.m3u8)
     *  有些補零         (WAAA-632 → waaa00632mmb.m3u8)
     *  兩個都試，失敗自動換下一個 */
    private fun javCodeToTrailerUrlCandidates(code: String): List<String> {
        val m = Regex("^([A-Za-z]+)-(\\d+)$").find(code.trim()) ?: return emptyList()
        val prefix = m.groupValues[1].lowercase()
        val numRaw = m.groupValues[2]
        val numPad = numRaw.padStart(5, '0')
        val c1 = prefix.take(1)
        val c3 = prefix.take(3)
        val base = "https://media.javtrailers.com/hlsvideo/freepv/$c1/$c3/$prefix$numPad"
        return listOf(
            "$base/${prefix}${numRaw}mmb.m3u8",   // 不補零（XVSR-851 style）
            "$base/${prefix}${numPad}mmb.m3u8"    // 補零（WAAA-632 style）
        ).distinct()  // 5 位以上數字時兩者相同，去重
    }

    // 保留舊名給 effectiveTrailerUrl 使用（取第一個候選）
    private fun javCodeToTrailerUrl(code: String): String? =
        javCodeToTrailerUrlCandidates(code).firstOrNull()

    private fun showGalleryDialog(
        context: android.content.Context,
        images: List<String>,
        bookmarkUrl: String,
        javCode: String? = null,
        hasExtraGallery: Boolean = false,
        trailerUrl: String? = null,
        autoScrapeCode: String? = null
    ) {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val dp = dm.density

        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        // ── 解析番號 / trailer URL ─────────────────────────────────────────────
        val resolvedCode: String? = javCode?.takeIf { it.isNotEmpty() }
            ?: autoScrapeCode?.takeIf { it.isNotEmpty() }
            ?: run {
                val m = Regex("javtrailers\\.com/[^/]+/video/([a-z]+)(\\d+)", RegexOption.IGNORE_CASE)
                    .find(bookmarkUrl)
                if (m != null) "${m.groupValues[1].uppercase()}-${m.groupValues[2].trimStart('0').ifEmpty { "0" }}"
                else null
            }
        val storedTrailerUrl: String? = trailerUrl?.takeIf { it.isNotEmpty() && !it.startsWith("blob:") }
        val effectiveTrailerUrl: String? = storedTrailerUrl ?: resolvedCode?.let { javCodeToTrailerUrl(it) }
        val hasTrailer = !storedTrailerUrl.isNullOrEmpty()
        val totalCount = if (hasTrailer) images.size + 1 else images.size

        // ── 可動態更新的狀態 ───────────────────────────────────────────────────
        var dynImages = images.toMutableList()
        var dynHasTrailer = hasTrailer
        var dynTotal = totalCount
        var dynEffTrailerUrl: String? = effectiveTrailerUrl

        android.util.Log.e("GALLERY_DEBUG",
            "showGalleryDialog: code=$resolvedCode imgs=${images.size} trailer=$effectiveTrailerUrl")

        // ── 尺寸 ───────────────────────────────────────────────────────────────
        val videoH   = (screenW * 9f / 16f).toInt()
        val thumbH   = (dp * 84).toInt()
        val statusBarH = try {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else 60
        } catch (e: Exception) { 60 }
        val btnTop = statusBarH + 24

        // ── VideoView ─────────────────────────────────────────────────────────
        val vv = android.widget.VideoView(context)

        // ── PhotoView（圖片顯示，初始隱藏）────────────────────────────────────
        val photoView = com.github.chrisbanes.photoview.PhotoView(context).apply {
            setAllowParentInterceptOnEdge(true)
            visibility = android.view.View.GONE
        }

        // ── Player Controls Bar（獨立區塊，不疊在影片上）─────────────────────
        fun makeCtrlTv(label: String) = android.widget.TextView(context).apply {
            text = label; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((dp*14).toInt(), (dp*10).toInt(), (dp*14).toInt(), (dp*10).toInt())
            gravity = android.view.Gravity.CENTER
        }
        val btnPlayPause = makeCtrlTv("⏸")
        val btnRewind    = makeCtrlTv("−10s")
        val btnFwd       = makeCtrlTv("+10s")
        val tvCurrTime = android.widget.TextView(context).apply {
            text = "0:00"; textSize = 11f; setTextColor(0xFFCCCCCC.toInt())
            setPadding((dp*4).toInt(), 0, (dp*4).toInt(), 0); minWidth = (dp*36).toInt()
        }
        val tvDuration = android.widget.TextView(context).apply {
            text = "0:00"; textSize = 11f; setTextColor(0xFFCCCCCC.toInt())
            setPadding((dp*4).toInt(), 0, (dp*4).toInt(), 0); minWidth = (dp*36).toInt()
            gravity = android.view.Gravity.END
        }
        val seekBar = android.widget.SeekBar(context)
        val controlRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(tvCurrTime)
            addView(btnRewind)
            addView(btnPlayPause, android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnFwd)
            addView(tvDuration)
        }
        // 獨立控制列（插在 mainDisplay 和 thumbRv 之間）
        val controlsBar = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            setPadding((dp*8).toInt(), (dp*4).toInt(), (dp*8).toInt(), (dp*4).toInt())
            addView(seekBar, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(controlRow, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = android.view.View.INVISIBLE   // 圖片模式：隱形但保留空間
        }

        // ── Loading overlay ───────────────────────────────────────────────────
        val tvLoading = android.widget.TextView(context).apply {
            text = "🔄 抓取中..."; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC000000.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding((dp*16).toInt(), (dp*12).toInt(), (dp*16).toInt(), (dp*12).toInt())
            visibility = android.view.View.GONE
        }

        // ── Main display (video + photo，無疊加控制列) ────────────────────────
        val mainDisplay = android.widget.FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(vv, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
            addView(photoView, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            addView(tvLoading, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        }

        // ── 頁碼 ──────────────────────────────────────────────────────────────
        val tvCounter = android.widget.TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 20f
            setPadding((dp*14).toInt(), (dp*10).toInt(), (dp*14).toInt(), (dp*10).toInt())
            setBackgroundColor(0xBB000000.toInt())
            text = if (hasTrailer) "▶ 預告片  1 / $totalCount"
                   else if (totalCount > 0) "1 / $totalCount" else ""
        }

        // ── Seek handler ──────────────────────────────────────────────────────
        val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun fmt(ms: Int): String { val s = ms / 1000; return "${s/60}:${(s%60).toString().padStart(2,'0')}" }

        val seekRunnable = object : Runnable {
            override fun run() {
                val dur = vv.duration.takeIf { it > 0 } ?: return
                val pos = vv.currentPosition
                seekBar.max = dur; seekBar.progress = pos; tvCurrTime.text = fmt(pos)
                seekHandler.postDelayed(this, 500L)
            }
        }

        // ── 切換到影片（顯示控制列）─────────────────────────────────────────
        fun switchToVideo() {
            photoView.visibility = android.view.View.GONE
            vv.visibility = android.view.View.VISIBLE
            controlsBar.visibility = android.view.View.VISIBLE
            if (!vv.isPlaying) vv.start()
            seekHandler.post(seekRunnable)
        }

        // ── 切換到圖片（控制列隱形但保留空間）──────────────────────────────
        fun switchToImage(imgIndex: Int) {
            vv.pause()
            seekHandler.removeCallbacks(seekRunnable)
            vv.visibility = android.view.View.GONE
            controlsBar.visibility = android.view.View.INVISIBLE
            photoView.visibility = android.view.View.VISIBLE
            photoView.setScale(1f, false)
            com.bumptech.glide.Glide.with(context).load(dynImages[imgIndex])
                .placeholder(android.R.color.black).error(android.R.drawable.ic_menu_gallery).into(photoView)
        }

        // ── Thumbnail strip adapter ───────────────────────────────────────────
        var selectedIndex = 0
        val thumbRv = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            setBackgroundColor(0xFF111111.toInt())
        }
        val thumbAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<
                androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class TH(
                val frame: android.widget.FrameLayout,
                val iv: android.widget.ImageView,
                val tvLabel: android.widget.TextView
            ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(frame)

            override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): TH {
                val iv = android.widget.ImageView(p.context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
                val tvLabel = android.widget.TextView(p.context).apply {
                    text = "影"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xBB000000.toInt())
                    setPadding((dp*4).toInt(), 2, (dp*4).toInt(), 2)
                    gravity = android.view.Gravity.CENTER
                    visibility = android.view.View.GONE
                }
                val frame = android.widget.FrameLayout(p.context).apply {
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(thumbH, thumbH)
                    setPadding(3, 3, 3, 3)
                    addView(iv)
                    addView(tvLabel, android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
                }
                return TH(frame, iv, tvLabel)
            }

            override fun getItemCount() = dynTotal
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, pos: Int) {
                val th = h as TH
                th.frame.setBackgroundColor(if (pos == selectedIndex) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
                if (dynHasTrailer && pos == 0) {
                    th.tvLabel.visibility = android.view.View.VISIBLE
                    if (dynImages.isNotEmpty())
                        com.bumptech.glide.Glide.with(context).load(dynImages[0]).centerCrop().into(th.iv)
                    else
                        th.iv.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    th.tvLabel.visibility = android.view.View.GONE
                    val imgPos = if (dynHasTrailer) pos - 1 else pos
                    com.bumptech.glide.Glide.with(context).load(dynImages[imgPos])
                        .centerCrop().placeholder(android.R.color.darker_gray).into(th.iv)
                }
                th.frame.setOnClickListener {
                    val prev = selectedIndex
                    selectedIndex = pos
                    notifyItemChanged(prev)
                    notifyItemChanged(pos)
                    if (dynHasTrailer && pos == 0) {
                        tvCounter.text = "▶ 預告片  1 / $dynTotal"
                        switchToVideo()
                    } else {
                        val imgPos = if (dynHasTrailer) pos - 1 else pos
                        tvCounter.text = "${pos + 1} / $dynTotal"
                        switchToImage(imgPos)
                    }
                }
            }
        }
        thumbRv.adapter = thumbAdapter

        btnPlayPause.setOnClickListener {
            if (vv.isPlaying) {
                vv.pause(); btnPlayPause.text = "▶"
                seekHandler.removeCallbacks(seekRunnable)
            } else {
                vv.start(); btnPlayPause.text = "⏸"
                seekHandler.post(seekRunnable)
            }
        }
        btnRewind.setOnClickListener { vv.seekTo(maxOf(0, vv.currentPosition - 10_000)) }
        btnFwd.setOnClickListener {
            val dur = vv.duration.takeIf { it > 0 } ?: return@setOnClickListener
            vv.seekTo(minOf(dur, vv.currentPosition + 10_000))
        }
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) tvCurrTime.text = fmt(p)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                seekHandler.removeCallbacks(seekRunnable)
            }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                vv.seekTo(sb.progress)
                if (vv.isPlaying) seekHandler.post(seekRunnable)
            }
        })

        // ── 載入影片 ──────────────────────────────────────────────────────────
        fun loadVideoFromUrl(url: String) {
            vv.setVideoURI(android.net.Uri.parse(url))
            vv.setOnPreparedListener { mp ->
                mp.isLooping = false
                val dur = vv.duration.takeIf { it > 0 } ?: 0
                tvDuration.text = fmt(dur); seekBar.max = dur.takeIf { it > 0 } ?: 100
                vv.start(); btnPlayPause.text = "⏸"
                controlsBar.visibility = android.view.View.VISIBLE
                seekHandler.post(seekRunnable)
            }
        }
        if (hasTrailer) {
            val candidates: List<String> = when {
                !effectiveTrailerUrl.isNullOrEmpty() -> listOf(effectiveTrailerUrl)
                resolvedCode != null -> javCodeToTrailerUrlCandidates(resolvedCode)
                else -> emptyList()
            }
            fun tryPlay(index: Int) {
                if (index >= candidates.size) return
                android.util.Log.d("GALLERY_DEBUG", "try trailer[$index]: ${candidates[index]}")
                vv.setOnErrorListener { _, what, extra ->
                    android.util.Log.w("GALLERY_DEBUG", "trailer error what=$what extra=$extra")
                    tryPlay(index + 1); true
                }
                loadVideoFromUrl(candidates[index])
            }
            tryPlay(0)
        } else if (dynImages.isNotEmpty()) {
            // 無預告片：直接顯示第一張圖，控制列隱形保留空間
            vv.visibility = android.view.View.GONE
            controlsBar.visibility = android.view.View.INVISIBLE
            photoView.visibility = android.view.View.VISIBLE
            com.bumptech.glide.Glide.with(context).load(dynImages[0]).into(photoView)
        }

        // ── in-dialog scrape & refresh ────────────────────────────────────────
        fun scrapeAndRefresh(code: String) {
            tvLoading.text = "🔄 抓取 $code..."; tvLoading.visibility = android.view.View.VISIBLE
            favoritesManager.markGalleryLookupAttempted(bookmarkUrl)
            JavTrailersScraper(this).scrape(code) { result ->
                runOnUiThread {
                    tvLoading.visibility = android.view.View.GONE
                    if (result != null && result.galleryImages.isNotEmpty()) {
                        // 更新書籤資料
                        val list = favoritesManager.getFavorites().toMutableList()
                        val idx = list.indexOfFirst { it.url == bookmarkUrl }
                        if (idx >= 0) {
                            list[idx] = list[idx].copy(
                                thumbnailUrl = list[idx].thumbnailUrl.takeIf { !it.isNullOrEmpty() } ?: result.coverUrl,
                                galleryImages = result.galleryImages,
                                trailerUrl = result.trailerUrl.ifEmpty { null }
                            )
                            favoritesManager.saveFavoritesPublic(list)
                        }
                        // 更新 dialog 狀態
                        dynImages.clear(); dynImages.addAll(result.galleryImages)
                        val newTrailerUrl = result.trailerUrl.takeIf { it.isNotEmpty() }
                        dynHasTrailer = newTrailerUrl != null
                        dynEffTrailerUrl = newTrailerUrl
                        dynTotal = if (dynHasTrailer) dynImages.size + 1 else dynImages.size
                        // 更新縮圖列
                        selectedIndex = 0
                        thumbAdapter.notifyDataSetChanged()
                        // 切換到第一個（預告片或圖片）
                        if (dynHasTrailer && dynEffTrailerUrl != null) {
                            tvCounter.text = "▶ 預告片  1 / $dynTotal"
                            vv.visibility = android.view.View.VISIBLE
                            photoView.visibility = android.view.View.GONE
                            loadVideoFromUrl(dynEffTrailerUrl!!)
                        } else if (dynImages.isNotEmpty()) {
                            tvCounter.text = "1 / $dynTotal"
                            switchToImage(0)
                        }
                        android.widget.Toast.makeText(
                            this, "抓到 ${dynImages.size} 張圖${if (dynHasTrailer) " + 預告片" else ""}！",
                            android.widget.Toast.LENGTH_SHORT).show()
                        loadFavorites()
                    } else {
                        android.widget.Toast.makeText(
                            this, "❌ JavTrailers 找不到 $code 的圖片", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── 右上角按鈕 ────────────────────────────────────────────────────────
        val gap = (dp * 8).toInt()
        fun makeBtn(label: String, bgColor: Int) = android.widget.TextView(context).apply {
            text = label; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((dp*14).toInt(), (dp*10).toInt(), (dp*14).toInt(), (dp*10).toInt())
            setBackgroundColor(bgColor); gravity = android.view.Gravity.CENTER
        }
        val btnBar = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val btnRescrape = makeBtn("🔄", 0xBB000000.toInt())
        btnRescrape.setOnClickListener {
            if (resolvedCode != null) {
                scrapeAndRefresh(resolvedCode)
            } else {
                val et = android.widget.EditText(context).apply {
                    hint = "例：CEAD-718"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                }
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("輸入番號").setView(et)
                    .setPositiveButton("抓取") { _, _ ->
                        val code = et.text.toString().trim().uppercase()
                        if (code.isNotEmpty()) scrapeAndRefresh(code)
                    }.setNegativeButton("取消", null).show()
            }
        }
        btnBar.addView(btnRescrape, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = gap })
        val btnClose = makeBtn("✕", 0xBB000000.toInt())
        btnClose.setOnClickListener { dialog.dismiss() }
        btnBar.addView(btnClose)

        // ── 組合 layout：主顯示 + 縮圖列，垂直置中 ───────────────────────────
        val contentLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(mainDisplay, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, videoH))
            addView(controlsBar, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(thumbRv, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, thumbH))
        }

        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(contentLayout, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
            addView(tvCounter, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START; topMargin = btnTop
            })
            addView(btnBar, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END; topMargin = btnTop
            })
        }

        dialog.setOnDismissListener {
            vv.stopPlayback()
            seekHandler.removeCallbacksAndMessages(null)
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()

        // 自動抓取（封面點入後 dialog 已開，背景開始抓）
        if (autoScrapeCode != null) scrapeAndRefresh(autoScrapeCode)
    }

    // 方案B：先載入 javdb.com 首頁暖機，通過 CF 後自動跳搜尋頁
    // 方案C：偵測 Verification Failed 時顯示引導按鈕讓用戶手動通過

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showJavDbManualWebViewDialog(item: FavoriteItem, javCode: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val dialog  = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        // ── WebView（儘量擬真 Chrome，提高 CF 通過率）──
        val webView = android.webkit.WebView(this)
        webView.settings.apply {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            // 行動版 Chrome UA（比桌面版更符合 Android WebView 的預期）
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240105.004) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.6261.119 Mobile Safari/537.36"
        }
        // WebChromeClient 讓 CF JS challenge 有完整執行環境
        webView.webChromeClient = android.webkit.WebChromeClient()
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            setCookie("https://javdb.com", "over18=1; path=/; domain=.javdb.com")
            flush()
        }

        val dp = resources.displayMetrics.density

        // ── 查詢狀態機變數（在 UI 元素前宣告，供 btnRetry lambda 引用）──
        var step        = "search"
        var detailHref  = ""
        val searchUrl   = "https://javdb.com/search?q=${java.net.URLEncoder.encode(javCode, "UTF-8")}&locale=zh"

        // ── 底部狀態列 ──
        val tvStatus = TextView(this).apply {
            text = "🔍 搜尋中..."
            textSize = 12f
            setPadding((dp * 12).toInt(), (dp * 6).toInt(), (dp * 6).toInt(), (dp * 6).toInt())
            setTextColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 方案C：Verification Failed 時顯示的重試按鈕
        val btnRetry = android.widget.Button(this).apply {
            text = "重新查詢"
            textSize = 12f
            visibility = View.GONE
            setPadding((dp * 12).toInt(), 0, (dp * 12).toInt(), 0)
            setOnClickListener {
                visibility = View.GONE
                step = "search"
                tvStatus.text = "🔍 重新搜尋..."
                webView.loadUrl(searchUrl)
            }
        }
        val btnClose = android.widget.Button(this).apply {
            text = "關閉"
            textSize = 12f
            setPadding((dp * 16).toInt(), 0, (dp * 16).toInt(), 0)
            setOnClickListener {
                webView.stopLoading(); webView.destroy(); dialog.dismiss()
            }
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            addView(tvStatus)
            addView(btnRetry)
            addView(btnClose)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView,   LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dialog.setContentView(container)
        dialog.window?.apply {
            val m = resources.displayMetrics
            setLayout((m.widthPixels * 0.93).toInt(), (m.heightPixels * 0.80).toInt())
        }

        fun evalAfterDelay(view: android.webkit.WebView, delayMs: Long, block: (android.webkit.WebView) -> Unit) {
            handler.postDelayed({ if (dialog.isShowing) block(view) }, delayMs)
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {

            // 在頁面 JS 執行前注入，掩蓋 WebView 特徵，提高 CF 通過率
            override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript("""
                    (function(){
                        try { Object.defineProperty(navigator,'webdriver',{get:()=>undefined}); } catch(e){}
                        try {
                            if (!window.chrome) {
                                window.chrome = {
                                    runtime: { onConnect:{addListener:function(){}}, onMessage:{addListener:function(){}} },
                                    loadTimes: function(){},
                                    csi: function(){}
                                };
                            }
                        } catch(e){}
                        try {
                            Object.defineProperty(navigator,'plugins',{get:function(){
                                return [{name:'Chrome PDF Plugin',filename:'internal-pdf-viewer',description:'Portable Document Format'},
                                        {name:'Chrome PDF Viewer',filename:'mhjfbmdgcfjbbpaeojofohoefgiehjai',description:''},
                                        {name:'Native Client',filename:'internal-nacl-plugin',description:''}];
                            }});
                        } catch(e){}
                        try {
                            Object.defineProperty(navigator,'languages',{get:()=>['zh-TW','zh','en-US','en']});
                        } catch(e){}
                    })();
                """.trimIndent(), null)
            }

            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                evalAfterDelay(view, 1800) { wv ->
                    // 同時抓 title + body 文字，body 用於偵測 "Verification failed" 字樣
                    val detectJs = """
                        (function(){
                            var t=document.title||'';
                            var b=(document.body&&document.body.innerText)||'';
                            return JSON.stringify({title:t, body:b.substring(0,400)});
                        })()
                    """.trimIndent()
                    wv.evaluateJavascript(detectJs) { raw ->
                        if (!dialog.isShowing) return@evaluateJavascript
                        var pageTitle = ""
                        var pageBody  = ""
                        try {
                            val jo = org.json.JSONObject(raw?.trim('"') ?: "{}")
                            pageTitle = jo.optString("title", "")
                            pageBody  = jo.optString("body",  "")
                        } catch (e: Exception) {
                            // JSON 解析失敗（body 含特殊字元等），以空值繼續讓狀態機正常推進
                        }
                        // CF 偵測：只看 title（title 是 CF 頁面特有的，正常 JavDB 頁面不會有這些）
                        // body 不拿來做 CF 判斷，因為正常頁面也可能含 "security verification" 等字
                        val isCF = pageTitle.contains("just a moment",        ignoreCase = true) ||
                                   pageTitle.contains("attention required",    ignoreCase = true) ||
                                   pageTitle.contains("checking your",        ignoreCase = true) ||
                                   pageTitle.contains("security verification", ignoreCase = true)
                        if (isCF) {
                            tvStatus.text = "⚠️ 請在上方完成人機驗證，完成後自動繼續"
                            return@evaluateJavascript
                        }

                        // 方案C：只有 body 明確出現「失敗」字樣才觸發（比 CF challenge 更嚴重）
                        val isVerifyFailed =
                            pageBody.contains("verification failed",                 ignoreCase = true) ||
                            pageBody.contains("please refresh the page and try again", ignoreCase = true) ||
                            pageBody.contains("sorry, you have been blocked",         ignoreCase = true) ||
                            pageBody.contains("error 1020",                           ignoreCase = true)
                        if (isVerifyFailed) {
                            tvStatus.text = "🚫 驗證失敗，請在上方手動瀏覽 JavDB 通過驗證，再按「重新查詢」"
                            btnRetry.visibility = View.VISIBLE
                            return@evaluateJavascript
                        }

                        when (step) {
                            // 方案B：首頁暖機成功，跳至搜尋頁
                            "search" -> {
                                tvStatus.text = "✅ 頁面載入，取得詳情連結..."
                                val esc = javCode.uppercase().replace("'", "\\'")
                                val js = """
                                    (function(){
                                        var items=document.querySelectorAll('.item');
                                        for(var i=0;i<items.length;i++){
                                            var s=items[i].querySelector('.video-title strong');
                                            if(s&&s.textContent.trim().toUpperCase()==='$esc'){
                                                var a=items[i].querySelector('a.box');
                                                if(a)return a.getAttribute('href');
                                            }
                                        }
                                        var first=document.querySelector('.item a.box');
                                        return first?first.getAttribute('href'):null;
                                    })()
                                """.trimIndent()
                                wv.evaluateJavascript(js) { href ->
                                    if (href == null || href == "null") {
                                        tvStatus.text = "❌ 找不到番號「$javCode」的資料"
                                        return@evaluateJavascript
                                    }
                                    detailHref = href.trim('"').replace("\\/", "/")
                                    step = "detail"
                                    tvStatus.text = "🔗 載入詳情頁..."
                                    wv.loadUrl("https://javdb.com$detailHref?locale=zh")
                                }
                            }
                            "detail" -> {
                                tvStatus.text = "📋 解析資料中..."
                                val js = """
                                    (function(){
                                        function fs(l){var els=document.querySelectorAll('strong');for(var i=0;i<els.length;i++){if(els[i].textContent.indexOf(l)!==-1)return els[i];}return null;}
                                        function mv(){for(var i=0;i<arguments.length;i++){var el=fs(arguments[i]);if(el&&el.nextElementSibling)return el.nextElementSibling.textContent.trim();}return '';}
                                        function ml(){for(var i=0;i<arguments.length;i++){var el=fs(arguments[i]);if(el&&el.nextElementSibling){var lk=el.nextElementSibling.querySelectorAll('a'),r=[];for(var k=0;k<lk.length;k++){var t=lk[k].textContent.trim();if(t)r.push(t);}if(r.length)return r;}}return [];}
                                        function ea(){for(var i=0;i<arguments.length;i++){var el=fs(arguments[i]);if(!el||!el.nextElementSibling)continue;var lk=el.nextElementSibling.querySelectorAll('a'),r=[];for(var k=0;k<lk.length;k++){var n=lk[k].textContent.trim();if(!n)continue;var sy=lk[k].nextElementSibling;var s=(sy&&sy.classList&&sy.classList.contains('symbol'))?sy.textContent.trim():'';r.push(n+s);}if(r.length)return r;}return [];}
                                        var te=document.querySelector('.title strong')||document.querySelector('.video-detail-header h2');
                                        return {title:te?te.textContent.trim():'',releaseDate:mv('\u65E5\u671F:','\u767C\u884C\u65E5\u671F:','Released Date:'),rating:mv('\u8A55\u5206:','Rating:').trim(),maker:mv('\u7247\u5546:','\u767C\u884C\u5546:','Maker:'),series:mv('\u7CFB\u5217:','Series:'),genres:ml('\u985E\u5225:','\u6A19\u7C64:','Tags:'),actors:ea('\u6F14\u54E1:','\u5973\u512A:','Actor(s):')};
                                    })()
                                """.trimIndent()
                                wv.evaluateJavascript(js) { result ->
                                    if (result == null || result == "null") {
                                        tvStatus.text = "❌ 資料解析失敗，請重試"; return@evaluateJavascript
                                    }
                                    try {
                                        val obj    = org.json.JSONObject(result)
                                        val genres = mutableListOf<String>().also { list -> obj.optJSONArray("genres")?.let { for (i in 0 until it.length()) list.add(it.getString(i)) } }
                                        val actors = mutableListOf<String>().also { list -> obj.optJSONArray("actors")?.let { for (i in 0 until it.length()) list.add(it.getString(i)) } }
                                        val detail = JavVideoDetail(
                                            code = javCode,
                                            title = obj.optString("title", ""),
                                            date = obj.optString("releaseDate", ""),
                                            duration = "",
                                            maker = obj.optString("maker", ""),
                                            series = obj.optString("series", ""),
                                            rating = obj.optString("rating", ""),
                                            genres = genres, actors = actors,
                                            detailUrl = "https://javdb.com$detailHref"
                                        )
                                        step = "done"
                                        favoritesManager.updateFavoriteDetail(item.url, detail)
                                        tvStatus.text = "✅ 查詢成功，即將關閉..."
                                        handler.postDelayed({
                                            webView.stopLoading(); webView.destroy(); dialog.dismiss()
                                            LocalBroadcastManager.getInstance(this@FavoritesActivity)
                                                .sendBroadcast(Intent(ACTION_FAVORITE_ENRICHED))
                                        }, 900)
                                    } catch (e: Exception) {
                                        tvStatus.text = "❌ 解析錯誤：${e.message}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener { if (step != "done") { webView.stopLoading(); webView.destroy() } }
        dialog.show()
        webView.loadUrl(searchUrl)
    }
}
