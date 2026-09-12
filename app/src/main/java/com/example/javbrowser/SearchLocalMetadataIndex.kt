package com.example.javbrowser

/** Read-only local associations for result badges; never infers identity from a similar title alone. */
data class SearchLocalMatch(val favorite: Boolean = false, val downloaded: Boolean = false)

class SearchLocalMetadataIndex private constructor(
    private val favoriteCodes: Set<String>,
    private val favoriteUrls: Set<String>,
    private val downloadedCodes: Set<String>,
    private val downloadedUrls: Set<String>
) {
    fun match(item: SearchItem): SearchLocalMatch {
        val code = SearchQueryNormalizer.extractCode(item.code.orEmpty(), item.title, item.detailUrl)
            ?.let(SearchQueryNormalizer::comparableCode)
        val url = SearchResultNormalizer.canonicalUrl(item.detailUrl)
        return SearchLocalMatch(
            favorite = (code != null && code in favoriteCodes) || (url != null && url in favoriteUrls),
            downloaded = (code != null && code in downloadedCodes) || (url != null && url in downloadedUrls)
        )
    }

    fun match(group: SearchResultGroup): SearchLocalMatch =
        group.links.map(::match).fold(SearchLocalMatch()) { result, next ->
            SearchLocalMatch(result.favorite || next.favorite, result.downloaded || next.downloaded)
        }

    companion object {
        val EMPTY = SearchLocalMetadataIndex(emptySet(), emptySet(), emptySet(), emptySet())

        fun from(favorites: List<FavoriteItem>, downloads: List<VideoDownloadRecord>): SearchLocalMetadataIndex {
            fun codeOf(code: String?, vararg values: String): String? =
                SearchQueryNormalizer.extractCode(code.orEmpty(), *values)
                    ?.let(SearchQueryNormalizer::comparableCode)
            fun urlOf(url: String?): String? = url?.let(SearchResultNormalizer::canonicalUrl)
            return SearchLocalMetadataIndex(
                favorites.mapNotNull { codeOf(it.javCode, it.title, it.url) }.toSet(),
                favorites.mapNotNull { urlOf(it.url) }.toSet(),
                downloads.mapNotNull { codeOf(it.javCode, it.title, it.fileName.orEmpty()) }.toSet(),
                downloads.mapNotNull { urlOf(it.sourceUrl) }.toSet()
            )
        }
    }
}
