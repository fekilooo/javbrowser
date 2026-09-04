package com.example.javbrowser.nativeapp.data

import android.content.Context
import com.example.javbrowser.nativeapp.domain.*
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("native_library", Context.MODE_PRIVATE)
    init {
        if (!prefs.getBoolean("legacy_migration_complete", false)) {
            val legacy = context.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE).getString("favorites_list", null)
            if (!legacy.isNullOrBlank() && favorites().isEmpty()) save(LegacyMigration.favorites(legacy))
            prefs.edit().putBoolean("legacy_migration_complete", true).apply()
        }
    }
    fun favorites(): List<JavTitle> = runCatching {
        val array = JSONArray(prefs.getString("favorites", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i); val code = o.optString("code").ifBlank { null }
            JavTitle(o.getString("id"), code, o.getString("title"), coverUrl = o.optString("cover").ifBlank { null },
                sourceRefs = o.optJSONArray("refs")?.let { refs -> (0 until refs.length()).map { j -> val r=refs.getJSONObject(j); SourceRef(r.getString("source"),r.getString("id"),r.optString("url").ifBlank { null }) } }.orEmpty())
        }
    }.getOrDefault(emptyList())

    fun isFavorite(id: String) = favorites().any { it.id == id }
    fun toggle(title: JavTitle): Boolean {
        val list = favorites().toMutableList(); val exists = list.removeAll { it.id == title.id }; if (!exists) list += title
        save(list); return !exists
    }

    private fun save(list: List<JavTitle>) {
        val array = JSONArray(); list.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("code", item.code ?: "").put("title", item.title).put("cover", item.coverUrl ?: "")
                .put("refs", JSONArray().apply { item.sourceRefs.forEach { r -> put(JSONObject().put("source",r.sourceId).put("id",r.id).put("url",r.url ?: "")) } }))
        }
        prefs.edit().putString("favorites", array.toString()).apply()
    }
}
