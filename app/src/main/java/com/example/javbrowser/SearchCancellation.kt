package com.example.javbrowser

import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Cancellation is observable by queued tasks, active HTTP calls and the browser cleanup. */
class SearchCancellation : SearchHandle {
    private val callbacks = mutableListOf<() -> Unit>()
    @Volatile override var isCancelled = false
        private set

    fun onCancel(callback: () -> Unit): () -> Unit {
        val runNow = synchronized(this) {
            if (isCancelled) true else { callbacks.add(callback); false }
        }
        if (runNow) callback()
        return { synchronized(this) { callbacks.remove(callback) }; Unit }
    }

    fun check() { if (isCancelled || Thread.currentThread().isInterrupted) throw InterruptedException() }

    override fun cancel() {
        val pending = synchronized(this) {
            if (isCancelled) return
            isCancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        pending.forEach { runCatching(it) }
    }
}

/** Process-wide limits, shared by every search screen. */
internal object SearchScheduler {
    val workers = Executors.newFixedThreadPool(3)
    val timers = Executors.newSingleThreadScheduledExecutor()
    val browserSlot = Semaphore(1, true)
}
