package com.example.javbrowser

/** Download playback preference only; web playback and bookmarks keep their own behavior. */
enum class DownloadPlayerChoice {
    ASK, INTERNAL, EXTERNAL;

    fun forPlayback(chooseAgain: Boolean): DownloadPlayerChoice = if (chooseAgain) ASK else this

    fun remembered(remember: Boolean): DownloadPlayerChoice = if (remember) this else ASK

    companion object {
        fun fromStored(value: String?): DownloadPlayerChoice = entries.firstOrNull { it.name == value } ?: ASK
    }
}
