package com.example.javbrowser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Preserve the actual page URL, including query and fragment; never copy app-owned placeholders. */
object PageUrlClipboard {
    fun copyableUrl(value: String?): String? {
        val original = value?.trim() ?: return null
        val url = original.toHttpUrlOrNull() ?: return null
        if (url.host == "javbrowser.app") return null
        return original
    }
}
