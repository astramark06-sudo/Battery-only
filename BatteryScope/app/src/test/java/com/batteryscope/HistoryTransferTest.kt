package com.batteryscope

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class HistoryTransferTest {
    @Test fun copyAndReadbackRequireIdenticalBytes() {
        val bytes = ByteArray(200_003) { (it % 251).toByte() }
        val destination = ByteArrayOutputStream()
        val expected = HistoryTransfer.fingerprint(ByteArrayInputStream(bytes), destination)
        assertEquals(bytes.size.toLong(), expected.bytes)
        assertArrayEquals(bytes, destination.toByteArray())
        HistoryTransfer.requireMatch(expected, HistoryTransfer.fingerprint(ByteArrayInputStream(destination.toByteArray())))
        assertTrue(runCatching { HistoryTransfer.requireMatch(expected, HistoryTransfer.fingerprint(ByteArrayInputStream(bytes.copyOf(10)))) }.isFailure)
        bytes[1] = 99
        assertTrue(runCatching { HistoryTransfer.requireMatch(expected, HistoryTransfer.fingerprint(ByteArrayInputStream(bytes))) }.isFailure)
    }

    @Test fun knownDigestAndZeroProgress() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HistoryTransfer.fingerprint("abc".byteInputStream()).sha256)
        val broken = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertTrue(runCatching { HistoryTransfer.fingerprint(broken) }.isFailure)
    }

    @Test fun onlyCompletedPrivateNamesAccepted() {
        assertTrue(HistoryTransfer.isReadyName("BatteryScope_Backup_20261125_123456_123.bsh"))
        assertTrue(HistoryTransfer.isReadyName("BatteryScope_History_123_abcd.csv"))
        for (bad in listOf("../BatteryScope_History_123.csv","BatteryScope_Backup_123.csv","BatteryScope_History_123.csv.part","BatteryScope_History_123.bsh","history.db","BatteryScope_History_123.csv/")) {
            assertFalse(bad,HistoryTransfer.isReadyName(bad))
        }
    }
}
