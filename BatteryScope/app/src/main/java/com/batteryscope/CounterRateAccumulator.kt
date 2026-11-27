package com.batteryscope

/** Rate denominators contain only intervals with two usable counter readings. */
internal class CounterRateAccumulator {
    var observedMs: Long = 0L
        private set
    var deltaUah: Long = 0L
        private set

    fun add(delta: Long?, durationMs: Long) {
        if (delta == null || !CounterDelta.isPlausibleIntervalDelta(delta, durationMs)) return
        // Commit both sums or neither; avoid wraparound and mismatched denominators.
        val nextDelta: Long
        val nextDuration: Long
        try {
            nextDelta = Math.addExact(deltaUah, delta)
            nextDuration = Math.addExact(observedMs, durationMs)
        } catch (_: ArithmeticException) {
            return
        }
        deltaUah = nextDelta
        observedMs = nextDuration
    }
    fun rateMa(): Double? = BatteryRateMath.counterRateMa(deltaUah, observedMs)
}

internal object CounterDelta {
    // Broad rejection-only bounds for RMX2161; not validated operating limits.
    private const val MAX_REMAINING_UAH = 12_000_000L
    private const val MAX_ABS_CURRENT_UA = 20_000_000L
    // Historical handset exports show 45,000 µAh SOC-sized counter steps.
    // Allow a step of quantization uncertainty rather than treating it as precise instantaneous current.
    private const val QUANTIZATION_ALLOWANCE_UAH = 45_000L

    fun isPlausibleReading(raw: Long?): Boolean = raw != null && raw in 0L..MAX_REMAINING_UAH

    fun between(before: Long?, after: Long?): Long? =
        if (!isPlausibleReading(before) || !isPlausibleReading(after)) null else after!! - before!!

    fun isPlausibleIntervalDelta(delta: Long, durationMs: Long): Boolean {
        if (!RecorderTimeMath.isRateDuration(durationMs)) return false
        val limit = MAX_ABS_CURRENT_UA * durationMs / 3_600_000L + QUANTIZATION_ALLOWANCE_UAH
        return delta in -limit..limit
    }

    fun forInterval(before: Long?, after: Long?, durationMs: Long): Long? =
        between(before, after)?.takeIf { isPlausibleIntervalDelta(it, durationMs) }

    fun gain(delta: Long?): Long? = delta?.takeIf { it >= 0L }
    fun loss(delta: Long?): Long? = delta?.takeIf { it <= 0L && it != Long.MIN_VALUE }?.let { -it }
}
