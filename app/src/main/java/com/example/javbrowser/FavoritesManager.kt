package com.example.javbrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class FavoritesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)
    private val KEY_FAVORITES = "favorites_list"

    fun addFavorite(title: String, url: String, thumbnailUrl: String? = null) {
        val favorites = getFavorites().toMutableList()
        // Avoid duplicates
        if (favorites.none { it.url == url }) {
            favorites.add(FavoriteItem(title, url, thumbnailUrl))
            saveFavorites(favorites)
        }
    }

    fun removeFavorite(url: String) {
        val favorites = getFavorites().toMutableList()
        favorites.removeAll { it.url == url }
        saveFavorites(favorites)
    }

    fun getFavorites(): List<FavoriteItem> {
        val jsonString = prefs.getString(KEY_FAVORITES, "[]")
        val list = mutableListOf<FavoriteItem>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val thumbnailUrl = if (obj.has("thumbnail")) obj.getString("thumbnail") else null
                list.add(FavoriteItem(obj.getString("title"), obj.getString("url"), thumbnailUrl))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveFavorites(list: List<FavoriteItem>) {
        val jsonArray = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("title", it.title)
            obj.put("url", it.url)
            if (it.thumbnailUrl != null) {
                obj.put("thumbnail", it.thumbnailUrl)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITES, jsonArray.toString()).apply()
    }
}
