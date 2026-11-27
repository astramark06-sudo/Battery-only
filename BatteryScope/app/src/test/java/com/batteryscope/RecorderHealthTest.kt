package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class RecorderHealthTest {
    @Test fun overdueChecksNeverSampleEarlyAndKeepElapsedDeadline() {
        assertFalse(RecorderClock.isDue(29_999L,30_000L))
        assertTrue(RecorderClock.isDue(30_000L,30_000L))
        assertTrue(RecorderClock.isDue(300_000L,30_000L))
        assertFalse(RecorderClock.isDue(0L,-1L))
        val next = RecorderCadence.nextSlot(30_000L,300_000L,30_000L)
        assertEquals(330_000L,next.elapsedMs)
        assertFalse(RecorderClock.isDue(300_001L,next.elapsedMs))
    }

    @Test fun clockEvidenceSeparatesSuspendFromAwakeTimeAndRejectsReset() {
        val before = RecorderClock(100_000L,70_000L)
        assertEquals(0L,RecorderClock(130_000L,100_000L).suspendSince(before)!!)
        assertEquals(90_000L,RecorderClock(220_000L,100_000L).suspendSince(before)!!)
        assertEquals(0L,RecorderClock(130_000L,100_001L).suspendSince(before)!!)
        assertNull(RecorderClock(99_999L,100_000L).suspendSince(before))
        assertNull(RecorderClock(130_000L,69_999L).suspendSince(before))
        assertNull(RecorderClock(-1L,0L).suspendSince(before))
    }

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

    @Test fun sampleWorkMetricsDistinguishAttemptsCpuAndSchedulingFailures() {
        val first = RecorderHealth(100).completed(100,200,null,7)
        val failed = first.completed(30_100,30_300,"disk error",10)
        val unscheduled = failed.schedulingFailed("queue rejected")
        assertEquals(2L,unscheduled.sampleAttempts)
        assertEquals(1L,unscheduled.successfulSamples)
        assertEquals(300L,unscheduled.totalSampleWallMs)
        assertEquals(200L,unscheduled.maxSampleWallMs)
        assertEquals(17L,unscheduled.totalSamplerCpuMs)
        assertEquals(2L,unscheduled.measuredCpuAttempts)
        assertEquals(200L,unscheduled.lastSuccessElapsedMs)
        assertEquals(30_300L,unscheduled.lastFinishedElapsedMs)
        assertEquals("DEGRADED",unscheduled.state(30_301))
        assertTrue(unscheduled.report(30_301).contains("mean wall duration 150.0 ms"))
        assertTrue(unscheduled.report(30_301).contains("17 ms across 2 attempts"))
        assertTrue(unscheduled.report(30_301).contains("not battery drain"))
        val next = unscheduled.completed(60_100,60_200,null,-1)
        assertEquals(3L,next.sampleAttempts)
        assertEquals(2L,next.successfulSamples)
        assertEquals(2L,next.measuredCpuAttempts)
        assertEquals(17L,next.totalSamplerCpuMs)
        assertNull(next.error)
        assertTrue(RecorderHealth(0).report(0).contains("Measured sampler-thread CPU time: unavailable"))
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
