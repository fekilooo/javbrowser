package com.example.javbrowser

/** Collapse once per query; asynchronous site updates must not override a user's choice. */
data class SearchPanelState(
    val collapsed: Boolean = false,
    val automaticCollapseHandled: Boolean = false
) {
    fun onResults(hasResults: Boolean): SearchPanelState =
        if (hasResults && !automaticCollapseHandled) SearchPanelState(true, true) else this

    fun toggle(): SearchPanelState = SearchPanelState(!collapsed, true)
}
