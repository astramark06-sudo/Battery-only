package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCapacityEstimatorTest {
    @Test
    fun streamingEstimatorMatchesListWithoutReiteratingInput() {
        val samples = listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "USB", 1_600_000L),
            CapacitySample(31000L, 1, 50, "CHARGING", "USB", 2_000_000L),
            CapacitySample(61000L, 1, 60, "CHARGING", "USB", 2_400_000L)
        )
        assertEquals(BatteryCapacityEstimator.estimate(samples),
            BatteryCapacityEstimator.estimateOrdered(samples.asSequence().constrainOnce()))
    }

    @Test
    fun detectsNominalCounterPatternAndKeepsConfidenceLow() {
        val samples = listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "USB", 1_800_000L),
            CapacitySample(31000L, 1, 50, "CHARGING", "USB", 2_250_000L),
            CapacitySample(61000L, 1, 60, "CHARGING", "USB", 2_700_000L)
        )
        val estimate = requireNotNull(BatteryCapacityEstimator.estimate(samples))
        org.junit.Assert.assertTrue(estimate.nominalCounterMatch)
        assertEquals("LOW", estimate.confidence)
    }
    @Test
    fun usesMonotonicTimeWhenWallClockMovesBackward() {
        val samples = listOf(
            CapacitySample(100000L, 1, 40, "CHARGING", "USB", 1_600_000L, 1000L),
            CapacitySample(50000L, 1, 60, "CHARGING", "USB", 2_400_000L, 31000L)
        )
        assertEquals(4000.0, requireNotNull(BatteryCapacityEstimator.estimate(samples)).capacityMah, 0.001)
    }
    @Test
    fun rejectsCapacityForOppositeSessionDirection() {
        val samples = listOf(
            CapacitySample(1000L, 1, 60, "CHARGING", "USB", 2_400_000L),
            CapacitySample(31000L, 1, 40, "CHARGING", "USB", 1_600_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }
    @Test
    fun estimatesCapacityFromChargeAndDischargeSessions() {
        val samples = listOf(
            CapacitySample(1_000L, 1, 40, "CHARGING", "USB", 1_800_000L),
            CapacitySample(2_000L, 1, 60, "CHARGING", "USB", 2_700_000L),
            CapacitySample(200_000L, 1, 80, "DISCHARGING", "NONE", 3_600_000L),
            CapacitySample(201_000L, 1, 60, "DISCHARGING", "NONE", 2_700_000L)
        )
        val result = BatteryCapacityEstimator.estimate(samples)
        requireNotNull(result)
        assertEquals(4500.0, result.capacityMah, 0.1)
        assertEquals(100.0, result.healthPercent, 0.1)
        assertEquals(2, result.validSessions)
    }

    @Test
    fun rejectsSmallStateOfChargeChanges() {
        val samples = listOf(
            CapacitySample(1_000L, 1, 50, "CHARGING", "USB", 2_250_000L),
            CapacitySample(2_000L, 1, 55, "CHARGING", "USB", 2_475_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }

    @Test
    fun rejectsCounterMovementThatDisagreesWithPercentageMovement() {
        val samples = listOf(
            CapacitySample(1_000L, 1, 40, "CHARGING", "USB", 2_400_000L),
            CapacitySample(2_000L, 1, 60, "CHARGING", "USB", 1_800_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }
    @Test
    fun unrelatedCounterReadingCannotMaskNominalSessions() {
        val samples = mutableListOf<CapacitySample>()
        for (i in 0..4) {
            val start = i * 300_000L + 1000L
            samples += CapacitySample(start, 1, 40, "CHARGING", "AC", 1_800_000L)
            samples += CapacitySample(start + 30_000L, 1, 50, "CHARGING", "AC", 2_250_000L)
            samples += CapacitySample(start + 60_000L, 1, 60, "CHARGING", "AC", 2_700_000L)
            samples += CapacitySample(start + 90_000L, 1, 60, "FULL", "AC", 2_700_001L)
        }
        val result = requireNotNull(BatteryCapacityEstimator.estimate(samples))
        assertEquals(5, result.validSessions)
        org.junit.Assert.assertTrue(result.nominalCounterMatch)
        assertEquals("LOW", result.confidence)
    }

    @Test
    fun reversalDoesNotBridgeTwoInsufficientSegments() {
        val samples = listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "AC", 1_600_000L),
            CapacitySample(31000L, 1, 49, "CHARGING", "AC", 1_960_000L),
            CapacitySample(61000L, 1, 48, "CHARGING", "AC", 1_920_000L),
            CapacitySample(91000L, 1, 58, "CHARGING", "AC", 2_320_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }

    @Test
    fun counterOnlyReversalSplitsSessionAndFullIdleDoesNotExtendIt() {
        val reset = listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "AC", 1_600_000L),
            CapacitySample(31000L, 1, 49, "CHARGING", "AC", 1_960_000L),
            CapacitySample(61000L, 1, 50, "CHARGING", "AC", 1_900_000L),
            CapacitySample(91000L, 1, 59, "CHARGING", "AC", 2_360_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(reset))
        assertNull(BatteryCapacityEstimator.estimate(listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "AC", 1_600_000L),
            CapacitySample(31000L, 1, 60, "FULL", "AC", 2_400_000L)
        )))
    }

    @Test
    fun rejectsSubSecondContinuityAndFlagsNominalSlopeWithOffset() {
        assertNull(BatteryCapacityEstimator.estimate(listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "AC", 1_600_000L),
            CapacitySample(1999L, 1, 60, "CHARGING", "AC", 2_400_000L)
        )))
        val estimate = requireNotNull(BatteryCapacityEstimator.estimate(listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "AC", 1_810_000L),
            CapacitySample(31000L, 1, 60, "CHARGING", "AC", 2_710_000L)
        )))
        org.junit.Assert.assertTrue(estimate.nominalCounterMatch)
        org.junit.Assert.assertTrue(BatteryCapacityEstimator.report(listOf(
            CapacitySample(1000L, 1, 40, "CHARGING", "AC", 1_600_000L),
            CapacitySample(31000L, 1, 60, "CHARGING", "AC", 2_400_000L)
        )).contains("not verified health"))
    }

    @Test
    fun rejectsSessionsWithoutBootCountEvidence() {
        val samples = listOf(
            CapacitySample(1_000L, null, 40, "CHARGING", "USB", 1_800_000L),
            CapacitySample(2_000L, null, 60, "CHARGING", "USB", 2_700_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }
}
