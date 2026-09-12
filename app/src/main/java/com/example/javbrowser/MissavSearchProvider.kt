package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class MissavSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        val RULES = SearchParserRules(".thumbnail a[href], .video-item a[href]", ".thumbnail, .video-item",
            Regex("/(?!(?:search|tags?|categories|category|actresses|actors|makers|genres|login|register)/)(?:[a-z0-9-]{1,16}/)?(?=[a-z0-9-]*[0-9])[a-z0-9]+(?:-[a-z0-9]+)+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", true)
    }
}
