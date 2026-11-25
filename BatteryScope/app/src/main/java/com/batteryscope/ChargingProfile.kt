package com.batteryscope

import java.util.Locale

/** Descriptive charging history. No assumed battery capacity, current conversion or power inference. */
internal class ChargingProfile {
    data class Sample(val elapsed: Long, val boot: Int?, val percent: Int?, val state: String,
        val plug: String, val voltageMv: Int?, val currentUa: Long?, val temperatureC: Double?,
        val counterUah: Long?, val usbVoltage: Long?, val usbCurrent: Long?)
    class Range {
        var count = 0L
            private set
        private var min: Double? = null
        private var max: Double? = null
        fun add(v: Double?) {
            if (v == null || !v.isFinite()) return
            count++
            min = minOf(min ?: v,v)
            max = maxOf(max ?: v,v)
        }
        fun text(unit: String, decimals: Int = 1): String =
            if (count == 0L) "unavailable" else String.format(Locale.US,"%.${decimals}f to %.${decimals}f %s (%d samples)",min,max,unit,count)
    }
    class Band {
        var ms = 0L
            private set
        var gained = 0L
            private set
        fun add(duration: Long, change: Int) {
            ms += duration
            gained += change
        }
        fun rate(): Double? = if (ms > 0L) gained * 3_600_000.0 / ms else null
    }
    /** Bounded actual observations, not interpolated threshold crossings. */
    class Curve(first: Sample) {
        private val retained = mutableListOf(0L to first)
        private var stride = 1L
        var count = 1L
            private set
        var last = first
            private set
        val first: Sample get() = retained.first().second
        fun add(sample: Sample) {
            val index = count++
            last = sample
            if (index % stride == 0L) retained.add(index to sample)
            if (retained.size > 15) {
                stride *= 2
                retained.removeAll { it.first % stride != 0L }
            }
        }
        fun points(): List<Sample> = retained.map { it.second }.let {
            if (it.last() === last) it else it + last
        }
    }
    var latestCurve: Curve? = null
        private set
    val bandTemperatures = List(10) { Range() }
    val bands = List(10) { Band() }
    val voltage = Range()
    val current = Range()
    val temperature = Range()
    val usablePower = Range()
    var samples = 0L
        private set
    var validVoltage = 0L
        private set
    var validCurrent = 0L
        private set
    var rawUsbPairs = 0L
        private set
    var counterPairs = 0L
        private set
    var nominalMatches = 0L
        private set
    var omittedIntervals = 0L
        private set
    private var previous: Sample? = null
    private fun connected(plug: String) = plug in setOf("AC","USB","WIRELESS","DOCK")
    fun accept(s: Sample) {
        val before = previous
        previous = s
        if (s.state != "CHARGING" || !connected(s.plug)) return
        samples++
        voltage.add(s.voltageMv?.toDouble())
        current.add(s.currentUa?.toDouble())
        val temp = s.temperatureC?.takeIf { it in -100.0..100.0 }
        temperature.add(temp)
        if (s.percent != null && s.percent in 0..100) {
            bandTemperatures[minOf(s.percent/10,9)].add(temp)
            if (s.boot != null && s.boot >= 0 && s.elapsed >= 0) {
                val curve = latestCurve
                if (curve != null && before === curve.last && before.plug == s.plug &&
                    RecorderTimeMath.isRateInterval(s.elapsed-before.elapsed,before.boot,s.boot)) {
                    curve.add(s)
                } else latestCurve = Curve(s)
            }
        }
        if (BatteryMeasurementQuality.voltage(s.voltageMv).usable) validVoltage++
        if (BatteryMeasurementQuality.current(s.currentUa,s.state,s.plug).usable) validCurrent++
        usablePower.add(BatteryMeasurementQuality.batteryPowerW(s.voltageMv,s.currentUa,s.state,s.plug))
        if (s.usbVoltage != null && s.usbCurrent != null) rawUsbPairs++
        if (s.percent != null && s.percent in 0..100 && s.counterUah != null) {
            counterPairs++
            if (s.counterUah == s.percent.toLong()*45_000L) nominalMatches++
        }
        if (before == null || before.state != "CHARGING" || before.plug != s.plug) return
        val elapsed = s.elapsed - before.elapsed
        val lo = before.percent
        val hi = s.percent
        if (lo == null || hi == null || lo !in 0..100 || hi !in lo..100 ||
            !RecorderTimeMath.isRateInterval(elapsed,before.boot,s.boot)) {
            omittedIntervals++
            return
        }
        val band = minOf(lo/10,9)
        if (hi > (band+1)*10) {
            omittedIntervals++
            return
        }
        bands[band].add(elapsed,hi-lo)
    }

    fun report(): String = buildString {
        appendLine("=== CHARGING MEASUREMENT EVIDENCE (LAST 30 DAYS) ===")
        appendLine("Android CHARGING with known connected source: $samples samples")
        appendLine("Raw battery voltage (mV-labelled): " + voltage.text("raw",0) + "; expected range passed $validVoltage/$samples")
        appendLine("Raw battery current (µA-labelled): " + current.text("raw",0) + "; direction/range passed $validCurrent/$samples")
        appendLine("Battery temperature: " + temperature.text("°C"))
        appendLine("Battery-side power by Android convention, not calibrated: " + usablePower.text("W",3))
        appendLine("Recorded USB raw candidate pairs: $rawUsbPairs; no verified calibrated charger input watts")
        appendLine("Charge counter exactly SOC × 45,000 µAh: $nominalMatches/$counterPairs paired samples. Matching nominal values are not independent capacity or current measurements.")
        appendLine("Observed SOC-band speed (percentage points/hour; no current or watts inferred):")
        bands.forEachIndexed { i, band ->
            appendLine("  " + i*10 + "–" + (i+1)*10 + "%: " +
                (band.rate()?.let { String.format(Locale.US,"%.2f pp/h • +%d points • %.1f min observed",it,band.gained,band.ms/60_000.0) } ?: "insufficient observations") +
                "; sampled temperature: " + bandTemperatures[i].text("°C"))
        }
        appendLine("Temperature bands use observed SOC at each sample (100% belongs to the final band), not interval averages.")
        appendLine()
        appendLine("Latest eligible charging curve checkpoints (last 30 days):")
        val curve = latestCurve
        if (curve == null) appendLine("No valid level with known boot identity.")
        else {
            val points = curve.points()
            appendLine("${curve.first.plug} • ${curve.count} observations • showing ${points.size} actual checkpoints, including first and last.")
            appendLine("Elapsed min | SOC | Battery temperature")
            points.forEach { point ->
                val temp = point.temperatureC?.takeIf { it in -100.0..100.0 }?.let {
                    String.format(Locale.US,"%.1f °C",it)
                } ?: "unavailable"
                appendLine(String.format(Locale.US,"%.2f | %d%% | %s",(point.elapsed-curve.first.elapsed)/60_000.0,point.percent,temp))
            }
            appendLine("A gap, boot/source change, invalid level or non-charging sample splits the curve. No interpolation; decline is retained. This is an observed segment, not necessarily a full plug-in session.")
        }
        append("Only same-plug, same-known-boot adjacent charging intervals of 1–90s within a band contribute. Cross-band jumps, inconsistent level and invalid intervals omitted: $omittedIntervals. Zero gain is retained. Historical rate is not a guaranteed future charging time.")
    }
}
