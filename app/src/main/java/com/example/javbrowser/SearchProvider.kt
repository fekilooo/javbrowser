package com.example.javbrowser

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

interface SearchProvider {
    val site: SearchSite
    /** Worker-only; attempt ID is supplied by the session and cancellation owns the real resources. */
    fun search(request: SearchRequest, pageToken: String? = null,
               attemptId: String = java.util.UUID.randomUUID().toString(),
               cancellation: SearchCancellation = SearchCancellation()): SiteSearchResult
}

data class SearchParserRules(val links: String, val card: String, val detailPath: Regex,
                             val empty: String, val browserFallback: Boolean = false)

/** Shared mechanics only. Each site owns its selectors and accepted detail paths in its own file. */
open class SiteHtmlSearchProvider(override val site: SearchSite,
                                 private val rules: SearchParserRules,
                                 private val fetchHtml: (String, SearchCancellation) -> SearchHttpResponse,
                                 private val renderHtml: (String, SearchCancellation) -> SearchHttpResponse) : SearchProvider {
    constructor(site: SearchSite, context: Context, rules: SearchParserRules) : this(site, rules,
        { url, cancellation -> SearchHttpFetcher().fetch(url, cancellation, site) },
        SearchWebViewFetcher(context).let { browser ->
            { url, cancellation -> browser.fetchBlocking(url, site, rules, cancellation) }
        })
    override fun search(request: SearchRequest, pageToken: String?, attemptId: String,
                        cancellation: SearchCancellation): SiteSearchResult {
        val url = site.buildSearchUrl(request.normalizedQuery, pageToken)
        fun error(status: SearchStatus, code: String) =
            SiteSearchResult(request.requestId, site.id, attemptId, status, errorCode = code,
                message = SearchFailure.zh(code), diagnostic = "phase=HTTP host=" + url.toHttpUrlOrNull()?.host)
        if (!SearchResultNormalizer.validUrl(url, site)) return error(SearchStatus.PARSE_ERROR, "INVALID_SEARCH_URL")
        try {
            cancellation.check()
            val started = System.nanoTime()
            val response = fetchHtml(url, cancellation)
            cancellation.check()
            var parsed = SearchHtmlParser(site, rules).parse(request, attemptId, response)
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > 15_000)
                return error(SearchStatus.TIMEOUT, "HTTP_DEADLINE")
            // Interactive verification is never automated; the user opens the original site.
            if (rules.browserFallback && (parsed.status == SearchStatus.PARSE_ERROR ||
                    parsed.status in setOf(SearchStatus.SUCCESS, SearchStatus.EMPTY) && parsed.pageState == SearchPageState.UNSUPPORTED) &&
                SearchResultNormalizer.validUrl(response.finalUrl, site)) {
                val rendered = renderHtml(response.finalUrl, cancellation)
                cancellation.check()
                val renderedResult = SearchHtmlParser(site, rules).parse(request, attemptId, rendered)
                parsed = renderedResult.copy(
                    items = if (renderedResult.status in setOf(SearchStatus.SUCCESS, SearchStatus.EMPTY)) renderedResult.items else parsed.items,
                    diagnostic = "phase=WebView " + renderedResult.diagnostic + " httpCode=" + parsed.errorCode)
            }
            return parsed
        } catch (e: InterruptedException) {
            throw e
        } catch (_: java.io.InterruptedIOException) {
            cancellation.check()
            return error(SearchStatus.TIMEOUT, "HTTP_TIMEOUT")
        } catch (cause: java.io.IOException) {
            cancellation.check()
            return error(SearchStatus.NETWORK_ERROR, SearchFailure.code(cause))
        } catch (_: Exception) {
            cancellation.check()
            return error(SearchStatus.PARSE_ERROR, "PARSER_EXCEPTION")
        }
    }
}

