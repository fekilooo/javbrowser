package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class WhosSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules("a[href*='/videos/']", ".video-item, .video, .card",
            Regex("/videos/[^/]+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", true)
    }
}
