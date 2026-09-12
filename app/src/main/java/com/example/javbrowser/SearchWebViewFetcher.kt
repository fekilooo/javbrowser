package com.example.javbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** No native bridge. One browser per process, released only after main-thread destruction. */
class SearchWebViewFetcher(context: Context) {
    private val appContext = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    fun fetchBlocking(url: String, site: SearchSite, rules: SearchParserRules,
                      cancellation: SearchCancellation, timeoutMs: Long = 25_000): SearchHttpResponse {
        cancellation.check()
        SearchScheduler.browserSlot.acquire()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val response = AtomicReference<SearchHttpResponse?>()
        var view: WebView? = null
        var lastHtml = ""
        var pageLoaded = false
        var mainStatus = 200
        var mitigated: String? = null
        var fromCloudflare = false
        var retryUntil = 0L
        val readiness = SearchDomReadiness()
        fun finish(status: Int, body: String = lastHtml, code: String? = null, retryAfter: Long = 0L) {
            if (!finished.compareAndSet(false, true)) return
            response.set(SearchHttpResponse(status, view?.url ?: url, body, "text/html",
                maxOf(retryAfter, retryUntil), code, mitigated, fromCloudflare, rendered = true))
            handler.removeCallbacksAndMessages(null)
            try {
                view?.stopLoading()
                view?.destroy()
                view = null
            } finally {
                SearchScheduler.browserSlot.release()
                latch.countDown()
            }
        }
        val detach = cancellation.onCancel { handler.post { finish(499, "") } }
        handler.post {
            if (finished.get()) return@post
            if (cancellation.isCancelled) { finish(499, ""); return@post }
            try {
                view = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.userAgentString = SearchHttpFetcher.USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            mainStatus = 200
                            mitigated = null
                            fromCloudflare = false
                            retryUntil = 0L
                            lastHtml = ""
                            pageLoaded = false
                            readiness.reset()
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            val blocked = uri.scheme !in listOf("http", "https") ||
                                !site.isAllowedHost(uri.host.orEmpty())
                            if (blocked && request.isForMainFrame) finish(599, "", "UNEXPECTED_REDIRECT_HOST")
                            return blocked
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                val code = when (error?.errorCode) {
                                    ERROR_HOST_LOOKUP -> "DNS_FAILURE"
                                    ERROR_CONNECT -> "CONNECT_FAILURE"
                                    ERROR_FAILED_SSL_HANDSHAKE -> "TLS_FAILURE"
                                    ERROR_TIMEOUT -> "HTTP_TIMEOUT"
                                    else -> "IO_ERROR"
                                }
                                finish(if (code == "HTTP_TIMEOUT") 598 else 599, "", code)
                            }
                        }
                        override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, error: android.net.http.SslError?) {
                            sslHandler?.cancel()
                            finish(599, "", "TLS_FAILURE")
                        }
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            if (request?.isForMainFrame != true) return
                            mainStatus = errorResponse?.statusCode ?: 599
                            val headers = errorResponse?.responseHeaders.orEmpty()
                            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                            mitigated = header("cf-mitigated")
                            fromCloudflare = header("Server").orEmpty().contains("cloudflare", true) || header("cf-ray") != null
                            retryUntil = SearchHttpFetcher.retryAfter(header("Retry-After"), System.currentTimeMillis())
                            // Keep the error page alive long enough to inspect its DOM; do not discard it as a format error.
                            if (mitigated.equals("challenge", true)) finish(mainStatus)
                        }
                    }
                }
                val script = """
                    (function() {
                      var hosts = [${site.allowedHosts.joinToString(",") { JSONObject.quote(it) }}];
                      var detailPath = new RegExp(${JSONObject.quote("^(?:" + rules.detailPath.pattern + ")$")}, 'i');
                      function detailUrl(a) {
                        try {
                          var u = new URL(a.getAttribute('href'), document.baseURI);
                          if (!/^https?:$/.test(u.protocol) || u.username || u.password || (u.port && u.port !== '80' && u.port !== '443')) return '';
                          if (!hosts.some(function(h) { return u.hostname === h || u.hostname.endsWith('.' + h); }) || !detailPath.test(u.pathname)) return '';
                          u.hash = '';
                          return u.href;
                        } catch (_) { return ''; }
                      }
                      function visible(e) {
                        for (var n = e; n; n = n.parentElement) {
                          var style = getComputedStyle(n);
                          if (n.hidden || n.getAttribute('aria-hidden') === 'true' || style.display === 'none' || style.visibility === 'hidden') return false;
                        }
                        return true;
                      }
                      var text = document.body ? document.body.innerText : '';
                      var blocked = new RegExp(${JSONObject.quote(SearchPageClassifier.challengeWords.pattern)}, 'i').test(document.title + ' ' + text)
                        || Array.from(document.querySelectorAll(${JSONObject.quote(SearchPageClassifier.CHALLENGE_SELECTORS + ", #cf-error-details")})).some(visible)
                        || /sign in to continue|login to continue|請先登入|you have been blocked|access denied/i.test(text);
                      var matches = Array.from(document.querySelectorAll(${JSONObject.quote(rules.links)})).map(function(a) {
                        var href = detailUrl(a);
                        if (!href || !visible(a) || a.closest('nav, .advertisement')) return '';
                        var card = a.closest(${JSONObject.quote(rules.card)});
                        if (!card) {
                          card = a;
                          for (var depth = 0; depth < 3; depth++) {
                            var parent = card.parentElement;
                            if (!parent || /^(BODY|HTML|MAIN|NAV|HEADER|FOOTER)$/.test(parent.tagName)) break;
                            var targets = Array.from(parent.querySelectorAll(${JSONObject.quote(rules.links)})).map(detailUrl).filter(Boolean);
                            if (new Set(targets).size !== 1) break;
                            card = parent;
                          }
                        }
                        if (a.closest('header, footer') && !card.closest('article')) return '';
                        var title = card.querySelector('.title, .video-title, .video-name, .content-title, h2, h3, h4');
                        var image = card.querySelector('img[alt]');
                        var label = [a.getAttribute('title'), a.textContent, title && title.textContent, image && image.alt]
                          .find(function(value) { return value && value.trim().length > 0; });
                        return label ? href + ':' + label.trim() : '';
                      }).filter(Boolean);
                      var empty = Array.from(document.querySelectorAll(${JSONObject.quote(rules.empty + ", " + SearchPageClassifier.EMPTY_SELECTORS + ", main h1, main h2")}))
                        .some(function(e) {
                          return visible(e) && !e.closest('nav, header, footer, .comments, #comments, .recommendations, .related, .related-videos')
                            && !e.closest(${JSONObject.quote(rules.card)})
                            && new RegExp(${JSONObject.quote(SearchPageClassifier.emptyWords.pattern)}, 'i').test(e.textContent.trim());
                        });
                      return JSON.stringify({ready: matches.length > 0 || empty, blocked: blocked,
                        signature: matches.length > 0 ? matches.join('|') : (empty ? 'empty' : ''), loaded: document.readyState === 'complete',
                        html: document.documentElement ? document.documentElement.outerHTML.substring(0, 2000000) : ''});
                    })()
                """.trimIndent()
                val poll = object : Runnable {
                    override fun run() {
                        if (finished.get()) return
                        view?.evaluateJavascript(script) { raw ->
                            if (finished.get()) return@evaluateJavascript
                            val json = runCatching { JSONObject(JSONTokener(raw).nextValue().toString()) }.getOrNull()
                            lastHtml = json?.optString("html").orEmpty()
                            pageLoaded = json?.optBoolean("loaded") == true
                            val ready = readiness.observe(pageLoaded, json?.optBoolean("ready") == true,
                                json?.optBoolean("blocked") == true, json?.optString("signature").orEmpty())
                            if (ready || (mainStatus >= 400 && pageLoaded && lastHtml.isNotBlank())) finish(mainStatus)
                            else handler.postDelayed(this, 400)
                        }
                    }
                }
                view!!.loadUrl(url)
                handler.post(poll)
                handler.postDelayed({ finish(if (pageLoaded && lastHtml.isNotBlank() || mainStatus >= 400) mainStatus else 598) }, timeoutMs)
            } catch (_: Exception) { finish(599, "") }
        }
        try {
            if (!latch.await(timeoutMs + 1500, TimeUnit.MILLISECONDS)) handler.post { finish(598) }
            return response.get() ?: SearchHttpResponse(598, url, "", null)
        } catch (e: InterruptedException) {
            handler.post { finish(499, "") }
            throw e
        } finally { detach() }
    }
}
