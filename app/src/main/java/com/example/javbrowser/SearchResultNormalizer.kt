package com.example.javbrowser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SearchResultNormalizer {
    data class RawItem(val title: String, val detailUrl: String, val coverUrl: String? = null,
                       val code: String? = null, val variantTags: List<String> = emptyList(), val sourceRank: Int = 0)
    fun validUrl(url: String, site: SearchSite): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.username.isEmpty() && parsed.password.isEmpty() && site.isAllowedHost(parsed.host) &&
            parsed.port in listOf(80, 443)
    }
    fun canonicalUrl(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val builder = parsed.newBuilder().fragment(null)
        parsed.queryParameterNames.filter {
            it.equals("fbclid", true) || it.equals("gclid", true) || it.startsWith("utm_", true)
        }.forEach(builder::removeAllQueryParameters)
        return builder.build().toString()
    }
    fun normalize(site: SearchSite, request: SearchRequest, rawItems: List<RawItem>): List<SearchItem> {
        val output = linkedMapOf<String, SearchItem>()
        rawItems.forEach { raw ->
            if (output.size >= SearchPagingPolicy.PAGE_ITEMS || !validUrl(raw.detailUrl, site)) return@forEach
            val canonical = canonicalUrl(raw.detailUrl) ?: return@forEach
            val code = SearchQueryNormalizer.extractCode(raw.code.orEmpty(), raw.title, raw.detailUrl)
            if (request.mode == SearchMode.CODE &&
                (code == null || SearchQueryNormalizer.comparableCode(code) !=
                    SearchQueryNormalizer.comparableCode(request.normalizedQuery))) return@forEach
            val title = raw.title.trim().ifBlank { code.orEmpty() }
            if (title.isBlank()) return@forEach
            output.putIfAbsent(canonical, SearchItem(site.id + ":" + canonical, site.id, title,
                raw.detailUrl, canonical, raw.coverUrl?.takeIf { it.toHttpUrlOrNull() != null }, code,
                raw.variantTags.distinct().take(6),
                if (request.mode == SearchMode.CODE) SearchMatchType.EXACT_CODE else SearchMatchType.KEYWORD,
                raw.sourceRank))
        }
        return output.values.toList()
    }
    fun merge(items: Iterable<SearchItem>): List<SearchItem> = items.distinctBy { it.siteId to it.canonicalUrl }
}
