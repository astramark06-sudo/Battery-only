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

    @Test fun directoryScanSeparatesFilesWithoutDeletingAnything() {
        val dir = java.nio.file.Files.createTempDirectory("batteryscope-stage-test").toFile()
        try {
            val ready = "BatteryScope_History_123_" + "a".repeat(32) + ".csv"
            val legacy = "BatteryScope_Backup_20261125_123456_123.bsh"
            val partial = "BatteryScope_Backup_123_" + "b".repeat(32) + ".bsh.part"
            listOf(ready, legacy, partial, "history.db", "unrelated.part").forEach {
                java.io.File(dir,it).writeText("preserve")
            }
            val stages = HistoryTransfer.scan(dir).associate { it.name to it.stage }
            assertEquals(mapOf(ready to HistoryTransfer.Stage.READY, legacy to HistoryTransfer.Stage.LEGACY_UNVERIFIED,
                partial to HistoryTransfer.Stage.INTERRUPTED), stages)
            assertEquals(5,dir.listFiles()!!.size)
            assertTrue(runCatching { HistoryTransfer.resolve(dir, "../history.db") }.isFailure)
            assertTrue(runCatching { HistoryTransfer.resolve(dir, "history.db") }.isFailure)
            val alias = java.io.File(dir,"BatteryScope_History_124_" + "c".repeat(32) + ".csv")
            java.nio.file.Files.createSymbolicLink(alias.toPath(),java.io.File(dir,"history.db").toPath())
            assertTrue(runCatching { HistoryTransfer.resolve(dir,alias.name) }.isFailure)
            assertFalse(HistoryTransfer.scan(dir).any { it.name == alias.name })
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    @Test fun onlyCompletedPrivateNamesAccepted() {
        val old = "BatteryScope_Backup_20261125_123456_123.bsh"
        val modern = "BatteryScope_History_123_" + "abcd".repeat(8) + ".csv"
        assertFalse(HistoryTransfer.isReadyName(old))
        assertTrue(HistoryTransfer.isSaveName(old))
        assertEquals(HistoryTransfer.Stage.LEGACY_UNVERIFIED, HistoryTransfer.classify(old))
        assertTrue(HistoryTransfer.isReadyName(modern))
        assertEquals(HistoryTransfer.Stage.INTERRUPTED, HistoryTransfer.classify(modern + ".part"))
        assertFalse(HistoryTransfer.isSaveName(modern + ".part"))
        for (bad in listOf("../BatteryScope_History_123.csv","BatteryScope_Backup_123.csv","BatteryScope_History_123.csv.part","BatteryScope_History_123.bsh","history.db","BatteryScope_History_123.csv/")) {
            assertFalse(bad,HistoryTransfer.isReadyName(bad))
        }
    }
}
