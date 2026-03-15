package com.example.javbrowser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FavoritesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: android.widget.EditText
    private lateinit var btnHome: android.widget.Button
    private lateinit var btnImport: android.widget.Button
    private lateinit var btnExport: android.widget.Button
    private lateinit var adapter: FavoritesAdapter
    private lateinit var favoritesManager: FavoritesManager
    private var allFavorites: List<FavoriteItem> = emptyList()

    companion object {
        private const val REQUEST_CODE_EXPORT = 2001
        private const val REQUEST_CODE_IMPORT = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and hide content in recent apps
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_favorites)

        favoritesManager = FavoritesManager(this)
        recyclerView = findViewById(R.id.rv_favorites)
        tvEmpty = findViewById(R.id.tv_empty)
        etSearch = findViewById(R.id.et_search)
        btnHome = findViewById(R.id.btn_home)
        btnImport = findViewById(R.id.btn_import)
        btnExport = findViewById(R.id.btn_export)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = FavoritesAdapter(mutableListOf()) { item ->
            // On Item Click
            val intent = Intent()
            intent.putExtra("url", item.url)
            setResult(RESULT_OK, intent)
            finish()
        }
        recyclerView.adapter = adapter

        setupSearch()
        setupHomeButton()
        setupBackupButtons()
        loadFavorites()
    }
    
    private fun setupHomeButton() {
        btnHome.setOnClickListener {
            finish() // Go back to MainActivity
        }
    }
    
    private fun setupBackupButtons() {
        btnExport.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "javbrowser_favorites.json")
            }
            startActivityForResult(intent, REQUEST_CODE_EXPORT)
        }

        btnImport.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, REQUEST_CODE_IMPORT)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data?.data != null) {
            when (requestCode) {
                REQUEST_CODE_EXPORT -> {
                    val success = favoritesManager.exportFavoritesToFile(this, data.data!!)
                    val msg = if (success) "匯出成功" else "匯出失敗"
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                REQUEST_CODE_IMPORT -> {
                    val result = favoritesManager.importFavoritesFromFile(this, data.data!!)
                    android.widget.Toast.makeText(this, result.second, android.widget.Toast.LENGTH_SHORT).show()
                    if (result.first) {
                        loadFavorites()
                    }
                }
            }
        }
    }
    
    private fun setupSearch() {
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterFavorites(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
    
    private fun filterFavorites(query: String) {
        val filtered = if (query.isEmpty()) {
            allFavorites
        } else {
            allFavorites.filter { 
                it.title.contains(query, ignoreCase = true) || 
                it.url.contains(query, ignoreCase = true)
            }
        }
        adapter.updateList(filtered)
        
        if (filtered.isEmpty()) {
            tvEmpty.text = if (query.isEmpty()) "No favorites yet" else "找不到符合的收藏"
            tvEmpty.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun loadFavorites() {
        allFavorites = favoritesManager.getFavorites()
        filterFavorites(etSearch.text.toString())
    }

    inner class FavoritesAdapter(
        private var items: MutableList<FavoriteItem>,
        private val onItemClick: (FavoriteItem) -> Unit
    ) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_title)
            val tvUrl: TextView = view.findViewById(R.id.tv_url)
            val ivThumbnail: android.widget.ImageView = view.findViewById(R.id.iv_thumbnail)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorite, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            
            // Extract and display domain only
            val domain = extractDomain(item.url)
            holder.tvUrl.text = "來自: $domain"
            
            // Load thumbnail using Glide
            if (!item.thumbnailUrl.isNullOrEmpty()) {
                com.bumptech.glide.Glide.with(holder.itemView.context)
                    .load(item.thumbnailUrl)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.drawable.ic_menu_gallery)
                    .centerCrop()
                    .into(holder.ivThumbnail)
            } else {
                // Set default icon based on site
                val iconRes = when {
                    item.url.contains("missav") -> android.R.drawable.ic_menu_camera
                    item.url.contains("jable") -> android.R.drawable.ic_menu_gallery
                    item.url.contains("rou.video") || item.url.contains("rouva") -> android.R.drawable.ic_menu_view
                    else -> android.R.drawable.ic_menu_gallery
                }
                holder.ivThumbnail.setImageResource(iconRes)
            }
            
            holder.itemView.setOnClickListener { onItemClick(item) }
            
            holder.btnDelete.setOnClickListener {
                favoritesManager.removeFavorite(item.url)
                items.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, items.size)
                
                if (items.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                }
            }
        }

        override fun getItemCount() = items.size

        fun updateList(newItems: List<FavoriteItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
        
        private fun extractDomain(url: String): String {
            return try {
                val uri = java.net.URI(url)
                uri.host ?: url
            } catch (e: Exception) {
                url
            }
        }
    }
}
