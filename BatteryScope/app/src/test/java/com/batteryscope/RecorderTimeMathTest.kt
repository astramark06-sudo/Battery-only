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
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(30_000L, null, 7))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(30_000L, 7, null))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(30_000L, null, null))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(30_000L, -1, -1))
    }

    @Test
    fun numericDurationCheckDoesNotRequireBootMetadata() {
        org.junit.Assert.assertTrue(RecorderTimeMath.isRateDuration(30_000L))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateInterval(30_000L,null,null))
        org.junit.Assert.assertTrue(RecorderTimeMath.isRateDuration(1_000L))
        org.junit.Assert.assertTrue(RecorderTimeMath.isRateDuration(90_000L))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateDuration(999L))
        org.junit.Assert.assertFalse(RecorderTimeMath.isRateDuration(90_001L))
        assertEquals(45_000L,CounterDelta.forInterval(1_000_000L,1_045_000L,30_000L)!!)
    }

    @Test
    fun appCorrelationRequiresTwoInteractiveMatchingEndpoints() {
        org.junit.Assert.assertTrue(RecorderTimeMath.sameForegroundApp(true, true, "app.a", "app.a", true, true))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(false, true, "app.a", "app.a", true, true))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, false, "app.a", "app.a", true, true))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, true, "app.a", "app.b", true, true))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, true, "", "", true, true))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, true, "app.a", "app.a", false, true))
        org.junit.Assert.assertFalse(RecorderTimeMath.sameForegroundApp(true, true, "app.a", "app.a", true, false))
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
