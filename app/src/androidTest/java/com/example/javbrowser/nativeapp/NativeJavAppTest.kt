package com.example.javbrowser.nativeapp

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.javbrowser.nativeapp.data.JavRepository
import com.example.javbrowser.nativeapp.data.LibraryStore
import com.example.javbrowser.nativeapp.domain.JavSearchResult
import com.example.javbrowser.nativeapp.domain.JavTitle
import com.example.javbrowser.nativeapp.domain.PlaybackVariant
import com.example.javbrowser.nativeapp.domain.SourceCapability
import com.example.javbrowser.nativeapp.domain.SourceError
import com.example.javbrowser.nativeapp.domain.SourceRef
import com.example.javbrowser.nativeapp.domain.SourceResult
import com.example.javbrowser.nativeapp.domain.StreamType
import com.example.javbrowser.nativeapp.source.JavSource
import com.example.javbrowser.nativeapp.source.JavSourceManager
import com.example.javbrowser.nativeapp.ui.NativeJavApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeJavAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearLibrary() {
        context.getSharedPreferences("native_library", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("native_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("secure_screen", false).commit()
    }

    @Test
    fun primaryNavigationAndSettingsAreNativeAndReachable() {
        launchApp(emptyList())

        composeRule.onNodeWithTag("screen-home").assertIsDisplayed()
        composeRule.onNodeWithTag("nav-discover").performClick()
        composeRule.onNodeWithTag("screen-discover").assertIsDisplayed()
        composeRule.onNodeWithText("Latest").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-library").performClick()
        composeRule.onNodeWithTag("screen-library").assertIsDisplayed()
        composeRule.onNodeWithText("Your library is empty").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-downloads").performClick()
        composeRule.onNodeWithText("No downloads yet").assertIsDisplayed()

        composeRule.onNodeWithTag("nav-home").performClick()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithTag("screen-settings").assertIsDisplayed()
        composeRule.onNodeWithText("Privacy").assertIsDisplayed()
    }

    @Test
    fun searchKeepsSuccessfulSourceWhenAnotherFailsAndOpensDetail() {
        launchApp(listOf(SuccessSource(), FailingSource()))

        composeRule.onNodeWithTag("global-search-input").performTextInput("ABP-123")
        composeRule.onNodeWithTag("global-search-submit").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("ABP-123").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("failure · Unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("media-ABP-123").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("screen-detail").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Native test title").assertIsDisplayed()
        composeRule.onNodeWithText("Playback sources").assertIsDisplayed()
        composeRule.onNodeWithText("SUCCESS · 1080p").assertIsDisplayed()
    }

    private fun launchApp(sources: List<JavSource>) {
        val repository = JavRepository(JavSourceManager(sources))
        val library = LibraryStore(context)
        composeRule.setContent { NativeJavApp(repository, library, incoming = null) }
    }

    private class SuccessSource : JavSource {
        override val id = "success"
        override val displayName = "Success"
        override val capabilities = setOf(
            SourceCapability.SEARCH,
            SourceCapability.DETAILS,
            SourceCapability.PLAYBACK,
        )
        private val ref = SourceRef(id, "ABP-123", "https://example.invalid/ABP-123")
        private val title = JavTitle(
            id = "ABP-123",
            code = "ABP-123",
            title = "Native test title",
            sourceRefs = listOf(ref),
        )

        override suspend fun search(query: String, page: Int, filters: List<com.example.javbrowser.nativeapp.domain.SourceFilterValue>) =
            SourceResult.Success(listOf(JavSearchResult(title, ref)))

        override suspend fun getDetails(ref: SourceRef) = SourceResult.Success(title)

        override suspend fun getPlaybackSources(ref: SourceRef) = SourceResult.Success(
            listOf(PlaybackVariant(id, "1080p", "https://example.invalid/video.m3u8", 1080, StreamType.HLS)),
        )
    }

    private class FailingSource : JavSource {
        override val id = "failure"
        override val displayName = "Failure"
        override val capabilities = setOf(SourceCapability.SEARCH)

        override suspend fun search(query: String, page: Int, filters: List<com.example.javbrowser.nativeapp.domain.SourceFilterValue>) =
            SourceResult.Failure(SourceError.Network("Offline fixture"))

        override suspend fun getDetails(ref: SourceRef): SourceResult<JavTitle> =
            SourceResult.Failure(SourceError.Unsupported("Details are not supported"))
    }
}