/** Pure HTML parsing: it can be tested using sanitised, non-content structural fixtures. */
class SearchHtmlParser(private val site: SearchSite, private val rules: SearchParserRules) {
    fun parse(request: SearchRequest, attempt: String, response: SearchHttpResponse): SiteSearchResult {
        var diagnostic = "host=" + response.finalUrl.toHttpUrlOrNull()?.host +
            " http=" + response.statusCode + " chars=" + response.body.length
        var pageState = SearchPageState.UNKNOWN
        var fingerprint: String? = null
        fun result(status: SearchStatus, items: List<SearchItem> = emptyList(), next: String? = null,
                   code: String? = null, message: String? = null) = SiteSearchResult(
            request.requestId, site.id, attempt, status, items, next, code,
            message ?: code?.let(SearchFailure::zh),
            retryAfterUntil = response.retryAfterUntil, diagnostic = diagnostic,
            pageState = pageState, pageFingerprint = fingerprint)
        response.transportError?.let { return result(if (it == "HTTP_TIMEOUT") SearchStatus.TIMEOUT else SearchStatus.NETWORK_ERROR, code = it) }
        val document = Jsoup.parse(response.body, response.finalUrl)
        if (response.statusCode == 499) return result(SearchStatus.CANCELLED)
        // Check challenge evidence before HTTP errors: CF may respond with 200, 403 or 503.
        val hasCards = document.select(rules.links).any { anchor ->
            val href = anchor.absUrl("href")
            SearchPageClassifier.visible(anchor) && anchor.closest("nav, .advertisement") == null &&
                SearchResultNormalizer.validUrl(href, site) &&
                rules.detailPath.matches(href.toHttpUrlOrNull()?.encodedPath.orEmpty()) &&
                (anchor.text().isNotBlank() || anchor.attr("title").isNotBlank() || anchor.select("img[alt]").isNotEmpty())
        }
        SearchPageClassifier.blocked(response, document, hasCards)?.let {
            diagnostic += " evidence=" + it.evidence
            return result(SearchStatus.NEEDS_USER_ACTION, code = it.code)
        }
        if (response.statusCode == 598) return result(SearchStatus.TIMEOUT, code = "DOM_TIMEOUT")
        if (response.statusCode !in 200..299)
            return result(SearchStatus.NETWORK_ERROR, code = "HTTP_" + response.statusCode,
                message = if (response.statusCode == 429) "網站暫時限制查詢，請稍後重試" else "網站連線失敗")
        if (!SearchResultNormalizer.validUrl(response.finalUrl, site))
            return result(SearchStatus.PARSE_ERROR, code = "UNEXPECTED_HOST")
        if (response.contentType != null && !response.contentType.contains("html", true))
            return result(SearchStatus.PARSE_ERROR, code = "UNSUPPORTED_CONTENT_TYPE")
        if (SearchPageClassifier.confirmedEmpty(document, rules)) {
            pageState = SearchPageState.END_REPORTED
            diagnostic += " evidence=explicit-empty-message"
            return result(SearchStatus.EMPTY, code = "NO_RESULTS")
        }
        val candidates = document.select(rules.links)
        val appShell = document.select("app-root, my-app, [ng-version], #__next, #__nuxt").isNotEmpty()
        if (candidates.isEmpty()) {
            diagnostic += " anchors=" + document.select("a[href]").size +
                " scripts=" + document.select("script[src]").size + " appShell=" + appShell +
                " rendered=" + response.rendered
            diagnostic += " baseHost=" + (document.selectFirst("base[href]")?.absUrl("href")?.toHttpUrlOrNull()?.host ?: "none")
            val localAnchors = document.select("a[href]").toList().filter { anchor -> SearchResultNormalizer.validUrl(anchor.absUrl("href"), site) }
            diagnostic += " sameHostAnchors=" + localAnchors.size
            diagnostic += " knownDetailLinks=" + localAnchors.count {
                rules.detailPath.matches(it.absUrl("href").toHttpUrlOrNull()?.encodedPath.orEmpty())
            }
            diagnostic += " pageRoutes=" + localAnchors.mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
                .map { safeRoute(it.pathSegments) }.groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.take(6).joinToString(",") { it.key + "=" + it.value }
        }
        var rejectedHost = 0
        var rejectedPath = 0
        var rejectedChrome = 0
        val anchors = candidates.filter { anchor ->
            val href = anchor.absUrl("href")
            val path = href.toHttpUrlOrNull()?.encodedPath.orEmpty()
            val card = anchor.closest(rules.card)
            val inChrome = anchor.parents().any { ancestor ->
                ancestor.tagName() == "nav" || ancestor.hasClass("advertisement") ||
                    (ancestor.tagName() in listOf("header", "footer") &&
                        (card == null || ancestor == card || ancestor in card.parents()))
            }
            when {
                !SearchPageClassifier.visible(anchor) -> { rejectedChrome++; false }
                !SearchResultNormalizer.validUrl(href, site) -> { rejectedHost++; false }
                !rules.detailPath.matches(path) -> { rejectedPath++; false }
                inChrome -> { rejectedChrome++; false }
                else -> true
            }
        }
        val raw = anchors.mapIndexedNotNull { index, anchor -> rawItem(anchor, index) }
        diagnostic += " selected=" + candidates.size + " detail=" + anchors.size + " titled=" + raw.size
        diagnostic += " rejectedHost=" + rejectedHost + " rejectedPath=" + rejectedPath + " rejectedChrome=" + rejectedChrome
        if (rejectedHost > 0 || rejectedPath > 0) {
            // Route shapes are redacted: no titles, identifiers, queries, or complete URLs.
            diagnostic += " linkHosts=" + candidates.mapNotNull { it.absUrl("href").toHttpUrlOrNull()?.host }.distinct().take(3).joinToString(",")
            diagnostic += " routes=" + candidates.mapNotNull { it.absUrl("href").toHttpUrlOrNull() }.map { url ->
                safeRoute(url.pathSegments)
            }.distinct().take(3).joinToString(",")
        }
        if (raw.isEmpty()) {
            val error = when {
                candidates.isEmpty() && appShell -> "DYNAMIC_RESULTS_NOT_READY"
                candidates.isEmpty() -> "RESULT_CONTAINER_NOT_FOUND"
                anchors.isEmpty() -> "DETAIL_LINKS_REJECTED"
                else -> "CARD_DATA_MISSING"
            }
            return result(if (error == "DYNAMIC_RESULTS_NOT_READY" && response.rendered)
                SearchStatus.TIMEOUT else SearchStatus.PARSE_ERROR, code = error)
        }
        val items = SearchResultNormalizer.normalize(site, request, raw)
        val identity = raw.mapNotNull { SearchResultNormalizer.canonicalUrl(it.detailUrl) }.distinct().sorted().joinToString("\n")
        fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (items.isEmpty() && request.mode == SearchMode.CODE && raw.all { it.code == null })
            return result(SearchStatus.PARSE_ERROR, code = "CODE_NOT_IDENTIFIED")
        val pagination = SearchPagination.detect(document, response.finalUrl, site)
        pageState = if (raw.mapNotNull { SearchResultNormalizer.canonicalUrl(it.detailUrl) }.distinct().size > SearchPagingPolicy.PAGE_ITEMS)
            SearchPageState.PAGE_ITEM_LIMIT else pagination.state
        diagnostic += " pagination=" + pageState
        val next = pagination.next
        return result(if (items.isEmpty()) SearchStatus.EMPTY else SearchStatus.SUCCESS, items, next,
            code = if (items.isEmpty()) "NO_EXACT_MATCH" else null)
    }
    private fun rawItem(anchor: Element, index: Int): SearchResultNormalizer.RawItem? {
        val card = anchor.closest(rules.card) ?: boundedCard(anchor)
        val href = anchor.absUrl("href")
        // Read only this card, never the containing result list.
        val title = sequenceOf(anchor.attr("title"),
            card.select(".title, .video-title, .video-name, .content-title, h2, h3, h4").text(),
            anchor.select("img[alt]").attr("alt"), card.select("img[alt]").attr("alt"), anchor.text())
            .firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val image = card.select("img").firstOrNull()
        val cover = image?.let { img ->
            listOf("data-src", "data-original", "src").map { img.absUrl(it) }.firstOrNull { it.toHttpUrlOrNull() != null }
        }
        val evidence = title + " " + href
        val tags = buildList {
            if (evidence.contains("中文字幕") || evidence.contains("中文")) add("中文字幕")
            if (evidence.contains("uncensored", true) || evidence.contains("無碼")) add("無碼")
            if (Regex("(?i)(?<![a-z0-9])4k(?![a-z0-9])").containsMatchIn(evidence)) add("4K")
        }
        return SearchResultNormalizer.RawItem(title, href, cover,
            SearchQueryNormalizer.extractCode(title, href), tags, index)
    }

