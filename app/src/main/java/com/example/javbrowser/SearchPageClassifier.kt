package com.example.javbrowser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Shared by all providers. Only positive evidence can mean verification or an empty result page. */
object SearchPageClassifier {
    data class Decision(val code: String, val evidence: String)

    const val EMPTY_SELECTORS = ".no-results, .no-result, .search-empty, .search-no-results, .search-results-empty, #no-results, [data-search-empty=true]"
    const val CHALLENGE_SELECTORS = "#challenge-form, #cf-challenge-running, #cf-please-wait, .cf-browser-verification, #challenge-stage"
    val challengeWords = Regex("just a moment|checking your browser|verify (?:that )?you are human|verifying you are human|performing security verification|驗證您是人類|验证您是真人|確認您是真人|正在驗證|正在验证|檢查您的瀏覽器|检查您的浏览器|セキュリティ確認", RegexOption.IGNORE_CASE)
    private val blockedWords = Regex("sorry,? you have been blocked|access denied|error (?:code )?10(?:20|15)|存取遭拒|存取被拒|訪問被拒|访问被拒|已被封鎖|已被封锁", RegexOption.IGNORE_CASE)
    private val loginWords = Regex("^(?:sign in|log ?in|登入|登錄|登录)(?:\\s|$)|sign in to continue|login to continue|請先登入|请先登录|需要登入", RegexOption.IGNORE_CASE)
    val emptyWords = Regex("^(?:(?:sorry|抱歉|很抱歉)[,，!！:：.。]?\\s*)?(?:no (?:matching )?(?:search )?(?:results?|videos?|items?)(?: (?:were )?found)?|nothing (?:was )?found|沒有(?:找到)?(?:任何|符合條件的|符合的|相關的|相關)?(?:搜尋|搜索)?(?:結果|影片|項目)|查無(?:搜尋|搜索)?(?:結果|資料)|找不到(?:任何|符合條件的|符合的|相關)?(?:結果|影片|資料)|未找到(?:任何|相關的|相关的|相關|相关)?(?:搜尋|搜索)?(?:結果|结果|影片|视频)|検索結果(?:が|は)?(?:ありません|見つかりません)|該当する(?:動画|結果)(?:が|は)?ありません)(?:[.!。！:：,，\\s-].*)?$", RegexOption.IGNORE_CASE)

    fun visible(element: Element): Boolean = (listOf(element) + element.parents()).none {
        val style = it.attr("style").replace(Regex("\\s+"), "").lowercase()
        it.hasAttr("hidden") || it.attr("aria-hidden").equals("true", true) ||
            style.contains("display:none") || style.contains("visibility:hidden") ||
            it.tagName() in setOf("template", "script", "style", "noscript")
    }

    fun blocked(response: SearchHttpResponse, document: Document, hasResultCards: Boolean): Decision? {
        if (response.cfMitigated.equals("challenge", true)) return Decision("CF_CHALLENGE", "cf-mitigated")
        val headings = document.select("h1, h2, [role=heading]").filter { node -> visible(node) }.joinToString(" ") { it.text() }
        val heading = document.title() + " " + headings
        val visibleText = document.body().clone().also { body ->
            body.select("script, style, template, noscript, [hidden], [aria-hidden=true]").remove()
            body.select("[style]").filter { node -> !visible(node) }.forEach { it.remove() }
        }.text()
        val cfDom = document.select("#cf-error-details, #cf-wrapper, .cf-error-details").any(::visible)
        val cfScript = document.select("script").any { it.data().contains("_cf_chl_opt") ||
            it.attr("src").contains("/cdn-cgi/challenge-platform/") && it.attr("src").contains("/orchestrate/") }
        val interstitial = document.select(CHALLENGE_SELECTORS).any(::visible)
        if (!hasResultCards && (cfDom || response.cloudflareServed) && blockedWords.containsMatchIn(visibleText))
            return Decision("CF_BLOCKED", "cf-block-page")
        if (interstitial && (!hasResultCards || challengeWords.containsMatchIn(heading)))
            return Decision("CF_CHALLENGE", "cf-challenge-dom")
        if (!hasResultCards && challengeWords.containsMatchIn(heading + " " + visibleText))
            return Decision(if (cfScript || cfDom || response.cloudflareServed) "CF_CHALLENGE" else "HUMAN_VERIFICATION", "verification-text")
        if (!hasResultCards && cfScript && document.select("#challenge-running").any(::visible))
            return Decision("CF_CHALLENGE", "cf-interstitial-script")
        val loginPath = response.finalUrl.toHttpUrlOrNull()?.encodedPath?.let {
            Regex("/(?:login|signin|auth)(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(it)
        } == true
        if (response.statusCode == 401 || loginPath || (!hasResultCards && loginWords.containsMatchIn((heading + " " + visibleText).trim())))
            return Decision("LOGIN_REQUIRED", "login-page")
        if (response.statusCode == 403) return Decision("HTTP_FORBIDDEN", "http-403")
        return null
    }

    fun confirmedEmpty(document: Document, rules: SearchParserRules): Boolean {
        fun eligible(node: Element): Boolean = visible(node) && node.closest(
            "nav, header, footer, .comments, #comments, .recommendations, .related, .related-videos"
        ) == null && node.closest(rules.card) == null
        val explicit = document.select(rules.empty + ", " + EMPTY_SELECTORS)
        if (explicit.any { eligible(it) && it.text().length <= 500 && emptyWords.matches(it.text().trim()) }) return true
        // Headings can state an empty search without any of the known site-specific class names.
        return document.select("main h1, main h2, [role=main] h1, [role=main] h2, .search-results h1, .search-results h2")
            .any { eligible(it) && it.text().length <= 200 && emptyWords.matches(it.text().trim()) }
    }
}
