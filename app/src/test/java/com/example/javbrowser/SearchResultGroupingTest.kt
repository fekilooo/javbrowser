package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SearchResultGroupingTest {
    private fun item(site: String, code: String? = "TEST-001", suffix: String = "one", tags: List<String> = emptyList()) =
        SearchItem(site + suffix, site, "Neutral title", "https://$site.invalid/$suffix?keep=1",
            "https://$site.invalid/$suffix?keep=1", code = code, variantTags = tags)

    @Test fun identicalCodesFromDifferentSitesBecomeOneCardWithAllLinks() {
        val raw = listOf(item("a"), item("b"), item("c"))
        val groups = SearchResultGrouping.group(raw)
        assertEquals(1, groups.size)
        assertEquals("TEST-001", groups.single().code)
        assertEquals(raw, groups.single().links)
    }
    @Test fun caseAndSeparatorDifferencesAreNormalised() {
        val groups = SearchResultGrouping.group(listOf(item("a", "test_001"), item("b", "TEST001"), item("c", "TEST-001")))
        assertEquals(1, groups.size)
    }
    @Test fun fc2AliasesAreGroupedWithoutMergingOtherNumbers() {
        val groups = SearchResultGrouping.group(listOf(item("a", "FC2-1234567"), item("b", "FC2-PPV-1234567"), item("c", "FC2-1234568")))
        assertEquals(2, groups.size)
        assertEquals(2, groups.first().links.size)
    }
    @Test fun differentPrefixesLongerNumbersAndLeadingZerosRemainSeparate() {
        val raw = listOf("TEST-001", "TEST-0012", "OTHER-001", "TEST-01").mapIndexed { i, code -> item("a", code, i.toString()) }
        assertEquals(4, SearchResultGrouping.group(raw).size)
    }
    @Test fun sameSiteVariantsKeepDistinctLinksAndLabels() {
        val raw = listOf(item("a", suffix = "original"), item("a", suffix = "subtitles", tags = listOf("中文字幕")),
            item("a", suffix = "alternate", tags = listOf("無碼")))
        val group = SearchResultGrouping.group(raw).single()
        assertEquals(3, group.links.size)
        assertEquals("Source #1", SearchResultGrouping.linkLabel(group, raw[0], "Source", false))
        assertEquals("Source #2 · 中文字幕", SearchResultGrouping.linkLabel(group, raw[1], "Source", false))
        assertEquals("Source #3 · Uncensored", SearchResultGrouping.linkLabel(group, raw[2], "Source", true))
        assertEquals(raw.map { it.detailUrl }, group.links.map { it.detailUrl })
    }
    @Test fun duplicateCanonicalLinkDoesNotCreateAnotherChip() {
        val original = item("a")
        val duplicate = original.copy(stableId = "second", detailUrl = original.detailUrl + "&utm_source=other")
        val group = SearchResultGrouping.group(listOf(original, duplicate)).single()
        assertEquals(listOf(original), group.links)
    }
    @Test fun unidentifiedCodesDoNotMergeByTitle() {
        val raw = listOf(item("a", null), item("b", null), item("c", "not a code"), item("d", ""))
        assertEquals(4, SearchResultGrouping.group(raw).size)
        assertTrue(SearchResultGrouping.group(raw).all { it.code == null })
    }
    @Test fun duplicateUnknownItemStillAppearsOnlyOnce() {
        val raw = item("a", null)
        assertEquals(1, SearchResultGrouping.group(listOf(raw, raw)).single().links.size)
    }
    @Test fun groupsKeepFirstSeenOrderAndStableIdentityAsSitesArrive() {
        val first = item("b")
        val before = SearchResultGrouping.group(listOf(first)).single()
        val after = SearchResultGrouping.group(listOf(item("a"), first, item("c", "TEST-002")))
        assertEquals(before.stableId, after.first().stableId)
        assertEquals("TEST-002", after.last().code)
    }
    @Test fun coverCanComeFromAnotherSourceWithoutLosingAnyLink() {
        val raw = listOf(item("a"), item("b").copy(coverUrl = "https://b.invalid/cover.png"))
        val group = SearchResultGrouping.group(raw).single()
        assertEquals("https://b.invalid/cover.png", group.coverUrl)
        assertEquals(raw, group.links)
    }
    @Test fun sourceFilteringDoesNotLeakOtherSitesIntoTheGroupedCard() {
        val raw = listOf(item("a"), item("b"), item("b", "TEST-002"))
        val groups = SearchResultGrouping.group(raw.filter { it.siteId == "a" })
        assertEquals(1, groups.size)
        assertEquals(listOf(raw.first()), groups.single().links)
    }
    @Test fun summariesShowGroupedCountAndRespectFilteredItems() {
        val raw = listOf(item("a"), item("b"), item("b", "TEST-002", "two"))
        val request = SearchRequest("r", "test", "test", SearchMode.KEYWORD, listOf("a", "b"), "config")
        val snapshot = SearchSnapshot(request, emptyList(), raw, 2, 2, false)
        assertTrue(SearchStatusPresentation.compactSummary(snapshot, false).contains("2 組 · 3 個連結"))
        assertTrue(SearchStatusPresentation.summary(snapshot, false, raw.filter { it.siteId == "a" }).contains("1 組 · 1 個連結"))
    }
}
