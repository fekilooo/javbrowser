package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.widget.Toast

/** 統一從結果頁回到可切換圖示的主瀏覽器入口。 */
object BrowserNavigator {
    const val EXTRA_RETURN_TO_SEARCH = "return_to_unified_search"
    fun open(context: Context, url: String): Boolean {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", true) && !cleanUrl.startsWith("https://", true)) {
            Toast.makeText(context, "結果網址無效", Toast.LENGTH_SHORT).show()
            return false
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent == null) {
            Toast.makeText(context, "找不到 APP 主頁入口", Toast.LENGTH_SHORT).show()
            return false
        }
        // Keep the search Activity below a fresh browser instance, including with launcher aliases.
        launchIntent.flags = 0
        launchIntent.putExtra(MainActivity.EXTRA_URL, cleanUrl)
            .putExtra(EXTRA_RETURN_TO_SEARCH, true)
        if (context !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrElse {
            Toast.makeText(context, "無法開啟結果頁", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
