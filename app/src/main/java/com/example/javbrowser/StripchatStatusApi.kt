package com.example.javbrowser

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Stripchat 在 2026-08 將以 username 直接查 cam 的舊 API 拆成兩段：
 * username -> model id -> cam。集中在這裡處理，避免書籤狀態與監錄狀態再次不同步。
 */
object StripchatStatusApi {
    data class Snapshot(
        val root: JSONObject,
        val modelId: Long,
    )

    private const val BASE_URL = "https://stripchat.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    fun fetchSnapshot(
        client: OkHttpClient,
        username: String,
        referer: String,
        cookie: String,
        knownModelId: Long = 0L,
    ): Snapshot? {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isEmpty()) return null

        if (knownModelId > 0L) {
            requestCam(client, knownModelId, referer, cookie)?.let {
                return Snapshot(it, knownModelId)
            }
        }

        val resolvedModelId = resolveModelId(client, normalizedUsername, referer, cookie)
        if (resolvedModelId > 0L && resolvedModelId != knownModelId) {
            requestCam(client, resolvedModelId, referer, cookie)?.let {
                return Snapshot(it, resolvedModelId)
            }
        }

        // 短暫相容尚未切換的節點；新版端點可用時不會走到這裡。
        requestJson(
            client = client,
            url = "$BASE_URL/api/front/v2/models/username/${Uri.encode(normalizedUsername)}/cam" +
                "?uniq=${System.currentTimeMillis()}",
            referer = referer,
            cookie = cookie,
        )?.let { root ->
            val legacyId = root.optJSONObject("user")?.optJSONObject("user")?.optLong("id", 0L)
                ?: 0L
            return Snapshot(root, legacyId)
        }
        return null
    }

    private fun resolveModelId(
        client: OkHttpClient,
        username: String,
        referer: String,
        cookie: String,
    ): Long {
        val root = requestJson(
            client = client,
            url = "$BASE_URL/api/front/users/user-ids/${Uri.encode(username)}",
            referer = referer,
            cookie = cookie,
        ) ?: return 0L
        return root.optLong("id", 0L)
    }

    private fun requestCam(
        client: OkHttpClient,
        modelId: Long,
        referer: String,
        cookie: String,
    ): JSONObject? = requestJson(
        client = client,
        url = "$BASE_URL/api/front/v2/models/$modelId/cam?uniq=${System.currentTimeMillis()}",
        referer = referer,
        cookie = cookie,
    )

    private fun requestJson(
        client: OkHttpClient,
        url: String,
        referer: String,
        cookie: String,
    ): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", BASE_URL)
            .header("Referer", referer.ifBlank { "$BASE_URL/" })
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("STRIPCHAT_STATUS", "HTTP ${response.code} url=$url")
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) null else JSONObject(body)
            }
        } catch (error: Exception) {
            android.util.Log.w("STRIPCHAT_STATUS", "request failed url=$url", error)
            null
        }
    }
}
