package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.app.Activity

/** 新着列表與影片內容頁共用的統一搜尋入口。 */
object CrossSiteSearchUi {
    /** 保留舊入口名稱，讓既有 Activity 不必同時改動即可切換到統一結果頁。 */
    fun show(context: Context, rawCode: String) {
        val extracted = extractCode(rawCode)
        val query = extracted.ifBlank { rawCode.trim() }
        if (query.isBlank()) return
        val intent = Intent(context, UnifiedSearchActivity::class.java).apply {
            putExtra(UnifiedSearchActivity.EXTRA_QUERY, query)
            putExtra(UnifiedSearchActivity.EXTRA_FORCE_CODE, extracted.isNotBlank())
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun extractCode(text: String): String =
        SearchQueryNormalizer.extractCode(text).orEmpty()
}
