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
