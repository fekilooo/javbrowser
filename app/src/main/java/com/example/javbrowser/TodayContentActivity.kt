package com.example.javbrowser

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TodayContentActivity : LocalizedActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_JAV_CODE = "jav_code"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: Button
    private lateinit var btnBookmark: Button
    private lateinit var btnCrossSearch: Button
    private lateinit var favoritesManager: FavoritesManager

    private var targetUrl: String = ""
    private var targetTitle: String = ""
    private var targetThumb: String? = null
    private var targetJavCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_today_content)

        favoritesManager = FavoritesManager(this)
        webView = findViewById(R.id.webview_today_content)
        progressBar = findViewById(R.id.progress_today_content)
        tvTitle = findViewById(R.id.tv_today_content_title)
        btnBack = findViewById(R.id.btn_back_today_content)
        btnBookmark = findViewById(R.id.btn_close_today_content)
        btnCrossSearch = findViewById(R.id.btn_today_content_cross_search)

        targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        targetTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: targetUrl
        targetThumb = intent.getStringExtra(EXTRA_THUMB)?.takeIf { it.isNotBlank() }
        targetJavCode = intent.getStringExtra(EXTRA_JAV_CODE)?.takeIf { it.isNotBlank() }

        if (targetJavCode.isNullOrBlank()) {
            targetJavCode = CrossSiteSearchUi.extractCode(targetTitle + " " + targetUrl)
        }

        tvTitle.text = targetTitle.ifBlank { "新着內容" }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnCrossSearch.visibility = if (targetJavCode.isNullOrBlank()) View.GONE else View.VISIBLE
        btnCrossSearch.setOnClickListener {
            CrossSiteSearchUi.show(this, targetJavCode.orEmpty().ifBlank { targetTitle + " " + targetUrl })
        }
        refreshBookmarkButton()

        setupWebView()

        if (targetUrl.isBlank()) {
            finish()
            return
        }
        webView.loadUrl(targetUrl)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240105.004) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.6261.119 Mobile Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tvTitle.text = title
                    if (targetTitle.isBlank() || targetTitle == targetUrl) {
                        targetTitle = title
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun toggleBookmark() {
        val isSaved = favoritesManager.getFavorites().any { it.url == targetUrl }
        if (isSaved) {
            favoritesManager.removeFavorite(targetUrl)
            Toast.makeText(this, "已移除書籤", Toast.LENGTH_SHORT).show()
        } else {
            favoritesManager.addFavorite(
                title = targetTitle.ifBlank { targetUrl },
                url = targetUrl,
                thumbnailUrl = targetThumb,
                javCode = targetJavCode
            )
            Toast.makeText(this, "已加入書籤", Toast.LENGTH_SHORT).show()
        }
        refreshBookmarkButton()
    }

    private fun refreshBookmarkButton() {
        val isSaved = targetUrl.isNotBlank() && favoritesManager.getFavorites().any { it.url == targetUrl }
        btnBookmark.text = if (isSaved) "已收藏" else "加入書籤"
        btnBookmark.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (isSaved) "#7B1FA2" else "#1565C0")
        )
    }
}
