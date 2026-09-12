package com.example.javbrowser

import android.content.Context
import java.net.URLEncoder

enum class SearchParserKind {
    JABLE,
    PIGAV,
    AVTODAY,
    MISSAV,
    AVJOY,
    JAVHD,
    SEVEN_MM_TV,
    AVPLE,
    WHOS
}

data class SearchSite(
    val id: String,
    val displayName: String,
    val order: Int,
    val parserKind: SearchParserKind,
    val configurationKey: String,
    val buildSearchUrl: (query: String, pageToken: String?) -> String,
    val allowedHosts: Set<String>
) {
    fun isAllowedHost(host: String): Boolean {
        val normalized = host.lowercase()
        return allowedHosts.any { allowed ->
            normalized == allowed || normalized.endsWith(".$allowed")
        }
    }
}

object SearchSiteIds {
    const val MISSAV = "missav"
    const val JABLE = "jable"
    const val AVJOY = "avjoy"
    const val PIGAV = "pigav"
    const val AVTODAY = "avtoday"
    const val JAVHD = "javhd"
    const val SEVEN_MM_TV = "7mmtv"
    const val AVPLE = "avple"
    const val WHOS = "whos"
}

/** 九個搜尋網站的唯一登錄表；網址與可變網域集中在這裡。 */
object SearchSiteRegistry {
    fun sites(context: Context): List<SearchSite> {
        val appContext = context.applicationContext
        val domain = DomainConfig(AdFilterRules(appContext))
        fun encodedQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun pathQuery(value: String): String = encodedQuery(value).replace("+", "%20")

        val missavHost = domain.getMissAvDomain().lowercase()
        val jableHost = domain.getJableDomain().lowercase()
        val avjoyHost = domain.getAvJoyDomain().lowercase()
        val pigavHost = domain.getPigAvDomain().lowercase()
        val avtodayHost = "avtoday.io"
        val javhdHost = "www.javhdporn.net"
        val mmtvHost = domain.get7MmTvDomain().lowercase()
        val avpleHost = domain.getAvpleDomain().lowercase()
        val whosHost = domain.getWhosDomain().lowercase()

        return listOf(
            SearchSite(SearchSiteIds.MISSAV, "MissAV", 0, SearchParserKind.MISSAV,
                "missav:$missavHost", { query, page -> page ?: "https://$missavHost/search/${pathQuery(query)}" }, setOf(missavHost)),
            SearchSite(SearchSiteIds.JABLE, "Jable.TV", 1, SearchParserKind.JABLE,
                "jable:$jableHost", { query, page -> page ?: "https://$jableHost/search/${pathQuery(query)}/" }, setOf(jableHost)),
            SearchSite(SearchSiteIds.AVJOY, "AvJoy", 2, SearchParserKind.AVJOY,
                "avjoy:$avjoyHost", { query, page -> page ?: "https://$avjoyHost/search/videos/${pathQuery(query)}" }, setOf(avjoyHost)),
            SearchSite(SearchSiteIds.PIGAV, "PigAV", 3, SearchParserKind.PIGAV,
                "pigav:$pigavHost", { query, page -> page ?: "https://$pigavHost/search?search=${encodedQuery(query)}&searchTarget=local" }, setOf(pigavHost)),
            SearchSite(SearchSiteIds.AVTODAY, "AVToday", 4, SearchParserKind.AVTODAY,
                "avtoday:$avtodayHost", { query, page -> page ?: "https://$avtodayHost/search?s=${encodedQuery(query)}" }, setOf(avtodayHost)),
            SearchSite(SearchSiteIds.JAVHD, "JavHDPorn", 5, SearchParserKind.JAVHD,
                "javhd:$javhdHost", { query, page -> page ?: "https://$javhdHost/?s=${encodedQuery(query)}" }, setOf(javhdHost, "javhdporn.net")),
            SearchSite(SearchSiteIds.SEVEN_MM_TV, "7MMTV", 6, SearchParserKind.SEVEN_MM_TV,
                "7mmtv:$mmtvHost", { query, page -> page ?: "https://$mmtvHost/zh/searchall_search/all/${pathQuery(query)}/1.html" }, setOf(mmtvHost)),
            SearchSite(SearchSiteIds.AVPLE, "Avple", 7, SearchParserKind.AVPLE,
                "avple:$avpleHost", { query, page -> page ?: "https://$avpleHost/search?key=${encodedQuery(query)}" }, setOf(avpleHost)),
            SearchSite(SearchSiteIds.WHOS, "Whos.tv", 8, SearchParserKind.WHOS,
                "whos:$whosHost", { query, page -> page ?: "https://$whosHost/result?search=${encodedQuery(query)}" }, setOf(whosHost))
        )
    }

    fun find(context: Context, siteId: String): SearchSite? =
        sites(context).firstOrNull { it.id == siteId }
}
