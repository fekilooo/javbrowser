package com.example.javbrowser

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import okio.ByteString.Companion.decodeBase64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independently implemented from the documented /js protocol; no upstream account credentials. */
object SevenSourceProtocol {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    data class Source(val id: String, val name: String, val domain: String, val categories: List<Pair<String, String>>) {
        val origin get() = "https://$domain"
        fun detailLink(videoId: String) = "$origin/?javbrowser_video=${validId(videoId)}"
    }
    data class Video(val source: String, val id: String, val title: String, val cover: String?,
        val duration: String, val description: String, val category: String, val date: String) {
        val stableId get() = "$source:$id"
        val pageUrl get() = SevenSourceProtocol.source(source).detailLink(id)
        fun searchItem(rank: Int = 0) = SearchItem(stableId, source, title, pageUrl, pageUrl, cover,
            matchType = SearchMatchType.KEYWORD, sourceRank = rank)
    }
    private val common = listOf("c1" to "麻豆影片", "c2" to "91製片廠",
        "c3" to "玩偶姐姐", "c4" to "小鳥醬", "c5" to "糖心 Vlog", "c6" to "天美傳媒",
        "c7" to "蜜桃傳媒", "c8" to "皇家華人", "c9" to "星空傳媒")
    val sources = listOf(
        Source("xchina", "XChina", "xchina.tube", listOf("recent" to "最新影片", "hot" to "目前最熱", "week_hot" to "週榜",
            "month_hot" to "月榜", "high" to "站長推薦", "5mins" to "5 分鐘以上", "10mins" to "10 分鐘以上", "c0" to "國產影片") + common +
            listOf("japan" to "日本 AV", "av" to "高清 AV", "uncensored" to "無碼流出", "west" to "歐美")),
        Source("theporny", "Theporny", "theporny.com", listOf("recent" to "最新", "c0" to "國產傳媒", "japan" to "日本高清", "swag" to "台灣 Swag") + common + listOf("ca" to "精東影業")))

    fun categoryLabel(category: Pair<String, String>, english: Boolean): String {
        if (!english) return category.second
        return mapOf("recent" to "Latest", "hot" to "Trending", "week_hot" to "Weekly", "month_hot" to "Monthly",
            "high" to "Recommended", "5mins" to "5+ minutes", "10mins" to "10+ minutes", "c0" to "Chinese productions",
            "japan" to "Japanese", "av" to "HD videos", "uncensored" to "Uncensored", "west" to "Western",
            "swag" to "Taiwan Swag", "c1" to "Madou", "c2" to "91 Studio", "c3" to "HongKongDoll", "c4" to "Xiaoniaojiang",
            "c5" to "Tangxin Vlog", "c6" to "Tianmei", "c7" to "Mitao", "c8" to "Royal Chinese", "c9" to "Xingkong",
            "ca" to "Jingdong")[category.first] ?: category.second
    }
    val hosts = listOf("v2.cdn199.com", "v2.kekecdn.net", "v2.luchu.org", "v2.madou.ws",
        "v2.papapa.biz", "v2.xiaoshuo.info", "v2.xiaoshuo.la", "v2.tianmtv.com")
    fun source(id: String) = sources.firstOrNull { it.id == id } ?: throw IOException("未知播放源 / Unknown source")
    fun validId(id: String): String = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
        ?: throw IOException("影片 ID 無效 / Invalid video ID")

