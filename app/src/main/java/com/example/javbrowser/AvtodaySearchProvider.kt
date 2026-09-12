package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class AvtodaySearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules(".thumbnail a[href], .video-item a[href]", ".thumbnail, .video-item",
            // The reported page uses /<prefix>/video/<slug>; the prefix is redacted in diagnostics.
            Regex("/(?:[a-z0-9_-]+/)?videos?/[^/]+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", true)
    }
}
