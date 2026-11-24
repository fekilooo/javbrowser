package com.example.javbrowser

import org.jsoup.Jsoup
import org.mp4parser.PropertyBoxParserImpl
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.MovieBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.boxes.iso14496.part12.EditBox
import org.mp4parser.boxes.iso14496.part12.EditListBox
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.Edit
import org.mp4parser.muxer.RandomAccessSource
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import org.mp4parser.muxer.tracks.TextTrackImpl
import java.io.IOException
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.channels.Channels
import java.util.TreeSet
import java.util.Properties

/** Standard MP4 timed text; video/audio samples are copied without encoding. */
object EmbeddedSubtitles {
    data class Subtitle(val label: String, val language: String, val vtt: String)
    data class Cue(val start: Long, val end: Long, val text: String)

    // Android's system class loader cannot see resources bundled in the app APK.
    // mp4parser's default constructor uses that loader; initialize its map from our app loader.
    @Synchronized private fun ensureParserConfiguration() {
        if (PropertyBoxParserImpl.BOX_MAP_CACHE != null) return
        val properties = Properties()
        val resource = EmbeddedSubtitles::class.java.getResourceAsStream("/isoparser2-default.properties")
            ?: throw IOException("MP4 parser configuration missing from APK")
        resource.use { properties.load(it) }
        PropertyBoxParserImpl.BOX_MAP_CACHE = properties
    }

    fun parseVtt(vtt: String): List<Cue> {
        val text = vtt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        require(text.startsWith("WEBVTT")) { "Invalid WebVTT" }
        val timing = Regex("^(\\S+)\\s+-->\\s+(\\S+)(?:\\s+.*)?$")
        return text.split(Regex("\n[ \\t]*\n")).mapNotNull { block ->
            val lines = block.lines()
            if (lines.firstOrNull()?.let { it.startsWith("NOTE") || it == "STYLE" || it == "REGION" } == true) return@mapNotNull null
            val index = lines.indexOfFirst { timing.matches(it.trim()) }
            if (index < 0) return@mapNotNull null
            val match = timing.matchEntire(lines[index].trim())!!
            val start = parseTime(match.groupValues[1]) ?: return@mapNotNull null
            val end = parseTime(match.groupValues[2]) ?: return@mapNotNull null
            val caption = Jsoup.parseBodyFragment(lines.drop(index + 1).joinToString("\n")).body().wholeText().trim()
            if (end <= start || caption.isBlank()) null else Cue(start, end, caption)
        }.also { require(it.isNotEmpty()) { "No subtitle cues" } }
    }

    // tx3g samples cannot overlap. Split concurrent cues into combined display intervals.
    fun normalize(cues: List<Cue>): List<Cue> {
        val events = sortedMapOf<Long, MutableList<Pair<Int, Boolean>>>()
        cues.forEachIndexed { index, cue ->
            events.getOrPut(cue.start) { mutableListOf() }.add(index to true)
            events.getOrPut(cue.end) { mutableListOf() }.add(index to false)
        }
        val active = TreeSet<Int>()
        val result = mutableListOf<Cue>()
        val times = events.keys.toList()
        times.forEachIndexed { index, time ->
            events.getValue(time).forEach { (cue, entering) -> if (entering) active.add(cue) else active.remove(cue) }
            val end = times.getOrNull(index + 1) ?: return@forEachIndexed
            if (active.isNotEmpty()) {
                val caption = active.map { cues[it].text }.distinct().joinToString("\n")
                require(caption.toByteArray(Charsets.UTF_8).size <= 65535) { "Subtitle sample too large" }
                val previous = result.lastOrNull()
                if (previous != null && previous.end == time && previous.text == caption) result[result.lastIndex] = previous.copy(end = end)
                else result.add(Cue(time, end, caption))
            }
        }
        return result
    }

    fun mux(input: FileChannel, output: WritableByteChannel, subtitles: List<Subtitle>, checkCanceled: () -> Unit = {}) {
        try {
            muxChecked(input, output, subtitles, checkCanceled)
        } catch (error: AssertionError) {
            // mp4parser also uses assertions for input/container invariants. Error
            // is not caught by the service's Exception fallback: translate only
            // this assertion type so failed subtitle output is deleted and the
            // already downloaded source remains available. Never swallow VM errors.
            throw IOException("MP4 subtitle packaging validation failed", error)
        }
    }