    /** This query is an app navigation marker, not a claim about the website's route layout. */
    fun parseLink(url: String): Pair<String, String>? = runCatching {
        val uri = URI(url)
        if (uri.scheme !in listOf("http", "https") || uri.userInfo != null) return null
        val source = sources.firstOrNull { uri.host.equals(it.domain, true) } ?: return null
        val value = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("javbrowser_video=") }
            ?.substringAfter('=') ?: return null
        source.id to validId(value)
    }.getOrNull()

    fun catalogSource(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme !in listOf("https", "http") || uri.userInfo != null ||
            uri.path !in listOf("", "/") || !uri.rawQuery.isNullOrBlank()) return null
        sources.firstOrNull { uri.host.equals(it.domain, true) }?.id
    }.getOrNull()

    fun body(source: Source, path: String, extra: JSONObject = JSONObject()): JSONObject {
        val result = JSONObject().put("app", source.domain).put("isStandalone", false)
            .put("theLink", if (source.id == "theporny") "https://rs.pppsuccess.com" else "novaluenull")
            .put("uuid", "novalue").put("url", path).put("version", "260901")
        if (source.id == "theporny") result.put("deviceInfo", JSONObject().put("model", "X11").put("platform", "web")
            .put("operatingSystem", "unknown").put("osVersion", "x86.64").put("manufacturer", "Google Inc.")
            .put("isVirtual", false).put("webViewVersion", "155.0.0.0"))
        extra.keys().forEach { result.put(it, extra.get(it)) }
        return result
    }

    fun unwrap(text: String, depth: Int = 0): Any {
        if (depth > 2) throw IOException("來源資料巢狀過深 / Nested source response")
        val outer = try { JSONTokener(text).nextValue() } catch (_: Exception) {
            throw IOException("來源回應格式異常 / Invalid source response")
        }
        if (outer is JSONObject) {
            if (outer.optBoolean("blocked")) throw IOException("來源限制存取，請稍後重試 / Source blocked the request")
            val encrypted = outer.optString("r")
            if (encrypted.isNotEmpty()) return unwrap(String(decrypt(encrypted), Charsets.UTF_8), depth + 1)
        }
        if (outer !is JSONObject && outer !is JSONArray) throw IOException("來源回應格式異常 / Invalid source response")
        return outer
    }

    fun decrypt(value: String): ByteArray {
        try {
            val raw = value.decodeBase64()?.toByteArray() ?: throw IOException()
            require(raw.size >= 32 && (raw.size - 16) % 16 == 0 && String(raw, 0, 8, Charsets.US_ASCII) == "Salted__")
            val salt = raw.copyOfRange(8, 16)
            var key = byteArrayOf()
            var previous = byteArrayOf()
            while (key.size < 48) {
                previous = MessageDigest.getInstance("MD5").digest(previous + "xxx".toByteArray() + salt)
                key += previous
            }
            return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 32), "AES"), IvParameterSpec(key.copyOfRange(32, 48)))
                doFinal(raw, 16, raw.size - 16)
            }
        } catch (_: Exception) { throw IOException("來源資料解密失敗 / Source decryption failed") }
    }

    fun videos(source: Source, value: Any): List<Video> {
        val rows = value as? JSONArray ?: (value as? JSONObject)?.optJSONArray("list")
            ?: throw IOException("來源缺少影片列表 / Missing video list")
        val videos = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let { row ->
            runCatching { video(source, row) }.getOrNull()
        } }.distinctBy { it.stableId }
        if (rows.length() > 0 && videos.isEmpty()) throw IOException("列表沒有有效影片資料 / Invalid video list")
        return videos
    }
    fun video(source: Source, row: JSONObject): Video {
        val id = validId(row.optString("id").ifBlank { row.optString("vId") })
        fun text(key: String) = row.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
        val covers = listOf("thumbnails", "thumbNails", "thumbnail").flatMap { key ->
            when (val value = row.opt(key)) {
                is String -> listOf(value)
                is JSONArray -> (0 until value.length()).map { value.optString(it) }
                else -> emptyList()
            }
        }
        val cover = covers.firstNotNullOfOrNull { mediaUrl(it, source) }
            ?: if (source.id == "xchina") "https://tp.xiaoxx.com/$id.jpg" else null
        return Video(source.id, id, text("title").ifBlank { id }, cover,
            text("durationStr").ifBlank { text("duration") }, text("keywords_gem"), text("videoType"), text("time").take(10))
    }
    fun mediaUrl(raw: String, source: Source): String? = runCatching {
        if (raw.isBlank()) return null
        val uri = URI(source.origin + "/").resolve(raw.trim())
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme !in listOf("https", "http") || uri.userInfo != null || host.contains(':') ||
            host == "localhost" || host.endsWith(".local") || host.matches(Regex("[0-9.]+"))) return null
        uri.toASCIIString()
    }.getOrNull()
    fun playlist(row: JSONObject, source: Source): String? {
        val candidates = row.optJSONArray("m3u8s") ?: JSONArray()
        return (0 until candidates.length()).firstNotNullOfOrNull { mediaUrl(candidates.optString(it), source) }
            ?: mediaUrl(row.optString("m3u8"), source)
    }
    fun catalogRequest(source: Source, category: String, page: Int, query: String): Pair<String, JSONObject> {
        require(page in 1..10000)
        if (query.isNotBlank()) return "/searchSevenVideos" to JSONObject().put("page", page).put("keywords", query.trim().take(300))
            .put("filterCategory", "all").put("filterTimeInDays", "all").put("filterViews", "all")
            .put("filterDurationInMins", "all").put("sortBy", if (source.id == "xchina") "relevance" else "time")
            .apply { if (source.id == "theporny") put("keywordLogic", "and") }
        val safeCategory = category.takeIf { key -> source.categories.any { it.first == key } } ?: "recent"
        return "/sevenVideos?page=$page&type=${URLEncoder.encode(safeCategory, "UTF-8")}" to JSONObject()
    }
}
