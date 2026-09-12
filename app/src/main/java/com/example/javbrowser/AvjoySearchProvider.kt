package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class AvjoySearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules(".content-row .content-info a[href]", ".content-row",
            Regex("/videos?/(?:[0-9]+/)?[^/]+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", true)
    }
}