    private fun muxChecked(input: FileChannel, output: WritableByteChannel,
        subtitles: List<Subtitle>, checkCanceled: () -> Unit) {
        ensureParserConfiguration()
        val oldEditScale = misScaledVideoEditFactor(input)
        input.position(0)
        val movie = MovieCreator.build(input, ChannelSource(input), "download")
        try {
            if (oldEditScale > 1) movie.tracks.filter { it.handler == "vide" }.forEach { track ->
                val repaired = track.edits.map { Edit(it.mediaTime, it.timeScale, it.mediaRate, it.segmentDuration * oldEditScale) }
                track.edits.clear()
                track.edits.addAll(repaired)
            }
            movie.tracks = movie.tracks.map { AdtsMp4Audio.normalize(it, checkCanceled) }.toMutableList()
            // Reuse the existing captions during repair, replacing rather than duplicating their tracks.
            if (subtitles.isNotEmpty()) movie.tracks.removeAll { track -> track.sampleEntries.any { it.type == "tx3g" } }
            val duration = movie.tracks.filter { it.handler == "vide" || it.handler == "soun" }
                .maxOfOrNull { it.duration * 1000 / it.trackMetaData.timescale } ?: Long.MAX_VALUE
            subtitles.forEach { subtitle ->
                checkCanceled()
                val cues = normalize(parseVtt(subtitle.vtt).mapNotNull { cue ->
                    val end = minOf(cue.end, duration)
                    if (cue.start >= end) null else cue.copy(end = end)
                })
                require(cues.isNotEmpty()) { "Subtitle outside video duration" }
                val track = TextTrackImpl()
                track.trackMetaData.language = isoLanguage(subtitle.language)
                track.trackMetaData.group = 2
                track.subs.addAll(cues.map { TextTrackImpl.Line(it.start, it.end, it.text) })
                movie.addTrack(track)
            }
            // Group small audio/text sample writes into large sequential writes. The
            // caller still owns output; flush only after a successful, uncanceled mux.
            val buffered = BufferedOutputStream(Channels.newOutputStream(output), 256 * 1024)
            val bufferedChannel = Channels.newChannel(buffered)
            val destination = object : WritableByteChannel {
                override fun isOpen() = output.isOpen
                override fun close() = Unit
                override fun write(buffer: ByteBuffer): Int { checkCanceled(); return bufferedChannel.write(buffer) }
            }
            // The stock builder writes mvhd/tkhd using the LCM of track timescales,
            // but elst using Movie.timescale (GCD). Use the same scale everywhere.
            val container = object : DefaultMp4Builder() {
                override fun getTimescale(movie: Movie): Long = movie.timescale
            }.build(movie)
            Mp4LargeFileOffsets.promote(container)
            container.writeContainer(destination)
            checkCanceled()
            buffered.flush()
        } finally {
            movie.tracks.forEach { runCatching { it.close() } }
        }
    }

    /** Recognize the old builder's edit-list scale mismatch without disabling valid edits. */
    fun hasMisScaledVideoEdits(input: FileChannel): Boolean = misScaledVideoEditFactor(input) > 1

    private fun misScaledVideoEditFactor(input: FileChannel): Long {
        ensureParserConfiguration()
        input.position(0)
        val movie = IsoFile(input).getBoxes(MovieBox::class.java).firstOrNull() ?: return 1
        val tracks = movie.getBoxes(TrackBox::class.java)
        val headerScale = movie.movieHeaderBox.timescale
        fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        val trackScale = tracks.map { it.mediaBox.mediaHeaderBox.timescale }.reduceOrNull(::gcd) ?: return 1
        if (trackScale <= 0 || headerScale <= trackScale || headerScale % trackScale != 0L) return 1
        val affected = tracks.any { track ->
            if (track.mediaBox.handlerBox.handlerType != "vide") return@any false
            val entries = track.getBoxes(EditBox::class.java).firstOrNull()
                ?.getBoxes(EditListBox::class.java)?.firstOrNull()?.entries ?: return@any false
            val duration = track.mediaBox.mediaHeaderBox.let { it.duration.toDouble() / it.timescale }
            val editDuration = entries.sumOf { it.segmentDuration }.toDouble()
            // Old elst duration is in GCD units while mvhd claims LCM units.
            duration > 0 && kotlin.math.abs(editDuration / trackScale - duration) < 1.0 &&
                editDuration / headerScale < duration / 2
        }
        return if (affected) headerScale / trackScale else 1
    }

