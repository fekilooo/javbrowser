package com.example.javbrowser.nativeapp.source

import com.example.javbrowser.nativeapp.domain.*

interface JavSource {
    val id: String
    val displayName: String
    val capabilities: Set<SourceCapability>
    val verificationUrl: String? get() = null
    suspend fun search(query: String, page: Int = 1, filters: List<SourceFilterValue> = emptyList()): SourceResult<List<JavSearchResult>>
    suspend fun getDetails(ref: SourceRef): SourceResult<JavTitle>
    suspend fun getPlaybackSources(ref: SourceRef): SourceResult<List<PlaybackVariant>> = SourceResult.Failure(SourceError.Unsupported("Playback is not supported"))
    suspend fun getDiscover(request: DiscoverRequest): SourceResult<List<JavSearchResult>> = SourceResult.Failure(SourceError.Unsupported("Discover is not supported"))
    suspend fun getGallery(ref: SourceRef): SourceResult<List<JavImage>> = SourceResult.Success(emptyList())
}

class JavSourceManager(sources: List<JavSource>) {
    private val sourcesById = sources.associateBy { it.id }
    fun enabled(): List<JavSource> = sourcesById.values.toList()
    fun get(id: String): JavSource? = sourcesById[id]
}
