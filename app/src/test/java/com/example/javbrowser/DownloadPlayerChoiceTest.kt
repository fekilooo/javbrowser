package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlayerChoiceTest {
    @Test fun newInstallAndUnknownPreferencesAsk() {
        listOf(null, "", "obsolete", "internal").forEach {
            assertEquals(DownloadPlayerChoice.ASK, DownloadPlayerChoice.fromStored(it))
        }
    }
    @Test fun storedChoiceSurvivesRoundTrip() {
        DownloadPlayerChoice.entries.forEach { assertEquals(it, DownloadPlayerChoice.fromStored(it.name)) }
    }
    @Test fun rememberedInternalAndExternalChoicesBypassPrompt() {
        listOf(DownloadPlayerChoice.INTERNAL, DownloadPlayerChoice.EXTERNAL).forEach {
            assertEquals(it, it.remembered(true).forPlayback(false))
        }
    }
    @Test fun iconAlwaysReopensPromptWithoutChangingPreference() {
        DownloadPlayerChoice.entries.forEach {
            assertEquals(DownloadPlayerChoice.ASK, it.forPlayback(true))
            assertEquals(it, it.forPlayback(false))
        }
    }
    @Test fun uncheckedRememberRestoresPromptForNextPlayback() {
        DownloadPlayerChoice.entries.forEach { assertEquals(DownloadPlayerChoice.ASK, it.remembered(false)) }
    }
    @Test fun clearingPreferenceRestoresPrompt() {
        assertEquals(DownloadPlayerChoice.ASK, DownloadPlayerChoice.fromStored(null).forPlayback(false))
    }
}
