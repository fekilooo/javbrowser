package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class JavhdSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules("article a[href], .video-item a[href]", "article, .video-item",
            Regex("/(?:videos?/|zh/(?:[^/]+/)?videos?/)?[^/]+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", true)
    }
}
