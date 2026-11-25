package com.batteryscope

import java.util.Locale

/** Descriptive charging history. No assumed battery capacity, current conversion or power inference. */
internal class ChargingProfile {
    data class Sample(val elapsed: Long, val boot: Int?, val percent: Int?, val state: String,
        val plug: String, val voltageMv: Int?, val currentUa: Long?, val temperatureC: Double?,
        val counterUah: Long?, val usbVoltage: Long?, val usbCurrent: Long?, val wallMs: Long? = null,
        val advertisedCurrentUa: Long? = null, val advertisedVoltageUv: Long? = null,
        val chargerLabel: ChargerLabel? = null)
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
    private data class Crossing(val earliest: Long, val latest: Long)
    private val phaseStart = arrayOfNulls<Crossing>(3)
    val bracketedPhaseLowerMinutes = List(3) { Range() }
    val bracketedPhaseUpperMinutes = List(3) { Range() }
    var fullEndpointSpans = 0L
        private set
    private val evidenceChanges = ArrayDeque<String>()
    private var lastEvidence: List<String>? = null
    private var evidenceCount = 0L
    private val sourceBands = linkedMapOf<String,Band>()
    private val labeledBands = linkedMapOf<Pair<String,String>,List<Band>>()
    private var omittedLabeledIntervals = 0L
    private fun observeLabeledInterval(before: Sample, after: Sample, stage: Int, gain: Int) {
        val label = after.chargerLabel ?: return
        if (before.chargerLabel != label ||
            !label.validAt(before.plug,before.elapsed) || !label.validAt(after.plug,after.elapsed)) return
        val key = label.name to label.source
        // Bound report memory even with many imported labels.
        if (key !in labeledBands && labeledBands.size >= 16) {
            omittedLabeledIntervals++
            return
        }
        labeledBands.getOrPut(key) { List(3) { Band() } }[stage].add(after.elapsed-before.elapsed,gain)
    }
    private fun observeEvidence(s: Sample) {
        val values = listOf(s.plug,s.state,
            advertisedValue(s.advertisedCurrentUa)?.toLong()?.toString() ?: "unavailable",
            advertisedValue(s.advertisedVoltageUv)?.toLong()?.toString() ?: "unavailable")
        if (values != lastEvidence) {
            evidenceCount++
            if (evidenceChanges.size == 24) evidenceChanges.removeFirst()
            val time = s.wallMs?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(java.util.Date(it))
            } ?: "wall time unavailable"
            evidenceChanges.addLast("$time • ${s.plug}/${s.state} • advertised µA=${values[2]}, µV=${values[3]}")
            lastEvidence = values
        }
    }
    var omittedPhaseIntervals = 0L
        private set

    private fun observePhases(before: Sample?, after: Sample) {
        val hi = after.percent
        val valid = after.state == "CHARGING" && connected(after.plug) &&
            hi != null && hi in 0..100 && after.elapsed >= 0L &&
            after.boot != null && after.boot >= 0
        val terminalFull = after.state == "FULL" && hi == 100 && connected(after.plug) &&
            after.elapsed >= 0L && after.boot != null && after.boot >= 0
        val lo = before?.percent
        val adjacent = (valid || terminalFull) && before != null && lo != null && lo in 0..100 &&
            hi!! >= lo && before.state == "CHARGING" && before.plug == after.plug &&
            before.elapsed >= 0L &&
            RecorderTimeMath.isRateInterval(after.elapsed-before.elapsed,before.boot,after.boot)
        if (adjacent && valid) {
            val low = checkNotNull(lo)
            val high = checkNotNull(hi)
            val prior = checkNotNull(before)
            if (low < 100) {
                sourceBands.getOrPut(after.plug) { Band() }.add(after.elapsed-prior.elapsed,high-low)
                val index = phaseBounds.indexOfFirst { (a,b) -> low >= a && low < b }
                if (index >= 0 && high <= phaseBounds[index].second) {
                    phaseBands[index].add(after.elapsed-prior.elapsed,high-low)
                    observeLabeledInterval(prior,after,index,high-low)
                } else omittedPhaseIntervals++
            }
        } else if (!adjacent && before != null && before.state == "CHARGING" &&
            after.state == "CHARGING" && connected(after.plug) && before.plug == after.plug) {
            omittedPhaseIntervals++
        }
        phaseBounds.forEachIndexed { k,(start,end) ->
            if (!adjacent) phaseStart[k] = null
            if (phaseStart[k] == null && valid) {
                if (hi == start) phaseStart[k] = Crossing(after.elapsed,after.elapsed)
                else if (adjacent && lo!! < start && hi!! > start)
                    phaseStart[k] = Crossing(checkNotNull(before).elapsed,after.elapsed)
            }
            val began = phaseStart[k]
            if (began != null && hi != null && hi >= end) {
                if (adjacent && after.elapsed > began.earliest) {
                    if (began.earliest == began.latest && hi == end) {
                        completedPhaseMinutes[k].add((after.elapsed-began.latest)/60_000.0)
                        if (terminalFull) fullEndpointSpans++
                    } else {
                        val finishEarliest = if (hi == end) after.elapsed else checkNotNull(before).elapsed
                        val lower = (finishEarliest-began.latest).coerceAtLeast(0L)
                        val upper = after.elapsed-began.earliest
                        bracketedPhaseLowerMinutes[k].add(lower/60_000.0)
                        bracketedPhaseUpperMinutes[k].add(upper/60_000.0)
                        if (terminalFull) fullEndpointSpans++
                    }
                }
                phaseStart[k] = null
            }
        }
    }
    data class Stage(val start: Int, val end: Int, val rate: Double?, val gained: Long,
        val observedMs: Long, val exactText: String?, val boundedText: String?)
    fun stages(): List<Stage> = phaseBounds.mapIndexed { i,(a,b) ->
        val band = phaseBands[i]
        val low = bracketedPhaseLowerMinutes[i].bounds()?.first
        val high = bracketedPhaseUpperMinutes[i].bounds()?.second
        Stage(a,b,band.rate(),band.gained,band.ms,
            completedPhaseMinutes[i].takeIf { it.count > 0L }?.text("min",2),
            if (low != null && high != null) String.format(Locale.US,"%.2f–%.2f min possible envelope (%d spans)",
                low,high,bracketedPhaseLowerMinutes[i].count) else null)
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
        observeEvidence(s)
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
        if (lo == 100) return // Time after the first 100% observation is not stage progress.
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
        stages().forEachIndexed { i,stage ->
            appendLine("  ${stage.start}–${stage.end}%: " +
                (stage.rate?.let { String.format(Locale.US,"%.2f pp/h • +%d points • %.2f min observed",it,stage.gained,stage.observedMs/60_000.0) } ?: "insufficient observations"))
            appendLine("    Temperature: " + phaseTemperatures[i].text("°C"))
            appendLine("    Completed exact-boundary spans: " + (stage.exactText ?: "unavailable"))
            appendLine("    Skipped-boundary spans: " + (stage.boundedText ?: "unavailable"))
        }
        appendLine("Stage speeds use eligible nondecreasing intervals wholly within each stage, including jumps across finer 10% bands. Cross-stage jumps or invalid adjacent same-source charging intervals omitted: $omittedPhaseIntervals. Temperature samples use the stage containing the observed SOC; 100% belongs to the final stage.")
        appendLine("Exact-boundary spans measure elapsed time between recorded boundary samples, not exact physical crossing times. Skipped boundaries use adjacent observation bounds; envelope is the smallest lower and largest upper bound across completed spans, not an average or interpolation. Gaps, reversals and source changes reset spans. Same-source FULL at 100% can finish a span ($fullEndpointSpans spans), but contributes no active charging rate. Time at 100% after first observation excluded from stage rates. Missing spans are unavailable, not zero.")
        appendLine("=== OBSERVED CHARGING SOURCE COMPARISON (LAST 30 DAYS) ===")
        if (sourceBands.isEmpty()) appendLine("No eligible charging intervals.")
        sourceBands.forEach { (source,band) ->
            appendLine(String.format(Locale.US,"%s: %.2f pp/h • +%d points • %.2f observed min",source,band.rate(),band.gained,band.ms/60_000.0))
        }
        appendLine("Android source category is not a physical charger identity. Different adapters of the same type can be combined; rates depend on SOC, temperature and phone load.")
        appendLine("=== USER-LABELLED CHARGING COMPARISON (LAST 30 DAYS) ===")
        appendLine("Labels are manual assertions, not verified adapter identity. Only eligible same-assignment endpoint pairs wholly within a stage contribute; no retroactive attribution. Different assignments of the same exact label and source are aggregated, not joined. Imported labels are not authenticated; SOC, temperature and phone load may differ.")
        if (labeledBands.isEmpty()) appendLine("No eligible manually labelled intervals.")
        labeledBands.forEach { (key,stageBands) ->
            appendLine("  ${key.first} • Android source ${key.second}")
            stageBands.forEachIndexed { i,band ->
                val (a,b) = phaseBounds[i]
                appendLine("    $a–$b%: " + (band.rate()?.let {
                    String.format(Locale.US,"%.2f pp/h • +%d points • %.2f observed min",it,band.gained,band.ms/60_000.0)
                } ?: "insufficient observations"))
            }
        }
        appendLine("At most 16 label/source groups in recording order; additional labelled intervals omitted: $omittedLabeledIntervals.")
        appendLine("=== CHARGER / PHONE REPORTED STATE CHANGES (LAST 30 DAYS) ===")
        appendLine("Showing latest ${evidenceChanges.size}/$evidenceCount observed state/advertised-limit changes in recording order, including initial observation. Missing values do not establish a protocol change. Sampled evidence only, not negotiation packets or active contracts.")
        evidenceChanges.forEach { appendLine(it) }
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
