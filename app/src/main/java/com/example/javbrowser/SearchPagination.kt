package com.example.javbrowser

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

enum class SearchPageState {
    UNKNOWN, HAS_NEXT, END_REPORTED, NEXT_NOT_FOUND, UNSUPPORTED,
    AUTO_LIMIT, PAGE_LIMIT, ITEM_LIMIT, PAGE_ITEM_LIMIT, REPEATED_PAGE
}

/** Only follow observed pagination controls; never guess a next URL from the search term. */
object SearchPagination {
    data class Detection(val next: String?, val state: SearchPageState)
    private const val CONTAINERS = ".pagination, .pagination-block, .paging, .pager, .page-links, nav"
    private val counters = setOf("page", "p", "paged", "from", "from_videos", "from_albums", "start", "offset")
    private val transport = setOf("mode", "function", "block_id", "_")
    private val nextWords = Regex("^(?:next(?: page)?|下一[頁页]|下[頁页]|次へ|次のページ)?\\s*[›»→>]*$", RegexOption.IGNORE_CASE)
    private fun disabled(node: Element) = node.hasAttr("disabled") || node.attr("aria-disabled") == "true" ||
        node.closest(".disabled, [aria-disabled=true]") != null
    private fun isNext(node: Element): Boolean = node.attr("rel").split(Regex("\\s+")).any { it.equals("next", true) } ||
        node.hasClass("next") || node.hasClass("pagination-next") || node.parent()?.hasClass("next") == true ||
        listOf(node.attr("aria-label"), node.attr("title"), node.text()).any { it.isNotBlank() && nextWords.matches(it.trim()) }

    fun detect(document: Document, currentUrl: String, site: SearchSite): Detection {
        val current = currentUrl.toHttpUrlOrNull() ?: return Detection(null, SearchPageState.NEXT_NOT_FOUND)
        val controls = document.select("a[href], link[rel=next], button, [data-parameters], [data-next-url], .next, [aria-disabled=true]")
            .filter { node -> node.tagName() == "link" || SearchPageClassifier.visible(node) }
        val candidates = mutableListOf<Pair<Int, String>>()
        var unsupported = false
        var explicitEnd = false
        controls.forEach { node ->
            val explicit = isNext(node)
            val inPager = node.closest(CONTAINERS) != null || node.hasAttr("data-parameters")
            val numbered = inPager && node.text().trim().toIntOrNull() != null
            if (!explicit && !numbered && !node.hasAttr("data-next-url")) return@forEach
            if (disabled(node)) { if (explicit) explicitEnd = true; return@forEach }
            val raw = node.absUrl("href").takeIf { it.isNotBlank() }
                ?: node.absUrl("data-next-url").takeIf { it.isNotBlank() }
            val ajax = if (site.parserKind == SearchParserKind.JABLE) jableAjax(node, current) else null
            val urls = listOfNotNull(ajax, raw)
            val valid = urls.firstOrNull { advances(current, it, site, explicit) }
            if (valid != null) candidates.add((if (explicit) 0 else pageNumber(valid.toHttpUrlOrNull()!!).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) to valid)
            else if (explicit && (node.tagName() == "button" || node.hasAttr("data-parameters") || raw == null || raw.contains("#"))) unsupported = true
        }
        val next = candidates.minByOrNull { it.first }?.second
        return when {
            next != null -> Detection(next, SearchPageState.HAS_NEXT)
            unsupported -> Detection(null, SearchPageState.UNSUPPORTED)
            explicitEnd -> Detection(null, SearchPageState.END_REPORTED)
            else -> Detection(null, SearchPageState.NEXT_NOT_FOUND)
        }
    }

    private fun jableAjax(node: Element, current: HttpUrl): String? {
        if (disabled(node)) return null
        val values = node.attr("data-parameters").split(';').mapNotNull {
            val pieces = it.split(':', limit = 2)
            if (pieces.size == 2) pieces[0].trim() to pieces[1].trim() else null
        }.toMap()
        val page = values.entries.firstOrNull { it.key in counters && it.value.toLongOrNull() != null } ?: return null
        val block = node.attr("data-block-id").ifBlank {
            node.closest("[data-block-id]")?.attr("data-block-id").orEmpty()
        }.ifBlank { node.attr("data-container-id") }.ifBlank { current.queryParameter("block_id").orEmpty() }
        if (!Regex("[a-zA-Z0-9_]{1,100}").matches(block)) return null
        // KVS block controls expose a page counter and block ID, not a usable href.
        return current.newBuilder().setQueryParameter("mode", "async")
            .setQueryParameter("function", "get_block").setQueryParameter("block_id", block)
            .setQueryParameter(page.key, page.value).build().toString()
    }

    private fun route(url: HttpUrl): String = url.encodedPath
        .replace(Regex("/page/\\d+/?$"), "/")
        .replace(Regex("(/search/[^/]+)/\\d+/?$"), "$1/")
        .replace(Regex("/\\d+\\.html$"), "/PAGE.html").trimEnd('/')

    private fun pathPage(url: HttpUrl): Long? = Regex("/(?:page/(\\d+)|(\\d+)\\.html|search/[^/]+/(\\d+))/?$")
        .find(url.encodedPath)?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toLongOrNull() }

    private fun pageNumber(url: HttpUrl): Long = counters.asSequence().mapNotNull { url.queryParameter(it)?.toLongOrNull() }
        .firstOrNull() ?: pathPage(url) ?: 1L

    fun advances(current: HttpUrl, rawNext: String, site: SearchSite, explicit: Boolean): Boolean {
        if (!SearchResultNormalizer.validUrl(rawNext, site)) return false
        val next = rawNext.toHttpUrlOrNull() ?: return false
        if (current.host != next.host || route(current) != route(next)) return false
        if ((current.queryParameterNames + next.queryParameterNames).filter { it !in counters && it !in transport && it != "cursor" }
                .any { current.queryParameterValues(it) != next.queryParameterValues(it) }) return false
        // A new block/transport setting alone is never evidence of another results page.
        val changed = counters.filter { current.queryParameterValues(it) != next.queryParameterValues(it) }
        if (changed.isNotEmpty()) return changed.all { key ->
            val before = current.queryParameter(key)?.toLongOrNull() ?: if (key in setOf("start", "offset")) 0L else 1L
            val after = next.queryParameter(key)?.toLongOrNull() ?: return@all false
            after > before
        }
        if (pathPage(next) != pathPage(current)) return (pathPage(next) ?: 0) > (pathPage(current) ?: 1)
        return explicit && !next.queryParameter("cursor").isNullOrBlank() && next.queryParameter("cursor") != current.queryParameter("cursor")
    }

    fun pageKey(url: String): String {
        val parsed = SearchResultNormalizer.canonicalUrl(url)?.toHttpUrlOrNull() ?: return url
        val builder = parsed.newBuilder().fragment(null)
        parsed.queryParameterNames.forEach(builder::removeAllQueryParameters)
        parsed.queryParameterNames.filter { it != "_" }.sorted().forEach { key ->
            parsed.queryParameterValues(key).forEach { builder.addQueryParameter(key, it) }
        }
        return builder.build().toString()
    }
}
