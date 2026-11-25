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