    private fun safeRoute(segments: List<String>): String = "/" + segments.filter { it.isNotBlank() }.map { segment ->
        when {
            segment in setOf("video", "videos", "watch", "en", "zh", "post", "w", "play", "detail", "search", "category") -> segment
            segment.all(Char::isDigit) -> ":id"
            else -> ":slug"
        }
    }.joinToString("/")

    private fun boundedCard(anchor: Element): Element {
        var card = anchor
        repeat(3) {
            val parent = card.parent() ?: return card
            if (parent.tagName() in setOf("body", "html", "main", "nav", "header", "footer")) return card
            val targets = parent.select(rules.links).map { it.absUrl("href") }
                .filter { SearchResultNormalizer.validUrl(it, site) }
                .mapNotNull(SearchResultNormalizer::canonicalUrl).distinct()
            // Only climb within one result. Never borrow a neighbouring card's title.
            if (targets.size != 1) return card
            card = parent
        }
        return card
    }
}

object SearchProviderFactory {
    fun create(context: Context): Map<String, SearchProvider> = SearchSiteRegistry.sites(context).associate { site ->
        site.id to when (site.parserKind) {
            SearchParserKind.MISSAV -> MissavSearchProvider(context, site)
            SearchParserKind.JABLE -> JableSearchProvider(context, site)
            SearchParserKind.AVJOY -> AvjoySearchProvider(context, site)
            SearchParserKind.PIGAV -> PigavSearchProvider(context, site)
            SearchParserKind.AVTODAY -> AvtodaySearchProvider(context, site)
            SearchParserKind.JAVHD -> JavhdSearchProvider(context, site)
            SearchParserKind.SEVEN_MM_TV -> SevenMmTvSearchProvider(context, site)
            SearchParserKind.AVPLE -> AvpleSearchProvider(context, site)
            SearchParserKind.WHOS -> WhosSearchProvider(context, site)
        }
    }
}
