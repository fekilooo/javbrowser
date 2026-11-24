package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.security.MessageDigest

class EmbeddedSubtitlesTest {
    @Test fun packagingAssertionBecomesRecoverableAndKeepsSourceUnchanged() {
        val source = File.createTempFile("subtitle-assertion-source", ".mp4")
        try {
            javaClass.getResourceAsStream("/subtitle-base.mp4")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            val original = source.readBytes()
            val assertion = AssertionError("Synthetic MP4 validation failure")
            var outputClosed = false
            val output = object : java.nio.channels.WritableByteChannel {
                override fun isOpen() = !outputClosed
                override fun close() { outputClosed = true }
                override fun write(buffer: java.nio.ByteBuffer): Int { throw assertion }
            }
            try {
                source.inputStream().use { EmbeddedSubtitles.mux(it.channel, output,
                    listOf(EmbeddedSubtitles.Subtitle("Test", "en", "WEBVTT\n\n00:00.000 --> 00:01.000\nTest\n\n"))) }
                fail("Assertion escaped recoverable boundary")
            } catch (error: java.io.IOException) { assertSame(assertion, error.cause) }
            assertArrayEquals(original, source.readBytes())
            assertFalse(outputClosed)
        } finally { source.delete() }
    }

    @Test fun cancellationAndVmErrorsAreNotConvertedToPackagingFailures() {
        val source = File.createTempFile("subtitle-cancel-source", ".mp4")
        val output = File.createTempFile("subtitle-cancel-output", ".mp4")
        try {
            javaClass.getResourceAsStream("/subtitle-base.mp4")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            val cancel = java.io.IOException("Synthetic cancellation")
            val vmError = OutOfMemoryError("Synthetic VM error")
            listOf<Throwable>(cancel, vmError).forEach { expected ->
                try {
                    source.inputStream().use { input -> output.outputStream().use { destination ->
                        EmbeddedSubtitles.mux(input.channel, destination.channel,
                            listOf(EmbeddedSubtitles.Subtitle("Test", "en", "WEBVTT\n\n00:00.000 --> 00:01.000\nTest\n\n"))) { throw expected }
                    } }
                    fail("Expected cancellation or VM error")
                } catch (actual: Throwable) { assertSame(expected, actual) }
            }
        } finally { source.delete(); output.delete() }
    }
    private val vtt = "WEBVTT\n\n00:00.500 --> 00:02.000\n<b>中文字幕</b> &amp; 測試\n第二行\n\n" +
        "00:01.500 --> 00:03.000\n重疊字幕\n\n"

