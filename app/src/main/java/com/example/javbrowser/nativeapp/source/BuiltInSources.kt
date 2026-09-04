package com.example.javbrowser.nativeapp.source

import com.example.javbrowser.nativeapp.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

abstract class HttpJavSource : JavSource {
    protected val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS).followRedirects(true).build()
    protected suspend fun document(url: String, referer: String? = null): SourceResult<Document> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-US,en;q=0.8")
                .apply { referer?.let { header("Referer", it) } }.build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 403 || response.code == 503 || body.contains("cf-chl-", true) -> SourceResult.Failure(SourceError.VerificationRequired("$displayName requires browser verification"))
                    !response.isSuccessful -> SourceResult.Failure(SourceError.Network("HTTP ${response.code}"))
                    body.isBlank() -> SourceResult.Failure(SourceError.Parse("Empty response"))
                    else -> SourceResult.Success(Jsoup.parse(body, response.request.url.toString()))
                }
            }
        } catch (e: Exception) { SourceResult.Failure(SourceError.Network(e.message ?: "Network error")) }
    }

    protected fun titleFrom(doc: Document, ref: SourceRef): JavTitle {
        val heading = doc.selectFirst("h1, h2.title, .video-title")?.text().orEmpty()
        val code = JavIdentity.extract("$heading ${ref.url.orEmpty()} ${ref.id}")
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video[poster]")?.attr("poster")
        return JavTitle(
            id = JavIdentity.identityFor(code, ref), code = code,
            title = heading.ifBlank { code ?: ref.id }, coverUrl = cover,
            sourceRefs = listOf(ref),
        )
    }

    protected fun playback(doc: Document, ref: SourceRef): List<PlaybackVariant> {
        val candidates = linkedSetOf<String>()
        doc.select("video source[src], source[src]").mapTo(candidates) { it.absUrl("src") }
        val html = doc.html().replace("\\/", "/")
        Regex("https?[^\\\"'\\s]+\\.(?:m3u8|mp4)(?:\\?[^\\\"'\\s<]+)?", RegexOption.IGNORE_CASE)
            .findAll(html).mapTo(candidates) { it.value.replace("&amp;", "&") }
        return candidates.filter { it.startsWith("http") }.map { url ->
            val quality = Regex("(?i)(2160|1440|1080|720|480|360)p?").find(url)?.groupValues?.get(1)?.toIntOrNull()
            PlaybackVariant(id, quality?.let { "${it}p" } ?: if (url.contains("m3u8")) "HLS" else "MP4", url, quality,
                if (url.contains("m3u8", true)) StreamType.HLS else StreamType.MP4,
                headers = mapOf("Referer" to (ref.url ?: doc.location()), "User-Agent" to UA))
        }
    }
}

class JavDbSource : HttpJavSource() {
    override val id = "javdb"
    override val displayName = "JavDB"
    override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS, SourceCapability.METADATA, SourceCapability.RATING)
    override val verificationUrl = "https://javdb.com"

    override suspend fun search(query: String, page: Int, filters: List<SourceFilterValue>): SourceResult<List<JavSearchResult>> {
        val url = "https://javdb.com/search?q=${URLEncoder.encode(query, "UTF-8")}&f=all&page=$page"
        return when (val result = document(url)) {
            is SourceResult.Failure -> result
            is SourceResult.Success -> SourceResult.Success(result.value.select(".item, .movie-list .item").mapNotNull { item ->
                val anchor = item.selectFirst("a.box, a[href*=/v/]") ?: return@mapNotNull null
                val href = anchor.absUrl("href").ifBlank { "https://javdb.com${anchor.attr("href")}" }
                val code = JavIdentity.extract(item.selectFirst(".video-title strong")?.text().orEmpty() + href)
                val ref = SourceRef(id, href.substringAfterLast('/'), href)
                val title = item.selectFirst(".video-title, .title")?.text().orEmpty().ifBlank { code ?: ref.id }
                val cover = item.selectFirst("img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
                JavSearchResult(JavTitle(JavIdentity.identityFor(code, ref), code, title, coverUrl = cover, sourceRefs = listOf(ref)), ref)
            })
        }
    }

    override suspend fun getDetails(ref: SourceRef): SourceResult<JavTitle> = when (val result = document(ref.url ?: "https://javdb.com/v/${ref.id}")) {
        is SourceResult.Failure -> result
        is SourceResult.Success -> {
            val doc = result.value
            val base = titleFrom(doc, ref)
            fun values(label: String) = doc.select("strong:matchesOwn((?i)$label)").firstOrNull()?.parent()?.select("a")?.map { JavEntity(it.text(), it.text()) }.orEmpty()
            fun text(label: String) = doc.select("strong:matchesOwn((?i)$label)").firstOrNull()?.nextElementSibling()?.text()?.trim()
            val date = text("Released Date|日期|發行日期")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            val ratingText = text("Rating|評分")
            SourceResult.Success(base.copy(
                title = doc.selectFirst(".title strong, .video-detail-header h2, h2.title")?.text().orEmpty().ifBlank { base.title },
                releaseDate = date, maker = values("Maker|片商|發行商").firstOrNull()?.copy(type = EntityType.MAKER),
                series = values("Series|系列").firstOrNull()?.copy(type = EntityType.SERIES),
                actors = values("Actor\\(s\\)|演員|女優").map { it.copy(type = EntityType.ACTOR) },
                genres = values("Tags|類別|標籤").map { it.copy(type = EntityType.GENRE) },
                rating = Regex("\\d+(?:\\.\\d+)?").find(ratingText.orEmpty())?.value?.toDoubleOrNull()
            ))
        }
    }
}

