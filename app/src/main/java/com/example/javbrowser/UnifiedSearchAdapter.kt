package com.example.javbrowser

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Result cards use background projection and AsyncListDiffer-backed updates. */
class UnifiedSearchAdapter(
    private val siteNames: Map<String, String>,
    private val onOpen: (SearchItem) -> Unit,
    private val onShowSources: (SearchResultGroup) -> Unit
) : ListAdapter<SearchResultGroup, UnifiedSearchAdapter.ViewHolder>(DIFF) {
    private val projectionExecutor = Executors.newSingleThreadExecutor()
    private val projectionSerial = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var orderFrozen = false
    @Volatile private var closed = false
    @Volatile private var localIndex = SearchLocalMetadataIndex.EMPTY
    var onListCommitted: (() -> Unit)? = null

    fun submitItems(newItems: List<SearchItem>) {
        val serial = projectionSerial.incrementAndGet()
        if (newItems.isEmpty()) {
            mainHandler.post { if (!closed && serial == projectionSerial.get()) submitList(emptyList()) }
            return
        }
        val existingOrder = currentList.map { it.stableId }
        val frozen = orderFrozen
        projectionExecutor.execute {
            val grouped = SearchResultGrouping.group(newItems)
            val projected = if (!frozen) grouped else {
                val byId = grouped.associateBy { it.stableId }
                existingOrder.mapNotNull(byId::get) + grouped.filter { it.stableId !in existingOrder }
            }
            mainHandler.post {
                if (!closed && serial == projectionSerial.get()) submitList(projected)
            }
        }
    }

    fun setOrderFrozen(frozen: Boolean) { orderFrozen = frozen }
    fun setLocalMetadata(index: SearchLocalMetadataIndex) {
        localIndex = index
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, BADGE_PAYLOAD)
    }
    fun groupAt(position: Int): SearchResultGroup? = currentList.getOrNull(position)
    fun positionOf(groupId: String): Int = currentList.indexOfFirst { it.stableId == groupId }
    fun close() { closed = true; projectionSerial.incrementAndGet(); projectionExecutor.shutdownNow() }

    override fun onCurrentListChanged(previousList: List<SearchResultGroup>, currentList: List<SearchResultGroup>) {
        super.onCurrentListChanged(previousList, currentList)
        onListCommitted?.invoke()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_unified_search, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = getItem(position)
        val context = holder.itemView.context
        val english = LanguageManager.isEnglish(context)
        holder.title.text = group.title
        holder.meta.text = buildString {
            append(group.code ?: LanguageManager.text(context, "未辨識番號", "Code unidentified"))
            append(" · ").append(LanguageManager.text(context,
                "${group.links.map { it.siteId }.distinct().size} 站／${group.links.size} 個連結",
                "${group.links.map { it.siteId }.distinct().size} sites / ${group.links.size} links"))
        }
        val flags = localIndex.match(group)
        holder.badges.apply {
            text = buildList {
                if (flags.favorite) add(LanguageManager.text(context, "已收藏", "Saved"))
                if (flags.downloaded) add(LanguageManager.text(context, "已有本機檔案", "Downloaded"))
            }.joinToString(" · ")
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        holder.links.removeAllViews()
        val previews = group.links.distinctBy { it.siteId }.take(3)
        previews.forEach { item -> holder.links.addView(sourceChip(context, group, item, english)) }
        if (group.links.size > previews.size) {
            holder.links.addView(Chip(context).apply {
                text = LanguageManager.text(context, "全部 ${group.links.size}", "All ${group.links.size}")
                textSize = 11f
                isCheckable = false
                isCheckedIconVisible = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(70, 55, 78))
                setTextColor(android.graphics.Color.WHITE)
                contentDescription = LanguageManager.text(context, "查看全部來源", "View all sources")
                setOnClickListener { onShowSources(group) }
            })
        }
        holder.title.setOnClickListener { onShowSources(group) }
        holder.cover.setOnClickListener { onShowSources(group) }
        holder.links.contentDescription = LanguageManager.text(context, "搜尋結果來源", "Search result sources")
        Glide.with(holder.cover.context).load(group.coverUrl)
            .placeholder(android.R.color.darker_gray).error(android.R.color.darker_gray).into(holder.cover)
    }

    private fun sourceChip(context: android.content.Context, group: SearchResultGroup,
                           item: SearchItem, english: Boolean): Chip = Chip(context).apply {
        text = SearchResultGrouping.linkLabel(group, item, siteNames[item.siteId] ?: item.siteId, english)
        textSize = 11f
        isCheckable = false
        isCheckedIconVisible = false
        isCloseIconVisible = false
        setEnsureMinTouchTargetSize(true)
        maxWidth = (context.resources.displayMetrics.widthPixels - 40 * context.resources.displayMetrics.density).toInt()
        ellipsize = android.text.TextUtils.TruncateAt.END
        chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(187, 134, 252))
        setTextColor(android.graphics.Color.rgb(36, 21, 43))
        contentDescription = LanguageManager.text(context, "開啟", "Open") + " " + text + " · " + item.title
        setOnClickListener { onOpen(item) }
        setOnLongClickListener { onShowSources(group); true }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.iv_unified_cover)
        val title: TextView = view.findViewById(R.id.tv_unified_item_title)
        val meta: TextView = view.findViewById(R.id.tv_unified_item_meta)
        val badges: TextView = view.findViewById(R.id.tv_unified_item_badges)
        val links: ChipGroup = view.findViewById(R.id.unified_item_links)
    }

    companion object {
        private val BADGE_PAYLOAD = Any()
        private val DIFF = object : androidx.recyclerview.widget.DiffUtil.ItemCallback<SearchResultGroup>() {
            override fun areItemsTheSame(old: SearchResultGroup, new: SearchResultGroup) = old.stableId == new.stableId
            override fun areContentsTheSame(old: SearchResultGroup, new: SearchResultGroup) = old == new
        }
    }
}
