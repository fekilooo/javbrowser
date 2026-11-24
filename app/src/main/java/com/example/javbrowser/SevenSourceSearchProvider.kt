package com.example.javbrowser

import android.content.Context
import java.net.URI
import java.security.MessageDigest

class SevenSourceSearchProvider(private val context: Context, override val site: SearchSite) : SearchProvider {
    override fun search(request: SearchRequest, pageToken: String?, attemptId: String, cancellation: SearchCancellation): SiteSearchResult {
        val attempt = attemptId
        fun result(status: SearchStatus, message: String) = SiteSearchResult(request.requestId, site.id, attempt, status, message = message)
        return try {
            val page = pageToken?.let { URI(it).rawQuery?.split('&')?.firstOrNull { it.startsWith("page=") }?.substringAfter('=')?.toIntOrNull() } ?: 1
            val source = SevenSourceProtocol.source(site.id)
            val data = SevenSourceClient().page(source, "recent", page, request.normalizedQuery, cancellation)
            cancellation.check()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(data.videos.joinToString("|") { it.stableId }.toByteArray())
                .joinToString("") { "%02x".format(it) }
            SiteSearchResult(request.requestId, site.id, attempt, if (data.videos.isEmpty()) SearchStatus.EMPTY else SearchStatus.SUCCESS,
                data.videos.mapIndexed { index, video -> video.searchItem(index) },
                nextPageToken = if (data.hasMore) "${source.origin}/?page=${page + 1}" else null,
                pageFingerprint = fingerprint,
                pageState = if (data.hasMore) SearchPageState.HAS_NEXT else SearchPageState.END_REPORTED)
        } catch (_: InterruptedException) { result(SearchStatus.CANCELLED, "已取消 / Canceled") }
        catch (error: Exception) { result(SearchStatus.NETWORK_ERROR, error.message ?: "來源無法回應 / Source unavailable") }
    }
}
