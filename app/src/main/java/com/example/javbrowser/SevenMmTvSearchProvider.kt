package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class SevenMmTvSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules("a[href*='_content/']", ".video, .item, li, .thumbnail",
            Regex("/(?:[a-z]+/)?[a-z_]+_content/[0-9]+/[^/]+[.]html", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", false)
    }
}
