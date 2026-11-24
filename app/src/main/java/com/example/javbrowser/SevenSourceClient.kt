package com.example.javbrowser

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Anonymous catalog access. No shared third-party login or cross-host cookies. */
class SevenSourceClient(private val transport: OkHttpClient = http,
    private val apiRoots: List<String> = SevenSourceProtocol.hosts.map { "https://$it" }) {
    data class Page(val videos: List<SevenSourceProtocol.Video>, val hasMore: Boolean)
    data class Detail(val video: SevenSourceProtocol.Video, val mediaUrl: String?)
    private data class Cached(val at: Long, val page: Page)
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
        private val workingHost = ConcurrentHashMap<String, String>()
        private val cache = ConcurrentHashMap<String, Cached>()
    }

    private fun call(source: SevenSourceProtocol.Source, path: String, extra: JSONObject,
        canceled: SearchCancellation, deadline: Long): Any {
        val body = SevenSourceProtocol.body(source, path, extra).toString()
        val candidates = (listOfNotNull(workingHost[source.id]?.takeIf { it in apiRoots }) + apiRoots).distinct()
        var failure: IOException = IOException("來源暫時無法連線 / Source unavailable")
        for (host in candidates) {
            canceled.check()
            if (System.nanoTime() >= deadline) throw IOException("來源回應逾時 / Source timed out")
            val call = transport.newCall(Request.Builder().url("$host/js")
                .header("User-Agent", SevenSourceProtocol.USER_AGENT).header("Origin", source.origin)
                .header("Referer", source.origin + "/").header("Accept", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
            call.timeout().timeout(minOf(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1), 10_000), TimeUnit.MILLISECONDS)
            val detach = canceled.onCancel { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("${source.name} HTTP ${response.code}")
                    val input = response.body ?: throw IOException("來源回應為空 / Empty response")
                    val bytes = input.byteStream().use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            canceled.check()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > 8_000_000) throw IOException("來源資料過大 / Response too large")
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    val result = SevenSourceProtocol.unwrap(String(bytes, Charsets.UTF_8))
                    canceled.check()
                    workingHost[source.id] = host
                    return result
                }
            } catch (error: IOException) {
                canceled.check()
                // Report server/challenge/decryption failures without response bodies or signed URLs.
                failure = error
            } finally { detach() }
        }
        throw failure
    }

    fun page(source: SevenSourceProtocol.Source, category: String, page: Int, query: String,
        canceled: SearchCancellation): Page {
        canceled.check()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(28)
        val results = ChineseSearchVariants.variants(query).map { keyword ->
            singlePage(source, category, page, keyword, canceled, deadline)
        }
        canceled.check()
        return Page(results.flatMap { it.videos }.distinctBy { it.stableId }, results.any { it.hasMore })
    }

    private fun singlePage(source: SevenSourceProtocol.Source, category: String, page: Int, query: String,
        canceled: SearchCancellation, deadline: Long): Page {
        canceled.check()
        val key = listOf(apiRoots.joinToString(), source.id, category, page.toString(), query).joinToString("\u0000")
        cache[key]?.takeIf { System.nanoTime() - it.at < TimeUnit.MINUTES.toNanos(1) }?.let { return it.page }
        val request = SevenSourceProtocol.catalogRequest(source, category, page, query)
        val rows = SevenSourceProtocol.videos(source, call(source, request.first, request.second, canceled, deadline))
        val result = Page(rows, if (source.id == "xchina") rows.isNotEmpty() else rows.size >= 20)
        if (cache.size > 80) cache.clear()
        cache[key] = Cached(System.nanoTime(), result)
        return result
    }

    /** Resolve again for every playback attempt; signed media URLs are never persisted. */
    fun detail(source: SevenSourceProtocol.Source, id: String, server: String?, canceled: SearchCancellation): Detail {
        val validId = SevenSourceProtocol.validId(id)
        val lines = if (source.id == "xchina") listOf("") else
            server?.takeIf { it in listOf("hd1", "hd2", "hd3", "default1") }?.let(::listOf)
                ?: listOf("hd1", "hd2", "hd3", "default1")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        var last: Detail? = null
        var failure: IOException? = null
        for (line in lines) {
            canceled.check()
            try {
                val extra = if (source.id == "xchina") JSONObject().put("url_search", "") else JSONObject().put("server", line)
                val row = call(source, "/sevenVideos/$validId", extra, canceled, deadline) as? JSONObject
                    ?: throw IOException("來源缺少影片詳情 / Missing video detail")
                val video = SevenSourceProtocol.video(source, row)
                if (video.id != validId) throw IOException("來源影片 ID 不符 / Unexpected video ID")
                last = Detail(video, SevenSourceProtocol.playlist(row, source))
                if (last.mediaUrl != null) return last
            } catch (error: IOException) { failure = error }
        }
        canceled.check()
        return last ?: throw (failure ?: IOException("無影片詳情 / No video detail"))
    }
}
