package com.example.javbrowser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SevenSourceProtocolTest {
    private val x = SevenSourceProtocol.source("xchina")
    private val t = SevenSourceProtocol.source("theporny")
    private inline fun fails(action: () -> Unit) {
        try { action(); fail("Expected a source error") } catch (_: IOException) { }
    }
    @Test fun retainsAllOriginalCategoryIdsAndDisplayNames() {
        assertEquals(listOf("recent", "hot", "week_hot", "month_hot", "high", "5mins", "10mins",
            "c0", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "japan", "av", "uncensored", "west"),
            x.categories.map { it.first })
        assertEquals(listOf("recent", "c0", "japan", "swag", "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "ca"),
            t.categories.map { it.first })
        assertEquals("Madou", SevenSourceProtocol.categoryLabel(x.categories.first { it.first == "c1" }, true))
        assertEquals("麻豆影片", SevenSourceProtocol.categoryLabel(x.categories.first { it.first == "c1" }, false))
    }
    @Test fun decryptsIndependentOpenSslVectorAndPreservesChinese() {
        val cipher = "U2FsdGVkX18xMjM0NTY3OGZn+BmjKTYSKrTceMMn5UcYP4xyQoPv0W9ChQBHBhMf8/aAXfd+p6uy9/zdQnBGd58+xyzceBOweWvr0AnSPd6Ues8G3GlBfpItDPqFqYr+"
        val videos = SevenSourceProtocol.videos(x, SevenSourceProtocol.unwrap(JSONObject().put("r", cipher).toString()))
        assertEquals("測試影片", videos.single().title)
        assertEquals("01:30", videos.single().duration)
        assertEquals("xchina:fixture-1", videos.single().stableId)
    }
    @Test fun rejectsDamagedCipherAndBlockedOrMalformedResponses() {
        listOf("", "not-base64", "U2FsdGVkX18xMjM0NTY3OA==", "U2FsdGVkX18xMjM0NTY3OGZn").forEach { value -> fails { SevenSourceProtocol.decrypt(value) } }
        fails { SevenSourceProtocol.unwrap("{\"blocked\":true,\"r\":\"invalid\"}") }
        fails { SevenSourceProtocol.unwrap("<html>blocked</html>") }
        fails { SevenSourceProtocol.videos(x, JSONObject().put("error", "denied")) }
        fails { SevenSourceProtocol.videos(x, JSONArray().put(JSONObject().put("id", "../bad"))) }
        assertTrue(SevenSourceProtocol.videos(x, JSONArray()).isEmpty())
    }
    @Test fun parsesRowsAndKeepsSameIdsDistinctAcrossSources() {
        val row = JSONObject().put("vId", "same-id").put("title", "Fixture")
            .put("thumbNails", JSONArray().put("javascript:bad").put("//images.example.com/cover.jpg"))
        val a = SevenSourceProtocol.video(x, row)
        val b = SevenSourceProtocol.video(t, row)
        assertNotEquals(a.stableId, b.stableId)
        assertNotEquals(a.pageUrl, b.pageUrl)
        assertEquals("https://images.example.com/cover.jpg", a.cover)
        assertEquals(x.id to a.id, SevenSourceProtocol.parseLink(a.pageUrl))
        assertNull(SevenSourceProtocol.parseLink("https://xchina.tube.evil.test/?javbrowser_video=same-id"))
        assertNull(SevenSourceProtocol.parseLink("https://user@xchina.tube/?javbrowser_video=same-id"))
        assertNull(SevenSourceProtocol.parseLink("https://xchina.tube/?javbrowser_video=../bad"))
        assertEquals("xchina", SevenSourceProtocol.catalogSource("https://xchina.tube/"))
        assertEquals("theporny", SevenSourceProtocol.catalogSource("https://theporny.com/"))
        assertNull(SevenSourceProtocol.catalogSource("https://xchina.tube.evil.test/"))
        assertNull(SevenSourceProtocol.catalogSource(a.pageUrl))
    }
    @Test fun selectsSafeMediaAndSupportsBothRelativeFormsAndFallback() {
        val row = JSONObject().put("m3u8s", JSONArray().put("javascript:bad").put("file:///tmp/video").put("//cdn.example.com/main.m3u8"))
        assertEquals("https://cdn.example.com/main.m3u8", SevenSourceProtocol.playlist(row, t))
        assertEquals("https://xchina.tube/main.m3u8", SevenSourceProtocol.playlist(JSONObject().put("m3u8", "/main.m3u8"), x))
        assertEquals("https://cdn.example.com/fallback.m3u8", SevenSourceProtocol.playlist(
            JSONObject().put("m3u8s", JSONArray().put("javascript:bad")).put("m3u8", "https://cdn.example.com/fallback.m3u8"), x))
        listOf("http://127.0.0.1/v", "http://192.168.1.1/v", "http://localhost/v", "https://a:b@cdn.example.com/v", "https://[::1]/v").forEach {
            assertNull(SevenSourceProtocol.mediaUrl(it, x))
        }
    }
    @Test fun buildsSourceSpecificRequestsWithoutCredentials() {
        val search = SevenSourceProtocol.catalogRequest(t, "", 2, "fixture")
        assertEquals("/searchSevenVideos", search.first)
        assertEquals("time", search.second.getString("sortBy"))
        assertEquals("and", search.second.getString("keywordLogic"))
        assertTrue(SevenSourceProtocol.catalogRequest(x, "invalid", 1, "").first.endsWith("type=recent"))
        val body = SevenSourceProtocol.body(t, search.first, search.second)
        assertEquals("theporny.com", body.getString("app"))
        assertFalse(body.has("password")); assertFalse(body.has("userEmail"))
        assertTrue(body.has("deviceInfo"))
    }
}
