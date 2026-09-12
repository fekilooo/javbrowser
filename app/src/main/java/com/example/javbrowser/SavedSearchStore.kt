package com.example.javbrowser

import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

data class SavedSearchInfo(val key: String, val query: String, val mode: SearchMode,
                           val savedAt: Long, val groups: Int, val links: Int)
data class SavedSearchRecord(val info: SavedSearchInfo, val snapshot: SearchSnapshot)

/** Versioned, bounded local snapshots. No HTML, cookies, diagnostic text or image bytes are stored. */
object SavedSearchCodec {
    private const val MAGIC = 0x53524348
    private const val VERSION = 1
    const val MAX_BYTES = 16 * 1024 * 1024
    fun key(query: String, mode: SearchMode): String = MessageDigest.getInstance("SHA-256")
        .digest((mode.name + "\n" + query).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.string(): String {
        val size = readInt(); require(size in 0..65536)
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) string(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) string() else null
    private fun DataOutputStream.strings(values: List<String>) { writeInt(values.size); values.forEach { string(it) } }
    private fun DataInputStream.strings(limit: Int): List<String> { val n = readInt(); require(n in 0..limit); return List(n) { string() } }

    fun encode(snapshot: SearchSnapshot, savedAt: Long): ByteArray {
        val request = snapshot.request
        require(request.siteIds.size in 1..9 && snapshot.siteStates.size == request.siteIds.size)
        val groups = SearchResultGrouping.group(snapshot.items)
        val bytes = object : ByteArrayOutputStream() {
            override fun write(value: Int) { require(size() < MAX_BYTES) { "SNAPSHOT_TOO_LARGE" }; super.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                require(length <= MAX_BYTES - size()) { "SNAPSHOT_TOO_LARGE" }
                super.write(buffer, offset, length)
            }
        }
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.string(key(request.normalizedQuery, request.mode)); out.string(request.rawQuery)
            out.string(request.mode.name); out.writeLong(savedAt); out.writeInt(groups.size)
            out.writeInt(groups.sumOf { it.links.size })
            out.string(request.normalizedQuery); out.strings(request.siteIds); out.string(request.configKey)
            out.writeLong(request.createdAt)
            out.writeInt(snapshot.siteStates.size)
            snapshot.siteStates.forEach { state ->
                out.string(state.siteId)
                out.string((if (state.status.isTerminal) state.status else SearchStatus.CANCELLED).name)
                out.optional(state.nextPageToken); out.optional(state.errorCode)
                out.writeLong(state.retryAfterUntil); out.writeInt(state.loadedPages)
                out.optional(state.pendingPageToken); out.strings(state.visitedPageTokens)
                out.string(state.pageState.name); out.strings(state.visitedPageFingerprints)
                require(state.items.size <= SearchPagingPolicy.MAX_ITEMS)
                out.writeInt(state.items.size)
                state.items.forEach { item ->
                    out.string(item.stableId); out.string(item.siteId); out.string(item.title)
                    out.string(item.detailUrl); out.string(item.canonicalUrl); out.optional(item.coverUrl)
                    out.optional(item.code); out.strings(item.variantTags); out.string(item.matchType.name); out.writeInt(item.sourceRank)
                }
            }
        }
        require(bytes.size() <= MAX_BYTES) { "SNAPSHOT_TOO_LARGE" }
        return bytes.toByteArray()
    }
    fun header(input: DataInputStream): SavedSearchInfo {
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val info = SavedSearchInfo(input.string(), input.string(), SearchMode.valueOf(input.string()), input.readLong(), input.readInt(), input.readInt())
        require(Regex("[a-f0-9]{64}").matches(info.key) && info.groups in 0..18000 && info.links in 0..18000)
        return info
    }
    fun decode(bytes: ByteArray): SavedSearchRecord {
        require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val info = header(input)
            val normalized = input.string(); val ids = input.strings(9); val config = input.string(); val created = input.readLong()
            require(ids.isNotEmpty() && ids.distinct().size == ids.size && info.key == key(normalized, info.mode))
            val request = SearchRequest(UUID.randomUUID().toString(), info.query, normalized, info.mode, ids, config, created)
            val count = input.readInt(); require(count == ids.size)
            val states = List(count) {
                val id = input.string(); require(id in ids)
                val status = SearchStatus.valueOf(input.string())
                val next = input.optional(); val error = input.optional()
                val retry = input.readLong(); val pages = input.readInt(); require(pages in 0..SearchPagingPolicy.MAX_PAGES)
                val pending = input.optional(); val visited = input.strings(SearchPagingPolicy.MAX_PAGES)
                val pageState = SearchPageState.valueOf(input.string()); val fingerprints = input.strings(SearchPagingPolicy.MAX_PAGES)
                val size = input.readInt(); require(size in 0..SearchPagingPolicy.MAX_ITEMS)
                val items = List(size) {
                    val item = SearchItem(input.string(), input.string(), input.string(), input.string(), input.string(), input.optional(),
                        input.optional(), input.strings(20), SearchMatchType.valueOf(input.string()), input.readInt())
                    require(item.siteId == id && PageUrlClipboard.copyableUrl(item.detailUrl) != null)
                    item
                }
                SiteSearchState(id, if (status.isTerminal) status else SearchStatus.CANCELLED, items, next, error,
                    retryAfterUntil = retry, loadedPages = pages, pendingPageToken = pending, visitedPageTokens = visited,
                    pageState = pageState, visitedPageFingerprints = fingerprints)
            }
            require(states.map { it.siteId }.distinct().size == ids.size && input.available() == 0)
            val items = SearchResultNormalizer.merge(states.flatMap { it.items })
            return SavedSearchRecord(info, SearchSnapshot(request, states, items, states.size, states.size, false))
        }
    }
}

