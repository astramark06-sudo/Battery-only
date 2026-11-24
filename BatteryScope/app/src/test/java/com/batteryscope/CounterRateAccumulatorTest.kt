package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class CounterRateAccumulatorTest {
    @Test fun missingIntervalsDoNotDiluteRate() {
        val a = CounterRateAccumulator()
        a.add(15_000L, 30_000L)
        a.add(null, 60_000L)
        assertEquals(30_000L, a.observedMs)
        assertEquals(1800.0, a.rateMa()!!, 0.001)
    }
    @Test fun zeroIsValidAndMissingIsNot() {
        val a = CounterRateAccumulator()
        a.add(null, 30_000L)
        assertNull(a.rateMa())
        a.add(0L, 30_000L)
        assertEquals(0.0, a.rateMa()!!, 0.001)
    }
    @Test fun rejectsImplausibleCounterEndpointsAndIntervalJumps() {
        assertNull(CounterDelta.between(12_000_001L, 12_000_000L))
        assertNull(CounterDelta.between(0L, Long.MAX_VALUE))
        assertNull(CounterDelta.forInterval(1_000_000L, 2_000_000L, 30_000L))
        assertEquals(45_000L, CounterDelta.forInterval(1_000_000L, 1_045_000L, 30_000L)!!)
        assertEquals(-45_000L, CounterDelta.forInterval(1_045_000L, 1_000_000L, 30_000L)!!)
        assertNull(CounterDelta.forInterval(1_000_000L, 1_000_000L, 999L))
        assertNull(CounterDelta.forInterval(1_000_000L, 1_000_000L, 90_001L))
        assertNull(CounterDelta.loss(Long.MIN_VALUE))
    }
    @Test fun rejectedIntervalsDoNotContributeToTotalsOrDenominator() {
        val a = CounterRateAccumulator()
        a.add(45_000L, 30_000L)
        a.add(Long.MAX_VALUE, 30_000L)
        a.add(Long.MIN_VALUE, 30_000L)
        a.add(1_000_000L, 30_000L)
        a.add(0L, 90_001L)
        assertEquals(45_000L, a.deltaUah)
        assertEquals(30_000L, a.observedMs)
        assertEquals(5400.0, a.rateMa()!!, 0.001)
    }
    @Test fun signedDeltaAndDirectionChecks() {
        assertNull(CounterDelta.between(-1L, 100L))
        assertNull(CounterDelta.between(null, 100L))
        assertEquals(-50L, CounterDelta.between(100L, 50L)!!)
        assertEquals(50L, CounterDelta.loss(-50L)!!)
        assertEquals(0L, CounterDelta.gain(0L)!!)
        assertNull(CounterDelta.gain(-1L))
        assertNull(CounterDelta.loss(1L))
    }
}
