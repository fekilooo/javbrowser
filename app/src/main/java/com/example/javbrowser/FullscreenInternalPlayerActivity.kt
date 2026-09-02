package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class FullscreenInternalPlayerActivity : LocalizedActivity() {

    companion object {
        private const val PLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val STATE_ORIENTATION_LOCK_MODE = "orientation_lock_mode"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_THUMB_PIC_NUM = "thumb_pic_num"
        const val EXTRA_THUMB_WIDTH = "thumb_width"
        const val EXTRA_THUMB_HEIGHT = "thumb_height"
        const val EXTRA_THUMB_COLUMNS = "thumb_columns"
        const val EXTRA_THUMB_ROWS = "thumb_rows"
        const val EXTRA_THUMB_URL_TEMPLATE = "thumb_url_template"
        const val EXTRA_PREVIEW_VTT_URL = "preview_vtt_url"
        const val EXTRA_DOWNLOAD_NAME = "download_name"
        const val EXTRA_LOCAL_PLAYBACK = "local_playback"
        const val EXTRA_PAGE_URL = "page_url"
        const val EXTRA_PC_RECORDING_EVENTS_URI = "pc_recording_events_uri"
        const val EXTRA_PC_RECORDING_CONTACT_URI = "pc_recording_contact_uri"
        const val EXTRA_PC_RECORDING_DISPLAY_NAME = "pc_recording_display_name"
    }

    private lateinit var webView: WebView
    private var localVideoServer: LocalVideoHttpServer? = null
    private var playbackProxyServer: VideoProxyServer? = null
    @Volatile
    private var orientationLockMode = "auto"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        orientationLockMode = savedInstanceState?.getString(STATE_ORIENTATION_LOCK_MODE) ?: "auto"
        requestedOrientation = if (orientationLockMode == "auto") {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        hideSystemUi()

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: run {
            finish()
            return
        }
        val referer = intent.getStringExtra(EXTRA_REFERER) ?: "https://missav.ai/"
        val thumbnailConfig = readThumbnailConfig()
        val previewVttUrl = intent.getStringExtra(EXTRA_PREVIEW_VTT_URL)
        val downloadName = intent.getStringExtra(EXTRA_DOWNLOAD_NAME) ?: "video"
        val pageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        val pcRecordingEventsUri = intent.getStringExtra(EXTRA_PC_RECORDING_EVENTS_URI)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        val isLocalPlayback = intent.getBooleanExtra(EXTRA_LOCAL_PLAYBACK, false) ||
            videoUrl.startsWith("content://") || videoUrl.startsWith("file://")
        val enableQualitySelector = !isLocalPlayback &&
            referer.contains("missav", ignoreCase = true) &&
            videoUrl.contains(".m3u8", ignoreCase = true)
        val playbackUrl = if (isLocalPlayback) {
            try {
                LocalVideoHttpServer(this, Uri.parse(videoUrl)).also {
                    it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    localVideoServer = it
                }.playbackUrl()
            } catch (error: Exception) {
                android.widget.Toast.makeText(
                    this,
                    "無法啟動本地播放器：${error.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
        } else {
            buildInternalPlaybackUrl(videoUrl, referer)
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowContentAccess = true
        webView.settings.allowFileAccess = true
        if (isLocalPlayback || playbackUrl.startsWith("http://127.0.0.1:", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            webView.settings.allowFileAccessFromFileURLs = true
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.settings.userAgentString = PLAYER_USER_AGENT
        // MISSAV 的影片由 surrit CDN 提供。Cloudflare 會在播放清單回應設定
        // __cf_bm；允許第三方 Cookie 才能讓背景下載沿用 WebView 已通過的驗證。
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    android.util.Log.i(
                        "INTERNAL_PLAYER",
                        "console ${it.messageLevel()} ${it.sourceId()}:${it.lineNumber()} ${it.message()}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        webView.addJavascriptInterface(
            PlayerBridge(
                playbackUrl,
                referer,
                pageUrl,
                downloadName,
                !isLocalPlayback,
                enableQualitySelector,
                pcRecordingEventsUri
            ),
            "AndroidPlayer"
        )

        webView.loadDataWithBaseURL(
            if (isLocalPlayback) playbackUrl.substringBeforeLast('/') + "/" else referer,
            buildPlayerHtml(
                playbackUrl,
                thumbnailConfig,
                previewVttUrl,
                isLocalPlayback,
                enableQualitySelector
            ),
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        localVideoServer?.stop()
        localVideoServer = null
        playbackProxyServer?.stop()
        playbackProxyServer = null
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ORIENTATION_LOCK_MODE, orientationLockMode)
        super.onSaveInstanceState(outState)
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private inner class PlayerBridge(
        private val videoUrl: String,
        private val referer: String,
        private val pageUrl: String,
        private val downloadName: String,
        private val canDownload: Boolean,
        private val canSelectQuality: Boolean,
        private val pcRecordingEventsUri: Uri?
    ) {
        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun setMuted(muted: Boolean) {
            PrivacySettings(this@FullscreenInternalPlayerActivity).internalPlayerMuted = muted
        }

        @JavascriptInterface
        fun download() {
            if (!canDownload) return
            runOnUiThread { startVideoDownload(videoUrl, referer, pageUrl, downloadName) }
        }

        @JavascriptInterface
        fun toggleOrientationLock(): String {
            if (orientationLockMode != "auto") {
                orientationLockMode = "auto"
                runOnUiThread {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
                return "auto"
            }

            val mode = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "landscape"
            } else {
                "portrait"
            }
            orientationLockMode = mode
            runOnUiThread {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                android.util.Log.i(
                    "INTERNAL_PLAYER",
                    "orientation locked mode=$mode config=${resources.configuration.orientation} " +
                        "rotation=${display?.rotation} requested=$requestedOrientation"
                )
            }
            return mode
        }

        @JavascriptInterface
        fun getQualityOptions(): String {
            if (!canSelectQuality) return "[]"
            return fetchHlsQualityOptions(videoUrl, referer)
        }

        @JavascriptInterface
        fun getPcRecordingEvents(): String =
            RecordingEventParser.playerPayload(this@FullscreenInternalPlayerActivity, pcRecordingEventsUri)

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.i("INTERNAL_PLAYER", message)
        }
    }

    private fun fetchHlsQualityOptions(masterUrl: String, referer: String): String {
        return try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(masterUrl).orEmpty()
                .ifEmpty { cookieManager.getCookie(referer).orEmpty() }
            val request = Request.Builder()
                .url(masterUrl)
                .header("Referer", referer)
                .header("User-Agent", PLAYER_USER_AGENT)
                .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
                .apply { if (cookies.isNotEmpty()) header("Cookie", cookies) }
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("INTERNAL_PLAYER", "Quality playlist HTTP ${response.code}")
                    return@use "[]"
                }
                val options = parseHlsQualityOptions(masterUrl, response.body?.string().orEmpty())
                android.util.Log.i(
                    "INTERNAL_PLAYER",
                    "Quality playlist variants=${options.length()} url=$masterUrl"
                )
                options.toString()
            }
        } catch (error: Exception) {
            android.util.Log.w("INTERNAL_PLAYER", "Quality playlist failed", error)
            "[]"
        }
    }

    private fun buildInternalPlaybackUrl(videoUrl: String, referer: String): String {
        val normalizedUrl = normalizeSurritMasterUrl(videoUrl)
        if (!normalizedUrl.startsWith("http", ignoreCase = true) || referer.isBlank()) {
            return normalizedUrl
        }

        val shouldBypassProxy =
            referer.contains("missav", ignoreCase = true) ||
                referer.contains("jable.tv", ignoreCase = true) ||
                referer.contains("avjoy", ignoreCase = true) ||
                referer.contains("avtoday.io", ignoreCase = true)
        if (shouldBypassProxy) {
            android.util.Log.i(
                "INTERNAL_PLAYER",
                "bypass playback proxy host=${Uri.parse(normalizedUrl).host} referer=$referer"
            )
            return normalizedUrl
        }

        return try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.flush()
            val cookies = cookieManager.getCookie(normalizedUrl).orEmpty()
                .ifEmpty { cookieManager.getCookie(referer).orEmpty() }

            VideoProxyServer().also {
                it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                playbackProxyServer = it
            }.buildProxyUrl(normalizedUrl, referer, cookies)
        } catch (error: Exception) {
            android.util.Log.w("INTERNAL_PLAYER", "Proxy bootstrap failed, fallback direct", error)
            normalizedUrl
        }
    }

    private fun normalizeSurritMasterUrl(url: String): String {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        if (!host.equals("surrit.com", true) && !host.endsWith(".surrit.com", true)) return url
        val variantPattern = Regex(
            """(https?://[^/]+/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/(?:\d+x\d+|\d+p)/[^?#]*\.m3u8""",
            RegexOption.IGNORE_CASE
        )
        val match = variantPattern.find(url) ?: return url
        return "${match.groupValues[1]}/playlist.m3u8"
    }

    private fun parseHlsQualityOptions(masterUrl: String, playlist: String): JSONArray {
        val result = JSONArray()
        val variants = mutableListOf<JSONObject>()
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        for (index in lines.indices) {
            val info = lines[index]
            if (!info.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(info)
            val width = resolution?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val height = resolution?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val bandwidth = Regex("""(?:AVERAGE-)?BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                .find(info)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val relativeUrl = lines.drop(index + 1).firstOrNull {
                it.isNotEmpty() && !it.startsWith("#")
            } ?: continue
            val resolvedUrl = URI(masterUrl).resolve(relativeUrl).toString()
            val label = if (height > 0) "${height}p" else if (width > 0) "${width}px" else "HLS"
            variants += JSONObject()
                .put("label", label)
                .put("url", resolvedUrl)
                .put("width", width)
                .put("height", height)
                .put("bandwidth", bandwidth)
        }
        variants
            .distinctBy { it.getString("url") }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("height") }
                .thenByDescending { it.optLong("bandwidth") })
            .forEach(result::put)
        return result
    }

    private fun startVideoDownload(videoUrl: String, referer: String, pageUrl: String, downloadName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                7101
            )
        }

        val pigAvDownload = resolvePigAvDownloadUrl(pageUrl, referer)
        if (pigAvDownload != null) {
            enqueueVideoDownload(pigAvDownload, pageUrl.ifBlank { referer }, downloadName)
            return
        }

        val cookieManager = android.webkit.CookieManager.getInstance()
        val isSurrit = Uri.parse(videoUrl).host?.let {
            it.equals("surrit.com", true) || it.endsWith(".surrit.com", true)
        } == true
        if (isSurrit && cookieManager.getCookie(videoUrl).isNullOrEmpty()) {
            bootstrapSurritCookie(videoUrl, referer) {
                enqueueVideoDownload(videoUrl, referer, downloadName)
            }
            return
        }
        enqueueVideoDownload(videoUrl, referer, downloadName)
    }

    private fun resolvePigAvDownloadUrl(pageUrl: String, referer: String): String? {
        if (!pageUrl.contains("pigav.ws", ignoreCase = true)) return null
        return try {
            val pageUri = Uri.parse(pageUrl)
            val videoId = pageUri.pathSegments
                .takeIf { it.size >= 2 && it[0] == "w" }
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(pageUrl).orEmpty()
                .ifEmpty { cookieManager.getCookie(referer).orEmpty() }
            val request = Request.Builder()
                .url("${pageUri.scheme ?: "https"}://${pageUri.host}/api/v1/videos/$videoId")
                .header("Referer", pageUrl)
                .header("User-Agent", PLAYER_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .apply { if (cookies.isNotEmpty()) header("Cookie", cookies) }
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("INTERNAL_PLAYER", "PigAV download API HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val candidates = mutableListOf<JSONObject>()
                json.optJSONArray("files")?.let { files ->
                    for (index in 0 until files.length()) {
                        files.optJSONObject(index)?.let(candidates::add)
                    }
                }
                json.optJSONArray("streamingPlaylists")?.let { playlists ->
                    for (playlistIndex in 0 until playlists.length()) {
                        val playlist = playlists.optJSONObject(playlistIndex) ?: continue
                        val files = playlist.optJSONArray("files") ?: continue
                        for (fileIndex in 0 until files.length()) {
                            files.optJSONObject(fileIndex)?.let(candidates::add)
                        }
                    }
                }
                candidates
                    .mapNotNull { file ->
                        val rawUrl = file.optString("fileDownloadUrl")
                            .ifBlank { file.optString("downloadUrl") }
                            .ifBlank { file.optString("fileUrl") }
                        val url = rawUrl
                            .takeIf { it.isNotBlank() }
                            ?.let { candidate ->
                                runCatching { URI(pageUrl).resolve(candidate).toString() }.getOrDefault(candidate)
                            }
                            ?.takeIf { it.startsWith("http", ignoreCase = true) && it.contains(".mp4", ignoreCase = true) }
                            ?: return@mapNotNull null
                        val height = file.optInt("resolution", file.optJSONObject("resolution")?.optInt("id") ?: 0)
                            .takeIf { it > 0 }
                            ?: Regex("""(\d{3,4})[pP]\b""").find(file.optString("label"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""-(\d{3,4})\.mp4(?:\?|$)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: 0
                        height to url
                    }
                    .maxByOrNull { it.first }
                    ?.second
                    ?.also {
                        android.util.Log.i("INTERNAL_PLAYER", "PigAV direct download resolved: $it")
                    }
            }
        } catch (error: Exception) {
            android.util.Log.w("INTERNAL_PLAYER", "PigAV download resolve failed", error)
            null
        }
    }

    private fun bootstrapSurritCookie(videoUrl: String, referer: String, onReady: () -> Unit) {
        val bootstrap = WebView(this)
        bootstrap.visibility = View.INVISIBLE
        bootstrap.settings.javaScriptEnabled = false
        bootstrap.settings.userAgentString = webView.settings.userAgentString
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(bootstrap, true)
        addContentView(bootstrap, android.view.ViewGroup.LayoutParams(1, 1))

        var finished = false
        fun finish(reason: String) {
            if (finished) return
            finished = true
            bootstrap.postDelayed({
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.flush()
                val cookies = cookieManager.getCookie(videoUrl).orEmpty()
                android.util.Log.i(
                    "VIDEO_DOWNLOAD_HTTP",
                    "surrit bootstrap=$reason cookieNames=${cookieNames(cookies)}"
                )
                (bootstrap.parent as? android.view.ViewGroup)?.removeView(bootstrap)
                bootstrap.destroy()
                onReady()
            }, 500L)
        }

        bootstrap.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                finish("finished")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    finish("http-${errorResponse?.statusCode}")
                }
            }
        }
        bootstrap.loadUrl(videoUrl, mapOf("Referer" to referer))
        bootstrap.postDelayed({ finish("timeout") }, 8_000L)
    }

    private fun enqueueVideoDownload(videoUrl: String, referer: String, downloadName: String) {
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.flush()
        val videoCookies = cookieManager.getCookie(videoUrl).orEmpty()
        val refererCookies = cookieManager.getCookie(referer).orEmpty()
        val cookies = videoCookies.ifEmpty { refererCookies }
        val cookieSourceUrl = if (videoCookies.isNotEmpty()) videoUrl else referer
        android.util.Log.i(
            "VIDEO_DOWNLOAD_HTTP",
            "enqueue videoHost=${Uri.parse(videoUrl).host} refererHost=${Uri.parse(referer).host} " +
                "cookieSourceHost=${Uri.parse(cookieSourceUrl).host} cookieNames=${cookieNames(cookies)}"
        )
        val downloadRecord = DownloadRepository.create(
            this,
            downloadName,
            videoUrl,
            referer,
            cookieSourceUrl
        )
        val serviceIntent = android.content.Intent(this, VideoDownloadService::class.java).apply {
            action = VideoDownloadService.ACTION_START
            putExtra(VideoDownloadService.EXTRA_URL, videoUrl)
            putExtra(VideoDownloadService.EXTRA_REFERER, referer)
            putExtra(VideoDownloadService.EXTRA_COOKIES, cookies)
            putExtra(VideoDownloadService.EXTRA_COOKIE_SOURCE_URL, cookieSourceUrl)
            putExtra(VideoDownloadService.EXTRA_FILE_NAME, downloadName)
            putExtra(VideoDownloadService.EXTRA_DOWNLOAD_ID, downloadRecord.id)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        android.widget.Toast.makeText(this, "影片已加入下載", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun cookieNames(cookieHeader: String): String = cookieHeader
        .split(';')
        .mapNotNull { item -> item.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
        .joinToString(",")

    private fun readThumbnailConfig(): MissAvThumbnailConfig? {
        val urlTemplate = intent.getStringExtra(EXTRA_THUMB_URL_TEMPLATE) ?: return null
        val config = MissAvThumbnailConfig(
            picNum = intent.getIntExtra(EXTRA_THUMB_PIC_NUM, 0),
            width = intent.getIntExtra(EXTRA_THUMB_WIDTH, 0),
            height = intent.getIntExtra(EXTRA_THUMB_HEIGHT, 0),
            columns = intent.getIntExtra(EXTRA_THUMB_COLUMNS, 0),
            rows = intent.getIntExtra(EXTRA_THUMB_ROWS, 0),
            urlTemplate = urlTemplate
        )
        return config.takeIf {
            it.picNum > 0 && it.width > 0 && it.height > 0 && it.columns > 0 && it.rows > 0
        }
    }

    private fun buildPlayerHtml(
        videoUrl: String,
        thumbnailConfig: MissAvThumbnailConfig?,
        previewVttUrl: String?,
        isLocalPlayback: Boolean,
        enableQualitySelector: Boolean
    ): String {
        val videoUrlJson = JSONObject.quote(videoUrl)
        val initialOrientationModeJson = JSONObject.quote(orientationLockMode)
        val previewVttUrlJson = previewVttUrl?.let(JSONObject::quote) ?: "null"
        val thumbnailConfigJson = thumbnailConfig?.let {
            JSONObject()
                .put("picNum", it.picNum)
                .put("width", it.width)
                .put("height", it.height)
                .put("columns", it.columns)
                .put("rows", it.rows)
                .put("urlTemplate", it.urlTemplate)
                .toString()
        } ?: "null"
        val privacySettings = PrivacySettings(this)
        val doubleTapSeekSeconds = privacySettings.doubleTapSeekSeconds
        val shortSeekSeconds = privacySettings.playerShortSeekSeconds
        val longSeekSeconds = privacySettings.playerLongSeekSeconds
        val playbackSpeedOptions = privacySettings.playbackSpeedOptions
        val pressHoldPlaybackRate = privacySettings.pressHoldPlaybackRate
        val pressHoldPlaybackRateText = PrivacySettings.formatPlaybackRate(pressHoldPlaybackRate)
        val uiEnglish = LanguageManager.isEnglish(this)
        fun seekLabel(seconds: Int): String {
            return if (uiEnglish) {
                if (seconds >= 60 && seconds % 60 == 0) "${seconds / 60}m" else "${seconds}s"
            } else {
                if (seconds >= 60 && seconds % 60 == 0) "${seconds / 60} 分" else "$seconds 秒"
            }
        }
        val shortSeekLabel = seekLabel(shortSeekSeconds)
        val longSeekLabel = seekLabel(longSeekSeconds)
        val speedButtonsHtml = playbackSpeedOptions.joinToString("\n") { rate ->
            val rateText = PrivacySettings.formatPlaybackRate(rate)
            val activeClass = if (rate == 1.0) " class=\"active\"" else ""
            "<button type=\"button\" data-rate=\"$rateText\"$activeClass>${rateText}x</button>"
        }
        val initialMuted = privacySettings.internalPlayerMuted
        val downloadButtonHtml = if (isLocalPlayback) {
            ""
        } else {
            "<button id=\"download\" title=\"下載影片\">⬇</button>"
        }
        val playerStatus = if (uiEnglish) {
            if (isLocalPlayback) "Local Player: Loading" else "Internal Player: Loading"
        } else {
            if (isLocalPlayback) "本地內建播放器：載入中" else "全螢幕內建播放器：載入中"
        }
        val closeLabel = if (uiEnglish) "Close" else "關閉"
        val speedLabel = if (uiEnglish) "Speed" else "速度"
        val qualityLabel = if (uiEnglish) "Auto" else "自動"
        val qualitySelectorHtml = if (enableQualitySelector) {
            "<select id=\"quality\" title=\"${if (uiEnglish) "Resolution" else "解析度"}\">" +
                "<option value=\"\">$qualityLabel</option></select>"
        } else {
            ""
        }
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
              <meta name="referrer" content="origin">
              <script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js"></script>
              <style>
                html, body {
                  margin: 0;
                  width: 100%;
                  height: 100%;
                  background: #000;
                  color: #fff;
                  overflow: hidden;
                  font-family: sans-serif;
                }
                #wrap {
                  position: fixed;
                  inset: 0;
                  background: #000;
                }
                #top {
                  position: absolute;
                  left: 0;
                  right: 0;
                  top: 0;
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 8px;
                  padding: 10px 12px;
                  background: linear-gradient(to bottom, rgba(0, 0, 0, .72), rgba(0, 0, 0, 0));
                  font-size: 13px;
                  z-index: 2;
                }
                #status {
                  flex: 1;
                  min-width: 0;
                  overflow: hidden;
                  white-space: nowrap;
                  text-overflow: ellipsis;
                }
                #top-actions {
                  display: flex;
                  gap: 6px;
                  flex: 0 0 auto;
                }
                .loop-point {
                  width: 40px;
                  padding: 0;
                  border: 1px solid rgba(255, 255, 255, .28);
                  font-weight: 800;
                }
                .loop-point.active {
                  border-color: #c57bff;
                  background: #8b00ff;
                  box-shadow: 0 0 0 2px rgba(139, 0, 255, .22);
                }
                #video {
                  position: absolute;
                  inset: 0;
                  width: 100%;
                  height: 100%;
                  background: #000;
                  object-fit: contain;
                  touch-action: none;
                }
                #press-hold-speed {
                  position: absolute;
                  left: 50%;
                  top: 50%;
                  transform: translate(-50%, -50%);
                  display: none;
                  flex-direction: column;
                  align-items: center;
                  justify-content: center;
                  min-width: 112px;
                  min-height: 52px;
                  padding: 0 18px;
                  border: 1px solid rgba(255, 255, 255, .3);
                  border-radius: 999px;
                  background: rgba(12, 12, 12, .72);
                  color: #fff;
                  font-size: 18px;
                  font-weight: 700;
                  box-shadow: 0 4px 18px rgba(0, 0, 0, .5);
                  pointer-events: none;
                  z-index: 3;
                }
                #press-hold-speed-main {
                  line-height: 1.25;
                  white-space: nowrap;
                }
                #press-hold-seek-time {
                  display: none;
                  margin-top: 5px;
                  color: rgba(255, 255, 255, .78);
                  font-size: 13px;
                  font-weight: 500;
                  line-height: 1.2;
                  white-space: nowrap;
                }
                #controls {
                  position: absolute;
                  left: 0;
                  right: 0;
                  bottom: 0;
                  display: flex;
                  flex-direction: column;
                  align-items: center;
                  gap: 9px;
                  padding: 12px 14px 14px;
                  background: linear-gradient(to top, rgba(0, 0, 0, .88), rgba(0, 0, 0, .12));
                  z-index: 2;
                }
                button, select {
                  min-height: 40px;
                  border-radius: 999px;
                  border: 0;
                  background: rgba(20, 20, 20, .78);
                  color: #fff;
                  font-size: 14px;
                  padding: 0 12px;
                }
                #play {
                  background: #8b00ff;
                  width: 54px;
                  height: 54px;
                  min-width: 54px;
                  padding: 0;
                  border: 2px solid rgba(255, 255, 255, .82);
                  box-shadow: 0 3px 12px rgba(0, 0, 0, .5);
                  font-size: 22px;
                  line-height: 1;
                }
                #progress-wrap {
                  width: 100%;
                  position: relative;
                  box-sizing: border-box;
                  padding-bottom: 16px;
                }
                #progress {
                  display: block;
                  position: relative;
                  z-index: 2;
                  width: 100%;
                  height: 26px;
                  margin: 0;
                  accent-color: #8b00ff;
                }
                #tip-marker-track {
                  position: absolute;
                  left: 8px;
                  right: 8px;
                  top: -18px;
                  height: 30px;
                  pointer-events: none;
                  z-index: 5;
                }
                .tip-marker {
                  position: absolute;
                  bottom: 0;
                  width: 26px;
                  height: 30px;
                  min-width: 0;
                  min-height: 0;
                  padding: 0;
                  border: 0;
                  border-radius: 0;
                  background: transparent;
                  transform: translateX(-50%);
                  pointer-events: auto;
                }
                .tip-marker::before {
                  content: '';
                  position: absolute;
                  left: 50%;
                  bottom: 8px;
                  width: 8px;
                  height: 8px;
                  transform: translateX(-50%) rotate(45deg);
                  border: 1px solid rgba(255, 248, 225, .92);
                  border-radius: 1px;
                  background: #ff9800;
                  box-shadow: 0 1px 3px rgba(0, 0, 0, .72);
                }
                .tip-marker::after {
                  content: '';
                  position: absolute;
                  left: 50%;
                  bottom: 0;
                  width: 1px;
                  height: 8px;
                  transform: translateX(-50%);
                  background: rgba(255, 183, 77, .9);
                }
                .tip-marker.selected::before {
                  width: 11px;
                  height: 11px;
                  bottom: 7px;
                  background: #ffc107;
                  border-color: #fff8e1;
                }
                #tip-message {
                  position: absolute;
                  left: 16px;
                  bottom: 150px;
                  display: none;
                  align-items: flex-start;
                  gap: 6px;
                  max-width: min(72vw, 560px);
                  padding: 8px 9px 8px 11px;
                  border: 1px solid rgba(255, 183, 77, .34);
                  border-radius: 9px;
                  background: rgba(18, 18, 18, .82);
                  color: #f5f5f5;
                  font-size: 13px;
                  line-height: 1.4;
                  box-shadow: 0 2px 8px rgba(0, 0, 0, .34);
                  z-index: 6;
                }
                #tip-message-text {
                  flex: 1 1 auto;
                  min-width: 0;
                  white-space: pre-wrap;
                  overflow-wrap: anywhere;
                }
                #tip-message-close {
                  flex: 0 0 auto;
                  width: auto;
                  height: 30px;
                  min-width: 58px;
                  min-height: 30px;
                  padding: 0 9px;
                  border: 1px solid rgba(255, 183, 77, .52);
                  border-radius: 999px;
                  background: rgba(255, 152, 0, .22);
                  color: #ffe0b2;
                  font-size: 12px;
                  font-weight: 700;
                  line-height: 30px;
                }
                #tip-list-toggle {
                  position: absolute;
                  left: 0;
                  top: 50%;
                  display: none;
                  align-items: center;
                  justify-content: center;
                  min-width: 54px;
                  height: 44px;
                  min-height: 44px;
                  padding: 0 10px 0 8px;
                  transform: translateY(-50%);
                  border: 1px solid rgba(255, 183, 77, .58);
                  border-left: 0;
                  border-radius: 0 13px 13px 0;
                  background: rgba(24, 20, 15, .9);
                  color: #ffcc80;
                  font-size: 12px;
                  font-weight: 700;
                  box-shadow: 2px 2px 10px rgba(0, 0, 0, .42);
                  z-index: 9;
                }
                #tip-list-backdrop {
                  position: absolute;
                  inset: 0;
                  display: none;
                  background: rgba(0, 0, 0, .24);
                  z-index: 9;
                }
                #tip-list-drawer {
                  position: absolute;
                  left: 0;
                  top: max(70px, env(safe-area-inset-top));
                  bottom: max(18px, env(safe-area-inset-bottom));
                  display: flex;
                  flex-direction: column;
                  width: min(84vw, 390px);
                  overflow: hidden;
                  transform: translateX(-104%);
                  transition: transform .2s ease;
                  border: 1px solid rgba(255, 183, 77, .34);
                  border-left: 0;
                  border-radius: 0 14px 14px 0;
                  background: rgba(15, 15, 15, .96);
                  box-shadow: 5px 0 22px rgba(0, 0, 0, .55);
                  z-index: 10;
                }
                #tip-list-drawer.open {
                  transform: translateX(0);
                }
                #tip-list-header {
                  display: flex;
                  align-items: center;
                  gap: 8px;
                  flex: 0 0 auto;
                  padding: 10px 10px 9px 14px;
                  border-bottom: 1px solid rgba(255, 255, 255, .11);
                  color: #ffcc80;
                  font-size: 14px;
                  font-weight: 700;
                }
                #tip-list-title {
                  flex: 1 1 auto;
                }
                #tip-list-close {
                  width: 36px;
                  height: 34px;
                  min-width: 36px;
                  min-height: 34px;
                  padding: 0;
                  border: 1px solid rgba(255, 255, 255, .18);
                  border-radius: 9px;
                  color: #fff;
                  font-size: 18px;
                  line-height: 34px;
                }
                #tip-list-items {
                  flex: 1 1 auto;
                  min-height: 0;
                  overflow-x: hidden;
                  overflow-y: auto;
                  overscroll-behavior: contain;
                  padding: 6px 0 10px;
                }
                .tip-list-item {
                  display: grid;
                  grid-template-columns: 58px minmax(0, 1fr);
                  gap: 9px;
                  width: 100%;
                  min-height: 50px;
                  padding: 9px 13px;
                  border-radius: 0;
                  border-bottom: 1px solid rgba(255, 255, 255, .07);
                  background: transparent;
                  text-align: left;
                }
                .tip-list-item.selected {
                  background: rgba(255, 152, 0, .17);
                }
                .tip-list-time {
                  color: #ffb74d;
                  font-size: 12px;
                  font-variant-numeric: tabular-nums;
                  line-height: 1.4;
                }
                .tip-list-message {
                  min-width: 0;
                  color: #f2f2f2;
                  font-size: 13px;
                  line-height: 1.4;
                  overflow-wrap: anywhere;
                }
                #ab-loop-track {
                  position: absolute;
                  left: 8px;
                  right: 8px;
                  top: 50%;
                  height: 5px;
                  display: none;
                  transform: translateY(-50%);
                  pointer-events: none;
                  z-index: 3;
                }
                #ab-loop-range {
                  position: absolute;
                  top: 0;
                  height: 5px;
                  border-radius: 999px;
                  background: linear-gradient(90deg, rgba(255, 179, 0, .34), rgba(255, 109, 0, .34));
                  box-shadow: 0 0 4px rgba(255, 145, 0, .38);
                }
                .ab-loop-marker {
                  position: absolute;
                  top: 50%;
                  width: 13px;
                  height: 13px;
                  transform: translate(-50%, -50%);
                  border: 2px solid rgba(255, 243, 224, .82);
                  border-radius: 50%;
                  background: rgba(255, 133, 0, .64);
                  box-shadow: 0 0 5px rgba(255, 145, 0, .58);
                }
                .ab-loop-marker::after {
                  content: attr(data-label);
                  position: absolute;
                  left: 50%;
                  bottom: 14px;
                  transform: translateX(-50%);
                  padding: 1px 4px;
                  border-radius: 4px;
                  background: rgba(118, 53, 0, .76);
                  color: #fff3e0;
                  font-size: 10px;
                  font-weight: 800;
                  line-height: 14px;
                }
                .ab-loop-thumbnail {
                  position: absolute;
                  bottom: 25px;
                  width: 112px;
                  height: 63px;
                  display: none;
                  transform: translateX(-50%);
                  border: 2px solid rgba(255, 145, 0, .78);
                  border-radius: 7px;
                  background-color: #080808;
                  background-repeat: no-repeat;
                  background-position: center;
                  background-size: cover;
                  box-shadow: 0 3px 11px rgba(0, 0, 0, .72);
                  overflow: hidden;
                }
                .ab-loop-thumbnail::after {
                  content: attr(data-label);
                  position: absolute;
                  left: 4px;
                  top: 4px;
                  min-width: 16px;
                  padding: 1px 3px;
                  border-radius: 4px;
                  background: rgba(118, 53, 0, .8);
                  color: #fff3e0;
                  font-size: 10px;
                  font-weight: 800;
                  line-height: 14px;
                  text-align: center;
                }
                #seek-preview {
                  position: absolute;
                  left: 50%;
                  bottom: 30px;
                  width: 160px;
                  height: 90px;
                  transform: translateX(-50%);
                  display: none;
                  overflow: hidden;
                  border: 2px solid rgba(255, 255, 255, .92);
                  border-radius: 8px;
                  background: #000;
                  box-shadow: 0 4px 16px rgba(0, 0, 0, .72);
                  pointer-events: none;
                  z-index: 4;
                }
                #seek-preview-video, #seek-preview-sprite {
                  display: block;
                  width: 160px;
                  height: 90px;
                  object-fit: contain;
                  background: #000;
                  transition: opacity .12s ease;
                }
                #seek-preview-sprite {
                  display: none;
                  background-repeat: no-repeat;
                }
                #seek-preview-time {
                  position: absolute;
                  right: 5px;
                  bottom: 4px;
                  padding: 2px 5px;
                  border-radius: 4px;
                  background: rgba(0, 0, 0, .76);
                  color: #fff;
                  font-size: 12px;
                  font-weight: bold;
                }
                #time {
                  flex: 1;
                  min-width: 110px;
                  font-size: 13px;
                  color: #fff;
                  text-align: left;
                  text-shadow: 0 1px 2px #000;
                }
                .control-row {
                  width: 100%;
                  display: flex;
                  align-items: center;
                }
                #primary-controls {
                  position: relative;
                  z-index: 2;
                  display: grid;
                  grid-template-columns: repeat(5, minmax(48px, 68px));
                  justify-content: center;
                  align-items: center;
                  gap: 8px;
                }
                .seek-button {
                  min-width: 0;
                  padding: 0 7px;
                  background: rgba(18, 18, 18, .8);
                  border: 1px solid rgba(255, 255, 255, .22);
                  font-size: 13px;
                  font-weight: 600;
                  white-space: nowrap;
                }
                #secondary-controls {
                  justify-content: space-between;
                  gap: 10px;
                  min-height: 40px;
                }
                #secondary-actions {
                  display: flex;
                  align-items: center;
                  gap: 8px;
                }
                #quality {
                  min-width: 76px;
                  max-width: 104px;
                  padding: 0 10px;
                  font-weight: 700;
                }
                #mute {
                  width: 42px;
                  min-width: 42px;
                  padding: 0;
                  font-size: 19px;
                }
                #speed-group {
                  position: relative;
                  display: flex;
                  align-items: center;
                  height: 40px;
                  padding-left: 11px;
                  border-radius: 999px;
                  background: rgba(20, 20, 20, .78);
                  color: rgba(255, 255, 255, .72);
                  font-size: 12px;
                }
                #speed {
                  min-width: 62px;
                  padding: 0 10px 0 6px;
                  background: transparent;
                  color: #fff;
                  font-weight: 700;
                }
                #speed-menu {
                  position: absolute;
                  right: 0;
                  bottom: calc(100% + 8px);
                  display: none;
                  grid-template-columns: repeat(4, 48px);
                  gap: 5px;
                  padding: 7px;
                  border: 1px solid rgba(255, 255, 255, .2);
                  border-radius: 12px;
                  background: rgba(12, 12, 12, .78);
                  box-shadow: 0 6px 24px rgba(0, 0, 0, .58);
                  backdrop-filter: blur(10px);
                  z-index: 8;
                }
                #speed-menu.open {
                  display: grid;
                }
                #speed-menu button {
                  min-width: 48px;
                  min-height: 34px;
                  padding: 0 5px;
                  border-radius: 8px;
                  background: rgba(255, 255, 255, .1);
                  font-size: 12px;
                }
                #speed-menu button.active {
                  background: #8b00ff;
                  color: #fff;
                }
                @media (max-width: 480px) {
                  #controls {
                    gap: 7px;
                    padding: 9px 8px 10px;
                  }
                  #primary-controls {
                    grid-template-columns: repeat(5, minmax(44px, 60px));
                    gap: 4px;
                  }
                  #progress-wrap {
                    padding-bottom: 14px;
                  }
                  .seek-button {
                    padding: 0 4px;
                    font-size: 12px;
                  }
                  #play {
                    width: 50px;
                    height: 50px;
                    min-width: 50px;
                  }
                  #time {
                    min-width: 96px;
                    font-size: 12px;
                  }
                  #secondary-actions {
                    gap: 5px;
                  }
                }
                @media (orientation: landscape) {
                  #tip-list-drawer {
                    width: min(44vw, 440px);
                  }
                  #controls {
                    display: grid;
                    grid-template-columns: minmax(95px, 1fr) auto minmax(145px, 1fr);
                    grid-template-rows: auto 42px;
                    gap: 3px 8px;
                    padding: 5px 10px 6px;
                  }
                  #progress-wrap {
                    grid-column: 1 / -1;
                    grid-row: 1;
                    padding-bottom: 12px;
                  }
                  #primary-controls {
                    grid-column: 2;
                    grid-row: 2;
                    width: auto;
                    grid-template-columns: repeat(5, 52px);
                    gap: 4px;
                  }
                  #secondary-controls {
                    display: contents;
                  }
                  #time {
                    grid-column: 1;
                    grid-row: 2;
                    align-self: center;
                  }
                  #secondary-actions {
                    grid-column: 3;
                    grid-row: 2;
                    justify-self: end;
                  }
                  .seek-button {
                    height: 34px;
                    min-height: 34px;
                    padding: 0 4px;
                    font-size: 12px;
                  }
                  #play {
                    width: 42px;
                    height: 42px;
                    min-width: 42px;
                    min-height: 42px;
                    justify-self: center;
                    font-size: 19px;
                  }
                  #mute {
                    height: 34px;
                    min-height: 34px;
                  }
                  #speed-group {
                    height: 34px;
                  }
                  #speed {
                    min-height: 34px;
                  }
                }
                .seek-zone-feedback {
                  position: absolute;
                  top: 0;
                  bottom: 0;
                  width: 0;
                  box-sizing: border-box;
                  border: 0;
                  opacity: 0;
                  filter: blur(3px);
                  pointer-events: none;
                  z-index: 1;
                }
                #seek-zone-left {
                  left: 0;
                  transform-origin: left center;
                  background: radial-gradient(ellipse at 0% 50%, rgba(255,255,255,.28) 0%, rgba(255,255,255,.12) 40%, rgba(255,255,255,.04) 64%, transparent 84%);
                }
                #seek-zone-right {
                  right: 0;
                  transform-origin: right center;
                  background: radial-gradient(ellipse at 100% 50%, rgba(255,255,255,.28) 0%, rgba(255,255,255,.12) 40%, rgba(255,255,255,.04) 64%, transparent 84%);
                }
                .seek-zone-feedback.flash {
                  animation: seek-zone-flash .38s ease-out;
                }
                @keyframes seek-zone-flash {
                  0% { opacity: .9; transform: scaleX(.97); }
                  38% { opacity: .62; }
                  100% { opacity: 0; transform: scaleX(1.02); }
                }
              </style>
            </head>
            <body>
              <div id="wrap">
                <div id="top">
                  <div id="status">$playerStatus</div>
                  <div id="top-actions">
                    $downloadButtonHtml
                    <button id="loop-a" class="loop-point" type="button" title="${if (uiEnglish) "Set or clear loop point A" else "設定或清除循環 A 點"}">A</button>
                    <button id="loop-b" class="loop-point" type="button" title="${if (uiEnglish) "Set or clear loop point B" else "設定或清除循環 B 點"}">B</button>
                    <button id="orientation" title="自動旋轉">🔓</button>
                    <button id="close">$closeLabel</button>
                  </div>
                </div>
                <video id="video" autoplay playsinline webkit-playsinline></video>
                <div id="press-hold-speed">
                  <div id="press-hold-speed-main">▶▶ ${pressHoldPlaybackRateText}x</div>
                  <div id="press-hold-seek-time"></div>
                </div>
                <div id="seek-zone-left" class="seek-zone-feedback"></div>
                <div id="seek-zone-right" class="seek-zone-feedback"></div>
                <div id="tip-message" role="status" aria-live="polite">
                  <span id="tip-message-text"></span>
                  <button id="tip-message-close" type="button" title="${if (uiEnglish) "Close message" else "關閉提示"}">${if (uiEnglish) "✕ Close" else "✕ 關閉"}</button>
                </div>
                <button id="tip-list-toggle" type="button" title="${if (uiEnglish) "Open tip event list" else "開啟小費事件清單"}"></button>
                <div id="tip-list-backdrop"></div>
                <aside id="tip-list-drawer" aria-hidden="true">
                  <div id="tip-list-header">
                    <span id="tip-list-title">${if (uiEnglish) "Tip events" else "小費事件"}</span>
                    <button id="tip-list-close" type="button" title="${if (uiEnglish) "Close tip event list" else "關閉小費事件清單"}">×</button>
                  </div>
                  <div id="tip-list-items"></div>
                </aside>
                <div id="controls">
                  <div id="progress-wrap">
                    <div id="seek-preview">
                      <video id="seek-preview-video" muted playsinline webkit-playsinline preload="metadata"></video>
                      <div id="seek-preview-sprite"></div>
                      <span id="seek-preview-time">00:00</span>
                    </div>
                    <div id="ab-loop-track">
                      <div id="ab-loop-range"></div>
                      <div id="ab-loop-thumbnail-a" class="ab-loop-thumbnail" data-label="A"></div>
                      <div id="ab-loop-thumbnail-b" class="ab-loop-thumbnail" data-label="B"></div>
                      <div id="ab-loop-marker-a" class="ab-loop-marker" data-label="A"></div>
                      <div id="ab-loop-marker-b" class="ab-loop-marker" data-label="B"></div>
                    </div>
                    <div id="tip-marker-track" aria-label="${if (uiEnglish) "Tip timeline markers" else "贊助時間標記"}"></div>
                    <input id="progress" type="range" min="0" max="1000" value="0">
                  </div>
                  <div id="primary-controls" class="control-row">
                    <button id="back60" class="seek-button" title="倒退 $longSeekLabel">−$longSeekLabel</button>
                    <button id="back20" class="seek-button" title="倒退 $shortSeekLabel">−$shortSeekLabel</button>
                    <button id="play" title="播放">▶</button>
                    <button id="fwd20" class="seek-button" title="前進 $shortSeekLabel">+$shortSeekLabel</button>
                    <button id="fwd60" class="seek-button" title="前進 $longSeekLabel">+$longSeekLabel</button>
                  </div>
                  <div id="secondary-controls" class="control-row">
                    <span id="time">00:00 / --:--</span>
                    <div id="secondary-actions">
                      $qualitySelectorHtml
                      <button id="mute" title="開啟或關閉聲音">🔊</button>
                      <div id="speed-group">
                        <span>$speedLabel</span>
                        <button id="speed" type="button" title="選擇播放速度">1x</button>
                        <div id="speed-menu">
                          $speedButtonsHtml
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
              <script>
                (function() {
                  var url = $videoUrlJson;
                  var isHls = /\.m3u8($|\?)/i.test(url);
                  var initialOrientationMode = $initialOrientationModeJson;
                  var thumbnailConfig = $thumbnailConfigJson;
                  var previewVttUrl = $previewVttUrlJson;
                  var video = document.getElementById('video');
                  var pressHoldSpeed = document.getElementById('press-hold-speed');
                  var pressHoldSpeedMain = document.getElementById('press-hold-speed-main');
                  var pressHoldSeekTime = document.getElementById('press-hold-seek-time');
                  var status = document.getElementById('status');
                  var play = document.getElementById('play');
                  var progress = document.getElementById('progress');
                  var progressWrap = document.getElementById('progress-wrap');
                  var tipMarkerTrack = document.getElementById('tip-marker-track');
                  var tipMessage = document.getElementById('tip-message');
                  var tipMessageText = document.getElementById('tip-message-text');
                  var tipMessageClose = document.getElementById('tip-message-close');
                  var tipListToggle = document.getElementById('tip-list-toggle');
                  var tipListBackdrop = document.getElementById('tip-list-backdrop');
                  var tipListDrawer = document.getElementById('tip-list-drawer');
                  var tipListTitle = document.getElementById('tip-list-title');
                  var tipListClose = document.getElementById('tip-list-close');
                  var tipListItems = document.getElementById('tip-list-items');
                  var seekPreview = document.getElementById('seek-preview');
                  var seekPreviewVideo = document.getElementById('seek-preview-video');
                  var seekPreviewSprite = document.getElementById('seek-preview-sprite');
                  var seekPreviewTime = document.getElementById('seek-preview-time');
                  var abLoopTrack = document.getElementById('ab-loop-track');
                  var abLoopRange = document.getElementById('ab-loop-range');
                  var abLoopMarkerA = document.getElementById('ab-loop-marker-a');
                  var abLoopMarkerB = document.getElementById('ab-loop-marker-b');
                  var abLoopThumbnailA = document.getElementById('ab-loop-thumbnail-a');
                  var abLoopThumbnailB = document.getElementById('ab-loop-thumbnail-b');
                  var speed = document.getElementById('speed');
                  var speedGroup = document.getElementById('speed-group');
                  var speedMenu = document.getElementById('speed-menu');
                  var time = document.getElementById('time');
                  var mute = document.getElementById('mute');
                  var orientation = document.getElementById('orientation');
                  var quality = document.getElementById('quality');
                  var loopAButton = document.getElementById('loop-a');
                  var loopBButton = document.getElementById('loop-b');
                  var doubleTapSeekSeconds = $doubleTapSeekSeconds;
                  var shortSeekSeconds = $shortSeekSeconds;
                  var longSeekSeconds = $longSeekSeconds;
                  var initialMuted = $initialMuted;
                  var pressHoldPlaybackRate = $pressHoldPlaybackRate;
                  var uiEnglish = $uiEnglish;
                  var seekPreviewVisible = false;
                  var seekPreviewHideTimer = null;
                  var seekPreviewRequestTimer = null;
                  var seekPreviewTarget = 0;
                  var seekPreviewSourceLoaded = false;
                  var seekPreviewReleasePending = false;
                  var currentSpriteSheet = -1;
                  var requestedSpriteSheet = -1;
                  var requestedSpriteFrame = 0;
                  var spriteRequestToken = 0;
                  var vttCues = [];
                  var vttReady = false;
                  var currentVttSpriteUrl = '';
                  var currentVttSpriteWidth = 0;
                  var currentVttSpriteHeight = 0;
                  var requestedVttCue = null;
                  var vttSpriteRequestToken = 0;
                  var loopPointA = null;
                  var loopPointB = null;
                  var abLoopActive = false;
                  var recordingTipEvents = [];
                  var recordingTipEventsLoaded = false;
                  var selectedTipEventIndex = -1;
                  var abLoopSeeking = false;
                  var loopThumbnailTokenA = 0;
                  var loopThumbnailTokenB = 0;

                  if (thumbnailConfig || previewVttUrl) {
                    seekPreviewVideo.style.display = 'none';
                    seekPreviewSprite.style.display = 'block';
                  }

                  function setStatus(text) {
                    try { AndroidPlayer.log('status=' + text + ' url=' + url); } catch (e) {}
                    if (uiEnglish) {
                      text = text
                        .replace('跳轉到 ', 'Seeked to ')
                        .replace('跳轉失敗', 'Seek failed')
                        .replace('開始載入', 'Loading')
                        .replace('已載入 metadata', 'Metadata loaded')
                        .replace('可播放', 'Ready')
                        .replace('播放中', 'Playing')
                        .replace('緩衝中', 'Buffering')
                        .replace('載入失敗 code=', 'Load failed, code=')
                        .replace('播放失敗：', 'Playback failed: ')
                        .replace('已靜音', 'Muted')
                        .replace('已開啟聲音', 'Sound on')
                        .replace('自動旋轉', 'Auto rotate')
                        .replace('已鎖定橫屏', 'Landscape locked')
                        .replace('已鎖定豎屏', 'Portrait locked')
                        .replace('已加入背景下載', 'Added to background downloads')
                        .replace('畫質已切換為 ', 'Resolution changed to ')
                        .replace('無法取得解析度清單', 'Resolution list unavailable')
                        .replace('已設定 A 點 ', 'A point set at ')
                        .replace('已設定 B 點 ', 'B point set at ')
                        .replace('已取消 A 點', 'A point cleared')
                        .replace('已取消 B 點', 'B point cleared')
                        .replace('A-B 區間太短', 'A-B range is too short')
                        .replace('A-B 循環 ', 'A-B loop ')
                        .replace('長按快轉 ', 'Press-and-hold speed ')
                        .replace('速度 ', 'Speed ')
                        .replace('此裝置不支援所選速度', 'Selected speed is unsupported')
                        .replace('等待手動播放：', 'Waiting for manual playback: ');
                    }
                    status.textContent = (uiEnglish ? 'Internal Player: ' : '全螢幕內建播放器：') + text;
                  }

                  function updatePlay() {
                    play.textContent = video.paused ? '▶' : '❚❚';
                    play.title = video.paused ? '播放' : '暫停';
                  }

                  function attachVideoSource(targetUrl) {
                    try { AndroidPlayer.log('attachVideoSource target=' + targetUrl); } catch (e) {}
                    if (window._hlsPlayer) {
                      try {
                        window._hlsPlayer.destroy();
                      } catch (e) {}
                      window._hlsPlayer = null;
                    }

                    if (/\.m3u8($|\?)/i.test(targetUrl) && window.Hls && window.Hls.isSupported()) {
                      try {
                        var hls = new Hls({
                          enableWorker: true,
                          lowLatencyMode: false
                        });
                        window._hlsPlayer = hls;
                        hls.on(Hls.Events.ERROR, function(event, data) {
                          try {
                            AndroidPlayer.log('hlsError type=' + data.type + ' details=' + data.details + ' fatal=' + data.fatal);
                          } catch (e) {}
                          if (data.fatal) {
                            if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                              try { hls.startLoad(); } catch (e) {}
                            } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                              try { hls.recoverMediaError(); } catch (e) {}
                            } else {
                              try { hls.destroy(); } catch (e) {}
                            }
                          }
                        });
                        hls.loadSource(targetUrl);
                        hls.attachMedia(video);
                        return;
                      } catch (e) {
                        try { AndroidPlayer.log('hlsInitFailed=' + (e && e.message ? e.message : e)); } catch (ignore) {}
                      }
                    }

                    video.src = targetUrl;
                  }

                  function updateOrientation(mode) {
                    if (mode === 'landscape') {
                      orientation.textContent = '🔒↔';
                      orientation.title = '已鎖定橫屏，點擊恢復自動旋轉';
                    } else if (mode === 'portrait') {
                      orientation.textContent = '🔒↕';
                      orientation.title = '已鎖定豎屏，點擊恢復自動旋轉';
                    } else {
                      orientation.textContent = '🔓';
                      orientation.title = '自動旋轉，點擊鎖定目前方向';
                    }
                  }

                  function updateMute() {
                    mute.textContent = video.muted ? '🔇' : '🔊';
                  }

                  function switchQuality(targetUrl, label) {
                    var resumeAt = video.currentTime || 0;
                    var wasPaused = video.paused;
                    attachVideoSource(targetUrl || url);
                    video.load();
                    video.addEventListener('loadedmetadata', function restorePlayback() {
                      if (resumeAt > 0 && isFinite(video.duration)) {
                        video.currentTime = Math.min(resumeAt, Math.max(0, video.duration - 0.25));
                      }
                      if (!wasPaused) {
                        var promise = video.play();
                        if (promise && promise.catch) promise.catch(function(){});
                      }
                    }, { once: true });
                    setStatus('畫質已切換為 ' + label);
                  }

                  function loadQualityOptions() {
                    if (!quality) return;
                    setTimeout(function() {
                      try {
                        var variants = JSON.parse(AndroidPlayer.getQualityOptions() || '[]');
                        if (!variants.length) {
                          quality.style.display = 'none';
                          return;
                        }
                        variants.forEach(function(variant) {
                          var option = document.createElement('option');
                          option.value = variant.url;
                          option.textContent = variant.label;
                          quality.appendChild(option);
                        });
                      } catch (e) {
                        quality.style.display = 'none';
                        setStatus('無法取得解析度清單');
                      }
                    }, 1200);
                  }

                  function seekBy(seconds) {
                    try {
                      video.currentTime = Math.max(0, video.currentTime + seconds);
                      setStatus('跳轉到 ' + Math.floor(video.currentTime) + 's');
                    } catch (e) {
                      setStatus('跳轉失敗');
                    }
                  }

                  function fmt(seconds) {
                    if (!seconds || !isFinite(seconds)) return '--:--';
                    var s = Math.floor(seconds % 60);
                    var m = Math.floor(seconds / 60) % 60;
                    var h = Math.floor(seconds / 3600);
                    var mm = (h > 0 && m < 10 ? '0' : '') + m;
                    var ss = (s < 10 ? '0' : '') + s;
                    return h > 0 ? h + ':' + mm + ':' + ss : m + ':' + ss;
                  }

                  function positionTipMessage() {
                    var controlsHeight = controls ? controls.offsetHeight : 138;
                    tipMessage.style.bottom = Math.max(90, controlsHeight + 12) + 'px';
                  }

                  function tipEventMessage(event) {
                    return event.message || (uiEnglish
                      ? 'No interaction details were included'
                      : '此筆通知沒有附帶其他互動內容');
                  }

                  function updateTipListToggle() {
                    if (recordingTipEvents.length === 0 || tipListDrawer.classList.contains('open')) {
                      tipListToggle.style.display = 'none';
                      return;
                    }
                    tipListToggle.textContent = (uiEnglish ? 'Tips ' : '小費 ') + recordingTipEvents.length;
                    tipListToggle.style.display = 'flex';
                  }

                  function renderRecordingTipList() {
                    tipListItems.innerHTML = '';
                    tipListTitle.textContent = (uiEnglish ? 'Tip events · ' : '小費事件 · ') + recordingTipEvents.length;
                    recordingTipEvents.forEach(function(event, index) {
                      var item = document.createElement('button');
                      item.type = 'button';
                      item.className = 'tip-list-item' + (index === selectedTipEventIndex ? ' selected' : '');
                      item.dataset.tipIndex = String(index);

                      var itemTime = document.createElement('span');
                      itemTime.className = 'tip-list-time';
                      itemTime.textContent = fmt(event.offsetSeconds);

                      var itemMessage = document.createElement('span');
                      itemMessage.className = 'tip-list-message';
                      itemMessage.textContent = tipEventMessage(event);

                      item.appendChild(itemTime);
                      item.appendChild(itemMessage);
                      item.onclick = function(clickEvent) {
                        clickEvent.preventDefault();
                        clickEvent.stopPropagation();
                        closeTipList();
                        activateRecordingTip(index);
                      };
                      tipListItems.appendChild(item);
                    });
                    updateTipListToggle();
                  }

                  function openTipList() {
                    if (recordingTipEvents.length === 0) return;
                    tipListDrawer.classList.add('open');
                    tipListDrawer.setAttribute('aria-hidden', 'false');
                    tipListBackdrop.style.display = 'block';
                    tipListToggle.style.display = 'none';
                    showChrome();
                  }

                  function closeTipList() {
                    tipListDrawer.classList.remove('open');
                    tipListDrawer.setAttribute('aria-hidden', 'true');
                    tipListBackdrop.style.display = 'none';
                    updateTipListToggle();
                    showChrome();
                  }

                  function renderRecordingTipMarkers() {
                    tipMarkerTrack.innerHTML = '';
                    if (!video.duration || !isFinite(video.duration) || recordingTipEvents.length === 0) {
                      tipMarkerTrack.style.display = 'none';
                      return;
                    }
                    tipMarkerTrack.style.display = 'block';
                    recordingTipEvents.forEach(function(event, index) {
                      var marker = document.createElement('button');
                      marker.type = 'button';
                      marker.className = 'tip-marker' + (index === selectedTipEventIndex ? ' selected' : '');
                      marker.style.left = Math.max(0, Math.min(100, event.offsetSeconds / video.duration * 100)) + '%';
                      marker.dataset.tipIndex = String(index);
                      var detail = fmt(event.offsetSeconds);
                      if (event.username) detail += ' · ' + event.username;
                      if (event.tokens > 0) detail += ' · ' + event.tokens + (uiEnglish ? ' tokens' : ' 代幣');
                      marker.setAttribute('aria-label', detail);
                      marker.title = detail;
                      marker.onclick = function(clickEvent) {
                        clickEvent.preventDefault();
                        clickEvent.stopPropagation();
                        selectNearestRecordingTip(clickEvent.clientX);
                      };
                      tipMarkerTrack.appendChild(marker);
                    });
                  }

                  function selectNearestRecordingTip(clientX) {
                    var markers = tipMarkerTrack.querySelectorAll('.tip-marker');
                    if (!markers.length) return;
                    var nearestIndex = 0;
                    var nearestDistance = Infinity;
                    Array.prototype.forEach.call(markers, function(marker) {
                      var rect = marker.getBoundingClientRect();
                      var distance = Math.abs(clientX - (rect.left + rect.width / 2));
                      if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestIndex = parseInt(marker.dataset.tipIndex || '0', 10);
                      }
                    });
                    activateRecordingTip(nearestIndex);
                  }

                  function activateRecordingTip(index) {
                    var event = recordingTipEvents[index];
                    if (!event) return;
                    var wasPaused = video.paused;
                    var preservePausedAfterSeek = function() {
                      if (wasPaused) {
                        video.pause();
                        updatePlay();
                      }
                      video.removeEventListener('seeked', preservePausedAfterSeek);
                    };
                    video.addEventListener('seeked', preservePausedAfterSeek);
                    try {
                      video.currentTime = Math.max(0, Math.min(video.duration || event.offsetSeconds, event.offsetSeconds));
                    } catch (error) {
                      video.removeEventListener('seeked', preservePausedAfterSeek);
                      setStatus(uiEnglish ? 'Unable to seek to this marker' : '無法跳到此標記');
                      return;
                    }
                    selectedTipEventIndex = index;
                    renderRecordingTipMarkers();
                    renderRecordingTipList();
                    tipMessageText.textContent = tipEventMessage(event);
                    positionTipMessage();
                    tipMessage.style.display = 'flex';
                    if (wasPaused) {
                      video.pause();
                      updatePlay();
                    } else if (video.paused) {
                      var resume = video.play();
                      if (resume && resume.catch) resume.catch(function(){});
                    }
                    setStatus((uiEnglish ? 'Tip marker · ' : '贊助標記 · ') + fmt(event.offsetSeconds));
                    showChrome();
                  }

                  function loadRecordingTipEvents() {
                    if (recordingTipEventsLoaded || !video.duration || !isFinite(video.duration)) return;
                    recordingTipEventsLoaded = true;
                    try {
                      var payload = JSON.parse(AndroidPlayer.getPcRecordingEvents() || '{"events":[]}');
                      var source = Array.isArray(payload.events) ? payload.events : [];
                      recordingTipEvents = source.filter(function(event) {
                        var offset = Number(event.offsetSeconds);
                        return isFinite(offset) && offset >= 0 && offset <= video.duration + 2;
                      }).map(function(event) {
                        event.offsetSeconds = Number(event.offsetSeconds);
                        return event;
                      }).sort(function(a, b) { return a.offsetSeconds - b.offsetSeconds; });
                      renderRecordingTipMarkers();
                      renderRecordingTipList();
                      if (payload.damaged) {
                        setStatus(uiEnglish ? 'Event data could not be read' : '事件資料無法讀取');
                      }
                    } catch (error) {
                      recordingTipEvents = [];
                      tipMarkerTrack.style.display = 'none';
                      renderRecordingTipList();
                      setStatus(uiEnglish ? 'Event data could not be read' : '事件資料無法讀取');
                    }
                  }

                  tipListToggle.onclick = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    openTipList();
                  };

                  tipListClose.onclick = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    closeTipList();
                  };

                  tipListBackdrop.onclick = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    closeTipList();
                  };

                  tipMessageClose.onclick = function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    tipMessage.style.display = 'none';
                    showChrome();
                  };

                  function updateTime() {
                    time.textContent = fmt(video.currentTime) + ' / ' + fmt(video.duration);
                  }

                  function abLoopBounds() {
                    if (loopPointA === null || loopPointB === null) return null;
                    return {
                      start: Math.min(loopPointA, loopPointB),
                      end: Math.max(loopPointA, loopPointB)
                    };
                  }

                  function updateAbLoopButtons() {
                    loopAButton.classList.toggle('active', loopPointA !== null);
                    loopBButton.classList.toggle('active', loopPointB !== null);
                    loopAButton.setAttribute('aria-pressed', loopPointA !== null ? 'true' : 'false');
                    loopBButton.setAttribute('aria-pressed', loopPointB !== null ? 'true' : 'false');
                    loopAButton.title = loopPointA === null
                      ? (uiEnglish ? 'Set loop point A' : '設定循環 A 點')
                      : (uiEnglish ? 'Clear loop point A (' : '清除循環 A 點（') + formatGestureTime(loopPointA) + (uiEnglish ? ')' : '）');
                    loopBButton.title = loopPointB === null
                      ? (uiEnglish ? 'Set loop point B' : '設定循環 B 點')
                      : (uiEnglish ? 'Clear loop point B (' : '清除循環 B 點（') + formatGestureTime(loopPointB) + (uiEnglish ? ')' : '）');
                    updateAbLoopProgress();
                  }

                  function updateAbLoopProgress() {
                    var hasA = loopPointA !== null;
                    var hasB = loopPointB !== null;
                    if ((!hasA && !hasB) || !video.duration || !isFinite(video.duration)) {
                      abLoopTrack.style.display = 'none';
                      return;
                    }
                    abLoopTrack.style.display = 'block';
                    var trackWidth = Math.max(1, abLoopTrack.clientWidth);
                    var thumbnailHalfPercent = Math.min(50, 56 / trackWidth * 100);
                    var aPercent = hasA ? Math.max(0, Math.min(100, loopPointA / video.duration * 100)) : 0;
                    var bPercent = hasB ? Math.max(0, Math.min(100, loopPointB / video.duration * 100)) : 0;

                    abLoopMarkerA.style.display = hasA ? 'block' : 'none';
                    abLoopMarkerB.style.display = hasB ? 'block' : 'none';
                    if (hasA) abLoopMarkerA.style.left = aPercent + '%';
                    if (hasB) abLoopMarkerB.style.left = bPercent + '%';

                    var showRange = abLoopActive && hasA && hasB;
                    abLoopRange.style.display = showRange ? 'block' : 'none';
                    if (showRange) {
                      var startPercent = Math.min(aPercent, bPercent);
                      var endPercent = Math.max(aPercent, bPercent);
                      abLoopRange.style.left = startPercent + '%';
                      abLoopRange.style.width = Math.max(0, endPercent - startPercent) + '%';
                    }

                    if (hasA) {
                      abLoopThumbnailA.style.left = Math.max(thumbnailHalfPercent, Math.min(100 - thumbnailHalfPercent, aPercent)) + '%';
                    }
                    if (hasB) {
                      abLoopThumbnailB.style.left = Math.max(thumbnailHalfPercent, Math.min(100 - thumbnailHalfPercent, bPercent)) + '%';
                    }
                    abLoopThumbnailA.style.display = hasA && abLoopThumbnailA.dataset.ready === 'true' ? 'block' : 'none';
                    abLoopThumbnailB.style.display = hasB && abLoopThumbnailB.dataset.ready === 'true' ? 'block' : 'none';
                  }

                  function loopThumbnailElement(point) {
                    return point === 'A' ? abLoopThumbnailA : abLoopThumbnailB;
                  }

                  function nextLoopThumbnailToken(point) {
                    if (point === 'A') return ++loopThumbnailTokenA;
                    return ++loopThumbnailTokenB;
                  }

                  function isLoopThumbnailTokenCurrent(point, token) {
                    return point === 'A' ? token === loopThumbnailTokenA : token === loopThumbnailTokenB;
                  }

                  function clearLoopPointThumbnail(point) {
                    nextLoopThumbnailToken(point);
                    var element = loopThumbnailElement(point);
                    element.dataset.ready = 'false';
                    element.style.display = 'none';
                    element.style.backgroundImage = 'none';
                    element.style.backgroundSize = 'cover';
                    element.style.backgroundPosition = 'center';
                  }

                  function applyLoopPointThumbnail(point, target, token, imageUrl, backgroundSize, backgroundPosition) {
                    if (!isLoopThumbnailTokenCurrent(point, token)) return;
                    var stored = point === 'A' ? loopPointA : loopPointB;
                    if (stored === null || Math.abs(stored - target) > 0.25) return;
                    var element = loopThumbnailElement(point);
                    element.style.backgroundImage = 'url("' + imageUrl.replace(/"/g, '%22') + '")';
                    element.style.backgroundSize = backgroundSize || 'cover';
                    element.style.backgroundPosition = backgroundPosition || 'center';
                    element.dataset.ready = 'true';
                    updateAbLoopProgress();
                  }

                  function tryCaptureLoopPointFrame(point, target, token) {
                    try {
                      if (!video.videoWidth || !video.videoHeight || video.readyState < 2) return false;
                      var canvas = document.createElement('canvas');
                      canvas.width = 224;
                      canvas.height = 126;
                      var context = canvas.getContext('2d', { alpha: false });
                      context.drawImage(video, 0, 0, canvas.width, canvas.height);
                      var dataUrl = canvas.toDataURL('image/jpeg', 0.72);
                      applyLoopPointThumbnail(point, target, token, dataUrl, 'cover', 'center');
                      return true;
                    } catch (e) {
                      return false;
                    }
                  }

                  function renderLoopPointSprite(point, target, token) {
                    if (!thumbnailConfig || !video.duration || !isFinite(video.duration)) return false;
                    var ratio = Math.max(0, Math.min(1, target / video.duration));
                    var frameIndex = Math.min(
                      thumbnailConfig.picNum - 1,
                      Math.max(0, Math.floor(ratio * thumbnailConfig.picNum))
                    );
                    var perSheet = thumbnailConfig.columns * thumbnailConfig.rows;
                    var sheetIndex = Math.floor(frameIndex / perSheet);
                    var cellIndex = frameIndex % perSheet;
                    var column = cellIndex % thumbnailConfig.columns;
                    var row = Math.floor(cellIndex / thumbnailConfig.columns);
                    var x = thumbnailConfig.columns > 1 ? column * 100 / (thumbnailConfig.columns - 1) : 0;
                    var y = thumbnailConfig.rows > 1 ? row * 100 / (thumbnailConfig.rows - 1) : 0;
                    var spriteUrl = thumbnailConfig.urlTemplate.replace('{index}', String(sheetIndex));
                    var image = new Image();
                    image.onload = function() {
                      applyLoopPointThumbnail(
                        point,
                        target,
                        token,
                        spriteUrl,
                        (thumbnailConfig.columns * 100) + '% ' + (thumbnailConfig.rows * 100) + '%',
                        x + '% ' + y + '%'
                      );
                    };
                    image.src = spriteUrl;
                    return true;
                  }

                  function findVttCueAt(target) {
                    if (!vttReady || vttCues.length === 0) return null;
                    var selected = vttCues[vttCues.length - 1];
                    for (var i = 0; i < vttCues.length; i++) {
                      if (target >= vttCues[i].start && target < vttCues[i].end) return vttCues[i];
                      if (target < vttCues[i].start) return i > 0 ? vttCues[i - 1] : vttCues[i];
                    }
                    return selected;
                  }

                  function renderLoopPointVtt(point, target, token) {
                    var cue = findVttCueAt(target);
                    if (!cue) return false;
                    var image = new Image();
                    image.onload = function() {
                      var scaleX = 112 / cue.width;
                      var scaleY = 63 / cue.height;
                      applyLoopPointThumbnail(
                        point,
                        target,
                        token,
                        cue.url,
                        (image.naturalWidth * scaleX) + 'px ' + (image.naturalHeight * scaleY) + 'px',
                        (-cue.x * scaleX) + 'px ' + (-cue.y * scaleY) + 'px'
                      );
                    };
                    image.src = cue.url;
                    return true;
                  }

                  function captureLoopPointThumbnail(point, target) {
                    clearLoopPointThumbnail(point);
                    var token = nextLoopThumbnailToken(point);
                    if (tryCaptureLoopPointFrame(point, target, token)) return;
                    if (renderLoopPointSprite(point, target, token)) return;
                    renderLoopPointVtt(point, target, token);
                  }

                  function startAbLoopIfReady(lastPoint) {
                    var bounds = abLoopBounds();
                    if (!bounds) return false;
                    if (bounds.end - bounds.start < 0.75) {
                      if (lastPoint === 'A') { loopPointA = null; clearLoopPointThumbnail('A'); }
                      if (lastPoint === 'B') { loopPointB = null; clearLoopPointThumbnail('B'); }
                      abLoopActive = false;
                      abLoopSeeking = false;
                      updateAbLoopButtons();
                      setStatus('A-B 區間太短');
                      return false;
                    }
                    abLoopActive = true;
                    abLoopSeeking = false;
                    updateAbLoopButtons();
                    try { video.currentTime = bounds.start; } catch (e) {}
                    try {
                      var loopPromise = video.play();
                      if (loopPromise && loopPromise.catch) loopPromise.catch(function(){});
                    } catch (e) {}
                    setStatus('A-B 循環 ' + formatGestureTime(bounds.start) + ' - ' + formatGestureTime(bounds.end));
                    return true;
                  }

                  function toggleLoopPoint(point) {
                    var keyIsA = point === 'A';
                    var existing = keyIsA ? loopPointA : loopPointB;
                    if (existing !== null) {
                      if (keyIsA) loopPointA = null; else loopPointB = null;
                      clearLoopPointThumbnail(point);
                      abLoopActive = false;
                      abLoopSeeking = false;
                      updateAbLoopButtons();
                      setStatus(keyIsA ? '已取消 A 點' : '已取消 B 點');
                      return;
                    }
                    var current = video.currentTime;
                    if (!isFinite(current)) return;
                    if (keyIsA) loopPointA = current; else loopPointB = current;
                    captureLoopPointThumbnail(point, current);
                    updateAbLoopButtons();
                    if (!startAbLoopIfReady(point)) {
                      if ((keyIsA ? loopPointA : loopPointB) !== null) {
                        setStatus((keyIsA ? '已設定 A 點 ' : '已設定 B 點 ') + formatGestureTime(current));
                      }
                    }
                  }

                  function enforceAbLoop() {
                    if (!abLoopActive || abLoopSeeking) return;
                    var bounds = abLoopBounds();
                    if (!bounds) {
                      abLoopActive = false;
                      return;
                    }
                    if (video.currentTime >= bounds.end - 0.05 || video.currentTime < bounds.start - 0.25) {
                      abLoopSeeking = true;
                      try { video.currentTime = bounds.start; } catch (e) { abLoopSeeking = false; }
                      setTimeout(function() { abLoopSeeking = false; }, 800);
                    }
                  }

                  function finishSeekPreviewLoad(element) {
                    element.style.opacity = '1';
                    if (seekPreviewReleasePending) {
                      if (seekPreviewHideTimer) clearTimeout(seekPreviewHideTimer);
                      seekPreviewHideTimer = setTimeout(function() {
                        seekPreviewReleasePending = false;
                        seekPreviewVisible = false;
                        seekPreview.style.display = 'none';
                      }, 650);
                    }
                  }

                  function fallbackToVideoPreview() {
                    thumbnailConfig = null;
                    previewVttUrl = null;
                    vttReady = false;
                    seekPreviewSprite.style.display = 'none';
                    seekPreviewVideo.style.display = 'block';
                    seekPreviewToTarget();
                  }

                  function parseVttTime(value) {
                    var parts = value.trim().split(':');
                    if (parts.length !== 3) return NaN;
                    return parseFloat(parts[0]) * 3600 + parseFloat(parts[1]) * 60 + parseFloat(parts[2]);
                  }

                  function parsePreviewVtt(text) {
                    var cues = [];
                    var blocks = text.replace(/\r/g, '').split(/\n\s*\n/);
                    blocks.forEach(function(block) {
                      var lines = block.split('\n').map(function(line){ return line.trim(); }).filter(Boolean);
                      var timingIndex = lines.findIndex(function(line){ return line.indexOf('-->') >= 0; });
                      if (timingIndex < 0 || timingIndex + 1 >= lines.length) return;
                      var timing = lines[timingIndex].match(/^([0-9:.]+)\s*-->\s*([0-9:.]+)/);
                      var image = lines[timingIndex + 1].match(/^(.+?)#xywh=(\d+),(\d+),(\d+),(\d+)$/);
                      if (!timing || !image) return;
                      var start = parseVttTime(timing[1]);
                      var end = parseVttTime(timing[2]);
                      if (!isFinite(start) || !isFinite(end)) return;
                      cues.push({
                        start: start,
                        end: end,
                        url: new URL(image[1], previewVttUrl).href,
                        x: parseInt(image[2], 10),
                        y: parseInt(image[3], 10),
                        width: parseInt(image[4], 10),
                        height: parseInt(image[5], 10)
                      });
                    });
                    return cues;
                  }

                  function applyVttCue(cue) {
                    var scaleX = 160 / cue.width;
                    var scaleY = 90 / cue.height;
                    seekPreviewSprite.style.backgroundSize =
                      (currentVttSpriteWidth * scaleX) + 'px ' + (currentVttSpriteHeight * scaleY) + 'px';
                    seekPreviewSprite.style.backgroundPosition =
                      (-cue.x * scaleX) + 'px ' + (-cue.y * scaleY) + 'px';
                  }

                  function loadVttCue(cue) {
                    requestedVttCue = cue;
                    if (currentVttSpriteUrl === cue.url && currentVttSpriteWidth > 0) {
                      applyVttCue(cue);
                      finishSeekPreviewLoad(seekPreviewSprite);
                      return;
                    }

                    seekPreviewSprite.style.opacity = '.38';
                    var requestToken = ++vttSpriteRequestToken;
                    var image = new Image();
                    image.onload = function() {
                      if (requestToken !== vttSpriteRequestToken || !requestedVttCue || requestedVttCue.url !== cue.url) return;
                      currentVttSpriteUrl = cue.url;
                      currentVttSpriteWidth = image.naturalWidth;
                      currentVttSpriteHeight = image.naturalHeight;
                      seekPreviewSprite.style.backgroundImage = 'url("' + cue.url.replace(/"/g, '%22') + '")';
                      applyVttCue(requestedVttCue);
                      finishSeekPreviewLoad(seekPreviewSprite);
                    };
                    image.onerror = fallbackToVideoPreview;
                    image.src = cue.url;
                  }

                  function updateVttPreview(target) {
                    if (!vttReady || vttCues.length === 0) return;
                    var low = 0;
                    var high = vttCues.length - 1;
                    var selected = vttCues[high];
                    while (low <= high) {
                      var middle = Math.floor((low + high) / 2);
                      var cue = vttCues[middle];
                      if (target < cue.start) {
                        high = middle - 1;
                      } else if (target >= cue.end) {
                        low = middle + 1;
                      } else {
                        selected = cue;
                        break;
                      }
                    }
                    loadVttCue(selected);
                  }

                  function loadPreviewVtt() {
                    fetch(previewVttUrl)
                      .then(function(response) {
                        if (!response.ok) throw new Error('VTT HTTP ' + response.status);
                        return response.text();
                      })
                      .then(function(text) {
                        vttCues = parsePreviewVtt(text);
                        if (vttCues.length === 0) throw new Error('No VTT cues');
                        vttReady = true;
                        updateVttPreview(seekPreviewTarget);
                        if (loopPointA !== null && abLoopThumbnailA.dataset.ready !== 'true') {
                          renderLoopPointVtt('A', loopPointA, nextLoopThumbnailToken('A'));
                        }
                        if (loopPointB !== null && abLoopThumbnailB.dataset.ready !== 'true') {
                          renderLoopPointVtt('B', loopPointB, nextLoopThumbnailToken('B'));
                        }
                      })
                      .catch(fallbackToVideoPreview);
                  }

                  function applySpritePosition(frameIndex) {
                    var perSheet = thumbnailConfig.columns * thumbnailConfig.rows;
                    var cellIndex = frameIndex % perSheet;
                    var column = cellIndex % thumbnailConfig.columns;
                    var row = Math.floor(cellIndex / thumbnailConfig.columns);
                    var x = thumbnailConfig.columns > 1 ? column * 100 / (thumbnailConfig.columns - 1) : 0;
                    var y = thumbnailConfig.rows > 1 ? row * 100 / (thumbnailConfig.rows - 1) : 0;
                    seekPreviewSprite.style.backgroundSize =
                      (thumbnailConfig.columns * 100) + '% ' + (thumbnailConfig.rows * 100) + '%';
                    seekPreviewSprite.style.backgroundPosition = x + '% ' + y + '%';
                  }

                  function updateSpritePreview(ratio) {
                    var frameIndex = Math.min(
                      thumbnailConfig.picNum - 1,
                      Math.max(0, Math.floor(ratio * thumbnailConfig.picNum))
                    );
                    var perSheet = thumbnailConfig.columns * thumbnailConfig.rows;
                    var sheetIndex = Math.floor(frameIndex / perSheet);
                    requestedSpriteSheet = sheetIndex;
                    requestedSpriteFrame = frameIndex;

                    if (currentSpriteSheet === sheetIndex) {
                      applySpritePosition(frameIndex);
                      finishSeekPreviewLoad(seekPreviewSprite);
                      return;
                    }

                    seekPreviewSprite.style.opacity = '.38';
                    var requestToken = ++spriteRequestToken;
                    var image = new Image();
                    var spriteUrl = thumbnailConfig.urlTemplate.replace('{index}', String(sheetIndex));
                    image.onload = function() {
                      if (requestToken !== spriteRequestToken || requestedSpriteSheet !== sheetIndex) return;
                      seekPreviewSprite.style.backgroundImage = 'url("' + spriteUrl.replace(/"/g, '%22') + '")';
                      currentSpriteSheet = sheetIndex;
                      applySpritePosition(requestedSpriteFrame);
                      finishSeekPreviewLoad(seekPreviewSprite);
                    };
                    image.onerror = function() {
                      if (requestToken !== spriteRequestToken) return;
                      fallbackToVideoPreview();
                    };
                    image.src = spriteUrl;
                  }

                  function seekPreviewToTarget() {
                    if (!seekPreviewSourceLoaded) {
                      seekPreviewSourceLoaded = true;
                      seekPreviewVideo.src = url;
                      seekPreviewVideo.load();
                      return;
                    }
                    if (seekPreviewVideo.readyState < 1) return;
                    try {
                      seekPreviewVideo.style.opacity = '.38';
                      if (typeof seekPreviewVideo.fastSeek === 'function') {
                        seekPreviewVideo.fastSeek(seekPreviewTarget);
                      } else {
                        seekPreviewVideo.currentTime = seekPreviewTarget;
                      }
                    } catch (e) {
                      seekPreviewVideo.style.opacity = '1';
                    }
                  }

                  function scheduleSeekPreview(target, ratio) {
                    seekPreviewTarget = target;
                    if (seekPreviewRequestTimer) clearTimeout(seekPreviewRequestTimer);
                    seekPreviewRequestTimer = setTimeout(function() {
                      if (thumbnailConfig) {
                        updateSpritePreview(ratio);
                      } else if (previewVttUrl) {
                        updateVttPreview(target);
                      } else {
                        seekPreviewToTarget();
                      }
                    }, (thumbnailConfig || previewVttUrl) ? 35 : 60);
                  }

                  function updateSeekPreview() {
                    if (!video.duration || !isFinite(video.duration)) return;
                    var ratio = parseInt(progress.value, 10) / 1000;
                    var previewHalfWidth = seekPreview.offsetWidth / 2 || 82;
                    var trackWidth = progressWrap.clientWidth;
                    var position = Math.max(previewHalfWidth, Math.min(trackWidth - previewHalfWidth, trackWidth * ratio));
                    seekPreview.style.left = position + 'px';
                    var target = video.duration * ratio;
                    seekPreviewTime.textContent = fmt(target);
                    scheduleSeekPreview(target, ratio);
                  }

                  function showSeekPreview() {
                    if (seekPreviewHideTimer) clearTimeout(seekPreviewHideTimer);
                    seekPreviewReleasePending = false;
                    seekPreviewVisible = true;
                    seekPreview.style.display = 'block';
                    updateSeekPreview();
                  }

                  function hideSeekPreviewSoon() {
                    if (seekPreviewHideTimer) clearTimeout(seekPreviewHideTimer);
                    seekPreviewReleasePending = true;
                    seekPreviewHideTimer = setTimeout(function() {
                      seekPreviewReleasePending = false;
                      seekPreviewVisible = false;
                      seekPreview.style.display = 'none';
                    }, 1800);
                  }

                  if (previewVttUrl) {
                    loadPreviewVtt();
                  }

                  video.addEventListener('loadstart', function(){ setStatus('開始載入'); });
                  video.addEventListener('loadedmetadata', function(){ setStatus('已載入 metadata'); });
                  video.addEventListener('canplay', function(){ setStatus('可播放'); });
                  video.addEventListener('playing', function(){ setStatus('播放中'); updatePlay(); });
                  video.addEventListener('pause', updatePlay);
                  video.addEventListener('volumechange', updateMute);
                  video.addEventListener('waiting', function(){ setStatus('緩衝中'); });
                  video.addEventListener('stalled', function(){ setStatus('串流停滯 stalled'); });
                  video.addEventListener('suspend', function(){ setStatus('載入暫停 suspend'); });
                  video.addEventListener('abort', function(){ setStatus('載入中止 abort'); });
                  video.addEventListener('emptied', function(){ setStatus('來源清空 emptied'); });
                  video.addEventListener('error', function(){
                    var err = video.error;
                    try {
                      AndroidPlayer.log('videoError code=' + (err ? err.code : 'unknown') +
                        ' currentSrc=' + (video.currentSrc || '') +
                        ' networkState=' + video.networkState +
                        ' readyState=' + video.readyState);
                    } catch (e) {}
                    if (/\.webm($|\?)/i.test(url)) {
                      setStatus(uiEnglish
                        ? 'This device cannot decode the VP9/Opus WebM video'
                        : '目前播放器不支援此影片的 VP9／Opus 格式');
                    } else {
                      setStatus((uiEnglish ? 'Playback failed code=' : '載入失敗 code=') + (err ? err.code : 'unknown'));
                    }
                  });
                  video.addEventListener('timeupdate', function() {
                    enforceAbLoop();
                    if (video.duration && isFinite(video.duration)) {
                      progress.value = String(Math.floor((video.currentTime / video.duration) * 1000));
                    }
                    updateTime();
                  });
                  video.addEventListener('durationchange', function() {
                    updateTime();
                    updateAbLoopProgress();
                    loadRecordingTipEvents();
                    renderRecordingTipMarkers();
                  });
                  video.addEventListener('loadedmetadata', function() {
                    loadRecordingTipEvents();
                    renderRecordingTipMarkers();
                  });
                  window.addEventListener('resize', function() {
                    renderRecordingTipMarkers();
                    if (tipMessage.style.display !== 'none') positionTipMessage();
                  });
                  seekPreviewVideo.addEventListener('loadedmetadata', function() {
                    seekPreviewToTarget();
                  });
                  seekPreviewVideo.addEventListener('seeking', function() {
                    seekPreviewVideo.style.opacity = '.38';
                  });
                  seekPreviewVideo.addEventListener('seeked', function() {
                    finishSeekPreviewLoad(seekPreviewVideo);
                  });

                  var controls = document.getElementById('controls');
                  var topBar = document.getElementById('top');
                  var hideTimer = null;
                  var chromeVisible = true;
                  function showChrome() {
                    chromeVisible = true;
                    controls.style.opacity = '1';
                    topBar.style.opacity = '1';
                    controls.style.pointerEvents = 'auto';
                    topBar.style.pointerEvents = 'auto';
                    if (hideTimer) clearTimeout(hideTimer);
                    hideTimer = setTimeout(function() {
                      if (!video.paused && !speedMenu.classList.contains('open') && !tipListDrawer.classList.contains('open')) {
                        controls.style.opacity = '0';
                        topBar.style.opacity = '0';
                        controls.style.pointerEvents = 'none';
                        topBar.style.pointerEvents = 'none';
                        chromeVisible = false;
                      }
                    }, 3000);
                  }
                  controls.style.transition = 'opacity .2s ease';
                  topBar.style.transition = 'opacity .2s ease';

                  function measureMm(mm) {
                    var probe = document.createElement('div');
                    probe.style.cssText = 'position:absolute;left:-9999px;top:-9999px;width:' + mm + 'mm;height:1px;';
                    document.body.appendChild(probe);
                    var px = probe.getBoundingClientRect().width;
                    probe.remove();
                    return px || 76;
                  }

                  var centerDeadZonePx = Math.max(48, measureMm(20));
                  var lastTapTime = 0;
                  var lastTapSide = '';
                  var singleTapTimer = null;
                  var suppressSyntheticClick = false;
                  var zoneFlashTimer = null;

                  function flashSeekZone(side, activeEdgeWidth) {
                    var zone = document.getElementById(side === 'left' ? 'seek-zone-left' : 'seek-zone-right');
                    zone.style.width = Math.max(0, activeEdgeWidth) + 'px';
                    zone.style.top = chromeVisible ? topBar.offsetHeight + 'px' : '0';
                    zone.style.bottom = chromeVisible ? controls.offsetHeight + 'px' : '0';
                    zone.classList.remove('flash');
                    void zone.offsetWidth;
                    zone.classList.add('flash');
                    if (zoneFlashTimer) clearTimeout(zoneFlashTimer);
                    zoneFlashTimer = setTimeout(function(){ zone.classList.remove('flash'); }, 420);
                  }

                  function isControlTarget(target) {
                    return target && target.closest && target.closest('#controls, #top, #tip-message, #tip-list-toggle, #tip-list-drawer, #tip-list-backdrop');
                  }

                  var pressHoldTimer = null;
                  var pressHoldActive = false;
                  var pressHoldConsumed = false;
                  var pressHoldRateApplied = false;
                  var pressHoldSeekActive = false;
                  var pressHoldPreviousRate = 1;
                  var pressHoldCurrentRate = pressHoldPlaybackRate;
                  var pressHoldStartX = 0;
                  var pressHoldStartY = 0;
                  var pressHoldPointerId = null;
                  var pressHoldSpeedStepPx = Math.max(42, measureMm(12));
                  var pressHoldSeekThresholdPx = 28;
                  var pressHoldSeekAnchorTime = 0;
                  var pressHoldSeekTargetTime = 0;
                  var pressHoldResumeAfterSeek = false;

                  function cancelPressHoldTimer() {
                    if (pressHoldTimer) clearTimeout(pressHoldTimer);
                    pressHoldTimer = null;
                  }

                  function beginPressHold(event) {
                    if (isControlTarget(event.target)) return;
                    if (event.pointerType === 'mouse' && event.button !== 0) return;
                    if (event.isPrimary === false) return;
                    cancelPressHoldTimer();
                    pressHoldConsumed = false;
                    pressHoldActive = false;
                    pressHoldRateApplied = false;
                    pressHoldSeekActive = false;
                    pressHoldResumeAfterSeek = false;
                    pressHoldCurrentRate = pressHoldPlaybackRate;
                    pressHoldPointerId = event.pointerId;
                    pressHoldStartX = event.clientX;
                    pressHoldStartY = event.clientY;
                    pressHoldTimer = setTimeout(function() {
                      pressHoldTimer = null;
                      if (pressHoldPointerId === null) return;
                      if (video.paused) return;
                      pressHoldPreviousRate = video.playbackRate || 1;
                      try {
                        video.playbackRate = pressHoldPlaybackRate;
                        pressHoldActive = true;
                        pressHoldRateApplied = true;
                        pressHoldConsumed = true;
                        pressHoldCurrentRate = pressHoldPlaybackRate;
                        pressHoldSpeedMain.textContent = '▶▶ ' + pressHoldPlaybackRate + 'x';
                        pressHoldSeekTime.style.display = 'none';
                        pressHoldSpeed.style.display = 'flex';
                        if (singleTapTimer) clearTimeout(singleTapTimer);
                        setStatus('長按快轉 ' + pressHoldPlaybackRate + 'x');
                      } catch (e) {
                        pressHoldActive = false;
                      }
                    }, 350);
                  }

                  function formatGestureTime(seconds) {
                    if (seconds === null || !isFinite(seconds)) return '--:--';
                    var value = Math.max(0, Math.floor(seconds));
                    var s = value % 60;
                    var m = Math.floor(value / 60) % 60;
                    var h = Math.floor(value / 3600);
                    var mm = (h > 0 && m < 10 ? '0' : '') + m;
                    var ss = (s < 10 ? '0' : '') + s;
                    return h > 0 ? h + ':' + mm + ':' + ss : m + ':' + ss;
                  }

                  function updatePressHoldDragSpeed(clientX) {
                    if (!pressHoldActive) return;
                    var dx = clientX - pressHoldStartX;
                    var distance = Math.max(0, dx);
                    var nextRate = pressHoldPlaybackRate;
                    if (distance >= pressHoldSpeedStepPx * 2) {
                      nextRate = Math.max(8, pressHoldPlaybackRate);
                    } else if (distance >= pressHoldSpeedStepPx) {
                      nextRate = Math.max(5, pressHoldPlaybackRate);
                    }
                    if (nextRate === pressHoldCurrentRate) return;
                    try {
                      video.playbackRate = nextRate;
                      pressHoldCurrentRate = nextRate;
                      pressHoldSpeedMain.textContent = '▶▶ ' + nextRate + 'x';
                      setStatus('長按快轉 ' + nextRate + 'x');
                    } catch (e) {}
                  }

                  function updatePressHoldBackwardSeek(clientX) {
                    if (!pressHoldSeekActive || !video.duration || !isFinite(video.duration)) return;
                    var distance = Math.max(0, pressHoldStartX - clientX);
                    var travelWidth = Math.max(160, (window.innerWidth || 360) * 0.75);
                    var normalized = Math.min(1, distance / travelWidth);
                    var maxSeekSeconds = Math.min(600, Math.max(60, video.duration * 0.10));
                    var seconds = Math.round(Math.pow(normalized, 1.2) * maxSeekSeconds);
                    if (distance >= pressHoldSeekThresholdPx && seconds < 1) seconds = 1;
                    pressHoldSeekTargetTime = Math.max(0, pressHoldSeekAnchorTime - seconds);
                    var actualSeconds = Math.round(pressHoldSeekAnchorTime - pressHoldSeekTargetTime);
                    pressHoldSpeedMain.textContent = '◀ -' + formatGestureTime(actualSeconds);
                    pressHoldSeekTime.textContent = formatGestureTime(pressHoldSeekTargetTime) + ' / ' + formatGestureTime(video.duration);
                  }

                  function beginPressHoldBackwardSeek(event) {
                    if (pressHoldSeekActive || !video.duration || !isFinite(video.duration)) return;
                    if (pressHoldRateApplied) {
                      try { video.playbackRate = pressHoldPreviousRate; } catch (e) {}
                      pressHoldRateApplied = false;
                    }
                    pressHoldSeekActive = true;
                    pressHoldSeekAnchorTime = video.currentTime || 0;
                    pressHoldSeekTargetTime = pressHoldSeekAnchorTime;
                    pressHoldResumeAfterSeek = !video.paused;
                    if (pressHoldResumeAfterSeek) {
                      try { video.pause(); } catch (e) {}
                    }
                    pressHoldSeekTime.style.display = 'block';
                    updatePressHoldBackwardSeek(event.clientX);
                  }

                  function movePressHold(event) {
                    if (pressHoldPointerId === null || event.pointerId !== pressHoldPointerId) return;
                    var dx = event.clientX - pressHoldStartX;
                    var dy = event.clientY - pressHoldStartY;
                    if (pressHoldTimer && !pressHoldActive) {
                      if (Math.sqrt(dx * dx + dy * dy) > 18) cancelPressHoldTimer();
                      return;
                    }
                    if (!pressHoldActive) return;
                    if (!pressHoldSeekActive && dx <= -pressHoldSeekThresholdPx && Math.abs(dx) > Math.abs(dy) * 1.15) {
                      beginPressHoldBackwardSeek(event);
                    }
                    if (pressHoldSeekActive) {
                      updatePressHoldBackwardSeek(event.clientX);
                    } else {
                      updatePressHoldDragSpeed(event.clientX);
                    }
                    event.preventDefault();
                    event.stopPropagation();
                  }

                  function finishPressHold(event, canceled) {
                    if (event && pressHoldPointerId !== null && event.pointerId !== undefined && event.pointerId !== pressHoldPointerId) return;
                    cancelPressHoldTimer();
                    var wasActive = pressHoldActive;
                    var wasSeeking = pressHoldSeekActive;
                    var shouldResume = pressHoldResumeAfterSeek;
                    if (pressHoldRateApplied) {
                      try { video.playbackRate = pressHoldPreviousRate; } catch (e) {}
                    }
                    if (wasSeeking && !canceled) {
                      try {
                        video.currentTime = pressHoldSeekTargetTime;
                        setStatus('跳轉到 ' + formatGestureTime(pressHoldSeekTargetTime));
                      } catch (e) {
                        setStatus('跳轉失敗');
                      }
                    } else if (wasActive) {
                      setStatus('播放中');
                    }
                    pressHoldActive = false;
                    pressHoldRateApplied = false;
                    pressHoldSeekActive = false;
                    pressHoldResumeAfterSeek = false;
                    pressHoldCurrentRate = pressHoldPlaybackRate;
                    pressHoldPointerId = null;
                    if (wasActive) pressHoldConsumed = true;
                    pressHoldSpeed.style.display = 'none';
                    pressHoldSeekTime.style.display = 'none';
                    if (wasSeeking && shouldResume) {
                      try {
                        var resumePromise = video.play();
                        if (resumePromise && resumePromise.catch) resumePromise.catch(function(){});
                      } catch (e) {}
                    }
                  }

                  document.body.addEventListener('pointerdown', beginPressHold, true);
                  document.body.addEventListener('pointermove', movePressHold, true);
                  document.body.addEventListener('pointerup', function(event) { finishPressHold(event, false); }, true);
                  document.body.addEventListener('pointercancel', function(event) { finishPressHold(event, true); }, true);
                  window.addEventListener('blur', function() { finishPressHold(null, true); });
                  document.addEventListener('visibilitychange', function() {
                    if (document.hidden) finishPressHold(null, true);
                  });

                  function handleSurfaceTap(event, clientX) {
                    if (isControlTarget(event.target)) return;

                    var width = window.innerWidth || document.documentElement.clientWidth || 0;
                    var center = width / 2;
                    var activeEdgeWidth = center - centerDeadZonePx / 2;
                    var side = '';
                    if (clientX <= activeEdgeWidth) side = 'left';
                    if (clientX >= width - activeEdgeWidth) side = 'right';
                    var inDeadZone = side === '';
                    var now = Date.now();

                    if (inDeadZone) {
                      if (singleTapTimer) clearTimeout(singleTapTimer);
                      singleTapTimer = setTimeout(showChrome, 220);
                      lastTapTime = 0;
                      lastTapSide = '';
                      return;
                    }

                    if (now - lastTapTime <= 320 && side === lastTapSide) {
                      if (singleTapTimer) clearTimeout(singleTapTimer);
                      flashSeekZone(side, activeEdgeWidth);
                      seekBy(side === 'right' ? doubleTapSeekSeconds : -doubleTapSeekSeconds);
                      lastTapTime = 0;
                      lastTapSide = '';
                      suppressSyntheticClick = true;
                      event.preventDefault();
                      event.stopPropagation();
                      return;
                    }

                    lastTapTime = now;
                    lastTapSide = side;
                    if (singleTapTimer) clearTimeout(singleTapTimer);
                    singleTapTimer = setTimeout(showChrome, 340);
                  }

                  var touchHandled = false;
                  document.body.addEventListener('touchend', function(event) {
                    if (isControlTarget(event.target)) return;
                    if (!event.changedTouches || event.changedTouches.length === 0) return;
                    touchHandled = true;
                    if (pressHoldConsumed) {
                      pressHoldConsumed = false;
                      suppressSyntheticClick = true;
                      event.preventDefault();
                      event.stopPropagation();
                      setTimeout(function(){ touchHandled = false; }, 420);
                      return;
                    }
                    handleSurfaceTap(event, event.changedTouches[0].clientX);
                    setTimeout(function(){ touchHandled = false; }, 420);
                  }, true);

                  document.body.addEventListener('click', function(event) {
                    if (isControlTarget(event.target)) return;
                    if (touchHandled || suppressSyntheticClick || pressHoldConsumed) {
                      suppressSyntheticClick = false;
                      pressHoldConsumed = false;
                      event.preventDefault();
                      event.stopPropagation();
                      return;
                    }
                    handleSurfaceTap(event, event.clientX);
                  }, true);

                  play.onclick = function() {
                    if (video.paused) {
                      var p = video.play();
                      if (p && p.catch) p.catch(function(e){ setStatus('播放失敗：' + (e && e.message ? e.message : 'unknown')); });
                    } else {
                      video.pause();
                    }
                    updatePlay();
                  };
                  document.getElementById('back20').onclick = function(){ seekBy(-shortSeekSeconds); };
                  document.getElementById('fwd20').onclick = function(){ seekBy(shortSeekSeconds); };
                  document.getElementById('back60').onclick = function(){ seekBy(-longSeekSeconds); };
                  document.getElementById('fwd60').onclick = function(){ seekBy(longSeekSeconds); };
                  mute.onclick = function() {
                    video.muted = !video.muted;
                    updateMute();
                    AndroidPlayer.setMuted(video.muted);
                    setStatus(video.muted ? '已靜音' : '已開啟聲音');
                  };
                  orientation.onclick = function() {
                    var mode = AndroidPlayer.toggleOrientationLock();
                    updateOrientation(mode);
                    setStatus(mode === 'auto' ? '自動旋轉' : (mode === 'landscape' ? '已鎖定橫屏' : '已鎖定豎屏'));
                    showChrome();
                  };
                  loopAButton.onclick = function() {
                    toggleLoopPoint('A');
                    showChrome();
                  };
                  loopBButton.onclick = function() {
                    toggleLoopPoint('B');
                    showChrome();
                  };
                  var downloadButton = document.getElementById('download');
                  if (downloadButton) {
                    downloadButton.onclick = function() {
                      AndroidPlayer.download();
                      setStatus('已加入背景下載');
                      showChrome();
                    };
                  }
                  if (quality) {
                    quality.onchange = function() {
                      switchQuality(quality.value, quality.options[quality.selectedIndex].textContent);
                      showChrome();
                    };
                  }
                  function closeSpeedMenu() {
                    speedMenu.classList.remove('open');
                  }
                  speed.onclick = function(event) {
                    event.stopPropagation();
                    speedMenu.classList.toggle('open');
                    showChrome();
                  };
                  Array.prototype.forEach.call(speedMenu.querySelectorAll('[data-rate]'), function(button) {
                    button.onclick = function(event) {
                      event.stopPropagation();
                      var rate = parseFloat(button.getAttribute('data-rate'));
                      try {
                        video.playbackRate = rate;
                        speed.textContent = button.textContent;
                        Array.prototype.forEach.call(speedMenu.querySelectorAll('[data-rate]'), function(item) {
                          item.classList.toggle('active', item === button);
                        });
                        setStatus('速度 ' + button.textContent);
                      } catch (e) {
                        setStatus('此裝置不支援所選速度');
                      }
                      closeSpeedMenu();
                      showChrome();
                    };
                  });
                  document.addEventListener('click', function(event) {
                    if (!speedGroup.contains(event.target)) closeSpeedMenu();
                  });
                  progress.oninput = function() {
                    if (video.duration && isFinite(video.duration)) {
                      showSeekPreview();
                      video.currentTime = video.duration * (parseInt(progress.value, 10) / 1000);
                      updateSeekPreview();
                    }
                  };
                  progress.addEventListener('pointerdown', showSeekPreview);
                  progress.addEventListener('pointerup', hideSeekPreviewSoon);
                  progress.addEventListener('touchend', hideSeekPreviewSoon);
                  progress.addEventListener('change', hideSeekPreviewSoon);
                  document.getElementById('close').onclick = function() {
                    video.pause();
                    AndroidPlayer.close();
                  };

                  video.muted = initialMuted;
                  attachVideoSource(url);
                  updateMute();
                  updatePlay();
                  updateAbLoopButtons();
                  updateOrientation(initialOrientationMode);
                  loadQualityOptions();
                  video.load();
                  var p = video.play();
                  if (p && p.catch) p.catch(function(e){ setStatus('等待手動播放：' + (e && e.message ? e.message : 'play rejected')); });
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
