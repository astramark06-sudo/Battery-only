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
