package com.example.javbrowser

data class SearchResultGroup(
    val stableId: String,
    val code: String?,
    val title: String,
    val coverUrl: String?,
    val links: List<SearchItem>
)

/** Presentation only: repository results, paging and per-site diagnostics stay intact. */
object SearchResultGrouping {
    fun group(items: List<SearchItem>): List<SearchResultGroup> {
        val buckets = linkedMapOf<String, MutableList<SearchItem>>()
        val seenLinks = mutableMapOf<String, MutableSet<String>>()
        val codes = mutableMapOf<String, String?>()
        items.forEach { item ->
            // Do not infer identity from the query, partial title matches or a missing code.
            val code = item.code?.let { SearchQueryNormalizer.normalize(it).code }
            val key = code?.let { "code:" + SearchQueryNormalizer.comparableCode(it) }
                ?: "url:" + item.siteId + ":" + item.canonicalUrl
            codes[key] = code
            val bucket = buckets.getOrPut(key) { mutableListOf() }
            val links = seenLinks.getOrPut(key) { hashSetOf() }
            if (links.add(item.siteId + "\u0000" + item.canonicalUrl)) bucket.add(item)
        }
        return buckets.map { (key, links) ->
            SearchResultGroup(key, codes[key], links.first().title,
                links.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) }, links.toList())
        }
    }

    fun linkLabel(group: SearchResultGroup, item: SearchItem, siteName: String, english: Boolean): String {
        val siblings = group.links.filter { it.siteId == item.siteId }
        val ordinal = if (siblings.size > 1) " #" + (siblings.indexOf(item) + 1) else ""
        val tags = item.variantTags.distinct().joinToString(" / ") {
            if (!english) it else when (it) { "中文字幕" -> "Subtitles"; "無碼" -> "Uncensored"; else -> it }
        }
        return siteName + ordinal + if (tags.isBlank()) "" else " · " + tags
    }
}
