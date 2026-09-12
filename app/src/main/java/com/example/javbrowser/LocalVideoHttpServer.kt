package com.example.javbrowser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

class LocalVideoHttpServer(
    context: Context,
    private val sourceUri: Uri
) : NanoHTTPD("127.0.0.1", 0) {
    private val appContext = context.applicationContext
    private val sourceName: String by lazy { querySourceName() }

    fun playbackUrl(): String {
        val extension = sourceName.substringAfterLast('.', "mp4").lowercase()
            .takeIf { it in setOf("webm", "mp4", "mkv", "m4v", "ts") }
            ?: "mp4"
        return "http://127.0.0.1:$listeningPort/video.$extension"
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/video" && !session.uri.startsWith("/video.")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return runCatching { serveVideo(session.headers["range"]) }
            .getOrElse { error ->
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Local video error: ${error.message}"
                )
            }
    }

    private fun serveVideo(rangeHeader: String?): Response {
        val totalSize = sourceSize()
        if (totalSize <= 0L) throw IOException("無法取得本地影片大小")
        val range = parseRange(rangeHeader, totalSize)
        if (range == null && !rangeHeader.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.lookup(416) ?: Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Invalid range"
            ).apply { addHeader("Content-Range", "bytes */$totalSize") }
        }

        val start = range?.first ?: 0L
        val end = range?.last ?: totalSize - 1L
        val length = end - start + 1L
        val stream = openStream(start, length)
        val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, detectedMimeType(), stream, length).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Cache-Control", "no-store")
            if (range != null) addHeader("Content-Range", "bytes $start-$end/$totalSize")
        }
    }

    private fun parseRange(header: String?, totalSize: Long): LongRange? {
        if (header.isNullOrBlank()) return null
        val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(header.trim())
            ?: return null
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        val start: Long
        val end: Long
        if (startText.isBlank()) {
            val suffixLength = endText.toLongOrNull()?.coerceAtMost(totalSize) ?: return null
            if (suffixLength <= 0L) return null
            start = totalSize - suffixLength
            end = totalSize - 1L
        } else {
            start = startText.toLongOrNull() ?: return null
            if (start >= totalSize) return null
            end = (endText.toLongOrNull() ?: totalSize - 1L).coerceAtMost(totalSize - 1L)
            if (end < start) return null
        }
        return start..end
    }

    private fun sourceSize(): Long {
        if (sourceUri.scheme == "file") return sourceUri.path?.let(::File)?.length() ?: 0L
        appContext.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { descriptor ->
            if (descriptor.length >= 0L) return descriptor.length
        }
        var size = 0L
        appContext.contentResolver.query(
            sourceUri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) size = cursor.getLong(index)
            }
        }
        return size
    }

    private fun detectedMimeType(): String {
        when (sourceName.substringAfterLast('.', "").lowercase()) {
            "webm" -> return "video/webm"
            "mkv" -> return "video/x-matroska"
            "mp4", "m4v" -> return "video/mp4"
            "ts" -> return "video/mp2t"
        }
        val header = runCatching {
            openStream(0L, min(sourceSize(), 512L)).use { stream ->
                ByteArray(512).also { buffer ->
                    val count = stream.read(buffer)
                    if (count in 0 until buffer.size) buffer.fill(0, count)
                }
            }
        }.getOrNull()
        if (header != null) {
            if (header.size >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") {
                return "video/mp4"
            }
            if (header.isNotEmpty() && header[0] == 0x47.toByte() &&
                header.size > 188 && header[188] == 0x47.toByte()
            ) {
                return "video/mp2t"
            }
            if (header.size >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) {
                return "video/x-matroska"
            }
        }
        return appContext.contentResolver.getType(sourceUri)
            ?.takeIf { it.startsWith("video/") }
            ?: when (sourceUri.toString().substringAfterLast('.', "").lowercase()) {
                "ts" -> "video/mp2t"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                else -> "video/mp4"
            }
    }

    private fun querySourceName(): String {
        if (sourceUri.scheme == "file") return sourceUri.path?.let(::File)?.name.orEmpty()
        var name = ""
        runCatching {
            appContext.contentResolver.query(
                sourceUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index).orEmpty()
                }
            }
        }
        return name
    }

    private fun openStream(start: Long, length: Long): InputStream {
        if (sourceUri.scheme == "file") {
            val file = sourceUri.path?.let(::File) ?: throw IOException("本地檔案路徑無效")
            val stream = FileInputStream(file)
            stream.channel.position(start)
            return LimitedInputStream(stream, length)
        }

        val descriptor = appContext.contentResolver.openAssetFileDescriptor(sourceUri, "r")
            ?: throw IOException("無法開啟本地影片")
        return try {
            val stream = FileInputStream(descriptor.fileDescriptor)
            stream.channel.position(descriptor.startOffset + start)
            LimitedInputStream(stream, length) { descriptor.close() }
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    private class LimitedInputStream(
        private val source: InputStream,
        length: Long,
        private val afterClose: () -> Unit = {}
    ) : InputStream() {
        private var remaining = length

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = source.read(buffer, offset, min(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count.toLong()
            return count
        }

        override fun close() {
            runCatching { source.close() }
            runCatching { afterClose() }
        }
    }
}
