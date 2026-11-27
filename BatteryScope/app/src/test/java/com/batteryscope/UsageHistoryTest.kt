package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class UsageHistoryTest {
    @Test fun plansBoundedReplayAndLateEventOverlap() {
        val now = 2_000_000_000_000L
        val first = UsageHistory.next(null,now)!!
        assertEquals(now-UsageHistory.SETTLE_MS-UsageHistory.MAX_LOOKBACK_MS,first.from)
        assertEquals(UsageHistory.CHUNK_MS,first.end-first.from)
        val next = UsageHistory.next(first.end,now)!!
        assertEquals(first.end-UsageHistory.OVERLAP_MS,next.from)
        assertEquals(first.end+UsageHistory.CHUNK_MS,next.end)
        assertNull(UsageHistory.next(now-UsageHistory.SETTLE_MS,now))
        assertEquals("CLOCK_ROLLBACK_BOUNDED_LOOKBACK",UsageHistory.next(now+1,now)!!.reason)
        assertEquals("RETENTION_GAP_BOUNDED_LOOKBACK",UsageHistory.next(1L,now)!!.reason)
        val small = UsageHistory.shrink(next,first.end)!!
        assertTrue(small.end > first.end && small.end < next.end)
    }
    @Test fun onlyPackageTransitionsInsideHalfOpenWindow() {
        val window = UsageHistory.Window(1000,2000,"INITIAL_BOUNDED_LOOKBACK")
        assertTrue(UsageHistory.Transition(1000,1,"com.example").valid(window))
        assertFalse(UsageHistory.Transition(2000,2,"com.example").valid(window))
        assertFalse(UsageHistory.Transition(1500,9,"com.example").valid(window))
        assertFalse(UsageHistory.Transition(1500,1,"").valid(window))
        assertFalse(UsageHistory.Transition(1500,1,"a/b").valid(window))
    }
}
