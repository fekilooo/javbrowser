package com.example.javbrowser

/** Stable list position used when new site results arrive or a detail page is closed. */
data class SearchReadAnchor(val groupId: String, val offsetPx: Int)

object SearchReadAnchorResolver {
    fun resolve(groups: List<SearchResultGroup>, anchor: SearchReadAnchor?): Int? {
        if (anchor == null) return null
        return groups.indexOfFirst { it.stableId == anchor.groupId }.takeIf { it >= 0 }
    }
}
