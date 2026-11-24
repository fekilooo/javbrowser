package com.example.javbrowser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** Source catalogs use clean poster columns; actions live in navigation and the detail page. */
class SevenSourceGridAdapter(private val onOpen: (SevenSourceProtocol.Video) -> Unit) :
    ListAdapter<SevenSourceProtocol.Video, SevenSourceGridAdapter.Holder>(object : DiffUtil.ItemCallback<SevenSourceProtocol.Video>() {
        override fun areItemsTheSame(a: SevenSourceProtocol.Video, b: SevenSourceProtocol.Video) = a.stableId == b.stableId
        override fun areContentsTheSame(a: SevenSourceProtocol.Video, b: SevenSourceProtocol.Video) = a == b
    }) {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.iv_seven_cover)
        val title: TextView = view.findViewById(R.id.tv_seven_title)
        val duration: TextView = view.findViewById(R.id.tv_seven_duration)
        val source: TextView = view.findViewById(R.id.tv_seven_source)
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_seven_source_video, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val video = getItem(position)
        val context = holder.itemView.context
        holder.title.text = video.title
        holder.source.text = SevenSourceProtocol.source(video.source).name
        holder.duration.text = video.duration.ifBlank { LanguageManager.text(context, "正片", "Full video") }
        Glide.with(holder.cover).load(video.cover).placeholder(android.R.color.darker_gray)
            .error(android.R.color.darker_gray).centerCrop().into(holder.cover)
        holder.itemView.setOnClickListener { onOpen(video) }
    }
    override fun onViewRecycled(holder: Holder) { Glide.with(holder.cover).clear(holder.cover); super.onViewRecycled(holder) }
}
