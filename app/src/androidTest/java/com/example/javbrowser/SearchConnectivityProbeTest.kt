package com.example.javbrowser

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Connectivity diagnosis only: HEAD /, no search request, content, credentials or response body. */
@RunWith(AndroidJUnit4::class)
class SearchConnectivityProbeTest {
    @Test fun diagnoseNamedSitesUsingHeadersOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS).followRedirects(false)
            .retryOnConnectionFailure(false).build()
        SearchSiteRegistry.sites(context).filter { it.id in setOf("avjoy", "pigav", "javhd", "avple", "whos") }.forEach { site ->
            val host = site.allowedHosts.first()
            val started = System.nanoTime()
            val detail = try {
                client.newCall(Request.Builder().url("https://$host/").head()
                    .header("User-Agent", SearchHttpFetcher.USER_AGENT).build()).execute().use {
                    "http=" + it.code + " redirectHost=" +
                        (it.header("Location")?.let { next -> it.request.url.resolve(next)?.host } ?: "none")
                }
            } catch (e: Exception) { "code=" + SearchFailure.code(e) }
            Log.i("SearchConnectivity", "site=" + site.id + " host=" + host + " " + detail +
                " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        }
    }
}
