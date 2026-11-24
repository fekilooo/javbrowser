package com.example.javbrowser

import org.mp4parser.Container
import org.mp4parser.boxes.iso14496.part12.ChunkOffset64BitBox
import org.mp4parser.boxes.iso14496.part12.ChunkOffsetBox
import org.mp4parser.boxes.iso14496.part12.StaticChunkOffsetBox

/** DefaultMp4Builder 1.9.56 always emits stco, even when final offsets exceed uint32. */
internal object Mp4LargeFileOffsets {
    fun promote(container: Container) {
        val offsets = container.getBoxes(ChunkOffsetBox::class.java, true)
        if (offsets.none { box -> box.chunkOffsets.any { it > 0xffff_ffffL } }) return
        val before = container.boxes.sumOf { it.size }
        fun replace(parent: Container) {
            parent.boxes = parent.boxes.map { box ->
                if (box is StaticChunkOffsetBox) ChunkOffset64BitBox().apply {
                    chunkOffsets = box.chunkOffsets.copyOf()
                } else {
                    if (box is Container) replace(box)
                    box
                }
            }
        }
        replace(container)
        // moov precedes mdat in DefaultMp4Builder output. Enlarging its tables
        // shifts every media sample, including offsets in tracks that were <4 GiB.
        val delta = container.boxes.sumOf { it.size } - before
        container.getBoxes(ChunkOffsetBox::class.java, true).forEach { box ->
            box.chunkOffsets = box.chunkOffsets.map { Math.addExact(it, delta) }.toLongArray()
        }
    }
}
