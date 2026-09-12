package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SearchLocalMetadataIndexTest {
    private fun item(code: String = "ABP-001", url: String = "https://site.invalid/w/1") =
        SearchItem("id", "site", "Neutral title", url, url, code = code)

    @Test fun exactCodeAssociatesFavoriteWithoutTitleGuess() {
        val index = SearchLocalMetadataIndex.from(
            listOf(FavoriteItem("Other title", "https://else.invalid/detail", javCode = "ABP-001")), emptyList())
        assertTrue(index.match(item()).favorite)
        assertFalse(index.match(item("ABP-002")).favorite)
    }

    @Test fun canonicalUrlAssociatesDownloadedSource() {
        val index = SearchLocalMetadataIndex.from(emptyList(), listOf(download("https://site.invalid/w/1?utm_source=x")))
        assertTrue(index.match(item(url = "https://site.invalid/w/1?utm_campaign=y")).downloaded)
    }

    @Test fun similarTitleWithoutCodeDoesNotAssociate() {
        val index = SearchLocalMetadataIndex.from(listOf(FavoriteItem("Neutral title", "https://else.invalid/detail")), emptyList())
        assertFalse(index.match(item(code = "UNKNOWN-999")).favorite)
    }

    @Test fun groupBadgeIsAnyLinkNotEveryLink() {
        val index = SearchLocalMetadataIndex.from(listOf(FavoriteItem("", "https://site.invalid/w/1", javCode = "ABP-001")), emptyList())
        val group = SearchResultGroup("group", "ABP-001", "title", null, listOf(item(), item("ABP-002", "https://site.invalid/w/2")))
        assertEquals(SearchLocalMatch(favorite = true), index.match(group))
    }

    private fun download(url: String) = VideoDownloadRecord("id", "title", url, DownloadRepository.STATUS_COMPLETED,
        100, 0L, "", "file.mp4", "content://file", 1L, null, "video/mp4", "local", 1L, 1L, "", "")
}
