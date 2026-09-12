package com.example.javbrowser

/** Bounded in-process results; no persisted search history or large Bundle. */
object SearchSessionStore {
    private val snapshots = object : LinkedHashMap<String, SearchSnapshot>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchSnapshot>?) = size > 5
    }
    @Synchronized fun put(snapshot: SearchSnapshot) { snapshots[snapshot.request.requestId] = snapshot }
    @Synchronized fun get(id: String?): SearchSnapshot? = snapshots[id]
    @Synchronized fun remove(id: String?) { snapshots.remove(id) }
}
