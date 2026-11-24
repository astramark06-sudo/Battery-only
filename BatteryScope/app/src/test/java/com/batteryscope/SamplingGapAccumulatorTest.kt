package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class SamplingGapAccumulatorTest {
    @Test fun reportsInvalidIntervalsRebootsAndDelayedCadenceSeparately() {
        val gap = SamplingGapAccumulator()
        gap.accept(-100L, false)
        gap.accept(0L, false)
        gap.accept(30_000L, true)
        gap.accept(60_000L, true)
        gap.accept(90_000L, true)
        gap.accept(91_000L, false)
        gap.accept(30_000L, false, true)
        assertEquals(4L, gap.invalidRateIntervals)
        assertEquals(2L, gap.nonPositiveIntervals)
        assertEquals(1L, gap.knownReboots)
        assertEquals(3L, gap.intervalsAtLeastDoubleTarget)
        assertEquals(180_000L, gap.validIntervalMs)
        assertEquals(1L, gap.gaps)
    }
    @Test fun ignoresNonpositiveIntervalsAndIncludesThresholdBoundary() {
        val gap = SamplingGapAccumulator()
        assertNull(gap.averageMs)
        for (value in listOf(-100L,0L,30_000L,90_000L,91_000L,120_000L)) gap.accept(value)
        assertEquals(2L,gap.gaps)
        assertEquals(151_000L,gap.missingMs)
        assertEquals(120_000L,gap.longestMs)
        assertEquals(82_750L,gap.averageMs)
    }
}
