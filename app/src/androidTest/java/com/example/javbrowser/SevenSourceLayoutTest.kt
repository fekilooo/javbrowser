package com.example.javbrowser

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Detached layout checks use synthetic metadata and never launch a screen or media. */
@RunWith(AndroidJUnit4::class)
class SevenSourceLayoutTest {
    @Test fun posterGridAndDetailPreviewFollowRequestedLayout() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(app, R.style.Theme_JAVBrowser)
            val width = (360 * context.resources.displayMetrics.density).toInt()
            val height = (640 * context.resources.displayMetrics.density).toInt()
            val grid = RecyclerView(context).apply { layoutManager = GridLayoutManager(context, 3) }
            val data = (0 until 6).map { index -> SevenSourceProtocol.Video("xchina", "fixture-$index", "Layout fixture $index",
                null, "01:30", "Synthetic description", "Fixture", "2026-11-10") }
            val adapter = SevenSourceGridAdapter({})
            adapter.submitList(data)
            grid.adapter = adapter
            grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            grid.layout(0, 0, width, height)
            assertEquals(3, (grid.layoutManager as GridLayoutManager).spanCount)
            val first = requireNotNull(grid.findViewHolderForAdapterPosition(0)).itemView
            val third = requireNotNull(grid.findViewHolderForAdapterPosition(2)).itemView
            val image = first.findViewById<ImageView>(R.id.iv_seven_cover)
            assertTrue(image.width > 0)
            assertEquals(1.5, image.height.toDouble() / image.width, 0.03)
            assertEquals(first.top, third.top)
            assertTrue(third.left > first.left)
            assertEquals(2, first.findViewById<TextView>(R.id.tv_seven_title).maxLines)
            fun countImageButtons(view: View): Int = if (view is android.widget.ImageButton) 1 else
                if (view is android.view.ViewGroup) (0 until view.childCount).sumOf { countImageButtons(view.getChildAt(it)) } else 0
            assertEquals(0, countImageButtons(first))

            val navigation = LayoutInflater.from(context).inflate(R.layout.view_browser_navigation, null)
            listOf(R.id.btn_home, R.id.btn_add_favorite, R.id.btn_view_favorites, R.id.btn_downloads,
                R.id.btn_settings, R.id.btn_play).forEach { assertNotNull(navigation.findViewById<View>(it)) }
            assertEquals("♡", navigation.findViewById<TextView>(R.id.btn_add_favorite).text.toString())

            val search = LayoutInflater.from(context).inflate(R.layout.view_browser_search, null)
            val query = search.findViewById<android.widget.EditText>(R.id.et_unified_query)
            assertEquals(android.graphics.Color.WHITE, query.currentTextColor)
            assertEquals(android.graphics.Color.parseColor("#999999"), query.currentHintTextColor)
            assertEquals(android.graphics.Color.parseColor("#BB86FC"), query.backgroundTintList!!.defaultColor)
            assertNotNull(search.findViewById<View>(R.id.btn_unified_search))

            val detail = LayoutInflater.from(context).inflate(R.layout.view_seven_source_detail, null)
            detail.findViewById<TextView>(R.id.tv_seven_detail_title).text = "Layout fixture"
            detail.findViewById<TextView>(R.id.tv_seven_detail_description_label).text = "About this video"
            detail.findViewById<TextView>(R.id.tv_seven_detail_description).text = "Synthetic description"
            detail.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            detail.layout(0, 0, width, detail.measuredHeight)
            val preview = detail.findViewById<View>(R.id.seven_playback_preview)
            val poster = detail.findViewById<ImageView>(R.id.iv_seven_detail_cover)
            val description = detail.findViewById<View>(R.id.tv_seven_detail_description)
            assertTrue(poster.width > 0)
            assertEquals(9.0 / 16, poster.height.toDouble() / poster.width, 0.03)
            assertTrue(description.top > preview.bottom)
            assertEquals(0, (detail as android.view.ViewGroup).let { parent ->
                (0 until parent.childCount).count { parent.getChildAt(it) is android.widget.Button }
            })
            assertNotNull(detail.findViewById<View>(R.id.btn_seven_detail_play))
            grid.adapter = null
        }
    }
}
