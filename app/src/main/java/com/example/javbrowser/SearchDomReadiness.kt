package com.example.javbrowser

/** Wait for two consecutive stable result/empty snapshots after page load. */
class SearchDomReadiness {
    private var previous: String? = null
    fun reset() { previous = null }
    fun observe(loaded: Boolean, ready: Boolean, blocked: Boolean, signature: String): Boolean {
        if (blocked) return true
        if (!loaded || !ready || signature.isEmpty()) { reset(); return false }
        val stable = previous == signature
        previous = signature
        return stable
    }
}
