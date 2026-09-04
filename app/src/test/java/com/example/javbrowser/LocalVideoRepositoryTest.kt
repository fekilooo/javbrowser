package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVideoRepositoryTest {
    @Test
    fun extractsCodesFromDownloadedFileNames() {
        assertEquals(
            "SNOS-275",
            LocalVideoRepository.extractJavCode("SNOS-275 title-20260628-100228.ts")
        )
        assertEquals(
            "REBD-01022",
            LocalVideoRepository.extractJavCode("REBD-01-022 sample.mp4")
        )
        assertEquals(
            "FC2-PPV-123456",
            LocalVideoRepository.extractJavCode("backup_FC2-PPV-123456.mp4")
        )
    }

    @Test
    fun ignoresFileNamesWithoutJavCode() {
        assertNull(LocalVideoRepository.extractJavCode("video-20260628-100228.mp4"))
    }
}
