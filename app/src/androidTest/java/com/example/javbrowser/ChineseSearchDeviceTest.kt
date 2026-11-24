package com.example.javbrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChineseSearchDeviceTest {
    @Test fun packagedDictionariesAndMergedLiveSourceSearchWork() {
        assertEquals(listOf("台灣", "台湾"), ChineseSearchVariants.variants("台灣"))
        assertEquals(listOf("乾隆與頭髮", "乾隆与头发"), ChineseSearchVariants.variants("乾隆與頭髮"))
        assertEquals(listOf("ABC-123"), ChineseSearchVariants.variants("ABC-123"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = File(context.cacheDir, "chinese-search-device-test.txt").apply { writeText("") }
        for (source in SevenSourceProtocol.sources) {
            val page = SevenSourceClient().page(source, "recent", 1, "台灣", SearchCancellation())
            assertTrue("No merged search results from ${source.id}", page.videos.isNotEmpty())
            assertEquals(page.videos.size, page.videos.distinctBy { it.stableId }.size)
            report.appendText("${source.id} mergedCount=${page.videos.size} unique=true\n")
        }
    }
}
