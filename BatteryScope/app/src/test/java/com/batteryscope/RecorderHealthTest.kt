package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class RecorderHealthTest {
    @Test fun cadenceCompensatesForProcessingWithoutCatchUpBursts() {
        assertEquals(30_000L, RecorderCadence.nextDelayMs(100,100,30_000))
        assertEquals(25_000L, RecorderCadence.nextDelayMs(100,5_100,30_000))
        assertEquals(30_000L, RecorderCadence.nextDelayMs(100,30_100,30_000))
        assertEquals(25_000L, RecorderCadence.nextDelayMs(100,35_100,30_000))
        assertEquals(5_000L, RecorderCadence.nextDelayMs(100,85_100,30_000))
    }
    @Test fun fixedDeadlineDoesNotReanchorToLateCallbacksAndCountsSkippedSlots() {
        assertEquals(RecorderCadence.Slot(31_000L,0L), RecorderCadence.nextSlot(1000L,5000L,30_000L))
        assertEquals(RecorderCadence.Slot(61_000L,0L), RecorderCadence.nextSlot(31_000L,45_000L,30_000L))
        assertEquals(RecorderCadence.Slot(121_000L,3L), RecorderCadence.nextSlot(1000L,96_000L,30_000L))
        assertEquals(RecorderCadence.Slot(61_000L,1L), RecorderCadence.nextSlot(1000L,31_000L,30_000L))
        val health = RecorderHealth(0L).skipped(3L).skipped(2L)
        assertEquals(5L,health.skippedDeadlines)
        assertTrue(health.report(0L).contains("skipped sampling deadlines this service session 5"))
        assertEquals(Long.MAX_VALUE,health.skipped(Long.MAX_VALUE).skippedDeadlines)
    }

    @Test fun startupAndStaleAreNotReportedAsRecording() {
        val starting = RecorderHealth(100)
        assertEquals("WAITING_FOR_FIRST_SAMPLE", starting.state(100))
        assertEquals("WAITING_FOR_FIRST_SAMPLE", starting.state(90_100))
        assertEquals("STALE", starting.state(90_101))
        assertEquals("STALE", starting.state(99))
    }
    @Test fun failureDoesNotAdvanceLastSuccessfulCommitAndRecoveryClearsError() {
        val first = RecorderHealth(100).completed(100, 200, null)
        assertEquals("RECENT_SAMPLE_SAVED", first.state(201))
        val failed = first.completed(30_100, 30_300, "disk full")
        assertEquals(200L, failed.lastSuccessElapsedMs!!)
        assertEquals(200L, failed.lastDurationMs!!)
        assertEquals("DEGRADED", failed.state(30_301))
        assertTrue(failed.report(30_301).contains("disk full"))
        assertEquals("STALE", failed.state(120_301))
        val recovered = failed.completed(60_100, 60_200, null)
        assertEquals(60_200L, recovered.lastSuccessElapsedMs!!)
        assertNull(recovered.error)
        assertEquals("RECENT_SAMPLE_SAVED", recovered.state(60_201))
        assertFalse(recovered.report(60_201).contains("disk full"))
    }
}
