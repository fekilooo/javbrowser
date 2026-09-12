package com.example.javbrowser

import android.content.Context

/** Structural contract; live-site acceptance status is recorded separately. */
class PigavSearchProvider(context: Context, site: SearchSite) : SiteHtmlSearchProvider(site, context, RULES) {
    companion object {
        // /w/<id> is also used by the existing site's bookmark/download integration.
        // Do not require a guessed card class after client-side rendering.
        val RULES = SearchParserRules(".video-item a[href], .video a[href], .thumbnail a[href], a[href*='/w/'], a[href*='/video/'], a[href*='/videos/']", ".video-item, .video, .thumbnail",
            Regex("/(?:w|videos?)/[^/]+/?", RegexOption.IGNORE_CASE),
            ".search-results .no-results, .search-results .no-result", true)
    }
}
