package com.example.javbrowser.nativeapp.domain

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object JavIdentity {
    private val fc2 = Regex("(?i)(?<![A-Z0-9])FC2(?:[\\s_\\-]*PPV)?[\\s_\\-]*(\\d{5,10})(?!\\d)")
    private val regular = Regex("(?i)(?<![A-Z0-9])((?=[A-Z0-9]{2,10}[\\s_\\-])(?=[A-Z0-9]*[A-Z])[A-Z0-9]{2,10})[\\s_\\-]*(\\d{2,6})(?!\\d)")

    fun extract(value: String): String? {
        val decoded = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
            .replace('–', '-').replace('—', '-').replace('＿', '-').uppercase(Locale.ROOT)
        fc2.find(decoded)?.let { return "FC2-PPV-${it.groupValues[1]}" }
        val match = regular.find(decoded) ?: return null
        return "${match.groupValues[1]}-${match.groupValues[2]}"
    }

    fun normalize(value: String): String = extract(value) ?: value.trim()
        .replace(Regex("[\\s_–—]+"), "-").uppercase(Locale.ROOT)

    fun identityFor(code: String?, source: SourceRef): String = code?.let(::normalize) ?: "${source.sourceId}:${source.id}"
}

object JavMerger {
    fun deduplicate(results: List<JavSearchResult>): List<JavTitle> = results
        .groupBy { JavIdentity.identityFor(it.title.code, it.sourceRef) }
        .values.map { group -> group.map { it.title }.reduce(::merge) }

    fun merge(primary: JavTitle, secondary: JavTitle): JavTitle {
        val refs = (primary.sourceRefs + secondary.sourceRefs).distinctBy { it.sourceId to it.id }
        fun entities(a: List<JavEntity>, b: List<JavEntity>) = (a + b).distinctBy { it.name.lowercase() }
        return primary.copy(
            code = primary.code ?: secondary.code,
            title = primary.title.takeIf { it.isNotBlank() } ?: secondary.title,
            originalTitle = primary.originalTitle ?: secondary.originalTitle,
            coverUrl = primary.coverUrl?.takeIf { it.isNotBlank() } ?: secondary.coverUrl,
            backdropUrl = primary.backdropUrl?.takeIf { it.isNotBlank() } ?: secondary.backdropUrl,
            releaseDate = primary.releaseDate ?: secondary.releaseDate,
            durationMinutes = primary.durationMinutes ?: secondary.durationMinutes,
            maker = primary.maker ?: secondary.maker,
            label = primary.label ?: secondary.label,
            series = primary.series ?: secondary.series,
            actors = entities(primary.actors, secondary.actors),
            genres = entities(primary.genres, secondary.genres),
            rating = primary.rating ?: secondary.rating,
            ratingCount = primary.ratingCount ?: secondary.ratingCount,
            sourceRefs = refs,
            gallery = (primary.gallery + secondary.gallery).distinctBy { it.url },
        )
    }
}

object PlaybackRanker {
    fun rank(variants: List<PlaybackVariant>, preferredSource: String? = null, successfulSource: String? = null): List<PlaybackVariant> =
        variants.sortedWith(compareByDescending<PlaybackVariant> { it.sourceId == preferredSource }
            .thenByDescending { it.sourceId == successfulSource }
            .thenByDescending { it.quality ?: 0 }
            .thenByDescending { it.type == StreamType.MP4 })
}
