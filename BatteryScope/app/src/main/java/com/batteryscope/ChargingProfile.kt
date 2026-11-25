package com.batteryscope

import java.util.Locale

/** Descriptive charging history. No assumed battery capacity, current conversion or power inference. */
internal class ChargingProfile {
    data class Sample(val elapsed: Long, val boot: Int?, val percent: Int?, val state: String,
        val plug: String, val voltageMv: Int?, val currentUa: Long?, val temperatureC: Double?,
        val counterUah: Long?, val usbVoltage: Long?, val usbCurrent: Long?, val wallMs: Long? = null,
        val advertisedCurrentUa: Long? = null, val advertisedVoltageUv: Long? = null)
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
        fun bounds(): Pair<Double,Double>? = min?.let { it to checkNotNull(max) }
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
        private val observedTemperatures = Range().apply {
            add(first.temperatureC?.takeIf { it in -100.0..100.0 })
        }
        val observedTemperatureCount: Long get() = observedTemperatures.count
        fun observedTemperatureBounds(): Pair<Double,Double>? = observedTemperatures.bounds()
        fun observedTemperatureText(): String = observedTemperatures.text("°C") +
            "; usable ${observedTemperatures.count}/$count"
        var count = 1L
            private set
        var last = first
            private set
        val first: Sample get() = retained.first().second
        fun add(sample: Sample) {
            val index = count++
            observedTemperatures.add(sample.temperatureC?.takeIf { it in -100.0..100.0 })
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
    private val curves = ArrayList<Curve>()
    /** Newest first; bounded to eight distinct observed segments. */
    fun recentCurves(): List<Curve> = curves.asReversed().toList()
    private val olderLongCurves = ArrayList<Curve>()
    var eligibleSegments = 0L
        private set
    var eligibleCurveSamples = 0L
        private set
    /** Eight newest segments plus up to eight longest earlier non-singleton segments.
     * All returned in reverse recording order, with no synthetic joins. */
    fun chartCurves(): List<Curve> = (olderLongCurves + curves).asReversed().toList()
    private fun keepOlder(curve: Curve) {
        val duration = curve.last.elapsed-curve.first.elapsed
        if (duration <= 0L) return
        olderLongCurves.add(curve)
        if (olderLongCurves.size > 8) {
            // Drop the shortest; prefer newer segments when durations are equal.
            val discard = olderLongCurves.indices.minByOrNull {
                olderLongCurves[it].last.elapsed-olderLongCurves[it].first.elapsed
            }!!
            olderLongCurves.removeAt(discard)
        }
    }
    var latestCurve: Curve? = null
        private set
    val bandTemperatures = List(10) { Range() }
    val bands = List(10) { Band() }
    /** Independent coarse ranges; do not sum rounded fine-band rates. */
    val phaseBounds = listOf(0 to 20, 20 to 80, 80 to 100)
    val phaseBands = List(3) { Band() }
    val phaseTemperatures = List(3) { Range() }
    val completedPhaseMinutes = List(3) { Range() }
    private val phaseStart = arrayOfNulls<Long>(3)
    var omittedPhaseIntervals = 0L
        private set

    private fun observePhases(before: Sample?, after: Sample) {
        val hi = after.percent
        val valid = after.state == "CHARGING" && connected(after.plug) &&
            hi != null && hi in 0..100 && after.elapsed >= 0L &&
            after.boot != null && after.boot >= 0
        val lo = before?.percent
        val adjacent = valid && before != null && lo != null && lo in 0..100 &&
            hi!! >= lo && before.state == "CHARGING" && before.plug == after.plug &&
            before.elapsed >= 0L &&
            RecorderTimeMath.isRateInterval(after.elapsed-before.elapsed,before.boot,after.boot)
        if (adjacent) {
            val low = checkNotNull(lo)
            val high = checkNotNull(hi)
            val prior = checkNotNull(before)
            val index = phaseBounds.indexOfFirst { (a,b) -> low >= a && low < b }
                .let { if (low == 100) 2 else it }
            if (index >= 0 && high <= phaseBounds[index].second) {
                phaseBands[index].add(after.elapsed-prior.elapsed,high-low)
            } else omittedPhaseIntervals++
        } else if (before != null && before.state == "CHARGING" &&
            after.state == "CHARGING" && connected(after.plug) && before.plug == after.plug) {
            omittedPhaseIntervals++
        }
        phaseBounds.forEachIndexed { i,(start,end) ->
            if (!adjacent) phaseStart[i] = null
            val began = phaseStart[i]
            if (began != null && hi != null && hi >= end) {
                if (adjacent && hi == end && after.elapsed > began) {
                    completedPhaseMinutes[i].add((after.elapsed-began)/60_000.0)
                }
                phaseStart[i] = null
            }
            // Exact observed start only; repeated equal readings keep the first.
            if (valid && hi == start && phaseStart[i] == null) phaseStart[i] = after.elapsed
        }
    }
    val voltage = Range()
    val current = Range()
    val temperature = Range()
    val usablePower = Range()
    // Optional OEM/framework advertised fields already stored in history.
    // Positive int-domain values are reported raw, not validated limits.
    val advertisedCurrent = Range()
    val advertisedVoltage = Range()
    var advertisedPairs = 0L
        private set
    var advertisedCurrentMissing = 0L
        private set
    var advertisedVoltageMissing = 0L
        private set
    private fun advertisedValue(value: Long?): Double? =
        value?.takeIf { it in 1L until Int.MAX_VALUE.toLong() }?.toDouble()
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
    enum class ConnectedState { CHARGING, FULL, OTHER, TRANSITION_OR_UNKNOWN }
    private val connectedDurations = LongArray(ConnectedState.values().size)
    fun observedMs(state: ConnectedState): Long = connectedDurations[state.ordinal]
    fun connectedMs(): Long = connectedDurations.sum()
    var excludedConnectedIntervals = 0L
        private set
    private val voltageStatuses = sortedMapOf<String,Long>()
    private val currentStatuses = sortedMapOf<String,Long>()
    private fun tally(map: MutableMap<String,Long>, status: String) {
        map[status] = (map[status] ?: 0L) + 1L
    }
    fun voltageQualityCounts(): Map<String,Long> = voltageStatuses.toMap()
    fun currentQualityCounts(): Map<String,Long> = currentStatuses.toMap()
    private fun classifyConnected(state: String): ConnectedState = when (state) {
        "CHARGING" -> ConnectedState.CHARGING
        "FULL" -> ConnectedState.FULL
        "NOT_CHARGING", "DISCHARGING" -> ConnectedState.OTHER
        else -> ConnectedState.TRANSITION_OR_UNKNOWN
    }
    private fun observeConnection(before: Sample?, after: Sample) {
        if (before == null || (!connected(before.plug) && !connected(after.plug))) return
        if (before.plug != after.plug || !connected(after.plug) ||
            before.elapsed < 0L || after.elapsed < 0L ||
            !RecorderTimeMath.isRateInterval(after.elapsed-before.elapsed,before.boot,after.boot)) {
            excludedConnectedIntervals++
            return
        }
        val a = classifyConnected(before.state)
        val b = classifyConnected(after.state)
        val state = if (a == b) a else ConnectedState.TRANSITION_OR_UNKNOWN
        connectedDurations[state.ordinal] += after.elapsed-before.elapsed
    }
    private var previous: Sample? = null
    private fun connected(plug: String) = plug in setOf("AC","USB","WIRELESS","DOCK")
    fun accept(s: Sample) {
        val before = previous
        previous = s
        observeConnection(before,s)
        observePhases(before,s)
        if (s.state != "CHARGING" || !connected(s.plug)) return
        samples++
        voltage.add(s.voltageMv?.toDouble())
        current.add(s.currentUa?.toDouble())
        val temp = s.temperatureC?.takeIf { it in -100.0..100.0 }
        temperature.add(temp)
        if (s.percent != null && s.percent in 0..100) {
            bandTemperatures[minOf(s.percent/10,9)].add(temp)
            val phase = if (s.percent < 20) 0 else if (s.percent < 80) 1 else 2
            phaseTemperatures[phase].add(temp)
            if (s.boot != null && s.boot >= 0 && s.elapsed >= 0) {
                eligibleCurveSamples++
                val curve = latestCurve
                if (curve != null && before === curve.last && before.plug == s.plug &&
                    RecorderTimeMath.isRateInterval(s.elapsed-before.elapsed,before.boot,s.boot)) {
                    curve.add(s)
                } else {
                    val next = Curve(s)
                    latestCurve = next
                    curves.add(next)
                    eligibleSegments++
                    if (curves.size > 8) keepOlder(curves.removeAt(0))
                }
            }
        }
        val voltageQuality = BatteryMeasurementQuality.voltage(s.voltageMv)
        val currentQuality = BatteryMeasurementQuality.current(s.currentUa,s.state,s.plug)
        tally(voltageStatuses,voltageQuality.status)
        tally(currentStatuses,currentQuality.status)
        if (voltageQuality.usable) validVoltage++
        if (currentQuality.usable) validCurrent++
        usablePower.add(BatteryMeasurementQuality.batteryPowerW(s.voltageMv,s.currentUa,s.state,s.plug))
        if (s.usbVoltage != null && s.usbCurrent != null) rawUsbPairs++
        val advertisedI = advertisedValue(s.advertisedCurrentUa)
        val advertisedV = advertisedValue(s.advertisedVoltageUv)
        advertisedCurrent.add(advertisedI)
        advertisedVoltage.add(advertisedV)
        if (s.advertisedCurrentUa == null) advertisedCurrentMissing++
        if (s.advertisedVoltageUv == null) advertisedVoltageMissing++
        if (advertisedI != null && advertisedV != null) advertisedPairs++
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
        fun qualityText(counts: Map<String,Long>): String =
            if (counts.isEmpty()) "no charging samples" else counts.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        appendLine("Voltage assessment counts: " + qualityText(voltageStatuses))
        appendLine("Current assessment counts: " + qualityText(currentStatuses))
        appendLine("Passing these checks does not confirm OEM units, calibration or actual charger input.")
        appendLine("Battery temperature: " + temperature.text("°C") + "; usable ${temperature.count}/$samples")
        appendLine("Battery-side power by Android convention, not calibrated: " + usablePower.text("W",3))
        appendLine("Optional advertised maximum charging fields (not live or verified electrical limits):")
        appendLine("  Current (µA-labelled): " + advertisedCurrent.text("raw",0) +
            "; missing $advertisedCurrentMissing; rejected ${samples-advertisedCurrent.count-advertisedCurrentMissing}")
        appendLine("  Voltage (µV-labelled): " + advertisedVoltage.text("raw",0) +
            "; missing $advertisedVoltageMissing; rejected ${samples-advertisedVoltage.count-advertisedVoltageMissing}")
        appendLine("  Both positive non-sentinel fields recorded together: $advertisedPairs/$samples. No multiplication into watts or inference of negotiated protocol.")
        appendLine("Recorded USB raw candidate pairs: $rawUsbPairs; no verified calibrated charger input watts")
        appendLine("Charge counter exactly SOC × 45,000 µAh: $nominalMatches/$counterPairs paired samples. Matching nominal values are not independent capacity or current measurements.")
        appendLine()
        appendLine("Observed connected time (last 30 days; minutes of eligible sampled intervals):")
        fun minutes(ms: Long) = String.format(Locale.US,"%.2f",ms/60_000.0)
        appendLine("Connected: " + minutes(connectedMs()) + " min")
        appendLine("  CHARGING at both endpoints: " + minutes(observedMs(ConnectedState.CHARGING)) + " min")
        appendLine("  FULL at both endpoints: " + minutes(observedMs(ConnectedState.FULL)) + " min")
        appendLine("  Other known non-charging endpoints: " + minutes(observedMs(ConnectedState.OTHER)) + " min")
        appendLine("  State transition or unknown endpoints: " + minutes(observedMs(ConnectedState.TRANSITION_OR_UNKNOWN)) + " min")
        appendLine("Connection-related intervals excluded: $excludedConnectedIntervals (source changes, invalid timing, gaps or unknown/reboot identity). Matching sampled endpoints do not establish continuous state; this is observed coverage, not full plug-in duration. Zero coverage means no eligible observed time.")
        appendLine("=== CHARGING STAGES (LAST 30 DAYS) ===")
        phaseBounds.forEachIndexed { i,(a,b) ->
            val band = phaseBands[i]
            appendLine("  $a–$b%: " +
                (band.rate()?.let { String.format(Locale.US,"%.2f pp/h • +%d points • %.2f min observed",it,band.gained,band.ms/60_000.0) } ?: "insufficient observations"))
            appendLine("    Temperature: " + phaseTemperatures[i].text("°C"))
            appendLine("    Completed exact-boundary spans: " + completedPhaseMinutes[i].text("min",2))
        }
        appendLine("Stage speeds use eligible nondecreasing intervals wholly within each stage, including jumps across finer 10% bands. Cross-stage jumps or invalid adjacent same-source charging intervals omitted: $omittedPhaseIntervals. Temperature samples use the stage containing the observed SOC; 100% belongs to the final stage.")
        appendLine("Completed spans require observed start and end percentages and uninterrupted eligible CHARGING samples; no interpolation, estimates or merging across gaps. Missing full spans are unavailable, not zero. Do not deliberately drain the battery to collect a span.")
        appendLine("Observed SOC-band speed (percentage points/hour; no current or watts inferred):")
        bands.forEachIndexed { i, band ->
            appendLine("  " + i*10 + "–" + (i+1)*10 + "%: " +
                (band.rate()?.let { String.format(Locale.US,"%.2f pp/h • +%d points • %.1f min observed",it,band.gained,band.ms/60_000.0) } ?: "insufficient observations") +
                "; sampled temperature: " + bandTemperatures[i].text("°C"))
        }
        appendLine("Temperature bands use observed SOC at each sample (100% belongs to the final band), not interval averages.")
        appendLine()
        val chartSelection = chartCurves()
        appendLine("Chart selection: ${chartSelection.size}/$eligibleSegments eligible segments; ${chartSelection.sumOf { it.count }}/$eligibleCurveSamples observations represented before thinning; ${chartSelection.sumOf { it.points().size }} retained dots.")
        appendLine("Charts retain the eight most recent segments plus up to eight longest earlier segments with observed duration; gaps remain separate, other segments are not shown.")
        appendLine("Latest eligible charging curve checkpoints (last 30 days):")
        val curve = latestCurve
        if (curve == null) appendLine("No valid level with known boot identity.")
        else {
            val points = curve.points()
            appendLine("${curve.first.plug} • ${curve.count} observations • showing ${points.size} actual checkpoints, including first and last.")
            appendLine("Full observed segment temperature: " + curve.observedTemperatureText() + " (before chart thinning)")
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
