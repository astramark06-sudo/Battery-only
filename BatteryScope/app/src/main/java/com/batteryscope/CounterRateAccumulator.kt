package com.batteryscope

/** Rate denominators contain only intervals with two usable counter readings. */
internal class CounterRateAccumulator {
    var observedMs: Long = 0L
        private set
    var deltaUah: Long = 0L
        private set
    fun add(delta: Long?, durationMs: Long) {
        if (delta == null || durationMs <= 0L) return
        deltaUah += delta
        observedMs += durationMs
    }
    fun rateMa(): Double? = BatteryRateMath.counterRateMa(deltaUah, observedMs)
}

internal object CounterDelta {
    fun between(before: Long?, after: Long?): Long? =
        if (before == null || after == null || before < 0L || after < 0L) null else after - before
    fun gain(delta: Long?): Long? = delta?.takeIf { it >= 0L }
    fun loss(delta: Long?): Long? = delta?.takeIf { it <= 0L }?.let { -it }
}
