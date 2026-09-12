package com.example.javbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Recyclable source list for groups with multiple site/version links. */
object UnifiedSearchSourceSheet {
    fun show(context: Context, group: SearchResultGroup, siteNames: Map<String, String>, onOpen: (SearchItem) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8))
        }
        root.addView(TextView(context).apply {
            text = group.code?.let { "$it · ${group.title}" } ?: group.title
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(context, 8))
        })
        root.addView(TextView(context).apply {
            text = LanguageManager.text(context, "選擇要開啟的來源；可複製網址。", "Choose a source to open or copy its URL.")
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(context, 8))
        })
        root.addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = SourceAdapter(context, group, siteNames, onOpen) { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 360))
        })
        dialog.setContentView(root)
        dialog.show()
    }

    private class SourceAdapter(private val context: Context, private val group: SearchResultGroup,
                                 private val siteNames: Map<String, String>, private val onOpen: (SearchItem) -> Unit,
                                 private val close: () -> Unit) : RecyclerView.Adapter<SourceAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        })
        override fun getItemCount() = group.links.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = group.links[position]
            val site = siteNames[item.siteId] ?: item.siteId
            holder.root.removeAllViews()
            holder.root.addView(TextView(context).apply {
                text = site + " · " + SearchResultGrouping.linkLabel(group, item, site, LanguageManager.isEnglish(context))
                textSize = 13f; setTextColor(Color.WHITE)
            })
            holder.root.addView(TextView(context).apply {
                text = item.title; textSize = 11f; setTextColor(Color.LTGRAY); maxLines = 2
            })
            holder.root.addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(Button(context).apply {
                    text = LanguageManager.text(context, "複製", "Copy"); isAllCaps = false; minWidth = 0
                    setOnClickListener {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("URL", item.detailUrl))
                    }
                })
                addView(Button(context).apply {
                    text = LanguageManager.text(context, "開啟", "Open"); isAllCaps = false; minWidth = 0
                    setOnClickListener { onOpen(item); close() }
                })
            })
        }
        class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root)
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
