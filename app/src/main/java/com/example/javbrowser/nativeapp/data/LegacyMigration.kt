package com.example.javbrowser.nativeapp.data

import com.example.javbrowser.nativeapp.domain.*
import org.json.JSONArray

object LegacyMigration {
    fun favorites(json: String): List<JavTitle> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val old = array.getJSONObject(i)
            val url = old.optString("url")
            val code = old.optString("javCode").ifBlank { JavIdentity.extract("${old.optString("title")} $url") }
            val source = SourceUrlDetector.detect(url)
            val ref = SourceRef(source ?: "legacy", url.substringAfterLast('/').ifBlank { "record-$i" }, url.ifBlank { null })
            JavTitle(JavIdentity.identityFor(code, ref), code, old.optString("title").ifBlank { code ?: "Legacy item" },
                coverUrl = old.optString("thumbnailUrl").ifBlank { null }, sourceRefs = listOf(ref))
        }
    }.getOrDefault(emptyList())
}

object SourceUrlDetector {
    fun detect(url: String): String? = when {
        url.contains("javdb.com", true) -> "javdb"
        url.contains("missav", true) -> "missav"
        url.contains("jable.tv", true) -> "jable"
        url.contains("javtrailers.com", true) -> "javtrailers"
        url.contains("pigav", true) -> "pigav"
        url.contains("avtoday", true) -> "avtoday"
        url.contains("javhdporn", true) -> "javhdporn"
        else -> null
    }
}

object CachePolicy { fun isFresh(storedAt: Long, now: Long, ttlMillis: Long) = storedAt <= now && now - storedAt < ttlMillis }
