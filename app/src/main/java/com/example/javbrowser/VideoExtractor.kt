package com.example.javbrowser

import java.util.regex.Pattern

data class MissAvThumbnailConfig(
    val picNum: Int,
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val urlTemplate: String
)

object VideoExtractor {

    fun extractJable(html: String): String? {
        // Pattern: var hlsUrl = 'URL'
        val pattern = Pattern.compile("var\\s+hlsUrl\\s*=\\s*'([^']+)'")
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    fun extractMissAV(html: String): String? {
        // 先嘗試從 packer 直接解出最高畫質 URL
        try {
            val packed = extractFromMissavPacker(html)
            if (packed != null) return packed
        } catch (e: Exception) {
            // packer 解析失敗，繼續 fallback
        }

        // Fallback：從縮圖 seek URL 取 UUID，拼出 surrit.com master playlist
        // urls: ["https:\/\/nineyu.com\/{UUID}\/seek\/_0.jpg"...]
        val uuidMatcher = Pattern.compile(
            "urls:\\s*\\[\"https:\\\\/\\\\/[^/]+\\\\/([a-f0-9\\-]+)\\\\/seek"
        ).matcher(html)
        if (uuidMatcher.find()) {
            return "https://surrit.com/${uuidMatcher.group(1)}/playlist.m3u8"
        }

        return null
    }

    fun extractJableVttUrl(html: String): String? {
        val normalized = html.replace("\\/", "/")
        val match = Regex("(?:var\\s+)?vttUrl\\s*=\\s*['\"](https?://[^'\"]+)['\"]")
            .find(normalized) ?: return null
        return match.groupValues[1].takeIf { it.contains("thumbvtt", ignoreCase = true) }
    }

    fun extractMissAvThumbnailConfig(html: String): MissAvThumbnailConfig? {
        val normalized = html.replace("\\/", "/")
        val startMatch = Regex("thumbnail\\s*:\\s*\\{").find(normalized) ?: return null
        val endMatch = Regex("keyboard\\s*:").find(normalized, startMatch.range.last + 1)
            ?: return null
        val block = normalized.substring(startMatch.range.first, endMatch.range.first)

        fun readInt(name: String): Int? = Regex("$name\\s*:\\s*(\\d+)")
            .find(block)?.groupValues?.get(1)?.toIntOrNull()

        val picNum = readInt("pic_num") ?: return null
        val width = readInt("width") ?: return null
        val height = readInt("height") ?: return null
        val columns = readInt("col") ?: return null
        val rows = readInt("row") ?: return null
        val firstUrl = Regex("https?://[^\\\"']+/seek/_0\\.jpg(?:\\?[^\\\"']*)?")
            .find(block)?.value ?: return null
        val template = firstUrl.replaceFirst("_0.jpg", "_{index}.jpg")

        if (picNum <= 0 || width <= 0 || height <= 0 || columns <= 0 || rows <= 0) return null
        return MissAvThumbnailConfig(picNum, width, height, columns, rows, template)
    }

    /**
     * 用字串搜尋（不用大範圍 regex）定位 packer 的 dict 與 payload，
     * 避免在超大 HTML 上發生 catastrophic backtracking。
     *
     * packer 格式：eval(function(p,a,c,k,e,d){...}('PAYLOAD',RADIX,COUNT,'DICT'.split('|'),0,{}))
     * dict 必定含有 'surrit'（CDN 域名），以此定位正確的 eval 區塊。
     */
    private fun extractFromMissavPacker(html: String): String? {
        // 1. 找到含有 surrit 的 dict 字串位置
        val surritIdx = html.indexOf("|surrit|")
        if (surritIdx < 0) return null

        val splitTag = "'.split('|')"
        val dictEnd = html.indexOf(splitTag, surritIdx)
        if (dictEnd < 0) return null

        val dictStart = html.lastIndexOf("'", surritIdx - 1)
        if (dictStart < 0 || dictStart >= dictEnd) return null

        val dictString = html.substring(dictStart + 1, dictEnd)
        if (!dictString.contains("m3u8") || !dictString.contains("source")) return null
        val dict = dictString.split("|")

        // 2. 在 dict 之前找 radix 和 payload
        // 結構：...'PAYLOAD',RADIX,COUNT,'DICT'...
        // dictStart 之前的部分：...'PAYLOAD',RADIX,COUNT,
        val beforeDict = html.substring(0, dictStart)

        // 最後一個逗號前是 COUNT，再往前一個逗號前是 RADIX
        val countComma = beforeDict.lastIndexOf(',')
        if (countComma < 0) return null
        val radixComma = beforeDict.lastIndexOf(',', countComma - 1)
        if (radixComma < 0) return null

        val radix = beforeDict.substring(radixComma + 1, countComma).trim().toIntOrNull() ?: return null

        // 3. 找 payload 結束引號（radix 逗號前的那個 '）
        val payloadEndQuote = beforeDict.lastIndexOf('\'', radixComma - 1)
        if (payloadEndQuote < 0) return null

        // 4. 反向找 payload 開始引號（跳過 \' 跳脫）
        var i = payloadEndQuote - 1
        while (i >= 0) {
            if (html[i] == '\'') {
                var bs = 0
                var j = i - 1
                while (j >= 0 && html[j] == '\\') { bs++; j-- }
                if (bs % 2 == 0) break  // 未跳脫的引號
            }
            i--
        }
        if (i < 0) return null

        val payload = html.substring(i + 1, payloadEndQuote).replace("\\'", "'")

        // 5. 解包：把 base(radix) token 換回字典詞
        var unpacked = payload
        for (idx in dict.indices.reversed()) {
            val word = dict[idx]
            if (word.isNotEmpty()) {
                val key = toBaseN(idx, radix)
                unpacked = unpacked.replace(Regex("\\b${Regex.escape(key)}\\b"), word)
            }
        }

        // 6. 依畫質優先：1080p → 720p → 任意 m3u8
        Regex("""source1280\s*=\s*['"]([^'"]+\.m3u8)['"]""")
            .find(unpacked)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { return it }

        Regex("""source842\s*=\s*['"]([^'"]+\.m3u8)['"]""")
            .find(unpacked)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { return it }

        Pattern.compile("['\"](https?://[^'\"]+\\.m3u8)['\"]").matcher(unpacked).let {
            if (it.find()) return it.group(1)
        }

        return null
    }

    fun extractRouVideo(html: String, pageUrl: String? = null): String? {
        try {
            // New logic: Check for "ev" object with "d" and "k"
            val evBlockPattern = Pattern.compile("\"ev\"\\s*:\\s*\\{([^}]+)\\}")
            val evBlockMatcher = evBlockPattern.matcher(html)
            
            if (evBlockMatcher.find()) {
                val block = evBlockMatcher.group(1) ?: ""
                val dMatcher = Pattern.compile("\"d\"\\s*:\\s*\"([^\"]+)\"").matcher(block)
                val kMatcher = Pattern.compile("\"k\"\\s*:\\s*(\\d+)").matcher(block)
                
                if (dMatcher.find() && kMatcher.find()) {
                    val dBase64 = dMatcher.group(1)
                    val kStr = kMatcher.group(1)
                    val k = kStr.toInt()
                    
                    // Decode base64
                    val decodedBytes = android.util.Base64.decode(dBase64, android.util.Base64.DEFAULT)
                    // Shift characters
                    val builder = java.lang.StringBuilder()
                    for (byte in decodedBytes) {
                        val shifted = (byte.toInt() and 0xFF) - k
                        builder.append(shifted.toChar())
                    }
                    val decryptedJson = builder.toString()
                    
                    // Extract videoUrl
                    val urlPattern = Pattern.compile("\"videoUrl\"\\s*:\\s*\"([^\"]+)\"")
                    val urlMatcher = urlPattern.matcher(decryptedJson)
                    if (urlMatcher.find()) {
                        var videoUrl = urlMatcher.group(1)
                        if (videoUrl != null) {
                            return normalizeRouVideoUrl(videoUrl, pageUrl)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to old method: Look for <video> tag with src attribute containing .m3u8
        val pattern = Pattern.compile("<video[^>]+src=[\"']([^\"']+\\.m3u8[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            var url = matcher.group(1)
            // Decode HTML entities like &amp; to &
            if (url != null) {
                url = url.replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                return normalizeRouVideoUrl(url, pageUrl)
            }
        }
        return null
    }
    fun extractAvJoy(html: String): String? {
        // Find all <source> tags and pick the one with the highest res value
        val sourceTagPattern = Pattern.compile("<source([^>]+)>", Pattern.CASE_INSENSITIVE)
        val srcPattern = Pattern.compile("src=[\"']([^\"']+\\.mp4[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val resPattern = Pattern.compile("res=[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE)

        val tagMatcher = sourceTagPattern.matcher(html)
        var bestUrl: String? = null
        var bestRes = -1

        while (tagMatcher.find()) {
            val attrs = tagMatcher.group(1) ?: continue
            val srcMatcher = srcPattern.matcher(attrs)
            if (srcMatcher.find()) {
                val url = srcMatcher.group(1) ?: continue
                val resMatcher = resPattern.matcher(attrs)
                val res = if (resMatcher.find()) resMatcher.group(1)?.toIntOrNull() ?: 0 else 0
                if (res > bestRes) {
                    bestRes = res
                    bestUrl = url
                }
            }
        }

        if (bestUrl != null) return bestUrl

        // Fallback: <video src="...mp4">
        val videoPattern = Pattern.compile(
            "<video[^>]+src=[\"']([^\"']+\\.mp4[^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE
        )
        val videoMatcher = videoPattern.matcher(html)
        if (videoMatcher.find()) {
            return videoMatcher.group(1)
        }

        return null
    }

    private fun normalizeRouVideoUrl(value: String, pageUrl: String?): String {
        val normalized = value.replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("index.jpg", "index.m3u8")
            .replace("index.png", "index.m3u8")
        if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) {
            return normalized
        }
        if (normalized.startsWith("//")) return "https:$normalized"
        return runCatching {
            val base = java.net.URI(pageUrl.orEmpty())
            base.resolve(normalized).toString()
        }.getOrNull().takeUnless { it.isNullOrBlank() } ?: normalized
    }

    fun extractPigAV(html: String): String? {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")

        Regex("""https?://[^"'\\s<>]+\.m3u8[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(0)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        Regex("""https?://[^"'\\s<>]+\.mp4[^"'\\s<>]*""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(0)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return null
    }

    /** 支援任意進位（最高 62）的整數轉字串，對應 packer 的 alphabet：0-9a-zA-Z */
    private fun toBaseN(n: Int, radix: Int): String {
        if (radix <= 36) return n.toString(radix)
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (n == 0) return "0"
        var num = n
        val sb = StringBuilder()
        while (num > 0) {
            sb.append(alphabet[num % radix])
            num /= radix
        }
        return sb.reverse().toString()
    }

    fun fetchBestQualityUrl(masterUrl: String, callback: (String) -> Unit) {
        kotlin.concurrent.thread {
            try {
                val connection = java.net.URL(masterUrl).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                if (connection.responseCode == 200) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }
                    val lines = content.lines()
                    
                    var bestUrl: String? = null
                    var maxResolution = 0
                    
                    for (i in lines.indices) {
                        val line = lines[i]
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val resMatcher = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(line)
                            if (resMatcher.find()) {
                                val width = resMatcher.group(1)?.toInt() ?: 0
                                val height = resMatcher.group(2)?.toInt() ?: 0
                                val pixelCount = width * height
                                
                                if (pixelCount > maxResolution) {
                                    maxResolution = pixelCount
                                    if (i + 1 < lines.size) {
                                        var nextLine = lines[i + 1].trim()
                                        if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                                            // Handle URL construction with URI to support relative paths and query params
                                            try {
                                                val masterUri = java.net.URI(masterUrl)
                                                // Resolve relative path correctly
                                                val nextUri = masterUri.resolve(nextLine)
                                                
                                                // If the new URL doesn't have query params, but the master did, inherit them
                                                // This is a heuristic for sites that use token authentication in the URL
                                                if (masterUri.rawQuery != null && nextUri.rawQuery == null) {
                                                    bestUrl = nextUri.toString() + "?" + masterUri.rawQuery
                                                } else {
                                                    bestUrl = nextUri.toString()
                                                }
                                            } catch (e: Exception) {
                                                // Fallback to simple string concatenation if URI parsing fails
                                                if (!nextLine.startsWith("http")) {
                                                    val baseUrl = masterUrl.substringBeforeLast("/") + "/"
                                                    bestUrl = baseUrl + nextLine
                                                } else {
                                                    bestUrl = nextLine
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    val finalUrl = bestUrl ?: masterUrl
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback(finalUrl)
                    }
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback(masterUrl)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(masterUrl)
                }
            }
        }
    }
}
