package com.example.javbrowser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FullscreenInternalPlayerActivityTest {

    @Test
    fun systemNavigationAndSubtitleFriendlyLandscapeAreDefaultAndDisplayModesPersist() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("internal_player_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val intent = Intent(context, FullscreenInternalPlayerActivity::class.java).apply {
            putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, "https://media.invalid/video.mp4")
            putExtra(FullscreenInternalPlayerActivity.EXTRA_REFERER, "https://missav.test/video")
        }

        try {
            ActivityScenario.launch<FullscreenInternalPlayerActivity>(intent).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                SystemClock.sleep(700)
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0)
                }

                val friendlyLayout = waitForEvaluation(scenario) {
                    "(function(){var v=document.getElementById('video'),c=document.getElementById('controls');" +
                        "return String(!!v&&!!c&&innerWidth>innerHeight&&" +
                        "v.getBoundingClientRect().bottom<=c.getBoundingClientRect().top+2)+" +
                        "'|'+innerWidth+'x'+innerHeight+'|'+(v?v.getBoundingClientRect().bottom:'missing')+" +
                        "'|'+(c?c.getBoundingClientRect().top:'missing');})()"
                }
                assertTrue("friendlyLayout=$friendlyLayout", friendlyLayout.contains("true|"))

                scenario.onActivity { activity ->
                    findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                        .evaluateJavascript("document.getElementById('picture-mode').click()", null)
                }
                SystemClock.sleep(250)
                val maximumLayout = waitForEvaluation(scenario) {
                    "(function(){var v=document.getElementById('video');return String(!!v&&" +
                        "v.getBoundingClientRect().height>=innerHeight-2)+'|'+" +
                        "(v?v.getBoundingClientRect().height:'missing')+'|'+innerHeight;})()"
                }
                assertTrue("maximumLayout=$maximumLayout", maximumLayout.contains("true|"))
                assertTrue(preferences.getBoolean("max_picture_mode", false))

                scenario.onActivity { activity ->
                    findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                        .evaluateJavascript("document.getElementById('immersive-mode').click()", null)
                }
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
                }
                assertTrue(preferences.getBoolean("immersive_mode", false))
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun missAvQualityOptionsAndCurrentLandscapeLockWork() {
        PrivacySettings(ApplicationProvider.getApplicationContext()).pressHoldPlaybackRate = 3.0
        val server = object : NanoHTTPD(18123) {
            override fun serve(session: IHTTPSession): Response {
                val body = when (session.uri) {
                    "/master.m3u8" -> """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1920x1080
                        1080/index.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720
                        720/index.m3u8
                    """.trimIndent()
                    else -> "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-ENDLIST"
                }
                return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", body)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val intent = Intent(
                ApplicationProvider.getApplicationContext(),
                FullscreenInternalPlayerActivity::class.java
            ).apply {
                putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, "http://127.0.0.1:18123/master.m3u8")
                putExtra(FullscreenInternalPlayerActivity.EXTRA_REFERER, "https://missav.test/video")
            }
            ActivityScenario.launch<FullscreenInternalPlayerActivity>(intent).use { scenario ->
                scenario.onActivity {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                SystemClock.sleep(900)
                scenario.onActivity { activity ->
                    val webView = findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                    webView.evaluateJavascript("document.getElementById('orientation').click()", null)
                }
                SystemClock.sleep(500)
                scenario.onActivity {
                    assertTrue(
                        it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED ||
                            it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                            it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    )
                }

                var options = ""
                repeat(10) {
                    val latch = CountDownLatch(1)
                    scenario.onActivity { activity ->
                        val webView = findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                        webView.evaluateJavascript(
                            "Array.from(document.querySelectorAll('#quality option')).map(function(o){return o.textContent}).join(',')"
                        ) {
                            options = it.orEmpty()
                            latch.countDown()
                        }
                    }
                    latch.await(2, TimeUnit.SECONDS)
                    if (options.contains("1080p") && options.contains("720p")) return@repeat
                    SystemClock.sleep(400)
                }
                assertTrue(options.contains("1080p"))
                assertTrue(options.contains("720p"))
                assertTrue(options.contains("自動") || options.contains("Auto"))

                scenario.onActivity { activity ->
                    val webView = findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                    webView.evaluateJavascript(
                        "try{Object.defineProperty(video,'paused',{get:function(){return false},configurable:true})}catch(e){};" +
                            "video.playbackRate=1;video.dispatchEvent(new PointerEvent('pointerdown'," +
                            "{pointerId:7,pointerType:'touch',clientX:500,clientY:400,bubbles:true}))",
                        null
                    )
                }
                SystemClock.sleep(500)
                var holdState = ""
                val holdLatch = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val webView = findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                    webView.evaluateJavascript(
                        "String(video.playbackRate)+'|'+document.getElementById('press-hold-speed').style.display"
                    ) {
                        holdState = it.orEmpty()
                        holdLatch.countDown()
                    }
                }
                holdLatch.await(2, TimeUnit.SECONDS)
                assertTrue(holdState.contains("3"))
                assertTrue(holdState.contains("flex"))

                scenario.onActivity { activity ->
                    val webView = findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                    webView.evaluateJavascript(
                        "video.dispatchEvent(new PointerEvent('pointerup'," +
                            "{pointerId:7,pointerType:'touch',clientX:500,clientY:400,bubbles:true}))",
                        null
                    )
                }
                SystemClock.sleep(100)
                var restoredRate = ""
                val restoreLatch = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val webView = findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                    webView.evaluateJavascript("String(video.playbackRate)") {
                        restoredRate = it.orEmpty()
                        restoreLatch.countDown()
                    }
                }
                restoreLatch.await(2, TimeUnit.SECONDS)
                assertTrue(restoredRate.contains("1"))
            }
        } finally {
            server.stop()
        }
    }

    private fun findWebView(root: android.view.ViewGroup): WebView {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is WebView) return child
            if (child is android.view.ViewGroup) {
                runCatching { return findWebView(child) }
            }
        }
        throw AssertionError("WebView not found")
    }

    private fun evaluate(
        scenario: ActivityScenario<FullscreenInternalPlayerActivity>,
        script: String
    ): String {
        var result = ""
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            findWebView(activity.window.decorView.rootView as android.view.ViewGroup)
                .evaluateJavascript(script) {
                    result = it.orEmpty()
                    latch.countDown()
                }
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun waitForEvaluation(
        scenario: ActivityScenario<FullscreenInternalPlayerActivity>,
        script: () -> String
    ): String {
        var result = ""
        repeat(20) {
            result = evaluate(scenario, script())
            if (result.contains("true|")) return result
            SystemClock.sleep(250)
        }
        return result
    }
}
