package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTimeMathTest {
    @Test
    fun rateIntervalsRejectRebootsAndOutOfRangeDurations() {
        org.junit.Assert.assertTrue(RecorderTimeMath.isRateInterval(1_000L, 7, 7))
        org.junit.Assert.assertTrue(RecorderTimeMath.isRateInterval(90_000L, 7, 7))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(999L, 7, 7))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(90_001L, 7, 7))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(30_000L, 7, 8))
        org.junit.Assert.assertTrue(RecorderTimeMath.isRateInterval(30_000L, null, 7))
    }

    @Test
    fun appCorrelationRequiresTwoInteractiveMatchingEndpoints() {
        org.junit.Assert.assertTrue(RecorderTimeMath.sameForegroundApp(true, true, "app.a", "app.a"))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(false, true, "app.a", "app.a"))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, false, "app.a", "app.a"))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, true, "app.a", "app.b"))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, true, "", ""))
    }

    @Test
    fun sameBootUsesMonotonicTimeDespiteWallClockAdjustment() {
        assertEquals(30_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 500_000L, 7,
            900_000L, 530_000L, 7
        ))
    }

    @Test
    fun rebootUsesWallTimeEvenWhenNewUptimeIsLarger() {
        assertEquals(1_500_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 120_000L, 7,
            2_500_000L, 1_020_000L, 8
        ))
    }

    @Test
    fun missingBootCountRequiresWallAndMonotonicAgreement() {
        assertEquals(30_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 500_000L, null,
            1_030_000L, 530_000L, null
        ))
        assertEquals(1_500_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 120_000L, null,
            2_500_000L, 1_020_000L, null
        ))
    }
}