    fun read(input: FileChannel): List<Subtitle> {
        input.position(0)
        val signature = ByteBuffer.allocate(8)
        while (signature.hasRemaining()) if (input.read(signature) < 0) return emptyList()
        if (String(signature.array(), 4, 4, Charsets.US_ASCII) != "ftyp") return emptyList()
        ensureParserConfiguration()
        input.position(0)
        val movie = MovieCreator.build(input, ChannelSource(input), "offline")
        try {
            return movie.tracks.filter { track -> track.sampleEntries.any { it.type == "tx3g" } }.take(20).map { track ->
                val vtt = StringBuilder("WEBVTT\n\n")
                var time = 0L
                track.samples.forEachIndexed { index, sample ->
                    val end = time + track.sampleDurations[index]
                    val data = sample.asByteBuffer()
                    if (data.remaining() >= 2) {
                        val length = data.short.toInt() and 0xffff
                        if (length > data.remaining()) throw IOException("Invalid subtitle sample")
                        if (length > 0) {
                            val bytes = ByteArray(length)
                            data.get(bytes)
                            val caption = String(bytes, Charsets.UTF_8)
                                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            vtt.append(formatTime(time * 1000 / track.trackMetaData.timescale))
                                .append(" --> ").append(formatTime(end * 1000 / track.trackMetaData.timescale))
                                .append('\n').append(caption).append("\n\n")
                            if (vtt.length > 5_000_000) throw IOException("Subtitle track too large")
                        }
                    }
                    time = end
                }
                val lang = track.trackMetaData.language
                Subtitle(when (lang) { "zho", "chi" -> "中文字幕"; "eng" -> "English"; "jpn" -> "日本語"; else -> lang },
                    when (lang) { "zho", "chi" -> "zh"; "eng" -> "en"; "jpn" -> "ja"; else -> lang }, vtt.toString())
            }
        } finally { movie.tracks.forEach { runCatching { it.close() } } }
    }

    private fun isoLanguage(language: String) = when (language.lowercase().substringBefore('-')) {
        "zh", "zho", "chi" -> "zho"; "en", "eng" -> "eng"; "ja", "jpn" -> "jpn"
        else -> language.lowercase().takeIf { it.matches(Regex("[a-z]{3}")) } ?: "und"
    }

    private fun parseTime(value: String): Long? {
        val match = Regex("^(?:(\\d+):)?([0-5]\\d):([0-5]\\d)\\.(\\d{3})$").matchEntire(value) ?: return null
        return (match.groupValues[1].toLongOrNull() ?: 0) * 3600000 + match.groupValues[2].toLong() * 60000 +
            match.groupValues[3].toLong() * 1000 + match.groupValues[4].toLong()
    }

    private fun formatTime(time: Long) = "%02d:%02d:%02d.%03d".format(java.util.Locale.US,
        time / 3600000, time / 60000 % 60, time / 1000 % 60, time % 1000)

    private class ChannelSource(private val channel: FileChannel) : RandomAccessSource {
        override fun get(offset: Long, size: Long): ByteBuffer {
            require(size in 0..Int.MAX_VALUE.toLong())
            val buffer = ByteBuffer.allocate(size.toInt())
            var position = offset
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, position)
                if (count < 0) throw IOException("Truncated MP4")
                if (count == 0) throw IOException("Unable to read MP4 sample")
                position += count
            }
            return buffer.apply { flip() }
        }
        override fun close() = Unit // The caller owns the descriptor.
    }
}
