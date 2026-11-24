package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test
import org.mp4parser.BasicContainer
import org.mp4parser.boxes.iso14496.part12.*

class Mp4LargeFileOffsetsTest {
    @Test fun promotesAllTracksAndAdjustsMediaOffsetsForLargerHeader() {
        val movie = MovieBox()
        val first = StaticChunkOffsetBox().apply { chunkOffsets = longArrayOf(1024, 0x1_0000_0000L) }
        val second = StaticChunkOffsetBox().apply { chunkOffsets = longArrayOf(2048, 4096) }
        movie.addBox(first); movie.addBox(second)
        val container = BasicContainer().apply { addBox(movie) }
        val oldSize = movie.size
        Mp4LargeFileOffsets.promote(container)
        val boxes = container.getBoxes(ChunkOffset64BitBox::class.java, true)
        assertEquals(2, boxes.size)
        assertTrue(container.getBoxes(StaticChunkOffsetBox::class.java, true).isEmpty())
        val delta = movie.size - oldSize
        assertEquals(16L, delta)
        assertArrayEquals(longArrayOf(1024 + delta, 0x1_0000_0000L + delta), boxes[0].chunkOffsets)
        assertArrayEquals(longArrayOf(2048 + delta, 4096 + delta), boxes[1].chunkOffsets)
        val output = java.io.ByteArrayOutputStream()
        container.writeContainer(java.nio.channels.Channels.newChannel(output))
        assertTrue(output.size() > 0)
        // A second validation pass must not shift offsets again.
        Mp4LargeFileOffsets.promote(container)
        assertEquals(1024 + delta, boxes[0].chunkOffsets[0])
    }
    @Test fun keepsSmallFileAndUnsigned32BitBoundaryUnchanged() {
        val box = StaticChunkOffsetBox().apply { chunkOffsets = longArrayOf(1024, 0xffff_ffffL) }
        val container = BasicContainer().apply { addBox(box) }
        Mp4LargeFileOffsets.promote(container)
        assertSame(box, container.boxes.single())
        assertArrayEquals(longArrayOf(1024, 0xffff_ffffL), box.chunkOffsets)
    }
}
