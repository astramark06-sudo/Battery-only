package com.batteryscope

import kotlin.math.abs

internal data class CapacitySample(
    val timestampMs: Long,
    val bootCount: Int?,
    val percent: Int?,
    val status: String,
    val plug: String,
    val chargeCounterUah: Long?,
    val elapsedRealtimeMs: Long? = null
)

internal data class CapacityEstimate(
    val capacityMah: Double,
    val healthPercent: Double,
    val validSessions: Int,
    val spreadPercent: Double,
    val confidence: String,
    val nominalCounterMatch: Boolean = false
)

internal object BatteryCapacityEstimator {
    const val DESIGN_CAPACITY_MAH = 4500.0
    private const val MIN_SOC_CHANGE_PERCENT = 12
    private const val NOMINAL_UAH_PER_PERCENT = 45_000L

    fun estimate(samples: List<CapacitySample>): CapacityEstimate? =
        estimateOrdered(samples.sortedWith(compareBy<CapacitySample> { it.bootCount ?: Int.MIN_VALUE }
            .thenBy { it.elapsedRealtimeMs ?: it.timestampMs }).asSequence())

    /** Consume already ordered samples once, retaining endpoints rather than entire sessions. */
    fun estimateOrdered(samples: Sequence<CapacitySample>): CapacityEstimate? {
        val estimates = mutableListOf<Double>()
        var first: CapacitySample? = null
        var previous: CapacitySample? = null
        var nominalSessions = 0

        fun flush() {
            val a = first
            val z = previous
            if (a != null && z != null && a !== z) {
                val start = a.percent
                val end = z.percent
                val deltaCharge = CounterDelta.between(a.chargeCounterUah, z.chargeCounterUah)
                if (start != null && end != null && deltaCharge != null) {
                    val deltaPercent = end - start
                    if (abs(deltaPercent) >= MIN_SOC_CHANGE_PERCENT &&
                        ((deltaPercent > 0 && deltaCharge > 0L) || (deltaPercent < 0 && deltaCharge < 0L)) &&
                        ((mode(a) == "CHARGING" && deltaPercent > 0) || (mode(a) == "DISCHARGING" && deltaPercent < 0))) {
                        val capacityMah = abs(deltaCharge).toDouble() /
                            (abs(deltaPercent).toDouble() / 100.0) / 1000.0
                        if (capacityMah in 1500.0..8000.0) {
                            estimates += capacityMah
                            // Per accepted session: exact nominal SOC proportionality can also
                            // occur with a constant offset. It is a warning, not proof of causation.
                            if (deltaCharge == deltaPercent.toLong() * NOMINAL_UAH_PER_PERCENT)
                                nominalSessions++
                        }
                    }
                }
            }
            first = null
            previous = null
        }

        for (sample in samples) {
            val mode = mode(sample)
            val percent = sample.percent
            val counter = sample.chargeCounterUah
            if (sample.bootCount == null || percent == null || percent !in 1..99 ||
                counter == null || counter < 0L || mode == null) {
                flush()
                continue
            }
            val prev = previous
            if (prev != null) {
                val interval = if (sample.elapsedRealtimeMs != null && prev.elapsedRealtimeMs != null)
                    sample.elapsedRealtimeMs - prev.elapsedRealtimeMs else sample.timestampMs - prev.timestampMs
                val socDelta = percent - requireNotNull(prev.percent)
                val counterDelta = counter - requireNotNull(prev.chargeCounterUah)
                val reversal = if (mode == "CHARGING") socDelta < 0 || counterDelta < 0L
                    else socDelta > 0 || counterDelta > 0L
                if (mode(prev) != mode || reversal ||
                    !RecorderTimeMath.isRateInterval(interval, prev.bootCount, sample.bootCount)) flush()
            }
            if (first == null) first = sample
            previous = sample
        }
        flush()
        if (estimates.isEmpty()) return null
        val sorted = estimates.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val spread = if (median > 0.0) (sorted.last() - sorted.first()) / median * 100.0 else 100.0
        val nominalMatch = nominalSessions > 0
        val confidence = when {
            nominalMatch -> "LOW"
            sorted.size >= 5 && spread <= 15.0 -> "HIGH"
            sorted.size >= 3 && spread <= 25.0 -> "MODERATE"
            else -> "LOW"
        }
        return CapacityEstimate(
            capacityMah = median,
            healthPercent = median / DESIGN_CAPACITY_MAH * 100.0,
            validSessions = sorted.size,
            spreadPercent = spread,
            confidence = confidence,
            nominalCounterMatch = nominalMatch
        )
    }

    fun report(samples: List<CapacitySample>): String = reportEstimate(estimate(samples))

    fun reportOrdered(samples: Sequence<CapacitySample>): String = reportEstimate(estimateOrdered(samples))

    private fun reportEstimate(result: CapacityEstimate?): String {
        val estimate = result ?: return buildString {
            appendLine("Current full-charge capacity: Not yet measurable")
            appendLine("Original design capacity: 4,500 mAh")
            appendLine("Method: Android charge-counter change compared with battery-percentage change across continuous sessions.")
            appendLine("BatteryScope needs valid charge-counter readings and at least a 12-percentage-point change in one uninterrupted charging or discharging session.")
            append("Keep the recorder enabled during normal use and charging. No forced 0% discharge is required.")
        }
        return buildString {
            appendLine("Estimated current capacity: ~${estimate.capacityMah.toInt()} mAh")
            appendLine("Apparent ratio to design: ~${String.format(java.util.Locale.US, "%.1f", estimate.healthPercent)}% (not verified health)")
            if (estimate.nominalCounterMatch) appendLine("Warning: at least one accepted session has counter change exactly proportional to nominal 4,500 mAh × SOC change. Independence is unverified; this cannot establish actual battery wear.")
            appendLine("Original design capacity: 4,500 mAh")
            appendLine("Repeatability indicator (not calibrated accuracy): ${estimate.confidence} • ${estimate.validSessions} valid sessions • ${String.format(java.util.Locale.US, "%.1f", estimate.spreadPercent)}% session spread")
            appendLine()
            appendLine("Estimate uses Android's remaining-charge counter against state-of-charge change. It is not a laboratory capacity test.")
            append("Collect more normal charge/discharge sessions to assess repeatability. Avoid deliberately draining the battery to 0%.")
        }
    }

    private fun mode(sample: CapacitySample): String? = when {
        sample.status == "CHARGING" -> "CHARGING"
        BatteryStateMath.discharging(sample.status, sample.plug) -> "DISCHARGING"
        else -> null
    }
}
