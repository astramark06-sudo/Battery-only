package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class ResourceHistoryTest {
    private fun row(t: Long, boot: Int?, rx: Long?, ram: Long = 100L, wall: Long = t) =
        ResourceObservation(wall,t,boot,200,ram,900L,2,listOf(rx,null,null,null,null,null))

    @Test fun excludesDuplicatesResetsRebootsAndGaps() {
        val result = ResourceHistory()
        result.accept(row(1_000,1,100))
        result.accept(row(1_000,1,100))
        result.accept(row(121_000,1,150,80))
        result.accept(row(241_000,1,10)) // reset
        result.accept(row(361_000,1,30))
        result.accept(row(481_000,2,100)) // reboot
        result.accept(row(901_000,2,200)) // gap
        result.accept(row(1_021_000,2,250))
        assertEquals(7,result.snapshots)
        assertEquals(120L,result.counters[0].bytes)
        assertEquals(360_000L,result.counters[0].coveredMs)
        assertEquals(3,result.counters[0].intervals)
        assertEquals(0,result.counters[1].intervals)
        assertEquals(80L,result.lowestAvailableRam!!)
    }

    @Test fun rejectsUnknownBootAndClockDiscontinuity() {
        val result = ResourceHistory()
        result.accept(row(1000,null,10))
        result.accept(row(2000,null,20))
        result.accept(row(3000,1,30))
        result.accept(row(4000,1,40,wall=100_000))
        assertEquals(0,result.counters[0].intervals)
    }
}
