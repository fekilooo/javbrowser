package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class AvpleSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules("a[href*='/video/']", ".video-item, .video, .card",
            Regex("/video/[^/]+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", true)
    }
}