class MissAvSource(private val baseUrl: String = "https://missav.ai") : HttpJavSource() {
    override val id = "missav"
    override val displayName = "MISSAV"
    override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS, SourceCapability.PLAYBACK, SourceCapability.DOWNLOAD)
    override val verificationUrl = baseUrl
    override suspend fun search(query: String, page: Int, filters: List<SourceFilterValue>): SourceResult<List<JavSearchResult>> {
        val code = JavIdentity.extract(query)
        val target = code?.let { "$baseUrl/${it.lowercase()}" } ?: "$baseUrl/en/search/${URLEncoder.encode(query, "UTF-8")}?page=$page"
        return when (val result = document(target)) {
            is SourceResult.Failure -> result
            is SourceResult.Success -> {
                val doc = result.value
                val cards = doc.select("a[href] img").mapNotNull { image ->
                    val a = image.parent() ?: return@mapNotNull null
                    val href = a.absUrl("href")
                    val found = JavIdentity.extract("${a.text()} $href") ?: return@mapNotNull null
                    val ref = SourceRef(id, href.substringAfterLast('/'), href)
                    JavSearchResult(JavTitle(found, found, a.text().ifBlank { found }, coverUrl = image.absUrl("data-src").ifBlank { image.absUrl("src") }, sourceRefs = listOf(ref)), ref)
                }.distinctBy { it.title.id }
                if (cards.isNotEmpty()) SourceResult.Success(cards) else {
                    val ref = SourceRef(id, target.substringAfterLast('/'), doc.location())
                    val title = titleFrom(doc, ref)
                    if (title.code != null) SourceResult.Success(listOf(JavSearchResult(title, ref))) else SourceResult.Success(emptyList())
                }
            }
        }
    }
    override suspend fun getDetails(ref: SourceRef): SourceResult<JavTitle> = when (val result = document(ref.url ?: "$baseUrl/${ref.id}")) { is SourceResult.Failure -> result; is SourceResult.Success -> SourceResult.Success(titleFrom(result.value, ref)) }
    override suspend fun getPlaybackSources(ref: SourceRef): SourceResult<List<PlaybackVariant>> = when (val result = document(ref.url ?: "$baseUrl/${ref.id}")) { is SourceResult.Failure -> result; is SourceResult.Success -> SourceResult.Success(playback(result.value, ref)) }
}

class JableSource : HttpJavSource() {
    override val id = "jable"
    override val displayName = "JABLE"
    override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS, SourceCapability.PLAYBACK, SourceCapability.DOWNLOAD)
    override val verificationUrl = "https://jable.tv"
    private val base = "https://jable.tv"
    override suspend fun search(query: String, page: Int, filters: List<SourceFilterValue>): SourceResult<List<JavSearchResult>> {
        val code = JavIdentity.extract(query)
        val url = if (code != null) "$base/videos/${code.lowercase()}/" else "$base/search/${URLEncoder.encode(query, "UTF-8")}/"
        return when (val result = document(url)) {
            is SourceResult.Failure -> result
            is SourceResult.Success -> {
                val refs = result.value.select("a[href*=/videos/]").mapNotNull { a ->
                    val href = a.absUrl("href"); val found = JavIdentity.extract("${a.text()} $href") ?: return@mapNotNull null
                    val ref = SourceRef(id, href.substringAfter("/videos/").trim('/'), href)
                    JavSearchResult(JavTitle(found, found, a.attr("title").ifBlank { a.text() }.ifBlank { found }, coverUrl = a.selectFirst("img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }, sourceRefs = listOf(ref)), ref)
                }.distinctBy { it.title.id }
                SourceResult.Success(refs.ifEmpty {
                    val ref = SourceRef(id, code?.lowercase() ?: url.substringAfterLast('/'), result.value.location())
                    titleFrom(result.value, ref).takeIf { it.code != null }?.let { listOf(JavSearchResult(it, ref)) }.orEmpty()
                })
            }
        }
    }
    override suspend fun getDetails(ref: SourceRef): SourceResult<JavTitle> = when (val r = document(ref.url ?: "$base/videos/${ref.id}/")) { is SourceResult.Failure -> r; is SourceResult.Success -> SourceResult.Success(titleFrom(r.value, ref)) }
    override suspend fun getPlaybackSources(ref: SourceRef): SourceResult<List<PlaybackVariant>> = when (val r = document(ref.url ?: "$base/videos/${ref.id}/")) { is SourceResult.Failure -> r; is SourceResult.Success -> SourceResult.Success(playback(r.value, ref)) }
}
