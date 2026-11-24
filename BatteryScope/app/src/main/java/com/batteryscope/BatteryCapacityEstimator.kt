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
    private const val MAX_SESSION_GAP_MS = 90_000L

    fun estimate(samples: List<CapacitySample>): CapacityEstimate? {
        if (samples.size < 2) return null
        val ordered = samples.sortedWith(compareBy<CapacitySample> { it.bootCount ?: Int.MIN_VALUE }
            .thenBy { it.elapsedRealtimeMs ?: it.timestampMs })
        val estimates = mutableListOf<Double>()
        var session = mutableListOf<CapacitySample>()

        fun flush() {
            if (session.size >= 2) {
                val first = session.first()
                val last = session.last()
                val start = first.percent
                val end = last.percent
                val startCharge = first.chargeCounterUah
                val endCharge = last.chargeCounterUah
                if (start != null && end != null && startCharge != null && endCharge != null) {
                    val deltaPercent = end - start
                    val deltaCharge = endCharge - startCharge
                    if (abs(deltaPercent) >= MIN_SOC_CHANGE_PERCENT &&
                        ((deltaPercent > 0 && deltaCharge > 0L) || (deltaPercent < 0 && deltaCharge < 0L)) &&
                        ((mode(first) == "CHARGING" && deltaPercent > 0) || (mode(first) == "DISCHARGING" && deltaPercent < 0))) {
                        val capacityMah = abs(deltaCharge).toDouble() /
                            (abs(deltaPercent).toDouble() / 100.0) / 1000.0
                        if (capacityMah in 1500.0..8000.0) estimates += capacityMah
                    }
                }
            }
            session = mutableListOf()
        }

        for (sample in ordered) {
            val mode = mode(sample)
            val valid = sample.bootCount != null && sample.percent?.let { it in 1..99 } == true && sample.chargeCounterUah?.let { it >= 0L } == true && mode != null
            if (!valid) {
                flush()
                continue
            }
            val previous = session.lastOrNull()
            if (previous != null) {
                val continuous = previous.bootCount == sample.bootCount &&
                    mode(previous) == mode &&
                    (if (sample.elapsedRealtimeMs != null && previous.elapsedRealtimeMs != null)
                        sample.elapsedRealtimeMs - previous.elapsedRealtimeMs else sample.timestampMs - previous.timestampMs) in 1L..MAX_SESSION_GAP_MS
                if (!continuous) flush()
            }
            session += sample
        }
        flush()
        if (estimates.isEmpty()) return null

        val sorted = estimates.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val spread = if (median > 0.0) (sorted.last() - sorted.first()) / median * 100.0 else 100.0
        val validPairs = samples.filter { it.percent != null && it.chargeCounterUah != null && it.percent in 1..99 && it.chargeCounterUah >= 0L }
        // Exact nominal-capacity proportionality across several levels is suspicious,
        // but cannot prove how the OEM computes the counter.
        val nominalMatch = validPairs.map { it.percent }.distinct().size >= 3 &&
            validPairs.all { it.chargeCounterUah == it.percent!!.toLong() * 45_000L }
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

    fun report(samples: List<CapacitySample>): String {
        val estimate = estimate(samples) ?: return buildString {
            appendLine("Current full-charge capacity: Not yet measurable")
            appendLine("Original design capacity: 4,500 mAh")
            appendLine("Method: Android charge-counter change compared with battery-percentage change across continuous sessions.")
            appendLine("BatteryScope needs valid charge-counter readings and at least a 12-percentage-point change in one uninterrupted charging or discharging session.")
            append("Keep the recorder enabled during normal use and charging. No forced 0% discharge is required.")
        }
        return buildString {
            appendLine("Estimated current capacity: ~${estimate.capacityMah.toInt()} mAh")
            appendLine(if (estimate.nominalCounterMatch) "Apparent ratio to design: ~${String.format(java.util.Locale.US, "%.1f", estimate.healthPercent)}% (not verified health)" else "Estimated battery health: ~${String.format(java.util.Locale.US, "%.1f", estimate.healthPercent)}%")
            if (estimate.nominalCounterMatch) appendLine("Warning: the counter exactly follows nominal 4,500 mAh × reported SOC. Independence is unverified; this cannot establish actual battery wear.")
            appendLine("Original design capacity: 4,500 mAh")
            appendLine("Confidence: ${estimate.confidence} • ${estimate.validSessions} valid sessions • ${String.format(java.util.Locale.US, "%.1f", estimate.spreadPercent)}% session spread")
            appendLine()
            appendLine("Estimate uses Android's remaining-charge counter against state-of-charge change. It is not a laboratory capacity test.")
            append("Collect more normal charge/discharge sessions to improve confidence. Avoid deliberately draining the battery to 0%.")
        }
    }

    private fun mode(sample: CapacitySample): String? = when {
        sample.status == "CHARGING" || (sample.status == "FULL" && BatteryStateMath.connected(sample.plug)) -> "CHARGING"
        BatteryStateMath.discharging(sample.status, sample.plug) -> "DISCHARGING"
        else -> null
    }
}
