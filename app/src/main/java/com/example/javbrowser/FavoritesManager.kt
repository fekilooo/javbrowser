package com.example.javbrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class FavoritesManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)
    private val KEY_FAVORITES = "favorites_list"
    private val KEY_GALLERY_LOOKUP_ATTEMPTED = "gallery_lookup_attempted_urls"

    fun addFavorite(title: String, url: String, thumbnailUrl: String? = null, javCode: String? = null): Boolean {
        val favorites = getFavorites().toMutableList()
        // 比對 URL
        if (favorites.any { it.url == url }) return false
        // 比對番號（正規化後比對），防止 REBD-01-022 與 REBD-1022 重複存入
        val safeJavCode = if (isStripchatUrl(url)) null else
            JavDbScraper.extractFc2Code(url, title, javCode.orEmpty()) ?: javCode
        val normalizedNew = if (!safeJavCode.isNullOrEmpty()) JavDbScraper.normalizeJavCode(safeJavCode) else null
        if (!normalizedNew.isNullOrEmpty() && favorites.any {
                !it.javCode.isNullOrEmpty() &&
                JavDbScraper.normalizeJavCode(it.javCode) == normalizedNew
            }) return false
        val storedCode = normalizedNew ?: safeJavCode
        favorites.add(FavoriteItem(title, url, thumbnailUrl, javCode = storedCode, addedAt = System.currentTimeMillis()))
        saveFavorites(favorites)
        return true
    }

    fun updateFavoriteUserData(url: String, customTags: List<String>, note: String) {
        val list = getFavorites().toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index >= 0) {
            list[index] = list[index].copy(
                customTags = normalizeCustomTags(customTags),
                note = note.trim()
            )
            saveFavorites(list)
        }
    }

    fun removeFavorite(url: String) {
        val favorites = getFavorites().toMutableList()
        favorites.removeAll { it.url == url }
        saveFavorites(favorites)
        clearGalleryLookupAttempted(url)
        FavoriteCoverCache(appContext).delete(url)
    }

    fun updateFavoriteThumbnail(url: String, thumbnailUrl: String?) {
        val list = getFavorites().toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index < 0) return
        list[index] = list[index].copy(thumbnailUrl = thumbnailUrl?.trim()?.takeIf(String::isNotEmpty))
        saveFavorites(list)
    }

    /** 更新書籤的 gallery 縮圖列表 */
    fun updateGalleryImages(url: String, images: List<String>, trailerUrl: String? = null) {
        val list = getFavorites().toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index >= 0) {
            list[index] = list[index].copy(
                galleryImages = images,
                trailerUrl = trailerUrl ?: list[index].trailerUrl
            )
            saveFavorites(list)
        }
        markGalleryLookupAttempted(url)
    }

    fun hasGalleryLookupAttempted(url: String): Boolean =
        prefs.getStringSet(KEY_GALLERY_LOOKUP_ATTEMPTED, emptySet())?.contains(url) == true

    fun markGalleryLookupAttempted(url: String) {
        val current = prefs.getStringSet(KEY_GALLERY_LOOKUP_ATTEMPTED, emptySet()).orEmpty()
        if (current.contains(url)) return
        prefs.edit()
            .putStringSet(KEY_GALLERY_LOOKUP_ATTEMPTED, current.toMutableSet().apply { add(url) })
            .apply()
    }

    private fun clearGalleryLookupAttempted(url: String) {
        val current = prefs.getStringSet(KEY_GALLERY_LOOKUP_ATTEMPTED, emptySet()).orEmpty()
        if (!current.contains(url)) return
        prefs.edit()
            .putStringSet(KEY_GALLERY_LOOKUP_ATTEMPTED, current.toMutableSet().apply { remove(url) })
            .apply()
    }

    /** 更新書籤的跨站相關 URL */
    fun updateRelatedUrls(url: String, relatedUrls: Map<String, String>) {
        if (isStripchatUrl(url)) return
        val list = getFavorites().toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index >= 0) {
            list[index] = list[index].copy(relatedUrls = relatedUrls)
            saveFavorites(list)
        }
    }

    /** 復原一筆完整書籤（含所有 enriched 資料），插回清單最前面 */
    fun restoreFavorite(item: FavoriteItem) {
        val list = getFavorites().toMutableList()
        if (list.none { it.url == item.url }) {
            list.add(0, sanitizeFc2Favorite(sanitizeStripchatFavorite(item)))
            saveFavorites(list)
        }
    }

    /** Called after JavDB scraping completes; updates the existing entry in-place. */
    fun updateFavoriteDetail(url: String, detail: JavVideoDetail) {
        if (isStripchatUrl(url)) return
        val list = getFavorites().toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index >= 0) {
            val old = list[index]
            val fc2Code = JavDbScraper.extractFc2Code(old.url, old.title, old.javCode.orEmpty(), detail.code)
            if (fc2Code != null && detail.detailUrl.contains("javdb.com", ignoreCase = true)) return

            // Auto-add "中文字幕" tag when appropriate
            val genres = if (fc2Code != null) {
                (old.genres + detail.genres).distinctBy { it.trim().lowercase() }.toMutableList()
            } else {
                detail.genres.toMutableList()
            }
            if (!genres.contains("中文字幕") && hasChineseSubtitle(old.title, old.url)) {
                genres.add("中文字幕")
            }

            // 若書籤標題仍是裸番號（JavTrailers 沒抓到），用 JavDB 的完整標題替換
            val currentTitleIsCode = old.title.trim().uppercase() == detail.code.trim().uppercase()
            val newTitle = if (currentTitleIsCode && detail.title.isNotEmpty())
                "${detail.code} ${detail.title}".trim()
            else old.title

            // 若封面還是空的（JavTrailers 沒找到），用 JavDB 的封面補上
            val newThumbnail = if (old.thumbnailUrl.isNullOrEmpty() && detail.coverUrl.isNotEmpty())
                detail.coverUrl
            else old.thumbnailUrl

            list[index] = old.copy(
                title = newTitle,
                thumbnailUrl = newThumbnail,
                javCode = fc2Code ?: detail.code,
                releaseDate = detail.date,
                rating = detail.rating,
                maker = detail.maker,
                series = detail.series,
                genres = genres,
                actors = detail.actors,
                isEnriched = true,
                javdbUrl = detail.detailUrl.takeIf { it.isNotEmpty() }
            )
            saveFavorites(list)
        }
    }

    private fun hasChineseSubtitle(title: String, url: String): Boolean {
        // Explicit keyword
        if (title.contains("中文")) return true
        // No Hiragana/Katakana in title, and NOT a MissAV URL
        // (MissAV often shows non-Japanese titles for original content too)
        val hasJapanese = title.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        val isMissAV = url.contains("missav", ignoreCase = true)
        return !hasJapanese && !isMissAV
    }

    fun getFavorites(): List<FavoriteItem> {
        val jsonString = prefs.getString(KEY_FAVORITES, "[]")
        val list = mutableListOf<FavoriteItem>()
        var stripchatMigrationNeeded = false
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val thumbnailUrl = if (obj.has("thumbnail")) obj.getString("thumbnail") else null
                val javCode = if (obj.has("javCode")) obj.getString("javCode") else null
                val releaseDate = if (obj.has("releaseDate")) obj.getString("releaseDate") else null
                val rating = if (obj.has("rating")) obj.getString("rating") else null
                val maker = if (obj.has("maker")) obj.getString("maker") else null
                val series = if (obj.has("series")) obj.getString("series") else null
                val isEnriched = if (obj.has("isEnriched")) obj.getBoolean("isEnriched") else false
                val addedAt    = if (obj.has("addedAt"))    obj.getLong("addedAt")       else 0L
                val relatedUrls = mutableMapOf<String, String>()
                if (obj.has("relatedUrls")) {
                    val ru = obj.getJSONObject("relatedUrls")
                    ru.keys().forEach { k -> relatedUrls[k] = ru.getString(k) }
                }

                val genres = mutableListOf<String>()
                if (obj.has("genres")) {
                    val arr = obj.getJSONArray("genres")
                    for (j in 0 until arr.length()) genres.add(arr.getString(j))
                }
                val actors = mutableListOf<String>()
                if (obj.has("actors")) {
                    val arr = obj.getJSONArray("actors")
                    for (j in 0 until arr.length()) actors.add(arr.getString(j))
                }
                val galleryImages = mutableListOf<String>()
                if (obj.has("galleryImages")) {
                    val arr = obj.getJSONArray("galleryImages")
                    for (j in 0 until arr.length()) galleryImages.add(arr.getString(j))
                }
                val trailerUrl = if (obj.has("trailerUrl")) obj.getString("trailerUrl") else null
                val javdbUrl   = if (obj.has("javdbUrl"))  obj.getString("javdbUrl")  else null
                val customTags = mutableListOf<String>()
                if (obj.has("customTags")) {
                    val arr = obj.getJSONArray("customTags")
                    for (j in 0 until arr.length()) customTags.add(arr.getString(j))
                }
                val note = if (obj.has("note")) obj.getString("note") else ""

                val parsedItem = FavoriteItem(
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        thumbnailUrl = thumbnailUrl,
                        javCode = javCode,
                        releaseDate = releaseDate,
                        rating = rating,
                        maker = maker,
                        series = series,
                        genres = genres,
                        actors = actors,
                        isEnriched = isEnriched,
                        addedAt = addedAt,
                        relatedUrls = relatedUrls,
                        galleryImages = galleryImages,
                        trailerUrl = trailerUrl,
                        javdbUrl = javdbUrl,
                        customTags = normalizeCustomTags(customTags),
                        note = note
                    )
                val sanitizedItem = sanitizeFc2Favorite(sanitizeStripchatFavorite(parsedItem))
                if (sanitizedItem != parsedItem) stripchatMigrationNeeded = true
                list.add(sanitizedItem)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 舊版本曾將 Stripchat 帳號名稱誤認成番號並送往 JavDB。
        // 讀取時一次性清除錯誤的系統資料，但保留網址、封面、自訂標籤與心得。
        if (stripchatMigrationNeeded) {
            saveFavorites(list)
        }
        return list
    }

    fun saveFavoritesPublic(list: List<FavoriteItem>) = saveFavorites(list)

    private fun saveFavorites(list: List<FavoriteItem>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            val obj = JSONObject()
            obj.put("title", item.title)
            obj.put("url", item.url)
            if (item.thumbnailUrl != null) obj.put("thumbnail", item.thumbnailUrl)
            if (item.javCode != null) obj.put("javCode", item.javCode)
            if (item.releaseDate != null) obj.put("releaseDate", item.releaseDate)
            if (item.rating != null) obj.put("rating", item.rating)
            if (item.maker != null) obj.put("maker", item.maker)
            if (item.series != null) obj.put("series", item.series)
            obj.put("isEnriched", item.isEnriched)
            obj.put("addedAt", item.addedAt)
            val ruObj = JSONObject()
            item.relatedUrls.forEach { (k, v) -> ruObj.put(k, v) }
            obj.put("relatedUrls", ruObj)
            val genresArr = JSONArray()
            item.genres.forEach { genresArr.put(it) }
            obj.put("genres", genresArr)
            val actorsArr = JSONArray()
            item.actors.forEach { actorsArr.put(it) }
            obj.put("actors", actorsArr)
            val galleryArr = JSONArray()
            item.galleryImages.forEach { galleryArr.put(it) }
            obj.put("galleryImages", galleryArr)
            if (item.trailerUrl != null) obj.put("trailerUrl", item.trailerUrl)
            if (item.javdbUrl  != null) obj.put("javdbUrl",   item.javdbUrl)
            val customTagsArr = JSONArray()
            normalizeCustomTags(item.customTags).forEach { customTagsArr.put(it) }
            obj.put("customTags", customTagsArr)
            obj.put("note", item.note)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITES, jsonArray.toString()).apply()
    }

    fun exportFavoritesToFile(context: Context, uri: android.net.Uri): Boolean {
        return try {
            val jsonString = prefs.getString(KEY_FAVORITES, "[]")
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString?.toByteArray() ?: "[]".toByteArray())
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importFavoritesFromFile(context: Context, uri: android.net.Uri): Pair<Boolean, String> {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (jsonString.isNullOrEmpty()) {
                return Pair(false, "檔案為空")
            }

            // 完整解析所有欄位（包含 enriched 資料）
            val jsonArray = JSONArray(jsonString)
            val importedList = mutableListOf<FavoriteItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val thumbnailUrl  = if (obj.has("thumbnail"))    obj.getString("thumbnail")          else null
                val javCode       = if (obj.has("javCode"))       obj.getString("javCode")            else null
                val releaseDate   = if (obj.has("releaseDate"))   obj.getString("releaseDate")        else null
                val rating        = if (obj.has("rating"))        obj.getString("rating")             else null
                val maker         = if (obj.has("maker"))         obj.getString("maker")              else null
                val series        = if (obj.has("series"))        obj.getString("series")             else null
                val isEnriched    = if (obj.has("isEnriched"))    obj.getBoolean("isEnriched")        else false
                val addedAt       = if (obj.has("addedAt"))       obj.getLong("addedAt")              else 0L
                val genres = mutableListOf<String>()
                if (obj.has("genres")) {
                    val arr = obj.getJSONArray("genres")
                    for (j in 0 until arr.length()) genres.add(arr.getString(j))
                }
                val actors = mutableListOf<String>()
                if (obj.has("actors")) {
                    val arr = obj.getJSONArray("actors")
                    for (j in 0 until arr.length()) actors.add(arr.getString(j))
                }
                val customTags = mutableListOf<String>()
                if (obj.has("customTags")) {
                    val arr = obj.getJSONArray("customTags")
                    for (j in 0 until arr.length()) customTags.add(arr.getString(j))
                }
                val note = if (obj.has("note")) obj.getString("note") else ""
                importedList.add(
                    sanitizeFc2Favorite(sanitizeStripchatFavorite(FavoriteItem(
                        title        = obj.getString("title"),
                        url          = obj.getString("url"),
                        thumbnailUrl = thumbnailUrl,
                        javCode      = javCode,
                        releaseDate  = releaseDate,
                        rating       = rating,
                        maker        = maker,
                        series       = series,
                        genres       = genres,
                        actors       = actors,
                        isEnriched   = isEnriched,
                        addedAt      = addedAt,
                        customTags   = normalizeCustomTags(customTags),
                        note         = note
                    )))
                )
            }

            val currentFavorites = getFavorites().toMutableList()
            var addedCount   = 0
            var updatedCount = 0
            importedList.forEach { imported ->
                val existingIndex = currentFavorites.indexOfFirst { it.url == imported.url }
                if (existingIndex < 0) {
                    // 新項目：直接新增
                    currentFavorites.add(imported)
                    addedCount++
                } else {
                    val existing = currentFavorites[existingIndex]
                    val merged = if (imported.isEnriched) {
                        imported
                    } else {
                        existing.copy(
                            customTags = normalizeCustomTags(imported.customTags),
                            note = imported.note
                        )
                    }
                    if (merged != existing) {
                        currentFavorites[existingIndex] = merged
                        updatedCount++
                    }
                }
            }

            saveFavorites(currentFavorites)
            val msg = buildString {
                if (addedCount   > 0) append("新增 ${addedCount} 筆  ")
                if (updatedCount > 0) append("更新 ${updatedCount} 筆")
                if (addedCount == 0 && updatedCount == 0) append("無變動")
            }
            Pair(true, "匯入完成：$msg")
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, "無法解析檔案格式")
        }
    }

    private fun normalizeCustomTags(tags: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        val output = mutableListOf<String>()
        tags.forEach { raw ->
            val cleaned = raw.trim()
            val key = cleaned.lowercase()
            if (cleaned.isEmpty() || seen.contains(key)) return@forEach
            seen.add(key)
            output.add(cleaned)
        }
        return output
    }

    private fun isStripchatUrl(url: String): Boolean =
        url.contains("stripchat.com", ignoreCase = true)

    private fun sanitizeStripchatFavorite(item: FavoriteItem): FavoriteItem {
        if (!isStripchatUrl(item.url)) return item
        return item.copy(
            javCode = null,
            releaseDate = null,
            rating = null,
            maker = null,
            series = null,
            genres = emptyList(),
            actors = emptyList(),
            isEnriched = false,
            relatedUrls = emptyMap(),
            galleryImages = emptyList(),
            trailerUrl = null,
            javdbUrl = null
        )
    }

    private fun sanitizeFc2Favorite(item: FavoriteItem): FavoriteItem {
        val canonicalCode = JavDbScraper.extractFc2Code(
            item.url,
            item.title,
            item.javCode.orEmpty()
        ) ?: return item
        val hasWrongJavDbMatch = item.javdbUrl?.contains("javdb.com", ignoreCase = true) == true
        return if (hasWrongJavDbMatch) {
            item.copy(
                javCode = canonicalCode,
                releaseDate = null,
                rating = null,
                maker = null,
                series = null,
                genres = emptyList(),
                actors = emptyList(),
                isEnriched = false,
                javdbUrl = null
            )
        } else {
            item.copy(javCode = canonicalCode)
        }
    }
}