    @Test fun muxPreservesAudioVideoAndReadsChineseAndOverlappingCues() {
        val source = File.createTempFile("subtitle-source", ".mp4")
        val output = File(System.getProperty("java.io.tmpdir"), "jav-subtitle-test-output.mp4")
        try {
            javaClass.getResourceAsStream("/subtitle-base.mp4")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            source.inputStream().use { input -> output.outputStream().use { destination ->
                EmbeddedSubtitles.mux(input.channel, destination.channel,
                    listOf(EmbeddedSubtitles.Subtitle("繁體中文", "zh-Hant", vtt)))
            } }
            val original = MovieCreator.build(source.path)
            val merged = MovieCreator.build(output.path)
            try {
                assertEquals(3, merged.tracks.size)
                original.tracks.forEachIndexed { index, before ->
                    val after = merged.tracks[index]
                    assertEquals(before.handler, after.handler)
                    assertArrayEquals(before.sampleDurations, after.sampleDurations)
                    assertEquals(before.samples.size, after.samples.size)
                    assertEquals(before.edits.size, after.edits.size)
                    before.edits.zip(after.edits).forEach { (beforeEdit, afterEdit) ->
                        assertEquals(beforeEdit.segmentDuration, afterEdit.segmentDuration, 0.05)
                        assertEquals(beforeEdit.mediaTime, afterEdit.mediaTime)
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    before.samples.forEach { digest.update(it.asByteBuffer()) }
                    val expected = digest.digest()
                    after.samples.forEach { digest.update(it.asByteBuffer()) }
                    assertArrayEquals(expected, digest.digest())
                }
            } finally { original.tracks.forEach { it.close() }; merged.tracks.forEach { it.close() } }
            assertFalse(output.inputStream().use { EmbeddedSubtitles.hasMisScaledVideoEdits(it.channel) })
            val embedded = output.inputStream().use { EmbeddedSubtitles.read(it.channel) }.single()
            assertEquals("zh", embedded.language)
            val cues = EmbeddedSubtitles.parseVtt(embedded.vtt)
            assertEquals(listOf(
                EmbeddedSubtitles.Cue(500, 1500, "中文字幕 & 測試\n第二行"),
                EmbeddedSubtitles.Cue(1500, 2000, "中文字幕 & 測試\n第二行\n重疊字幕"),
                EmbeddedSubtitles.Cue(2000, 3000, "重疊字幕")
            ), cues)
        } finally { source.delete() }
    }

    @Test fun detectsOldMuxerEditScaleBugButKeepsValidEditLists() {
        val source = File.createTempFile("subtitle-source", ".mp4")
        val broken = File.createTempFile("subtitle-old-builder", ".mp4")
        val repaired = File.createTempFile("subtitle-repaired", ".mp4")
        try {
            javaClass.getResourceAsStream("/subtitle-base.mp4")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            assertFalse(source.inputStream().use { EmbeddedSubtitles.hasMisScaledVideoEdits(it.channel) })
            val movie = MovieCreator.build(source.path)
            try {
                val subtitle = org.mp4parser.muxer.tracks.TextTrackImpl()
                subtitle.subs.add(org.mp4parser.muxer.tracks.TextTrackImpl.Line(500, 2000, "測試"))
                movie.addTrack(subtitle)
                broken.outputStream().use { org.mp4parser.muxer.builder.DefaultMp4Builder().build(movie).writeContainer(it.channel) }
            } finally { movie.tracks.forEach { it.close() } }
            assertTrue(broken.inputStream().use { EmbeddedSubtitles.hasMisScaledVideoEdits(it.channel) })
            broken.inputStream().use { input -> repaired.outputStream().use { output ->
                EmbeddedSubtitles.mux(input.channel, output.channel, listOf(EmbeddedSubtitles.Subtitle("Chinese", "zh", vtt)))
            } }
            assertFalse(repaired.inputStream().use { EmbeddedSubtitles.hasMisScaledVideoEdits(it.channel) })
            val result = MovieCreator.build(repaired.path)
            try {
                assertEquals(3, result.tracks.size)
                assertEquals(4.0, result.tracks.first { it.handler == "vide" }.edits.sumOf { it.segmentDuration }, .05)
            } finally { result.tracks.forEach { it.close() } }
        } finally { source.delete(); broken.delete(); repaired.delete() }
    }

    @Test fun clipsSubtitleToVideoDuration() {
        val source = File.createTempFile("subtitle-source", ".mp4")
        val output = File.createTempFile("subtitle-clipped", ".mp4")
        try {
            javaClass.getResourceAsStream("/subtitle-base.mp4")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            source.inputStream().use { input -> output.outputStream().use { destination ->
                EmbeddedSubtitles.mux(input.channel, destination.channel, listOf(EmbeddedSubtitles.Subtitle("English", "en",
                    "WEBVTT\n\n00:03.000 --> 00:30.000\nTail\n\n")))
            } }
            val cues = output.inputStream().use { EmbeddedSubtitles.parseVtt(EmbeddedSubtitles.read(it.channel).single().vtt) }
            assertEquals(3000L, cues.single().start)
            assertTrue(cues.single().end in 4000L..4100L)
        } finally { source.delete(); output.delete() }
    }

    @Test fun ignoresNotesAndPreservesLiteralAngleBrackets() {
        val cues = EmbeddedSubtitles.parseVtt("\uFEFFWEBVTT\r\n\r\nNOTE ignored\r\n00:00.000 --> 00:01.000\r\nignore\r\n\r\n" +
            "cue-id\r\n01:00:00.000 --> 01:00:01.250 align:center\r\n&lt;字幕&gt;\r\n\r\n")
        assertEquals(listOf(EmbeddedSubtitles.Cue(3600000, 3601250, "<字幕>")), cues)
    }
}
