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
    var invalidRateIntervals = 0L
        private set
    var nonPositiveIntervals = 0L
        private set
    var intervalsAtLeastDoubleTarget = 0L
        private set
    var validIntervalMs = 0L
        private set
    var knownReboots = 0L
        private set
    private var intervals = 0L
    private var intervalTotal = 0L

    private fun saturatedSum(a: Long, b: Long): Long =
        if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

    fun accept(deltaMs: Long, rateEligible: Boolean = deltaMs in 1_000L..targetMs * 3L, knownReboot: Boolean = false) {
        if (knownReboot) knownReboots++
        if (!rateEligible || knownReboot || deltaMs <= 0L) invalidRateIntervals++
        if (deltaMs <= 0L) {
            nonPositiveIntervals++
            return
        }
        if (rateEligible && !knownReboot) validIntervalMs = saturatedSum(validIntervalMs, deltaMs)
        intervals++
        intervalTotal = saturatedSum(intervalTotal, deltaMs)
        if (deltaMs >= targetMs * 2L) intervalsAtLeastDoubleTarget++
        if (deltaMs > targetMs * 3L) {
            gaps++
            missingMs = saturatedSum(missingMs, deltaMs - targetMs)
            longestMs = maxOf(longestMs, deltaMs)
        }
    }

    val averageMs: Long? get() = if (intervals > 0L) intervalTotal / intervals else null
}
