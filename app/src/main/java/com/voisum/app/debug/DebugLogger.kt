package com.voisum.app.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-memory circular log buffer for debugging.
 * Max 1000 entries — oldest evicted first.
 *
 * Usage:
 *   DebugLogger.d("BubbleManager", "showBubble() called, isShowing=$isShowing")
 */
object DebugLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun formatted(): String {
            val ts = fmt.format(Date(timestamp))
            val lvl = level.name.first()
            return "$ts [$lvl/$tag] $message"
        }
    }

    private const val MAX_ENTRIES = 1000
    private val entries = CopyOnWriteArrayList<Entry>()

    var isEnabled: Boolean = true

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    private fun log(level: Level, tag: String, message: String) {
        if (!isEnabled) return
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        entries.add(entry)
        // Evict oldest when over limit
        while (entries.size > MAX_ENTRIES) {
            try { entries.removeAt(0) } catch (_: Exception) { break }
        }
        // Also log to Android logcat
        when (level) {
            Level.DEBUG -> android.util.Log.d("VA_$tag", message)
            Level.INFO -> android.util.Log.i("VA_$tag", message)
            Level.WARN -> android.util.Log.w("VA_$tag", message)
            Level.ERROR -> android.util.Log.e("VA_$tag", message)
        }
    }

    fun getAll(): List<Entry> = entries.toList()

    fun getFiltered(tag: String? = null, level: Level? = null): List<Entry> {
        return entries.filter { entry ->
            (tag == null || entry.tag == tag) &&
            (level == null || entry.level == level)
        }
    }

    fun getAllTags(): Set<String> = entries.map { it.tag }.toSet()

    fun clear() = entries.clear()

    fun export(): String {
        return entries.joinToString("\n") { it.formatted() }
    }
}
