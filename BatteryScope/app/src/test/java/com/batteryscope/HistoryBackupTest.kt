package com.batteryscope

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class HistoryBackupTest {
    private fun sample(): List<String?> = HistoryBackup.sampleColumns.map {
        when (it.name) {
            "timestamp_ms" -> "123"
            "elapsed_realtime_ms" -> "100"
            "battery_status" -> "DISCHARGING"
            "plug_type" -> "NONE"
            "battery_current_ua" -> "-123456"
            "temperature_c" -> "31.25"
            "screen_on", "usage_access" -> "0"
            "power_supply_json" -> "{\"raw\":\"value\"}"
            else -> null
        }
    }
    private val event = listOf("124", "TEST", "Unicode Ω\n=raw")
    private fun encoded(): ByteArray = ByteArrayOutputStream().also {
        HistoryBackup.write(it, HistoryBackup.Counts(1,1), sequenceOf(sample()), sequenceOf(event))
    }.toByteArray()

    @Test fun roundTripPreservesNullsNumbersAndRawText() {
        val samples = mutableListOf<List<String?>>()
        val events = mutableListOf<List<String?>>()
        val result = HistoryBackup.read(ByteArrayInputStream(encoded()), {}, { samples.add(it) }, { events.add(it) })
        assertEquals(HistoryBackup.Counts(1,1), result)
        assertEquals(listOf(sample()), samples)
        assertEquals(listOf(event), events)
    }

    @Test fun checksumTruncationAndTrailingDataAreRejected() {
        val valid = encoded()
        val corrupt = valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        for (bad in listOf(corrupt, valid.copyOf(valid.size - 1), valid + byteArrayOf(0))) {
            assertTrue(runCatching { HistoryBackup.read(ByteArrayInputStream(bad), {}, {}, {}) }.isFailure)
        }
    }

    @Test fun writerRejectsCountsAndMalformedNumbers() {
        assertTrue(runCatching {
            HistoryBackup.write(ByteArrayOutputStream(), HistoryBackup.Counts(2,0), sequenceOf(sample()), emptySequence())
        }.isFailure)
        val invalid = sample().toMutableList().apply { this[0] = "not-a-timestamp" }
        assertTrue(runCatching {
            HistoryBackup.write(ByteArrayOutputStream(), HistoryBackup.Counts(1,0), sequenceOf(invalid), emptySequence())
        }.isFailure)
    }

    @Test fun restoreIdsPrecedeLocalRowsAndOverlapIsRejected() {
        assertEquals(1L, HistoryBackup.firstImportedId(null, 2))
        assertEquals(-1L, HistoryBackup.firstImportedId(1, 2))
        assertEquals(-4L, HistoryBackup.firstImportedId(-1, 3))
        HistoryBackup.requireEarlier(100, 101)
        assertTrue(runCatching { HistoryBackup.requireEarlier(100,100) }.isFailure)
    }
}
