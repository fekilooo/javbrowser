package com.example.javbrowser.nativeapp.data

import com.example.javbrowser.nativeapp.domain.*
import com.example.javbrowser.nativeapp.source.JavSourceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SearchSnapshot(
    val results: List<JavTitle> = emptyList(),
    val sourceStates: Map<String, SourceLoadState> = emptyMap(),
    val complete: Boolean = false,
)
sealed interface SourceLoadState { data object Loading : SourceLoadState; data class Success(val count: Int) : SourceLoadState; data class Error(val message: String, val verificationRequired: Boolean = false) : SourceLoadState }

class JavRepository(private val manager: JavSourceManager) {
    private data class CacheEntry(val at: Long, val result: List<JavSearchResult>)
    private val cache = mutableMapOf<String, CacheEntry>()
    private val cacheTtl = 10 * 60_000L
    private val health = mutableMapOf<String, SourceHealth>()

    fun search(query: String): Flow<SearchSnapshot> = channelFlow {
        val sources = manager.enabled().filter { SourceCapability.SEARCH in it.capabilities }
        val states: MutableMap<String, SourceLoadState> = sources
            .associate { it.id to (SourceLoadState.Loading as SourceLoadState) }
            .toMutableMap()
        val all = mutableListOf<JavSearchResult>()
        val mutex = Mutex()
        send(SearchSnapshot(sourceStates = states.toMap()))
        coroutineScope {
            sources.forEach { source -> launch {
                val key = "${source.id}:${query.lowercase()}"
                val cached = cache[key]?.takeIf { System.currentTimeMillis() - it.at < cacheTtl }?.result
                val result = cached?.let { SourceResult.Success(it) } ?: withTimeoutOrNull(20_000) { source.search(query) }
                    ?: SourceResult.Failure(SourceError.Network("Request timed out"))
                mutex.withLock {
                    when (result) {
                        is SourceResult.Success -> {
                            cache[key] = CacheEntry(System.currentTimeMillis(), result.value)
                            all += result.value
                            states[source.id] = SourceLoadState.Success(result.value.size)
                            health[source.id] = SourceHealth(source.id, lastSuccess = System.currentTimeMillis())
                        }
                        is SourceResult.Failure -> {
                            states[source.id] = SourceLoadState.Error(result.error.message, result.error is SourceError.VerificationRequired)
                            val old = health[source.id]
                            health[source.id] = SourceHealth(source.id, old?.lastSuccess, (old?.recentFailures ?: 0) + 1, result.error::class.simpleName, result.error is SourceError.VerificationRequired)
                        }
                    }
                    send(SearchSnapshot(JavMerger.deduplicate(all), states.toMap(), states.values.none { it is SourceLoadState.Loading }))
                }
            } }
        }
    }

    suspend fun details(seed: JavTitle): JavTitle = coroutineScope {
        val resolved = seed.sourceRefs.mapNotNull { ref -> manager.get(ref.sourceId)?.takeIf { SourceCapability.DETAILS in it.capabilities }?.let { source -> async { source.getDetails(ref) } } }
            .awaitAll().mapNotNull { (it as? SourceResult.Success)?.value }
        resolved.fold(seed, JavMerger::merge)
    }

    suspend fun playback(title: JavTitle, preferred: String? = null): Pair<List<PlaybackVariant>, Map<String, String>> = coroutineScope {
        val errors = mutableMapOf<String, String>()
        val variants = title.sourceRefs.mapNotNull { ref -> manager.get(ref.sourceId)?.takeIf { SourceCapability.PLAYBACK in it.capabilities }?.let { source -> async { source.id to source.getPlaybackSources(ref) } } }
            .awaitAll().flatMap { (id, result) -> when (result) { is SourceResult.Success -> result.value; is SourceResult.Failure -> { errors[id] = result.error.message; emptyList() } } }
        PlaybackRanker.rank(variants, preferred) to errors
    }

    fun health(): List<SourceHealth> = manager.enabled().map { health[it.id] ?: SourceHealth(it.id) }
    fun sourceSettings(): List<Pair<SourceHealth, String?>> = manager.enabled().map { (health[it.id] ?: SourceHealth(it.id)) to it.verificationUrl }
}
