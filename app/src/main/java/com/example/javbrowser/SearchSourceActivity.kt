package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** User-opened original page: no scraping, ad-removal scripts or native JavaScript bridge. */
class SearchSourceActivity : LocalizedActivity() {
    companion object {
        const val EXTRA_SITE = "source_site"
        const val EXTRA_URL = "source_url"
        const val EXTRA_REQUEST = "source_request"
        const val EXTRA_RETRY = "source_retry"
    }
    private lateinit var browser: WebView
    private lateinit var address: TextView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_SITE).orEmpty()
        val site = SearchSiteRegistry.find(this, id)
        val initial = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (site == null || !SearchResultNormalizer.validUrl(initial, site)) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 18, 18)) }
        fun caption(textValue: String, size: Float) = TextView(this).apply {
            text = textValue; textSize = size; setTextColor(Color.WHITE); setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(caption(tr("原始搜尋頁", "Original search page") + " · " + site.displayName, 17f))
        root.addView(caption(tr("直接顯示原站，不代表統一抓取已成功。完成驗證後可返回重試。",
            "This is the original site, not a successful extraction. Return and retry after verification."), 12f))
        address = caption(initial, 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setOnClickListener { copyUrl() } }
        root.addView(address)
        val actions = LinearLayout(this)
        fun action(zh: String, en: String, click: () -> Unit) {
            actions.addView(Button(this).apply {
                text = tr(zh, en); textSize = 11f; minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0); setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        action("返回結果", "Back") { finish() }
        action("複製網址", "Copy URL") { copyUrl() }
        action("重新載入", "Reload") { browser.reload() }
        action("返回並重試", "Retry site") {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SITE, id)
                .putExtra(EXTRA_REQUEST, intent.getStringExtra(EXTRA_REQUEST)).putExtra(EXTRA_RETRY, true))
            finish()
        }
        root.addView(actions)
        status = caption(tr("載入原始網頁中", "Loading the original page"), 12f)
        root.addView(status)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = SearchHttpFetcher.USER_AGENT
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return request.url.scheme !in setOf("http", "https")
                    val blocked = !SearchResultNormalizer.validUrl(request.url.toString(), site)
                    if (blocked) status.text = tr("原站轉向其他網域，已停止自動跳轉", "The site redirected to a different domain; navigation stopped")
                    return blocked
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    address.text = url ?: initial
                    status.visibility = View.VISIBLE
                    status.text = tr("載入原始網頁中", "Loading the original page")
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    address.text = view?.url ?: url ?: initial
                    if (status.text == tr("載入原始網頁中", "Loading the original page"))
                        status.text = tr("原始頁面已載入，請查看網站實際訊息", "Original page loaded; check the site's message below")
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (request?.isForMainFrame != true) return
                    val cf = errorResponse?.responseHeaders?.entries?.any {
                        it.key.equals("cf-mitigated", true) && it.value.equals("challenge", true)
                    } == true
                    status.text = if (cf) tr("Cloudflare（CF）驗證：請在下方原始頁面操作", "Cloudflare (CF) verification: use the original page below")
                        else tr("原站回傳 HTTP ", "Site returned HTTP ") + (errorResponse?.statusCode ?: "?")
                }
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.cancel()
                    status.text = tr(SearchFailure.zh("TLS_FAILURE"), SearchFailure.en("TLS_FAILURE"))
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) status.text = tr("原始頁面載入失敗，可重新載入或返回結果", "Original page failed to load; reload or return to results")
                }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        val restoredUrl = savedInstanceState?.getString("page")?.takeIf { SearchResultNormalizer.validUrl(it, site) }
        browser.loadUrl(restoredUrl ?: initial)
    }
    private fun copyUrl() {
        val url = if (::browser.isInitialized) browser.url else null
        val copyable = PageUrlClipboard.copyableUrl(url) ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("URL", copyable))
        Toast.makeText(this, tr("已複製網址", "URL copied"), Toast.LENGTH_SHORT).show()
    }
    private fun tr(zh: String, en: String) = LanguageManager.text(this, zh, en)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onResume() { super.onResume(); if (::browser.isInitialized) browser.onResume() }
    override fun onStop() { if (::browser.isInitialized) { browser.stopLoading(); browser.onPause() }; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::browser.isInitialized) outState.putString("page", browser.url)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (::browser.isInitialized) { browser.stopLoading(); (browser.parent as? android.view.ViewGroup)?.removeView(browser); browser.destroy() }
        super.onDestroy()
    }
}
