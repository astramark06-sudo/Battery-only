package com.batteryscope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshGateTest {
    @Test fun forcedRequestsDuringReadAreCoalescedAndBypassCooldown() {
        val gate = RefreshGate(300_000L)
        assertTrue(gate.request(0L, false))
        assertFalse(gate.request(10L, true))
        assertFalse(gate.request(20L, true))
        assertTrue(gate.finish())
        assertTrue(gate.request(30L, true))
        assertFalse(gate.finish())
        assertFalse(gate.request(300_029L, false))
        assertTrue(gate.request(300_030L, false))
        assertFalse(gate.finish())
    }

    @Test fun periodicRequestsNeverQueueExtraWork() {
        val gate = RefreshGate(300_000L)
        assertTrue(gate.request(0L, false))
        assertFalse(gate.request(400_000L, false))
        assertFalse(gate.finish())
        assertTrue(gate.request(400_001L, false))
        assertFalse(gate.finish())
    }

    @Test fun capacityRefreshCoalescesAndCanRunImmediatelyAfterCompletion() {
        val gate = RefreshGate(0L)
        assertTrue(gate.request(100L, true))
        assertFalse(gate.request(101L, true))
        assertTrue(gate.finish())
        assertTrue(gate.request(102L, true))
        assertFalse(gate.finish())
        assertTrue(gate.request(102L, false))
        assertFalse(gate.finish())
    }
}
