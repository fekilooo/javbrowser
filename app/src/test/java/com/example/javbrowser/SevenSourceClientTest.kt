package com.example.javbrowser

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SevenSourceClientTest {
    private val source = SevenSourceProtocol.source("theporny")
    @Test fun searchesBothScriptsAndMergesStableIdsAcrossPages() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[{\"id\":\"shared\",\"title\":\"Original title\"},{\"id\":\"traditional-only\"}]"))
            server.enqueue(MockResponse().setBody("[{\"id\":\"shared\",\"title\":\"Simplified title\"},{\"id\":\"simplified-only\"}]"))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[{\"id\":\"page-two\"}]"))
            val client = SevenSourceClient(OkHttpClient(), listOf(server.url("/").toString().trimEnd('/')))
            val xchina = SevenSourceProtocol.source("xchina")
            val first = client.page(xchina, "recent", 1, "台灣", SearchCancellation())
            assertEquals(listOf("shared", "traditional-only", "simplified-only"), first.videos.map { it.id })
            assertEquals("Original title", first.videos.first().title)
            assertTrue(first.hasMore)
            val second = client.page(xchina, "recent", 2, "台灣", SearchCancellation())
            assertEquals("page-two", second.videos.single().id)
            assertTrue(second.hasMore)
            val bodies = (0 until 4).map { org.json.JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()) }
            assertEquals(listOf("台灣", "台湾", "台灣", "台湾"), bodies.map { it.getString("keywords") })
            assertEquals(listOf(1, 1, 2, 2), bodies.map { it.getInt("page") })
        }
    }
    @Test fun simplifiedAndEnglishQueriesUseOnlyOneRequest() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("[]")) }
            val client = SevenSourceClient(OkHttpClient(), listOf(server.url("/").toString().trimEnd('/')))
            client.page(source, "recent", 1, "台湾", SearchCancellation())
            client.page(source, "recent", 1, "ABC-123", SearchCancellation())
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun variantFailureIsRetriedWithoutSkippingItsPage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[{\"id\":\"original\"}]"))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("[{\"id\":\"converted\"}]"))
            val client = SevenSourceClient(OkHttpClient(), listOf(server.url("/").toString().trimEnd('/')))
            try { client.page(source, "recent", 1, "台灣", SearchCancellation()); fail("Incomplete merged page accepted") }
            catch (_: java.io.IOException) { }
            val retried = client.page(source, "recent", 1, "台灣", SearchCancellation())
            assertEquals(listOf("original", "converted"), retried.videos.map { it.id })
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun switchesHostsAndSendsScopedSiteHeadersWithoutCredentials() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(503))
            second.enqueue(MockResponse().setBody("{\"list\":[{\"id\":\"fixture\",\"title\":\"Fixture\"}]}"))
            val client = SevenSourceClient(OkHttpClient(), listOf(first.url("/").toString().trimEnd('/'), second.url("/").toString().trimEnd('/')))
            assertEquals("fixture", client.page(source, "recent", 1, "", SearchCancellation()).videos.single().id)
            assertNotNull(first.takeRequest(2, TimeUnit.SECONDS))
            val request = second.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("https://theporny.com/", request.getHeader("Referer"))
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertFalse(request.body.readUtf8().contains("userEmail"))
        } }
    }
    @Test fun cancellationAbortsActiveRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]").setBodyDelay(2, TimeUnit.SECONDS))
            val client = SevenSourceClient(OkHttpClient(), listOf(server.url("/").toString().trimEnd('/')))
            val canceled = SearchCancellation()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<Boolean> {
                    try { client.page(source, "recent", 1, "cancel-fixture", canceled); false }
                    catch (_: InterruptedException) { true }
                }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                canceled.cancel()
                assertTrue(future.get(3, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun resolvesSelectedServerAndRefreshesInsteadOfCachingSignedMedia() {
        MockWebServer().use { server ->
            repeat(2) { version -> server.enqueue(MockResponse().setBody("{\"id\":\"fixture\",\"title\":\"Fixture\",\"m3u8s\":[\"https://cdn.example.com/v.m3u8?token=$version\"]}")) }
            val client = SevenSourceClient(OkHttpClient(), listOf(server.url("/").toString().trimEnd('/')))
            val first = client.detail(source, "fixture", "hd2", SearchCancellation())
            val second = client.detail(source, "fixture", "hd2", SearchCancellation())
            assertNotEquals(first.mediaUrl, second.mediaUrl)
            repeat(2) { assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("\"server\":\"hd2\"")) }
        }
    }
}
