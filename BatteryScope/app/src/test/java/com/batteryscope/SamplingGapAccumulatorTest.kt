package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class SamplingGapAccumulatorTest {
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
