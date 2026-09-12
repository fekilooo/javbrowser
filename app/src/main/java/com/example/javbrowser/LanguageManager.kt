package com.example.javbrowser

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

object LanguageManager {
    const val AUTO = "auto"
    const val TRADITIONAL_CHINESE = "zh-TW"
    const val ENGLISH = "en"

    private const val PREFS_NAME = "app_language"
    private const val KEY_LANGUAGE = "language"

    fun preference(context: Context): String = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_LANGUAGE, AUTO) ?: AUTO

    fun setPreference(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun resolvedLanguage(context: Context): String = when (preference(context)) {
        TRADITIONAL_CHINESE -> TRADITIONAL_CHINESE
        ENGLISH -> ENGLISH
        else -> if (Locale.getDefault().language == "zh") TRADITIONAL_CHINESE else ENGLISH
    }

    fun isEnglish(context: Context): Boolean = resolvedLanguage(context) == ENGLISH

    fun wrapContext(context: Context): Context {
        val locale = if (isEnglish(context)) Locale.ENGLISH else Locale.TAIWAN
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    fun text(context: Context, traditionalChinese: String, english: String): String =
        if (isEnglish(context)) english else traditionalChinese

    fun translateViewTree(context: Context, root: View) {
        if (!isEnglish(context)) return
        if (root is TextView) {
            root.text = translateFixed(root.text.toString())
            root.hint = translateFixed(root.hint?.toString().orEmpty()).takeIf { it.isNotEmpty() }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) translateViewTree(context, root.getChildAt(index))
        }
    }

    fun translateFixed(value: String): String = fixedEnglish[value] ?: value

    fun genre(context: Context, value: String): String =
        if (isEnglish(context)) genreEnglish[value] ?: value else value

    fun downloadMessage(context: Context, value: String): String {
        if (!isEnglish(context)) return value
        val exact = mapOf(
            "等待下載" to "Waiting",
            "準備下載" to "Preparing download",
            "已加入下載佇列" to "Added to download queue",
            "正在取消下載..." to "Canceling download...",
            "下載完成" to "Download complete",
            "下載已取消" to "Download canceled",
            "本地影片" to "Local video",
            "檔案已移動或無法存取" to "File moved or unavailable; import it again",
            "並發受限，切換單線下載" to "Parallel limit reached; switching to a single connection",
            "正在合併影片" to "Merging video",
            "正在封裝 MP4" to "Packaging MP4",
            "MP4 封裝失敗，保留 TS" to "MP4 packaging failed; keeping TS",
            "下載影片中" to "Downloading video",
            "開始下載" to "Download started",
            "等待重試" to "Waiting to retry"
        )
        exact[value]?.let { return it }
        return value
            .replace("下載完成：", "Download complete: ")
            .replace("下載失敗：", "Download failed: ")
            .replace("單線下載分段", "Downloading sequential segments")
            .replace("下載分段", "Downloading segments")
            .replace("正在封裝 MP4", "Packaging MP4")
            .replace("接續分段", "Resuming segments")
            .replace("連線中斷，正在重試", "Connection interrupted, retrying")
            .replace("已有其他影片正在下載", "Another video is already downloading")
    }

    fun storageLocation(context: Context, value: String): String {
        if (!isEnglish(context)) return value
        return value
            .replace("系統下載/JAV Browser", "Downloads/JAV Browser")
            .replace("自訂：", "Custom: ")
            .replace("匯入影片", "Imported video")
            .replace("媒體庫：", "Media library: ")
            .replace("影片媒體庫", "Video library")
            .replace("既有下載檔案", "Existing download")
    }

    private val genreEnglish = mapOf(
        "主題" to "Themes", "角色" to "Roles", "服裝" to "Clothing", "體型" to "Body Type",
        "行爲" to "Acts", "玩法" to "Play Style", "類別" to "Categories", "其他" to "Other",
        "淫亂真實" to "Explicit Documentary", "出軌" to "Infidelity", "強姦" to "Rape",
        "亂倫" to "Incest", "溫泉" to "Hot Spring", "女同性戀" to "Lesbian", "企畫" to "Special Project",
        "戀腿癖" to "Leg Fetish", "獵豔" to "Pickup", "偷窺" to "Voyeur", "洗澡" to "Bathing",
        "其他戀物癖" to "Other Fetish", "處女" to "Virgin", "性愛" to "Sex", "學校作品" to "School",
        "妄想" to "Fantasy", "M男" to "Male Submissive", "跳舞" to "Dancing", "戀物癖" to "Fetish",
        "戀乳癖" to "Breast Fetish", "惡作劇" to "Prank", "運動" to "Sports", "倒追" to "Reverse Romance",
        "女同接吻" to "Lesbian Kissing", "美容院" to "Beauty Salon", "奴隸" to "Slave",
        "白天出軌" to "Daytime Affair", "流汗" to "Sweating", "性騷擾" to "Sexual Harassment",
        "情侶" to "Couple", "爛醉如泥的" to "Drunk", "魔鬼系" to "Demonic", "處男" to "Male Virgin",
        "殘忍畫面" to "Cruel Scenes", "性感的" to "Sexy", "曬黑" to "Tanned", "雙性人" to "Intersex",
        "全裸" to "Full Nudity", "正太控" to "Shota", "觸手" to "Tentacles", "正常" to "Normal",
        "奇異的" to "Bizarre", "蠻橫嬌羞" to "Tsundere", "性轉換·女體化" to "Gender Swap",
        "男同性戀" to "Gay", "韓國" to "Korean", "形象俱樂部" to "Image Club", "友誼" to "Friendship",
        "亞洲" to "Asian", "暗黑系" to "Dark", "天賦" to "Natural Talent", "被外國人幹" to "Interracial",
        "刺青紋身" to "Tattoo", "黑白配" to "Interracial", "絕頂高潮" to "Intense Orgasm",
        "純欲" to "Pure Desire", "經歷告白" to "Experience Confession", "濕身" to "Wet Clothing",
        "高中女生" to "High School Girl", "美少女" to "Beautiful Girl", "已婚婦女" to "Married Woman",
        "藝人" to "Celebrity", "姐姐" to "Older Sister", "各種職業" to "Various Professions", "蕩婦" to "Slut",
        "母親" to "Mother", "辣妹" to "Gyaru", "妓女" to "Prostitute", "新娘，年輕妻子" to "Bride / Young Wife",
        "女教師" to "Female Teacher", "白人" to "White", "婆婆" to "Mother-in-law", "女大學生" to "College Student",
        "偶像" to "Idol", "明星臉" to "Celebrity Lookalike", "大小姐" to "Young Lady", "秘書" to "Secretary",
        "護士" to "Nurse", "角色扮演者" to "Cosplayer", "賽車女郎" to "Race Queen", "家教" to "Tutor",
        "黑人演員" to "Black Performer", "妹妹" to "Younger Sister", "寡婦" to "Widow", "女醫生" to "Female Doctor",
        "老闆娘，女主人" to "Proprietress", "女主播" to "Female Announcer", "其他學生" to "Other Student",
        "模特兒" to "Model", "格鬥家" to "Fighter", "展場女孩" to "Booth Model", "禮儀小姐" to "Companion",
        "女檢察官" to "Female Prosecutor", "講師" to "Lecturer", "服務生" to "Waitress", "伴侶" to "Partner",
        "車掌小姐" to "Train Attendant", "女兒" to "Daughter", "年輕女孩" to "Young Girl", "公主" to "Princess",
        "童年朋友" to "Childhood Friend", "飛特族" to "Freeter", "亞洲女演員" to "Asian Actress",
        "痴漢" to "Molester", "御宅族" to "Otaku", "老太婆" to "Elderly Woman", "老年男性" to "Elderly Man",
        "拉拉隊" to "Cheerleader", "媽媽的朋友" to "Mother's Friend", "養女" to "Adopted Daughter", "女王" to "Queen",
        "眼鏡" to "Glasses", "角色扮演" to "Cosplay", "內衣" to "Lingerie", "制服" to "Uniform",
        "水手服" to "Sailor Uniform", "泳裝" to "Swimsuit", "和服，喪服" to "Kimono / Mourning Dress",
        "連褲襪" to "Pantyhose", "女傭" to "Maid", "運動短褲" to "Gym Shorts", "女戰士" to "Female Warrior",
        "校服" to "School Uniform", "制服外套" to "Blazer", "裸體圍裙" to "Naked Apron", "女忍者" to "Kunoichi",
        "身體意識" to "Body-conscious", "OL" to "Office Lady", "貓耳女" to "Cat Ears", "短裙" to "Short Skirt",
        "學校泳裝" to "School Swimsuit", "迷你裙" to "Mini Skirt", "浴衣" to "Yukata", "猥褻穿著" to "Lewd Outfit",
        "緊身衣" to "Leotard", "娃娃" to "Doll", "蘿莉角色扮演" to "Lolita Cosplay", "女裝人妖" to "Cross-dressing",
        "絲襪、過膝襪" to "Stockings / Knee Socks", "泡泡襪" to "Loose Socks", "空中小姐" to "Flight Attendant",
        "旗袍" to "Cheongsam", "兔女郎" to "Bunny Girl", "女祭司" to "Priestess", "動畫人物" to "Anime Character",
        "迷你裙警察" to "Mini-skirt Police", "修女" to "Nun", "COSPLAY服飾" to "Cosplay Costume",
        "高跟鞋" to "High Heels", "靴子" to "Boots",
        "熟女" to "Mature Woman", "巨乳" to "Big Breasts", "蘿莉塔" to "Lolita", "無毛" to "Hairless",
        "美臀" to "Beautiful Butt", "苗條" to "Slim", "美乳" to "Beautiful Breasts", "巨大陰莖" to "Huge Penis",
        "胖女人" to "BBW", "平胸" to "Small Breasts", "素人" to "Amateur", "高挑" to "Tall",
        "孕婦" to "Pregnant", "大屁股" to "Big Butt", "瘦小身型" to "Petite", "變性者" to "Transgender",
        "肌肉" to "Muscular", "超乳" to "Huge Breasts", "美腳" to "Beautiful Legs", "多毛" to "Hairy",
        "乳交" to "Paizuri", "中出" to "Creampie", "多P" to "Group Sex", "69" to "69",
        "淫語" to "Dirty Talk", "女上位" to "Cowgirl", "自慰" to "Masturbation", "顏射" to "Facial",
        "潮吹" to "Squirting", "口交" to "Oral Sex", "舔陰" to "Cunnilingus", "肛門・肛交" to "Anal Sex",
        "手指插入" to "Fingering", "手淫" to "Handjob", "深喉" to "Deep Throat", "放尿" to "Urination",
        "足交" to "Footjob", "按摩" to "Massage", "吞精" to "Swallowing", "母乳" to "Lactation",
        "濫交" to "Promiscuity", "接吻" to "Kissing", "拳交" to "Fisting", "飲尿" to "Drinking Urine",
        "騎乗位" to "Riding Position", "排便" to "Defecation", "食糞" to "Scat", "剃毛" to "Shaving",
        "二穴同入" to "Double Penetration", "兩女一男" to "Two Women One Man", "兩男兩女" to "Two Men Two Women",
        "兩男一女" to "Two Men One Woman", "打屁股" to "Spanking", "約會" to "Dating", "不穿內褲" to "No Panties",
        "不穿胸罩" to "No Bra", "後入" to "Doggy Style", "瑜伽·健身" to "Yoga / Fitness",
        "白眼失神" to "Ahegao", "搔癢" to "Tickling",
        "凌辱" to "Humiliation", "捆綁" to "Bondage", "緊縛" to "Shibari", "輪姦" to "Gangbang",
        "玩具" to "Toys", "SM" to "SM", "戶外" to "Outdoor", "乳液" to "Lotion", "羞恥" to "Shame",
        "女優按摩棒" to "Vibrator", "拘束" to "Restraint", "調教" to "Training", "立即口交" to "Immediate Oral",
        "跳蛋" to "Egg Vibrator", "監禁" to "Confinement", "按摩棒" to "Vibrator", "插入異物" to "Object Insertion",
        "灌腸" to "Enema", "藥物" to "Drugs", "露出" to "Exposure", "汽車性愛" to "Car Sex",
        "催眠" to "Hypnosis", "鴨嘴" to "Speculum", "糞便" to "Scat", "脫衣" to "Undressing",
        "子宮頸" to "Cervix", "導尿" to "Catheter", "蒙面・面罩" to "Mask", "唾液敷面" to "Saliva Facial",
        "乳釘、穿孔、乳環" to "Nipple Piercing", "口球" to "Gag", "輔助自慰" to "Assisted Masturbation",
        "夫妻交換" to "Swinging", "假陽具" to "Dildo", "鼻鉤" to "Nose Hook", "蠟燭" to "Wax Play",
        "站立後入" to "Standing Doggy",
        "單體作品" to "Solo Title", "首次亮相" to "Debut", "故事集" to "Omnibus", "經典" to "Classic",
        "戀愛" to "Romance", "VR" to "VR", "感謝祭" to "Fan Appreciation", "給女性觀眾" to "For Women",
        "無碼流出" to "Uncensored Leak", "4K" to "4K", "無碼破解" to "Uncensored", "綜藝" to "Variety",
        "精選綜合" to "Compilation", "國外進口" to "Imported", "4小時以上作品" to "4+ Hours",
        "戲劇" to "Drama", "成人電影" to "Adult Movie", "介紹影片" to "Introduction Video",
        "中文字幕" to "Chinese Subtitles"
    )

    private val fixedEnglish = mapOf(
        "隱私設定" to "Settings",
        "跟隨手機語言" to "Follow device language",
        "繁體中文" to "Traditional Chinese",
        "應用鎖定" to "App Lock",
        "啟用生物識別鎖定" to "Enable biometric lock",
        "設定 PIN 碼 (備用)" to "Set backup PIN",
        "APP 後台關閉或超過 1 小時後需重新驗證" to "Authentication is required after the app closes or remains locked for one hour.",
        "🔒 防截圖 / 防投屏" to "🔒 Block screenshots / casting",
        "關閉後可截圖、螢幕錄影及遠端投屏。重新開啟 APP 後生效。" to "When disabled, screenshots, recording and casting are allowed after reopening the app.",
        "播放器控制" to "Player Controls",
        "永遠使用內建播放器" to "Always use internal player",
        "開啟後，所有解析出的影片網址都直接使用內建播放器，不再呼叫外部播放器或本機 Proxy。" to "Open every extracted video URL in the internal player instead of an external player or local proxy.",
        "快退 / 快進按鈕秒數" to "Seek button intervals",
        "短距離 20 秒" to "Short interval: 20 seconds",
        "長距離 60 秒" to "Long interval: 60 seconds",
        "儲存快退 / 快進按鈕" to "Save seek intervals",
        "播放速度選項" to "Playback speed options",
        "儲存播放速度選項" to "Save speed options",
        "倍速請用逗號分隔，可設定 0.25x–16x、最多 12 個；1x 會自動保留。" to "Separate speeds with commas. Range: 0.25x–16x, up to 12 options; 1x is always included.",
        "雙擊左/右側快退快進秒數" to "Double-tap seek interval",
        "儲存" to "Save",
        "可設定 5-300 秒。雙擊左側倒退，雙擊右側前進；中間區域保留給顯示/隱藏控制列。" to "Range: 5–300 seconds. Double-tap left to rewind or right to advance; tap the center to show controls.",
        "應用外觀" to "App Appearance",
        "🎬 JAV Browser（預設）" to "🎬 JAV Browser (Default)",
        "🔢 Calculator（計算機）" to "🔢 Calculator",
        "📝 Notes（記事本）" to "📝 Notes",
        "📁 File Manager（檔案管理）" to "📁 File Manager",
        "廣告過濾規則" to "Ad-blocking Rules",
        "雲端規則網址" to "Cloud rules URL",
        "輸入 GitHub 規則文件網址" to "Enter the GitHub rules file URL",
        "從雲端更新規則" to "Update rules from cloud",
        "載入中..." to "Loading...",
        "導出規則" to "Export rules",
        "導入規則" to "Import rules",
        "書籤備份" to "Bookmark Backup",
        "📥 匯入書籤" to "📥 Import bookmarks",
        "📤 匯出書籤" to "📤 Export bookmarks",
        "書籤自動分類（JavDB）" to "Bookmark Auto-categorization (JavDB)",
        "貼上你的 Scrape.do Token" to "Paste your Scrape.do token",
        "儲存 Token" to "Save token",
        "查詢方式" to "Lookup method",
        "Scrape.do API（付費，需 Token）" to "Scrape.do API (paid, token required)",
        "WebView（免費，較慢）" to "WebView (free, slower)",
        "加入書籤時，APP 會自動用番號查詢 JavDB，填入女優、片商、類別等標籤。每人請自行申請 Token 以避免額度共用。" to "When adding a bookmark, the app looks up its code on JavDB and fills actors, maker and genre tags.",
        "返回" to "Back",
        "← 返回" to "← Back",
        "下載管理" to "Downloads",
        "儲存位置" to "Storage Location",
        "選擇資料夾" to "Choose Folder",
        "恢復預設" to "Restore Default",
        "重新掃描" to "Rescan",
        "尚無下載或本地影片" to "No downloads or local videos",
        "播放" to "Play",
        "取消" to "Cancel",
        "重試" to "Retry",
        "刪除" to "Delete",
        "影片封面" to "Video cover",
        "開源@fekilooo/javbrowser" to "Open source @fekilooo/javbrowser",
        "▶ 本地播放" to "▶ Play Local",
        "加入書籤" to "Add Bookmark",
        "搜尋收藏..." to "Search bookmarks...",
        "快速篩選" to "Quick Filters",
        "✕ 清除條件" to "✕ Clear Filters",
        "🏷️ 自訂標籤" to "🏷️ Custom Tags",
        "📅 加入日期" to "📅 Date Added",
        "🗓 上市年份" to "🗓 Release Year",
        "👩 女優" to "👩 Actresses",
        "👨 男優" to "👨 Actors",
        "⭐ 評分" to "⭐ Rating",
        "👥 評價人數" to "👥 Rating Count",
        "🏷 類別" to "🏷 Genres",
        "搜尋女優..." to "Search actresses...",
        "搜尋男優..." to "Search actors...",
        "全選" to "Select All",
        "🗑️ 刪除(0)" to "🗑️ Delete (0)",
        "新着內容" to "New Releases",
        "準備中..." to "Preparing...",
        "📅 近3日新着" to "📅 Last 3 Days",
        "統一跨站搜尋" to "Unified cross-site search",
        "複製網址" to "Copy URL",
        "複製目前網頁網址" to "Copy current page URL",
        "複製診斷" to "Copy diagnostics",
        "已複製診斷資訊（不含搜尋內容）" to "Diagnostics copied (query excluded)",
        "繼續未完成站點" to "Resume unfinished sites",
        "重新搜尋" to "Search again",
        "搜尋狀態已清除，請重新搜尋" to "Search state was cleared. Search again.",
        "整體搜尋期限已到" to "Overall search deadline reached",
        "搜尋工作失敗" to "Search failed",
        "網站連線失敗" to "Site connection failed",
        "更多" to "More",
        "停止" to "Stop",
        "輸入番號或關鍵字" to "Enter a code or keyword",
        "搜尋" to "Search",
        "全選網站" to "Select all sites",
        "清除網站" to "Clear sites",
        "全部來源" to "All sources",
        "輸入內容後按搜尋" to "Enter a query and press Search",
        "搜尋結果封面" to "Search result cover",
        "開啟" to "Open",
        "精確匹配" to "Exact match",
        "請輸入番號或關鍵字" to "Enter a code or keyword",
        "至少選擇一個網站" to "Select at least one site",
        "沒有符合的結果" to "No matching results",
        "已停止" to "Stopped",
        "網站要求驗證或拒絕目前連線" to "The site requires verification or rejected this connection",
        "需要在原網站完成驗證後再重試" to "Complete verification on the original site, then retry",
        "網站要求登入後才能搜尋" to "Sign in to the site before searching",
        "開啟網站" to "Open site",
        "網站暫時限制查詢，請稍後重試" to "The site is rate limiting searches; try again later",
        "網站回應逾時" to "The site timed out",
        "已載入頁面但無法辨識結果列表" to "The page loaded, but its result list could not be identified",
        "結果網址無效" to "Invalid result URL",
        "找不到 APP 主頁入口" to "The app home entry could not be found",
        "無法開啟結果頁" to "The result page could not be opened",
        "⚠️ 更換圖標後，舊圖標會從桌面消失，新圖標會出現\n⚠️ 部分啟動器可能需要重啟才能看到變化" to "⚠️ The old launcher icon disappears after switching.\n⚠️ Some launchers may require a restart.",
        "App Locked" to "App Locked",
        "Use Biometric" to "Use Biometric"
    )
}

open class LocalizedActivity : AppCompatActivity() {
    private var activityLanguage = ""

    override fun attachBaseContext(newBase: Context) {
        activityLanguage = LanguageManager.resolvedLanguage(newBase)
        super.attachBaseContext(LanguageManager.wrapContext(newBase))
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        LanguageManager.translateViewTree(this, window.decorView)
    }

    override fun onResume() {
        super.onResume()
        val current = LanguageManager.resolvedLanguage(this)
        if (activityLanguage.isNotEmpty() && current != activityLanguage) recreate()
    }
}
