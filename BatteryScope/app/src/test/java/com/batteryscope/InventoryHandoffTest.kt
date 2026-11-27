package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class InventoryHandoffTest {
    @Test fun requestIsBoundedAndOnlyCompletedPastSnapshotIsEligible() {
        val box = InventoryHandoff<String>(120_000L)
        assertTrue(box.request(100L))
        assertFalse(box.request(240_100L))
        assertNull(box.readyFor(240_100L))
        box.complete("inventory", 110L, 250_100L)
        assertNull(box.readyFor(250_099L))
        val ready = requireNotNull(box.readyFor(250_100L))
        assertEquals("inventory", ready.value)
        assertEquals(110L, ready.startedElapsedMs)
        assertFalse(box.request(370_100L))
        box.committed(ready)
        assertNull(box.readyFor(370_100L))
        assertTrue(box.request(370_100L))
    }

    @Test fun failedInsertRetainsExactSnapshotAndStaleAcknowledgementCannotClearNewOne() {
        val box = InventoryHandoff<String>(120L)
        assertTrue(box.request(0L))
        box.complete("first", 1L, 2L)
        val first = requireNotNull(box.readyFor(2L))
        assertSame(first, box.readyFor(1000L))
        assertFalse(box.request(1000L))
        box.committed(first)
        assertTrue(box.request(1000L))
        box.complete("second", 1001L, 1002L)
        val second = requireNotNull(box.readyFor(1002L))
        box.committed(first)
        assertSame(second, box.readyFor(1002L))
        box.committed(second)
        assertNull(box.readyFor(1002L))
    }

    @Test fun failedScanCanRetryAfterIntervalButCannotQueueBurst() {
        val box = InventoryHandoff<String>(120L)
        assertTrue(box.request(100L))
        box.failed()
        assertFalse(box.request(219L))
        assertTrue(box.request(220L))
        assertFalse(box.request(221L))
        box.failed()
        assertNull(box.readyFor(1000L))
    }
}
