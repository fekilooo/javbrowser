package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Anonymous live-source checks. Reports contain state/counts only, never URLs, titles or tokens. */
@RunWith(AndroidJUnit4::class)
class SevenSourcePlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun categoriesStayInsideEachSourceAcrossRecreation() {
        for (source in SevenSourceProtocol.sources) {
            ActivityScenario.launch<SevenSourceActivity>(Intent(context, SevenSourceActivity::class.java)
                .putExtra(SevenSourceActivity.EXTRA_SOURCE, source.id)).use { scenario ->
                fun verify(selected: String, click: Boolean) {
                    scenario.onActivity { activity ->
                        fun find(view: android.view.View): com.google.android.material.chip.ChipGroup? {
                            if (view is com.google.android.material.chip.ChipGroup) return view
                            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                                find(view.getChildAt(index))?.let { return it }
                            }
                            return null
                        }
                        val chips = requireNotNull(find(activity.window.decorView))
                        assertEquals(source.categories.size, chips.childCount)
                        val tags = (0 until chips.childCount).map { chips.getChildAt(it).tag.toString() }
                        assertEquals(source.categories.map { it.first }, tags)
                        if (click) chips.getChildAt(tags.indexOf(selected)).performClick()
                        assertEquals(selected, chips.findViewById<android.view.View>(chips.checkedChipId).tag)
                    }
                }
                verify("japan", true)
                scenario.recreate()
                verify("japan", false)
            }
        }
    }
    @Test fun bothSourcesResolvePlayAndReleaseTheirPlayer() {
        val report = File(context.cacheDir, "seven-source-playback-test.txt").apply { writeText("") }
        val existingHistory = PlaybackHistoryRepository.list(context).map { it.url }.toSet()
        val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        for (source in SevenSourceProtocol.sources) {
            val cancellation = SearchCancellation()
            val client = SevenSourceClient()
            val page = client.page(source, "recent", 1, "", cancellation)
            assertTrue(page.videos.isNotEmpty())
            val item = page.videos.first { it.pageUrl !in existingHistory }
            try {
                val detail = client.detail(source, item.id, null, cancellation)
                assertNotNull("No anonymous media for ${source.id}", detail.mediaUrl)
                val intent = Intent(context, FullscreenInternalPlayerActivity::class.java)
                    .putExtra(FullscreenInternalPlayerActivity.EXTRA_SEVEN_SOURCE, source.id)
                    .putExtra(FullscreenInternalPlayerActivity.EXTRA_SEVEN_VIDEO, item.id)
                    .putExtra(FullscreenInternalPlayerActivity.EXTRA_PAGE_URL, item.pageUrl)
                    .putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, detail.video.title)
                var healthUrl: String? = null
                ActivityScenario.launch<FullscreenInternalPlayerActivity>(intent).use { scenario ->
                    var firstTime: Double? = null
                    var advanced = 0.0
                    val started = System.nanoTime()
                    val deadline = started + TimeUnit.SECONDS.toNanos(70)
                    while (System.nanoTime() < deadline && (advanced < 8.0 || System.nanoTime() - started < TimeUnit.SECONDS.toNanos(12))) {
                        val sample = sample(scenario)
                        if (sample != null) {
                            assertEquals("Media error for ${source.id}", 0, sample.optInt("error"))
                            val time = sample.optDouble("time", 0.0)
                            if (time > 0 && firstTime == null) firstTime = time
                            advanced = time - (firstTime ?: time)
                            report.writeText(report.readText() + "${source.id} ready=${sample.optInt("ready")} advancing=${advanced.toInt()}\n")
                        }
                        if (advanced < 8) Thread.sleep(1000)
                    }
                    assertTrue("Playback did not advance for ${source.id}", advanced >= 8)
                    scenario.onActivity { activity ->
                        val field = FullscreenInternalPlayerActivity::class.java.getDeclaredField("playbackProxyServer").apply { isAccessible = true }
                        healthUrl = (field.get(activity) as? VideoProxyServer)?.healthUrl()
                    }
                    assertNotNull(healthUrl)
                    scenario.moveToState(Lifecycle.State.STARTED)
                    assertTrue("Video did not pause", sample(scenario)?.optBoolean("paused") == true)
                }
                try {
                    http.newCall(Request.Builder().url(requireNotNull(healthUrl)).build()).execute().use { fail("Proxy remains available after player close") }
                } catch (_: java.io.IOException) { }
                report.appendText("${source.id} playback=passed pause=passed release=passed\n")
            } finally { PlaybackHistoryRepository.remove(context, item.pageUrl) }
        }
    }
    private fun sample(scenario: ActivityScenario<FullscreenInternalPlayerActivity>): JSONObject? {
        val latch = CountDownLatch(1)
        var result: JSONObject? = null
        scenario.onActivity { activity ->
            val field = FullscreenInternalPlayerActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
            val web = field.get(activity) as? WebView
            if (web == null) latch.countDown()
            else web.evaluateJavascript("(function(){var v=document.querySelector('video');return v?{time:v.currentTime,ready:v.readyState,paused:v.paused,error:v.error?v.error.code:0}:null;})()") { value ->
                result = runCatching { JSONObject(value) }.getOrNull(); latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }
}
