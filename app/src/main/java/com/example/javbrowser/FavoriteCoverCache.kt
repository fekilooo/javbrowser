package com.example.javbrowser

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Persistent bookmark-cover cache plus a short negative cache for broken remote URLs. */
class FavoriteCoverCache(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "favorite_covers").apply { mkdirs() }
    private val failures = appContext.getSharedPreferences("favorite_cover_failures", Context.MODE_PRIVATE)

    fun cachedFile(bookmarkUrl: String): File? = fileFor(bookmarkUrl).takeIf { it.isFile && it.length() > 0L }

    fun shouldTry(candidateUrl: String, now: Long = System.currentTimeMillis()): Boolean =
        failures.getLong(failureKey(candidateUrl), 0L) <= now

    fun markFailed(candidateUrl: String, permanent: Boolean) {
        val duration = if (permanent) PERMANENT_FAILURE_TTL_MS else TRANSIENT_FAILURE_TTL_MS
        failures.edit().putLong(failureKey(candidateUrl), System.currentTimeMillis() + duration).apply()
    }

    fun markSucceeded(candidateUrl: String) {
        failures.edit().remove(failureKey(candidateUrl)).apply()
    }

    fun saveAsync(bookmarkUrl: String, bitmap: Bitmap) {
        if (bookmarkUrl.isBlank() || bitmap.width <= 0 || bitmap.height <= 0) return
        val detached = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        IO.execute {
            var output = detached
            try {
                val largest = maxOf(detached.width, detached.height)
                if (largest > MAX_EDGE_PX) {
                    val scale = MAX_EDGE_PX.toFloat() / largest
                    output = Bitmap.createScaledBitmap(
                        detached,
                        (detached.width * scale).toInt().coerceAtLeast(1),
                        (detached.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                }
                val target = fileFor(bookmarkUrl)
                val temporary = File(directory, target.name + ".tmp")
                FileOutputStream(temporary).use { stream ->
                    output.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                    stream.fd.sync()
                }
                if (temporary.length() > 0L) {
                    if (target.exists()) target.delete()
                    if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
                    target.setLastModified(System.currentTimeMillis())
                }
                temporary.delete()
                prune()
            } catch (_: Exception) {
                // Remote display may still succeed even when local persistence is unavailable.
            } finally {
                if (output !== detached) output.recycle()
                detached.recycle()
            }
        }
    }

    fun clearForRetry(bookmarkUrl: String, candidates: Collection<String>) {
        fileFor(bookmarkUrl).delete()
        val editor = failures.edit()
        candidates.forEach { editor.remove(failureKey(it)) }
        editor.apply()
    }

    fun delete(bookmarkUrl: String) {
        fileFor(bookmarkUrl).delete()
    }

    private fun fileFor(bookmarkUrl: String): File = File(directory, digest(bookmarkUrl) + ".jpg")

    private fun failureKey(candidateUrl: String): String = "failed_" + digest(candidateUrl)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun prune() {
        val files = directory.listFiles { file -> file.extension == "jpg" }.orEmpty()
            .sortedByDescending(File::lastModified)
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            retainedBytes += file.length()
            if (index >= MAX_FILES || retainedBytes > MAX_BYTES) file.delete()
        }
    }

    companion object {
        private const val MAX_EDGE_PX = 720
        private const val MAX_FILES = 400
        private const val MAX_BYTES = 60L * 1024L * 1024L
        private const val TRANSIENT_FAILURE_TTL_MS = 10L * 60L * 1000L
        private const val PERMANENT_FAILURE_TTL_MS = 24L * 60L * 60L * 1000L
        private val IO = Executors.newSingleThreadExecutor()
    }
}
