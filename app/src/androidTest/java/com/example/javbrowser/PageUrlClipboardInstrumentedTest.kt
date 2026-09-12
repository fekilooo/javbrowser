package com.example.javbrowser

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.webkit.WebView
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PageUrlClipboardInstrumentedTest {
    @Test fun floatingButtonCopiesFinalUrlAfterRedirectAndHistoryChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = object : NanoHTTPD(18129) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/redirect") return newFixedLengthResponse(Response.Status.REDIRECT,
                    "text/plain", "").apply { addHeader("Location", "/page?value=1%202#final") }
                return newFixedLengthResponse("<html><title>Local URL test</title><body>Local test only</body></html>")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                flags = 0
                putExtra(MainActivity.EXTRA_URL, "http://127.0.0.1:18129/redirect")
                putExtra(BrowserNavigator.EXTRA_RETURN_TO_SEARCH, true)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val ready = CountDownLatch(1)
                val until = android.os.SystemClock.elapsedRealtime() + 8000
                scenario.onActivity { activity ->
                    val web = activity.findViewById<WebView>(R.id.webView)
                    web.post(object : Runnable {
                        override fun run() {
                            if (web.url?.contains("/page?") == true &&
                                activity.findViewById<Button>(R.id.btn_copy_page_url).visibility == View.VISIBLE) ready.countDown()
                            else if (android.os.SystemClock.elapsedRealtime() < until) web.postDelayed(this, 100)
                        }
                    })
                }
                assertTrue("Local redirected page did not load", ready.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    val button = activity.findViewById<Button>(R.id.btn_copy_page_url)
                    val web = activity.findViewById<WebView>(R.id.webView)
                    assertEquals(View.VISIBLE, button.visibility)
                    button.performClick()
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals(web.url, clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
                val changed = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.webView).evaluateJavascript(
                        "history.pushState({}, '', '/page?changed=2#spa')") { changed.countDown() }
                }
                assertTrue(changed.await(3, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.btn_copy_page_url).performClick()
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals("http://127.0.0.1:18129/page?changed=2#spa", clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
            }
        } finally { server.stop() }
    }
}
