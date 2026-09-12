package com.example.javbrowser

import android.webkit.CookieManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Locale

data class SearchHttpResponse(val statusCode: Int, val finalUrl: String, val body: String,
                              val contentType: String?, val retryAfterUntil: Long = 0L,
                              val transportError: String? = null,
                              val cfMitigated: String? = null,
                              val cloudflareServed: Boolean = false,
                              val rendered: Boolean = false)

class SearchHttpFetcher {
    fun fetch(url: String, cancellation: SearchCancellation, site: SearchSite): SearchHttpResponse {
        val client = baseClient.newBuilder().addNetworkInterceptor { chain ->
            val target = chain.request().url
            if (!site.isAllowedHost(target.host)) throw SearchTransportException("UNEXPECTED_REDIRECT_HOST")
            val cookies = CookieManager.getInstance().getCookie(target.toString())
            val request = chain.request().newBuilder().removeHeader("Cookie")
            if (!cookies.isNullOrBlank()) request.header("Cookie", cookies)
            val response = chain.proceed(request.build())
            response.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(target.toString(), it) }
            response
        }.build()
        cancellation.check()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
        if (site.parserKind == SearchParserKind.JABLE && url.contains("function=get_block"))
            request.header("X-Requested-With", "XMLHttpRequest")
        val call = client.newCall(request.build())
        val detach = cancellation.onCancel { call.cancel() }
        try {
            call.execute().use { response ->
                val source = response.body?.source()
                source?.request(MAX_BODY + 1)
                if ((source?.buffer?.size ?: 0L) > MAX_BODY) throw SearchTransportException("RESPONSE_TOO_LARGE")
                val charset = response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                return SearchHttpResponse(response.code, response.request.url.toString(),
                    source?.readByteArray()?.toString(charset).orEmpty(), response.header("Content-Type"),
                    retryAfter(response.header("Retry-After"), System.currentTimeMillis()),
                    cfMitigated = response.header("cf-mitigated"),
                    cloudflareServed = response.header("Server").orEmpty().contains("cloudflare", true) || response.header("cf-ray") != null)
            }
        } finally { detach() }
    }
    companion object {
        private const val MAX_BODY = 2L * 1024 * 1024
        private val baseClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        fun retryAfter(value: String?, now: Long): Long {
            if (value == null) return 0L
            value.trim().toLongOrNull()?.let {
                return now + it.coerceIn(0, (Long.MAX_VALUE - now) / 1000) * 1000
            }
            return runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
            }.getOrDefault(0L).coerceAtLeast(now)
        }
    }
}
