package com.example.javbrowser

import java.util.regex.Pattern

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
        // Pattern: eval(function(p,a,c,k,e,d)...)
        // We need to find the packed code and unpack it.
        // This is a simplified unpacker for the specific format described.
        
        // 1. Find the eval block
        val evalPattern = Pattern.compile("eval\\(function\\(p,a,c,k,e,d\\)\\{.*\\}\\('([^']+)',(\\d+),(\\d+),'([^']+)'\\.split\\('\\|'\\)")
        val matcher = evalPattern.matcher(html)
        
        if (matcher.find()) {
            val payload = matcher.group(1) ?: ""
            val radix = matcher.group(2).toInt()
            val count = matcher.group(3).toInt()
            val dictString = matcher.group(4)
            val dict = dictString.split("|")

            // 2. Unpack
            // The logic described: replace Base36 words in payload with dict words.
            // Since we don't have a full JS engine, we'll try a heuristic replacement.
            // The payload looks like: f='8://7.6/...'
            // We need to replace words like 'e', 'c', 'a' with dict[14], dict[12], dict[10] etc.
            
            // Regex to find all alphanumeric words in payload that match the radix encoding
            // But Dean Edwards packer matches \b\w+\b usually.
            
            // Let's implement a simple replacer.
            // Iterate through the dictionary. If dict[i] is empty, it means the token is the index itself (in base 36), 
            // but usually the packer fills all slots or uses empty for "no replacement".
            // Actually, the packer logic is: if (dict[i]) replace regex /\\b(base36(i))\\b/ with dict[i]
            
            var unpacked = payload
            
            // We need to handle the replacement order carefully or use a single pass.
            // But for this specific case, let's try replacing from largest index to smallest to avoid sub-match issues?
            // Or just use word boundaries.
            
            // The JS code usually iterates backwards: while(c--) ...
            // So we should also iterate from largest index to smallest to avoid replacing substrings of larger keys if they overlap (though \b helps).
            
            for (i in dict.indices.reversed()) {
                val word = dict[i]
                if (word.isNotEmpty()) {
                    val key = i.toString(radix)
                    // Replace \bkey\b with word
                    val regex = "\\b$key\\b"
                    unpacked = unpacked.replace(Regex(regex), word)
                }
            }
            
            // 3. Extract URL from unpacked code
            // Look for source='...' or similar
            // The user example: source='https://...'
            // Or f='...' as in the example.
            
            // Let's look for .m3u8
            val urlPattern = Pattern.compile("['\"](https?://[^'\"]+\\.m3u8)['\"]")
            val urlMatcher = urlPattern.matcher(unpacked)
            if (urlMatcher.find()) {
                return urlMatcher.group(1)
            }
        }
        
        // Fallback: Check for thumbnail UUID if the above fails (Heuristic)
        // urls: ["https:\/\/nineyu.com\/UUID\/seek\/_0.jpg"...]
        val uuidPattern = Pattern.compile("urls:\\s*\\[\"https:\\\\/\\\\/[^/]+\\\\/([a-f0-9\\-]+)\\\\/seek")
        val uuidMatcher = uuidPattern.matcher(html)
        if (uuidMatcher.find()) {
            val uuid = uuidMatcher.group(1)
            // Construct URL: https://surrit.com/{UUID}/playlist.m3u8
            // Note: Domain might change, this is risky.
            return "https://surrit.com/$uuid/playlist.m3u8"
        }

        return null
    }

    fun extractRouVideo(html: String): String? {
        // Look for <video> tag with src attribute containing .m3u8
        // Pattern: <video ... src="URL.m3u8?..."
        val pattern = Pattern.compile("<video[^>]+src=\"([^\"]+\\.m3u8[^\"]*)\"", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            var url = matcher.group(1)
            // Decode HTML entities like &amp; to &
            if (url != null) {
                url = url.replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                return url
            }
        }
        return null
    }
}