/** Called on the shared IO queue. Backup-before-replace protects an existing save on write failure. */
class SavedSearchStore(private val directory: File) {
    companion object {
        const val MAX_SAVES = 20
        val io = Executors.newSingleThreadExecutor()
        private val lock = Any()
    }
    private fun file(key: String, suffix: String = ".bin"): File {
        require(Regex("[a-f0-9]{64}").matches(key))
        return File(directory, key + suffix)
    }
    private fun recover() {
        require(directory.isDirectory || directory.mkdirs())
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.bak")) }.forEach { backup ->
            val target = file(backup.name.removeSuffix(".bak"))
            if (!target.exists()) require(backup.renameTo(target))
        }
    }
    fun list(): List<SavedSearchInfo> = synchronized(lock) {
        recover()
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.bin")) }.map { path ->
            runCatching { DataInputStream(BufferedInputStream(FileInputStream(path))).use(SavedSearchCodec::header)
                .also { require(it.key == path.name.removeSuffix(".bin")) } }
                .getOrElse { SavedSearchInfo(path.name.removeSuffix(".bin"), "（無法讀取的已存搜尋）", SearchMode.KEYWORD, path.lastModified(), 0, 0) }
        }.sortedByDescending { it.savedAt }
    }
    fun load(key: String): SavedSearchRecord = synchronized(lock) {
        recover()
        val target = file(key)
        require(target.length() in 1..SavedSearchCodec.MAX_BYTES.toLong())
        SavedSearchCodec.decode(target.readBytes()).also { require(it.info.key == key) }
    }
    fun save(snapshot: SearchSnapshot, now: Long = System.currentTimeMillis()): SavedSearchInfo = synchronized(lock) {
        recover()
        val key = SavedSearchCodec.key(snapshot.request.normalizedQuery, snapshot.request.mode)
        val target = file(key); val backup = file(key, ".bak"); val pending = file(key, ".new")
        check(target.exists() || list().size < MAX_SAVES) { "SAVED_SEARCH_LIMIT" }
        val bytes = SavedSearchCodec.encode(snapshot, now)
        // Validate before touching a previous save; never silently trim a large search.
        val record = SavedSearchCodec.decode(bytes)
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            if (backup.exists()) check(backup.delete())
            if (target.exists()) check(target.renameTo(backup))
            if (!pending.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("SAVE_REPLACE_FAILED")
            }
            backup.delete()
            record.info
        } finally { pending.delete() }
    }
    fun delete(key: String) = synchronized(lock) {
        // Delete only the explicitly chosen record and its recovery files.
        listOf(".bin", ".bak", ".new").forEach { suffix -> val path = file(key, suffix); check(!path.exists() || path.delete()) }
    }
}
