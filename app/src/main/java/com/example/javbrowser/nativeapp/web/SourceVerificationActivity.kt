package com.example.javbrowser.nativeapp.web

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class SourceVerificationActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(webView: WebView, url: String) {
                val challenge = url.contains("challenge", true) || webView.title?.contains("just a moment", true) == true
                if (!challenge && CookieManager.getInstance().getCookie(url)?.isNotBlank() == true) {
                    CookieManager.getInstance().flush()
                    setResult(RESULT_OK)
                    webView.postDelayed({ finish() }, 700)
                }
            }
        }
        setContentView(view)
        view.loadUrl(target)
    }
    companion object { const val EXTRA_URL = "source_url" }
}
