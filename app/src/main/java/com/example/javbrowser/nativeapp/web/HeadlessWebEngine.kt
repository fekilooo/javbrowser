package com.example.javbrowser.nativeapp.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class RenderedPage(val finalUrl: String, val html: String, val cookies: String?)

class HeadlessWebEngine(context: Context) {
    private val appContext = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(url: String, timeoutMillis: Long = 20_000): Result<RenderedPage> = suspendCancellableCoroutine { continuation ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(appContext)
            var finished = false
            fun cleanup() { webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeAllViews(); webView.destroy() }
            fun complete(result: Result<RenderedPage>) { if (finished) return; finished = true; cleanup(); if (continuation.isActive) continuation.resume(result) }
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finalUrl: String) {
                    view.evaluateJavascript("document.documentElement.outerHTML") { encoded ->
                        val html = encoded?.removeSurrounding("\"")?.replace("\\n", "\n")?.replace("\\\"", "\"").orEmpty()
                        complete(Result.success(RenderedPage(finalUrl, html, CookieManager.getInstance().getCookie(finalUrl))))
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) complete(Result.failure(IllegalStateException(error.description.toString())))
                }
            }
            val timeout = Runnable { complete(Result.failure(IllegalStateException("Web rendering timed out"))) }
            Handler(Looper.getMainLooper()).postDelayed(timeout, timeoutMillis)
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { if (!finished) { finished = true; cleanup() } } }
            webView.loadUrl(url)
        }
    }
}
