package com.example.javbrowser.nativeapp.domain

import java.time.LocalDate

data class JavEntity(val id: String, val name: String, val type: EntityType = EntityType.OTHER)
enum class EntityType { ACTOR, MAKER, LABEL, SERIES, GENRE, OTHER }
data class JavImage(val url: String, val kind: ImageKind = ImageKind.GALLERY)
enum class ImageKind { COVER, BACKDROP, GALLERY }

data class SourceRef(val sourceId: String, val id: String, val url: String? = null)

data class JavTitle(
    val id: String,
    val code: String? = null,
    val title: String,
    val originalTitle: String? = null,
    val coverUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseDate: LocalDate? = null,
    val durationMinutes: Int? = null,
    val maker: JavEntity? = null,
    val label: JavEntity? = null,
    val series: JavEntity? = null,
    val actors: List<JavEntity> = emptyList(),
    val genres: List<JavEntity> = emptyList(),
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val sourceRefs: List<SourceRef> = emptyList(),
    val gallery: List<JavImage> = emptyList(),
)

data class JavSearchResult(
    val title: JavTitle,
    val sourceRef: SourceRef,
    val confidence: Double = if (title.code != null) 1.0 else .5,
)

enum class StreamType { HLS, MP4, LOCAL }
data class PlaybackVariant(
    val sourceId: String,
    val label: String,
    val url: String,
    val quality: Int? = null,
    val type: StreamType,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtMillis: Long? = null,
)

enum class SourceCapability { SEARCH, DETAILS, METADATA, DISCOVER, POPULAR, LATEST, PLAYBACK, DOWNLOAD, TRAILER, GALLERY, RATING, AUTH }
data class DiscoverRequest(val section: String = "latest", val page: Int = 1)
data class SourceFilterValue(val id: String, val value: String)
data class SourceHealth(val sourceId: String, val lastSuccess: Long? = null, val recentFailures: Int = 0, val lastError: String? = null, val verificationRequired: Boolean = false)
data class Favorite(val title: JavTitle, val addedAt: Long)
data class WatchHistory(val title: JavTitle, val watchedAt: Long, val progress: WatchProgress? = null)
data class WatchProgress(val positionMillis: Long, val durationMillis: Long)
data class Download(val title: JavTitle, val state: DownloadState, val progress: Float = 0f, val localUri: String? = null)
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED }
data class LocalMedia(val uri: String, val title: JavTitle?, val displayName: String)

sealed interface SourceError {
    val message: String
    data class Network(override val message: String) : SourceError
    data class Parse(override val message: String) : SourceError
    data class VerificationRequired(override val message: String) : SourceError
    data class Unsupported(override val message: String) : SourceError
}

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val error: SourceError) : SourceResult<Nothing>
}
