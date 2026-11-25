package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class ChargingProfileTest {
    private fun sample(t: Long, p: Int, boot: Int? = 9, state: String = "CHARGING", plug: String = "AC") =
        ChargingProfile.Sample(t,boot,p,state,plug,3,-560L,39.0,p*45_000L,null,null)

    @Test fun abnormalElectricalReadingsRemainRawWhileSocRateIsObserved() {
        val report = ChargingProfile()
        report.accept(sample(1000,9))
        report.accept(sample(31000,10))
        report.accept(sample(61000,10))
        report.accept(sample(91000,11))
        assertEquals(4L,report.samples)
        assertEquals(0L,report.validVoltage)
        assertEquals(0L,report.validCurrent)
        assertEquals(4L,report.nominalMatches)
        assertEquals(0L,report.usablePower.count)
        assertEquals(0L,report.rawUsbPairs)
        assertEquals(120.0,report.bands[0].rate()!!,0.001)
        assertEquals(60.0,report.bands[1].rate()!!,0.001)
        assertTrue(report.report().contains("4/4 paired samples"))
    }

    @Test fun curveRetainsActualEndpointsAndBoundsMemory() {
        val r = ChargingProfile()
        repeat(1000) { i -> r.accept(sample(1000L + i*30_000L, i%101)) }
        val c = r.latestCurve!!
        val points = c.points()
        assertEquals(1000L,c.count)
        assertTrue(points.size <= 16)
        assertEquals(1000L,points.first().elapsed)
        assertEquals(1000L+999*30_000L,points.last().elapsed)
        assertTrue(points.zipWithNext().all { (a,b) -> a.elapsed < b.elapsed })
        assertTrue(points.all { it.percent == ((it.elapsed-1000L)/30_000L%101).toInt() })
        assertEquals(1000L,r.bandTemperatures.sumOf { it.count })
        assertTrue(r.report().contains("1000 observations"))
    }

    @Test fun fullSegmentTemperatureRangeSurvivesThinningAndInvalidValues() {
        val r = ChargingProfile()
        repeat(64) { i ->
            val temperature = when (i) {
                1 -> 25.0
                3 -> 45.0
                2 -> null
                4 -> 150.0
                5 -> Double.NaN
                else -> 35.0
            }
            r.accept(sample(i*30_000L,20).copy(temperatureC=temperature))
        }
        val c = checkNotNull(r.latestCurve)
        assertEquals(61L,c.observedTemperatureCount)
        assertEquals(25.0 to 45.0,c.observedTemperatureBounds())
        assertTrue(c.points().none { it.temperatureC == 25.0 || it.temperatureC == 45.0 })
        assertTrue(c.points().size <= 16)
        assertTrue(r.report().contains("Full observed segment temperature: 25.0 to 45.0 °C (61 samples); usable 61/64"))

        val sparse = ChargingProfile.Curve(sample(0,20).copy(temperatureC=null))
        repeat(63) { i ->
            sparse.add(sample((i+1)*30_000L,20).copy(temperatureC=if(i==0) 37.0 else null))
        }
        assertEquals(1L,sparse.observedTemperatureCount)
        assertEquals(37.0 to 37.0,sparse.observedTemperatureBounds())
        assertTrue(sparse.points().all { it.temperatureC == null })
        val empty = ChargingProfile.Curve(sample(0,20).copy(temperatureC=null))
        assertNull(empty.observedTemperatureBounds())
        assertEquals(0L,empty.observedTemperatureCount)
    }

    @Test fun curveSplitsAtGapsInvalidLevelsAndSourceChanges() {
        val r = ChargingProfile()
        r.accept(sample(1000,15))
        r.accept(sample(31000,14).copy(temperatureC = null))
        assertEquals(listOf(15,14),r.latestCurve!!.points().map { it.percent })
        r.accept(sample(131001,16))
        assertEquals(1L,r.latestCurve!!.count)
        r.accept(sample(161001,17).copy(percent = null))
        r.accept(sample(191001,18))
        assertEquals(1L,r.latestCurve!!.count)
        r.accept(sample(221001,19,plug="USB"))
        assertEquals("USB",r.latestCurve!!.first.plug)
        r.accept(sample(251001,20,boot=10,plug="USB"))
        assertEquals(1L,r.latestCurve!!.count)
        r.accept(sample(281001,20,boot=null,plug="USB"))
        r.accept(sample(311001,21,boot=10,plug="USB"))
        assertEquals(1L,r.latestCurve!!.count)
        r.accept(sample(341001,22,boot=10,state="FULL",plug="USB"))
        assertEquals(21,r.latestCurve!!.last.percent)
        assertTrue(r.report().contains("sampled temperature"))
    }

    @Test fun keepsOnlyEightRecentSegmentsNewestFirst() {
        val r = ChargingProfile()
        repeat(12) { i ->
            val start = i*200_000L
            r.accept(sample(start,i).copy(wallMs=1_000_000L+start))
            r.accept(sample(start+30_000L,i+1))
        }
        val curves = r.recentCurves()
        assertEquals(8,curves.size)
        assertEquals((11 downTo 4).toList(),curves.map { it.first.percent })
        assertTrue(curves.all { it.count == 2L })
        assertEquals(3_200_000L,curves.first().first.wallMs)
        assertEquals(r.latestCurve,curves.first())
    }

    @Test fun connectedCoveragePartitionsWithoutCountingUnknownTime() {
        val r = ChargingProfile()
        r.accept(sample(0,90))
        r.accept(sample(30_000,91))
        r.accept(sample(60_000,100,state="FULL"))
        r.accept(sample(90_000,100,state="FULL"))
        r.accept(sample(120_000,100,state="NOT_CHARGING"))
        r.accept(sample(150_000,99,state="DISCHARGING"))
        r.accept(sample(180_000,99,state="UNKNOWN"))
        r.accept(sample(210_000,99,state="UNKNOWN"))
        r.accept(sample(310_000,99))
        r.accept(sample(340_000,99,boot=null))
        r.accept(sample(370_000,99,boot=10))
        r.accept(sample(400_000,99,boot=10,plug="USB"))
        r.accept(sample(430_000,99,boot=10,plug="NONE",state="DISCHARGING"))
        r.accept(sample(460_000,99,boot=10,plug="NONE",state="DISCHARGING"))
        assertEquals(210_000L,r.connectedMs())
        assertEquals(30_000L,r.observedMs(ChargingProfile.ConnectedState.CHARGING))
        assertEquals(30_000L,r.observedMs(ChargingProfile.ConnectedState.FULL))
        assertEquals(30_000L,r.observedMs(ChargingProfile.ConnectedState.OTHER))
        assertEquals(120_000L,r.observedMs(ChargingProfile.ConnectedState.TRANSITION_OR_UNKNOWN))
        assertEquals(5L,r.excludedConnectedIntervals)
        assertTrue(r.report().contains("Connected: 3.50 min"))
    }

    @Test fun qualityReasonsReconcileToChargingSampleCount() {
        val r = ChargingProfile()
        r.accept(sample(0,20))
        r.accept(sample(30_000,21).copy(voltageMv=null,currentUa=null,temperatureC=null))
        r.accept(sample(60_000,22).copy(voltageMv=4000,currentUa=100_000L))
        r.accept(sample(90_000,23).copy(voltageMv=4000,currentUa=0L))
        r.accept(sample(120_000,24).copy(currentUa=Long.MIN_VALUE))
        r.accept(sample(150_000,25,state="FULL"))
        assertEquals(5L,r.samples)
        assertEquals(5L,r.voltageQualityCounts().values.sum())
        assertEquals(5L,r.currentQualityCounts().values.sum())
        assertEquals(2L,r.voltageQualityCounts()["OUT_OF_RANGE_EXPECTED_MILLIVOLTS"]!!)
        assertEquals(1L,r.currentQualityCounts()["SIGN_CONFLICT_WITH_ANDROID_CONVENTION"]!!)
        assertEquals(1L,r.currentQualityCounts()["UNAVAILABLE_OR_SENTINEL"]!!)
        assertEquals(1L,r.currentQualityCounts()["ZERO_OR_IDLE"]!!)
        assertEquals(1L,r.usablePower.count)
        assertEquals(4L,r.temperature.count)
    }

    @Test fun chartKeepsLongOlderSegmentsDespiteRecentSingletons() {
        val r = ChargingProfile()
        repeat(20) { segment ->
            val start = segment*2_000_000L
            val count = if (segment < 12) segment+2 else 1
            repeat(count) { i -> r.accept(sample(start+i*30_000L,segment)) }
        }
        assertEquals(20L,r.eligibleSegments)
        assertEquals(98L,r.eligibleCurveSamples)
        assertEquals((19 downTo 12).toList(),r.recentCurves().map { it.first.percent })
        val selected = r.chartCurves()
        assertEquals(16,selected.size)
        assertEquals((19 downTo 4).toList(),selected.map { it.first.percent })
        assertEquals(84L,selected.sumOf { it.count })
        assertTrue(selected.sumOf { it.points().size } <= 256)
        assertTrue(r.report().contains("16/20 eligible segments; 84/98 observations"))
    }

    @Test fun olderEqualDurationsPreferNewerAndSingletonsAreNotPromoted() {
        val r = ChargingProfile()
        repeat(24) { segment ->
            val start = segment*200_000L
            r.accept(sample(start,segment))
            if (segment != 15) r.accept(sample(start+30_000L,segment))
        }
        val selected = r.chartCurves()
        assertEquals((23 downTo 16).toList()+(14 downTo 7).toList(),selected.map { it.first.percent })
        assertEquals(16,selected.distinct().size)
        assertEquals(24L,r.eligibleSegments)
    }

    @Test fun advertisedLimitsRemainSeparateFromMeasuredPower() {
        val r = ChargingProfile()
        r.accept(sample(0,20).copy(advertisedCurrentUa=3_000_000,advertisedVoltageUv=5_000_000))
        r.accept(sample(30_000,21).copy(advertisedCurrentUa=0,advertisedVoltageUv=9_000_000))
        r.accept(sample(60_000,22).copy(advertisedCurrentUa=Int.MAX_VALUE.toLong()))
        r.accept(sample(90_000,23).copy(advertisedCurrentUa=2_000_000,advertisedVoltageUv=-1))
        r.accept(sample(120_000,24))
        r.accept(sample(150_000,25,state="FULL").copy(advertisedCurrentUa=1_000_000,advertisedVoltageUv=5_000_000))
        assertEquals(5L,r.samples)
        assertEquals(2L,r.advertisedCurrent.count)
        assertEquals(2L,r.advertisedVoltage.count)
        assertEquals(1L,r.advertisedCurrentMissing)
        assertEquals(2L,r.advertisedVoltageMissing)
        assertEquals(1L,r.advertisedPairs)
        assertEquals(0L,r.usablePower.count)
        assertEquals(0L,r.rawUsbPairs)
        assertTrue(r.report().contains("missing 1; rejected 2"))
        assertTrue(r.report().contains("missing 2; rejected 1"))
    }

    @Test fun coarseStagesCountIndependentIntervalsAndExactCompletedSpans() {
        val r = ChargingProfile()
        listOf(0,10,20,20,35,60,80,90,100).forEachIndexed { i,p ->
            r.accept(sample(i*30_000L,p))
        }
        assertEquals(listOf(20L,60L,20L),r.phaseBands.map { it.gained })
        assertEquals(listOf(60_000L,120_000L,60_000L),r.phaseBands.map { it.ms })
        assertEquals(listOf(1L,1L,1L),r.completedPhaseMinutes.map { it.count })
        assertEquals(listOf(1.0 to 1.0,2.0 to 2.0,1.0 to 1.0),r.completedPhaseMinutes.map { it.bounds() })
        assertEquals(0L,r.omittedPhaseIntervals)
        assertTrue(r.report().contains("20–80%: 1800.00 pp/h"))
    }

    @Test fun stagesNeverInventMissingBoundariesOrBridgeGaps() {
        val r = ChargingProfile()
        listOf(9,19,21,80,90,100).forEachIndexed { i,p ->
            r.accept(sample(i*30_000L,p))
        }
        assertEquals(0L,r.completedPhaseMinutes[0].count)
        assertEquals(0L,r.completedPhaseMinutes[1].count)
        assertEquals(1L,r.completedPhaseMinutes[2].count)
        assertEquals(1L,r.omittedPhaseIntervals)
        assertEquals(59L,r.phaseBands[1].gained)
        val g = ChargingProfile()
        g.accept(sample(0,20))
        g.accept(sample(120_000,50))
        g.accept(sample(150_000,80))
        assertEquals(0L,g.completedPhaseMinutes[1].count)
        assertEquals(1L,g.omittedPhaseIntervals)
        assertEquals(30L,g.phaseBands[1].gained)
        val d = ChargingProfile()
        d.accept(sample(0,80))
        d.accept(sample(30_000,90))
        d.accept(sample(60_000,89))
        d.accept(sample(90_000,100))
        assertEquals(0L,d.completedPhaseMinutes[2].count)
    }

    @Test fun fullEndpointCompletesFinalStageWithoutAddingActiveRateOrPlateau() {
        val r = ChargingProfile()
        r.accept(sample(0,80))
        r.accept(sample(30_000,90))
        r.accept(sample(60_000,100,state="FULL"))
        r.accept(sample(90_000,100,state="FULL"))
        assertEquals(1L,r.completedPhaseMinutes[2].count)
        assertEquals(1.0 to 1.0,r.completedPhaseMinutes[2].bounds())
        assertEquals(1L,r.fullEndpointSpans)
        assertEquals(30_000L,r.phaseBands[2].ms)
        val p = ChargingProfile()
        p.accept(sample(0,99))
        p.accept(sample(30_000,100))
        p.accept(sample(60_000,100))
        assertEquals(30_000L,p.phaseBands[2].ms)
        assertEquals(30_000L,p.bands[9].ms)
    }

    @Test fun skippedBoundariesAreBracketedAndNeverCrossGapsOrSourceChanges() {
        val r = ChargingProfile()
        listOf(19,21,79,81,99,100).forEachIndexed { i,level ->
            r.accept(sample(i*30_000L,level,state=if(level==100) "FULL" else "CHARGING"))
        }
        assertEquals(0L,r.completedPhaseMinutes[1].count)
        assertEquals(1L,r.bracketedPhaseLowerMinutes[1].count)
        assertEquals(0.5 to 0.5,r.bracketedPhaseLowerMinutes[1].bounds())
        assertEquals(1.5 to 1.5,r.bracketedPhaseUpperMinutes[1].bounds())
        assertEquals(1L,r.bracketedPhaseLowerMinutes[2].count)
        assertEquals(1.0 to 1.0,r.bracketedPhaseLowerMinutes[2].bounds())
        assertEquals(1.5 to 1.5,r.bracketedPhaseUpperMinutes[2].bounds())
        val g = ChargingProfile()
        g.accept(sample(0,19))
        g.accept(sample(30_000,21))
        g.accept(sample(60_000,79,plug="USB"))
        g.accept(sample(90_000,81,plug="USB"))
        assertEquals(0L,g.bracketedPhaseLowerMinutes[1].count)
        assertTrue(r.report().contains("Android source category is not a physical charger identity"))
        assertTrue(r.report().contains("CHARGER / PHONE REPORTED STATE CHANGES"))
    }

    @Test fun chargerLabelsCompareOnlyWithinSameAssignmentAndStage() {
        val a = ChargerLabel("Adapter A","AC","11111111-1111-1111-1111-111111111111",0)
        val b = a.copy(assignment="22222222-2222-2222-2222-222222222222",assignedElapsed=60_000)
        val c = ChargerLabel("Adapter B","AC","33333333-3333-3333-3333-333333333333",120_000)
        val r = ChargingProfile()
        r.accept(sample(0,20).copy(chargerLabel=a))
        r.accept(sample(30_000,21).copy(chargerLabel=a))
        r.accept(sample(60_000,22).copy(chargerLabel=b)) // not counted across assignments
        r.accept(sample(90_000,23).copy(chargerLabel=b))
        r.accept(sample(120_000,24).copy(chargerLabel=c))
        r.accept(sample(150_000,26).copy(chargerLabel=c))
        r.accept(sample(180_000,27)) // not retrospectively labelled
        val text = r.report().substringAfter("=== USER-LABELLED CHARGING COMPARISON").substringBefore("=== CHARGER / PHONE")
        val groupA = text.substringAfter("Adapter A • Android source AC").substringBefore("Adapter B")
        val groupB = text.substringAfter("Adapter B • Android source AC")
        assertTrue(groupA.contains("20–80%: 120.00 pp/h • +2 points • 1.00 observed min"))
        assertTrue(groupB.contains("20–80%: 240.00 pp/h • +2 points • 0.50 observed min"))
    }

    @Test fun rejectsGapsBootChangesReversalsAndCrossBandJumps() {
        val r = ChargingProfile()
        r.accept(sample(1000,15))
        r.accept(sample(31000,16,10))
        r.accept(sample(61000,15,10))
        r.accept(sample(91000,25,10))
        r.accept(sample(191001,26,10))
        r.accept(sample(221001,27,null))
        r.accept(sample(251001,28,10,state="NOT_CHARGING",plug="NONE"))
        r.accept(sample(281001,29,10))
        assertEquals(5L,r.omittedIntervals)
        assertEquals(0L,r.bands.sumOf { it.ms })
    }
}
