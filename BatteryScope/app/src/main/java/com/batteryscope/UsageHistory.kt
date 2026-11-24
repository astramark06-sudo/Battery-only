package com.batteryscope

/** Bounded replay plan for package-level activity transitions. No activity names or content. */
internal object UsageHistory {
    const val EVENT = "USAGE_EVENT_V1"
    const val CHECKPOINT = "USAGE_IMPORT_CHECKPOINT_V1"
    const val MAX_EVENTS = 512
    const val MAX_LOOKBACK_MS = 86_400_000L
    const val CHUNK_MS = 900_000L
    const val SETTLE_MS = 120_000L
    const val OVERLAP_MS = 120_000L

    data class Window(val from: Long, val end: Long, val reason: String)
    data class Transition(val time: Long, val type: Int, val packageName: String) {
        fun valid(window: Window) = time >= window.from && time < window.end &&
            type in 1..2 && packageName.length in 1..255 &&
            packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }
        fun details() = "$type:$packageName"
    }

    fun next(checkpoint: Long?, now: Long): Window? {
        if (now <= SETTLE_MS) return null
        val ceiling = now - SETTLE_MS
        val earliest = (ceiling - MAX_LOOKBACK_MS).coerceAtLeast(0L)
        val reason = when {
            checkpoint == null -> "INITIAL_BOUNDED_LOOKBACK"
            checkpoint > now -> "CLOCK_ROLLBACK_BOUNDED_LOOKBACK"
            checkpoint < earliest -> "RETENTION_GAP_BOUNDED_LOOKBACK"
            else -> "CONTINUATION"
        }
        val base = if (reason == "CONTINUATION") checkpoint!!.coerceAtLeast(earliest) else earliest
        if (base >= ceiling) return null
        val from = if (reason == "CONTINUATION") (base - OVERLAP_MS).coerceAtLeast(earliest) else base
        val end = minOf(ceiling,base + CHUNK_MS)
        return Window(from,end,reason)
    }

    /** A full query must succeed; a density cap never advances the checkpoint. */
    fun shrink(window: Window, checkpoint: Long?): Window? {
        val floor = if (window.reason == "CONTINUATION") maxOf(window.from,checkpoint ?: window.from) else window.from
        val span = window.end - floor
        if (span <= 1L) return null
        return window.copy(end = floor + span / 2)
    }
}
