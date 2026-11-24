package com.batteryscope

/** A gap describes missing observations, not a known cause or a reconstructed battery rate. */
internal class SamplingGapAccumulator(private val targetMs: Long = 30_000L) {
    init { require(targetMs in 1L..Long.MAX_VALUE / 3L) }
    var gaps = 0L
        private set
    var missingMs = 0L
        private set
    var longestMs = 0L
        private set
    private var intervals = 0L
    private var intervalTotal = 0L

    fun accept(deltaMs: Long) {
        if (deltaMs <= 0L) return
        intervals++
        intervalTotal += deltaMs
        if (deltaMs > targetMs * 3L) {
            gaps++
            missingMs += deltaMs - targetMs
            longestMs = maxOf(longestMs, deltaMs)
        }
    }

    val averageMs: Long? get() = if (intervals > 0L) intervalTotal / intervals else null
}
