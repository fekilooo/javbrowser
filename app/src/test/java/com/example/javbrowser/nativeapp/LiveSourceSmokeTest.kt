package com.example.javbrowser.nativeapp

import com.example.javbrowser.nativeapp.domain.JavIdentity
import com.example.javbrowser.nativeapp.domain.JavSearchResult
import com.example.javbrowser.nativeapp.domain.SourceResult
import com.example.javbrowser.nativeapp.source.JableSource
import com.example.javbrowser.nativeapp.source.JavDbSource
import com.example.javbrowser.nativeapp.source.JavSource
import com.example.javbrowser.nativeapp.source.MissAvSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Explicit live-network diagnostics. These tests are skipped during deterministic
 * unit-test runs and enabled only by the non-blocking live-source CI job.
 */
class LiveSourceSmokeTest {
    private val code = "PPPD-526"

    @Before
    fun requireExplicitLiveRun() {
        assumeTrue("Set LUMA_LIVE_SMOKE=true to contact third-party sources", System.getenv("LUMA_LIVE_SMOKE") == "true")
    }

    @Test
    fun javDbSearchAndMetadata() = runBlocking {
        val source = JavDbSource()
        val result = exactResult(source)
        val details = source.getDetails(result.sourceRef)
        assertTrue("JavDB details failed: ${details.describe()}", details is SourceResult.Success)
        val title = (details as SourceResult.Success).value
        assertEquals(code, JavIdentity.normalize(title.code.orEmpty()))
        println("LIVE_SMOKE javdb PASS code=${title.code} title=${title.title} actors=${title.actors.size}")
    }

    @Test
    fun missAvSearchAndPlayback() = runBlocking {
        val source = MissAvSource()
        val result = exactResult(source)
        val playback = source.getPlaybackSources(result.sourceRef)
        assertTrue("MISSAV playback failed: ${playback.describe()}", playback is SourceResult.Success)
        val variants = (playback as SourceResult.Success).value
        assertTrue("MISSAV returned no HLS/MP4 variants for $code", variants.isNotEmpty())
        println("LIVE_SMOKE missav PASS variants=${variants.joinToString { it.label + ":" + it.type }}")
    }

    @Test
    fun jableSearchAndPlayback() = runBlocking {
        val source = JableSource()
        val result = exactResult(source)
        val playback = source.getPlaybackSources(result.sourceRef)
        assertTrue("JABLE playback failed: ${playback.describe()}", playback is SourceResult.Success)
        val variants = (playback as SourceResult.Success).value
        assertTrue("JABLE returned no HLS/MP4 variants for $code", variants.isNotEmpty())
        println("LIVE_SMOKE jable PASS variants=${variants.joinToString { it.label + ":" + it.type }}")
    }

    private suspend fun exactResult(source: JavSource): JavSearchResult {
        val search = source.search(code)
        assertTrue("${source.displayName} search failed: ${search.describe()}", search is SourceResult.Success)
        val values = (search as SourceResult.Success).value
        val exact = values.firstOrNull { JavIdentity.normalize(it.title.code.orEmpty()) == code }
        assertTrue("${source.displayName} returned ${values.size} results but no exact $code match", exact != null)
        return requireNotNull(exact)
    }

    private fun SourceResult<*>.describe(): String = when (this) {
        is SourceResult.Success -> "success(${value})"
        is SourceResult.Failure -> "${error::class.simpleName}: ${error.message}"
    }
}
