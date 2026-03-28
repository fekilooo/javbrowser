package com.example.javbrowser

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地 HTTP Proxy Server
 * 用途：為受 CDN 防盜鏈保護的影片（如 avjoy.me）加上正確的 Request Headers，
 *       讓外部播放器（MX Player / VLC）不需要自行帶 headers 就能正常播放。
 *
 * 使用方式：
 *   val proxy = VideoProxyServer()
 *   proxy.start()
 *   val localUrl = proxy.buildProxyUrl(realVideoUrl, referer, cookies)
 *   // 將 localUrl 傳給外部播放器
 */
class VideoProxyServer : NanoHTTPD(0) { // port=0 讓系統自動選空閒 port

    companion object {
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    /**
     * 產生給外部播放器用的本地 URL
     * @param realUrl  真實的 CDN 影片網址
     * @param referer  需要帶的 Referer（例如 https://avjoy.me/）
     * @param cookies  從 WebView 讀到的 Cookie 字串（可空）
     */
    fun buildProxyUrl(realUrl: String, referer: String, cookies: String?): String {
        val encodedUrl = java.net.URLEncoder.encode(realUrl, "UTF-8")
        val encodedReferer = java.net.URLEncoder.encode(referer, "UTF-8")
        val encodedCookies = java.net.URLEncoder.encode(cookies ?: "", "UTF-8")
        return "http://127.0.0.1:$listeningPort/proxy?url=$encodedUrl&referer=$encodedReferer&cookies=$encodedCookies"
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.uri != "/proxy") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }

            val params = session.parameters
            val realUrl = params["url"]?.firstOrNull()
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
            val referer = params["referer"]?.firstOrNull()
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
            val cookies = params["cookies"]?.firstOrNull()
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""

            // 取得外部播放器送來的 Range header（支援 seek）
            val rangeHeader = session.headers["range"]

            proxyRequest(realUrl, referer, cookies, rangeHeader)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: ${e.message}")
        }
    }

    private fun proxyRequest(
        realUrl: String,
        referer: String,
        cookies: String,
        rangeHeader: String?
    ): Response {
        val connection = URL(realUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true

            // 加上所有必要的 headers，模擬瀏覽器行為
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (referer.isNotEmpty()) {
                connection.setRequestProperty("Referer", referer)
            }
            if (cookies.isNotEmpty()) {
                connection.setRequestProperty("Cookie", cookies)
            }
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity;q=1, *;q=0")
            connection.setRequestProperty("Sec-Fetch-Dest", "video")
            connection.setRequestProperty("Sec-Fetch-Mode", "no-cors")
            connection.setRequestProperty("Sec-Fetch-Site", "same-site")

            // 將播放器的 Range header 透傳（讓 seek 正常運作）
            if (!rangeHeader.isNullOrEmpty()) {
                connection.setRequestProperty("Range", rangeHeader)
            }

            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: "video/mp4"
            val contentLength = connection.contentLengthLong
            val contentRange = connection.getHeaderField("Content-Range")
            val acceptRanges = connection.getHeaderField("Accept-Ranges")

            val inputStream: InputStream = if (responseCode >= 400) {
                connection.errorStream ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "CDN error $responseCode"
                )
            } else {
                connection.inputStream
            }

            val status = when (responseCode) {
                206 -> Response.Status.PARTIAL_CONTENT
                200 -> Response.Status.OK
                else -> Response.Status.lookup(responseCode) ?: Response.Status.INTERNAL_ERROR
            }

            val response = if (contentLength > 0) {
                newFixedLengthResponse(status, contentType, inputStream, contentLength)
            } else {
                newChunkedResponse(status, contentType, inputStream)
            }

            if (!contentRange.isNullOrEmpty()) {
                response.addHeader("Content-Range", contentRange)
            }
            if (!acceptRanges.isNullOrEmpty()) {
                response.addHeader("Accept-Ranges", acceptRanges)
            }

            return response
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }
}
