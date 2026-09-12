package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedSearchStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun snapshot(query: String = "測試關鍵字 📚", mode: SearchMode = SearchMode.KEYWORD): SearchSnapshot {
        val request = SearchRequest("original", query, query, mode, listOf("a", "b"), "config", 123L)
        val states = listOf("a", "b").map { id ->
            val url = "https://$id.invalid/detail/test-001?language=zh#section"
            val item = SearchItem(id, id, "測試標題 📚", url, url, "https://$id.invalid/cover.png",
                "TEST-001", listOf("中文字幕"))
            SiteSearchState(id, SearchStatus.SUCCESS, listOf(item), "https://$id.invalid/search?page=3",
                loadedPages = 2, visitedPageTokens = listOf("https://$id.invalid/search?page=2"),
                pageState = SearchPageState.AUTO_LIMIT, visitedPageFingerprints = listOf("fingerprint-$id"))
        }
        return SearchSnapshot(request, states, states.flatMap { it.items }, 2, 2, false)
    }
    private fun fails(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun roundTripPreservesChineseEmojiLinksAndGrouping() {
        val original = snapshot()
        val result = SavedSearchCodec.decode(SavedSearchCodec.encode(original, 456L))
        assertEquals(original.request.rawQuery, result.info.query)
        assertEquals(456L, result.info.savedAt)
        assertEquals(1, result.info.groups)
        assertEquals(2, result.info.links)
        assertEquals(original.items.toSet(), result.snapshot.items.toSet())
        assertEquals(2, SearchResultGrouping.group(result.snapshot.items).single().links.size)
        assertEquals(original.siteStates, result.snapshot.siteStates)
        assertNotEquals(original.request.requestId, result.snapshot.request.requestId)
        assertFalse(result.snapshot.isRunning)
    }

    @Test fun restartReadsPersistentSaveAndSameQueryReplacesIt() {
        val folder = temp.newFolder()
        val first = SavedSearchStore(folder).save(snapshot(), 1L)
        SavedSearchStore(folder).save(snapshot().copy(items = snapshot().items), 2L)
        val restarted = SavedSearchStore(folder)
        assertEquals(1, restarted.list().size)
        assertEquals(2L, restarted.load(first.key).info.savedAt)
    }

    @Test fun distinctQueriesAndModesHaveSeparateKeysAndNewestFirst() {
        val store = SavedSearchStore(temp.newFolder())
        val a = store.save(snapshot("TEST-001"), 1L)
        val b = store.save(snapshot("TEST-001", SearchMode.CODE), 2L)
        val c = store.save(snapshot("另一個關鍵字"), 3L)
        assertEquals(listOf(c.key, b.key, a.key), store.list().map { it.key })
    }

    @Test fun runningWorkIsRestoredAsPausedWithPendingPage() {
        val original = snapshot()
        val states = original.siteStates.map { it.copy(status = SearchStatus.RUNNING,
            pendingPageToken = "https://${it.siteId}.invalid/search?page=3", attemptId = "in-flight") }
        val restored = SavedSearchCodec.decode(SavedSearchCodec.encode(original.copy(siteStates = states, isRunning = true), 1L)).snapshot
        assertFalse(restored.isRunning)
        assertEquals(2, restored.completedSites)
        restored.siteStates.forEach {
            assertEquals(SearchStatus.CANCELLED, it.status)
            assertEquals(2, it.loadedPages)
            assertTrue(it.pendingPageToken!!.endsWith("page=3"))
            assertNull(it.attemptId)
        }
    }

    @Test fun challengeStatusIsRetainedButDiagnosticsAndPageContentAreNotSaved() {
        val original = snapshot()
        val states = original.siteStates.map { it.copy(status = SearchStatus.NEEDS_USER_ACTION,
            errorCode = "AUTH_OR_CHALLENGE", message = "private-page-text", diagnostic = "private-diagnostics",
            retryAfterUntil = 9000L) }
        val bytes = SavedSearchCodec.encode(original.copy(siteStates = states), 1L)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("private-"))
        val restored = SavedSearchCodec.decode(bytes).snapshot.siteStates.first()
        assertEquals(SearchStatus.NEEDS_USER_ACTION, restored.status)
        assertEquals("AUTH_OR_CHALLENGE", restored.errorCode)
        assertEquals(9000L, restored.retryAfterUntil)
        assertTrue(restored.diagnostic.isEmpty())
    }

    @Test fun malformedTruncatedUnknownVersionAndTrailingBytesAreRejected() {
        val bytes = SavedSearchCodec.encode(snapshot(), 1L)
        fails { SavedSearchCodec.decode(bytes.copyOf(bytes.size - 1)) }
        fails { SavedSearchCodec.decode(bytes.copyOf().also { it[7] = 99 }) }
        fails { SavedSearchCodec.decode(bytes + byteArrayOf(1)) }
        fails { SavedSearchCodec.decode(byteArrayOf(0, 1, 2)) }
    }

    @Test fun malformedUtf8IsRejectedInsteadOfDamagingChinese() {
        val bytes = SavedSearchCodec.encode(snapshot(), 1L)
        // Header: magic/version + key length + 64 ASCII key bytes + query length.
        bytes[80] = 0xFF.toByte()
        fails { SavedSearchCodec.decode(bytes) }
    }

    @Test fun overlongTitleCannotReplacePreviousSave() {
        val store = SavedSearchStore(temp.newFolder())
        val original = snapshot()
        val first = store.save(original, 1L)
        val states = original.siteStates.map { state -> state.copy(items = state.items.map { it.copy(title = "中".repeat(30000)) }) }
        fails { store.save(original.copy(siteStates = states), 2L) }
        assertEquals(1L, store.load(first.key).info.savedAt)
        assertEquals(original.items.toSet(), store.load(first.key).snapshot.items.toSet())
    }

    @Test fun oversizeSnapshotCannotReplacePreviousSave() {
        val store = SavedSearchStore(temp.newFolder())
        val original = snapshot()
        val first = store.save(original, 1L)
        val states = original.siteStates.map { state -> state.copy(items = List(1000) { n ->
            state.items.single().copy(stableId = "$n", title = "x".repeat(10000)) }) }
        fails { store.save(original.copy(siteStates = states), 2L) }
        assertEquals(1L, store.load(first.key).info.savedAt)
    }

    @Test fun limitDoesNotEvictSavedSearchesAndStillAllowsUpdating() {
        val store = SavedSearchStore(temp.newFolder())
        repeat(SavedSearchStore.MAX_SAVES) { store.save(snapshot("query-$it"), it.toLong()) }
        fails { store.save(snapshot("overflow"), 99L) }
        assertEquals(SavedSearchStore.MAX_SAVES, store.list().size)
        val updated = store.save(snapshot("query-0"), 100L)
        assertEquals(updated.key, store.list().first().key)
        assertEquals(SavedSearchStore.MAX_SAVES, store.list().size)
    }

    @Test fun interruptedReplacementRecoversBackup() {
        val folder = temp.newFolder()
        val info = SavedSearchStore(folder).save(snapshot(), 1L)
        assertTrue(File(folder, info.key + ".bin").renameTo(File(folder, info.key + ".bak")))
        assertEquals(info, SavedSearchStore(folder).load(info.key).info)
        assertTrue(File(folder, info.key + ".bin").exists())
    }

    @Test fun corruptedSaveRemainsVisibleAndCanBeDeleted() {
        val folder = temp.newFolder()
        val store = SavedSearchStore(folder)
        val info = store.save(snapshot(), 1L)
        File(folder, info.key + ".bin").writeBytes(byteArrayOf(0))
        assertEquals(info.key, store.list().single().key)
        assertTrue(store.list().single().query.contains("無法讀取"))
        fails { store.load(info.key) }
        store.delete(info.key)
        assertTrue(store.list().isEmpty())
    }

    @Test fun deletingOneSaveDoesNotAffectOtherRecordsOrBookmarks() {
        val folder = temp.newFolder()
        val store = SavedSearchStore(folder)
        val bookmark = File(folder, "bookmarks.json").apply { writeText("keep", Charsets.UTF_8) }
        val first = store.save(snapshot("first"))
        val second = store.save(snapshot("second"))
        store.delete(first.key)
        assertEquals(listOf(second.key), store.list().map { it.key })
        assertEquals("keep", bookmark.readText(Charsets.UTF_8))
    }

    @Test fun traversalKeysAreRejected() {
        val store = SavedSearchStore(temp.newFolder())
        fails { store.load("../outside") }
        fails { store.delete("../outside") }
    }
}
