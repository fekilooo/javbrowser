package com.example.javbrowser

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.webkit.CookieManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackHistoryActivity : LocalizedActivity() {
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private lateinit var adapter: HistoryAdapter
    private val artworkExecutor = Executors.newFixedThreadPool(2)
    private val artworkAttempts = mutableSetOf<String>()
    private val artworkClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val imageUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private fun t(zh: String, en: String) = LanguageManager.text(this, zh, en)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrivacySettings(this).isScreenSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val header = LinearLayout(this)
        header.addView(Button(this).apply { text = t("返回", "Back"); setOnClickListener { finish() } })
        header.addView(TextView(this).apply {
            text = t("播放紀錄", "Playback history"); textSize = 20f; setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(Button(this).apply {
            text = t("清空", "Clear")
            setOnClickListener {
                if (PlaybackHistoryRepository.list(this@PlaybackHistoryActivity).isEmpty()) return@setOnClickListener
                AlertDialog.Builder(this@PlaybackHistoryActivity).setTitle(t("清空播放紀錄？", "Clear playback history?"))
                    .setMessage(t("這會移除所有播放網頁紀錄。", "This removes all recorded playback pages."))
                    .setNegativeButton(t("取消", "Cancel"), null)
                    .setPositiveButton(t("清空", "Clear")) { _, _ -> PlaybackHistoryRepository.clear(this@PlaybackHistoryActivity); refresh() }.show()
            }
        })
        root.addView(header)
        search = EditText(this).apply {
            hint = t("搜尋影片、女優或網址", "Search titles, actresses or URLs")
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(TextView(this).apply {
            text = t("點選可回到原網頁；左滑刪除。最多保留最近 200 筆。", "Tap to reopen the page; swipe left to delete. Keeps the latest 200 records.")
            setTextColor(Color.LTGRAY); textSize = 12f; setPadding(0, dp(6), 0, dp(6))
        })
        empty = TextView(this).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(16), 0, dp(16)) }
        root.addView(empty)
        adapter = HistoryAdapter()
        val historyList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlaybackHistoryActivity)
            adapter = this@PlaybackHistoryActivity.adapter
        }
        root.addView(historyList, LinearLayout.LayoutParams(-1, 0, 1f))
        attachSwipeDeletion(historyList)
        setContentView(root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun attachSwipeDeletion(list: RecyclerView) {
        val background = Paint().apply { color = Color.rgb(157, 43, 43) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 14f * resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.RIGHT
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder) = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val position = holder.bindingAdapterPosition
                val item = adapter.items.getOrNull(position) ?: return
                PlaybackHistoryRepository.remove(this@PlaybackHistoryActivity, item.url)
                adapter.items = adapter.items.filterNot { it.url == item.url }
                adapter.notifyItemRemoved(position)
                updateEmptyState()
            }

            override fun onChildDraw(canvas: Canvas, recyclerView: RecyclerView,
                                     holder: RecyclerView.ViewHolder, dX: Float, dY: Float,
                                     actionState: Int, active: Boolean) {
                if (dX < 0 && actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val row = holder.itemView
                    canvas.save()
                    canvas.clipRect(row.right + dX, row.top.toFloat(), row.right.toFloat(), row.bottom.toFloat())
                    canvas.drawRoundRect(row.left.toFloat(), row.top.toFloat(), row.right.toFloat(), row.bottom.toFloat(),
                        dp(12).toFloat(), dp(12).toFloat(), background)
                    canvas.drawText(t("刪除", "Delete"), (row.right - dp(18)).toFloat(),
                        (row.top + row.bottom) / 2f - (label.ascent() + label.descent()) / 2f, label)
                    canvas.restore()
                }
                super.onChildDraw(canvas, recyclerView, holder, dX, dY, actionState, active)
            }
        }).attachToRecyclerView(list)
    }

    override fun onDestroy() {
        artworkClient.dispatcher.cancelAll()
        artworkExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun imageModel(url: String?, pageUrl: String): GlideUrl? {
        if (url == null) return null
        val headers = LazyHeaders.Builder().addHeader("User-Agent", imageUserAgent).addHeader("Referer", pageUrl)
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        if (cookies.isNotEmpty()) headers.addHeader("Cookie", cookies)
        return GlideUrl(url, headers.build())
    }

    private fun fillMissingArtwork(entry: PlaybackHistoryEntry) {
        if ((entry.coverUrl != null && entry.actresses.isNotEmpty()) || !artworkAttempts.add(entry.url)) return
        val cookies = CookieManager.getInstance().getCookie(entry.url).orEmpty()
        artworkExecutor.execute {
            runCatching {
                val request = Request.Builder().url(entry.url).header("User-Agent", imageUserAgent)
                if (cookies.isNotEmpty()) request.header("Cookie", cookies)
                artworkClient.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful || !response.header("Content-Type").orEmpty().contains("html", true)) return@use
                    val html = response.body?.string()?.take(1500000) ?: return@use
                    val images = PlaybackHistoryArtwork.parse(html, response.request.url.toString())
                    PlaybackHistoryRepository.updateArtwork(applicationContext, entry.url, images.cover, images.icon, images.actresses)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val index = adapter.items.indexOfFirst { it.url == entry.url }
                        val updated = PlaybackHistoryRepository.list(this).firstOrNull { it.url == entry.url }
                        if (index >= 0 && updated != null) {
                            adapter.items = adapter.items.toMutableList().also { it[index] = updated }
                            adapter.notifyItemChanged(index)
                        }
                    }
                }
            }
        }
    }

    private fun refresh() {
        val all = PlaybackHistoryRepository.list(this)
        val query = search.text.toString().trim()
        adapter.items = all.filter { it.title.contains(query, true) || it.url.contains(query, true) ||
            it.actresses.any { name -> name.contains(query, true) } }
        adapter.notifyDataSetChanged()
        updateEmptyState(all.isEmpty())
    }

    private fun updateEmptyState(noRecords: Boolean = PlaybackHistoryRepository.list(this).isEmpty()) {
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (noRecords) t("尚無播放紀錄；使用內建播放器後會自動記錄網頁。", "Pages are recorded when you use the internal player.")
            else t("沒有符合的紀錄", "No matching records")
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        var items = emptyList<PlaybackHistoryEntry>()
        inner class Holder(val row: LinearLayout, val cover: ImageView, val icon: ImageView,
            val site: TextView, val title: TextView, val actresses: TextView, val detail: TextView) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@PlaybackHistoryActivity).apply {
                setPadding(dp(10), dp(12), dp(10), dp(12)); minimumHeight = dp(112)
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Color.rgb(30, 27, 36)); cornerRadius = dp(12).toFloat() }
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            }
            val cover = ImageView(this@PlaybackHistoryActivity).apply {
                contentDescription = t("影片封面", "Video cover"); scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(cover, LinearLayout.LayoutParams(dp(116), dp(78)).apply { marginEnd = dp(10) })
            val info = LinearLayout(this@PlaybackHistoryActivity).apply { orientation = LinearLayout.VERTICAL }
            val siteRow = LinearLayout(this@PlaybackHistoryActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val icon = ImageView(this@PlaybackHistoryActivity).apply { contentDescription = t("網站圖示", "Website icon") }
            val site = TextView(this@PlaybackHistoryActivity).apply {
                setTextColor(Color.rgb(225, 195, 107)); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            siteRow.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) })
            siteRow.addView(site, LinearLayout.LayoutParams(0, -2, 1f))
            val title = TextView(this@PlaybackHistoryActivity).apply {
                setTextColor(Color.WHITE); textSize = 14f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, dp(5))
            }
            val detail = TextView(this@PlaybackHistoryActivity).apply {
                setTextColor(Color.LTGRAY); textSize = 12f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            }
            val actresses = TextView(this@PlaybackHistoryActivity).apply {
                setTextColor(Color.rgb(225, 195, 107)); textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(4))
            }
            info.addView(siteRow); info.addView(title); info.addView(actresses); info.addView(detail)
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            return Holder(row, cover, icon, site, title, actresses, detail)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.actresses.text = t("女優：", "Actress: ") + item.actresses.joinToString("、").ifEmpty { t("未提供", "Not provided") }
            holder.site.text = runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
            holder.detail.text = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(item.playedAt))
            Glide.with(holder.cover).load(imageModel(item.coverUrl, item.url))
                .placeholder(ColorDrawable(Color.rgb(48, 44, 38))).error(android.R.drawable.ic_menu_gallery)
                .transform(CenterCrop(), RoundedCorners(dp(6))).into(holder.cover)
            val iconUrl = item.iconUrl ?: runCatching { URI(item.url).resolve("/favicon.ico").toString() }.getOrNull()
            Glide.with(holder.icon).load(imageModel(iconUrl, item.url)).placeholder(android.R.drawable.ic_menu_view)
                .error(android.R.drawable.ic_menu_view).fitCenter().into(holder.icon)
            holder.row.setOnClickListener { BrowserNavigator.open(this@PlaybackHistoryActivity, item.url, returnToHistory = true) }
            fillMissingArtwork(item)
        }
    }
}
