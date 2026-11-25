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

    @Test fun importRejectsInvalidNormalizedValuesButPreservesRawEvidence() {
        fun changed(name: String, value: String): List<String?> = sample().toMutableList().apply {
            this[HistoryBackup.sampleColumns.indexOfFirst { it.name == name }] = value
        }
        for ((name, value) in listOf("battery_percent" to "999", "screen_on" to "2",
            "elapsed_realtime_ms" to "-1", "boot_count" to "2147483648",
            "battery_status" to "UNKNOWN_CUSTOM", "plug_type" to "OTHER_0",
            "usage_access" to "-1", "battery_present" to "3")) {
            val bytes = ByteArrayOutputStream().also {
                HistoryBackup.write(it, HistoryBackup.Counts(1,0), sequenceOf(changed(name,value)), emptySequence())
            }.toByteArray()
            var imported = false
            assertTrue(name, runCatching {
                HistoryBackup.read(ByteArrayInputStream(bytes), {}, { imported = true }, {})
            }.isFailure)
            assertFalse(name, imported)
        }
        for ((name,value) in listOf("battery_current_ua" to "-2147483648", "battery_voltage_mv" to "3",
            "charge_counter_uah" to "-100", "temperature_c" to "-4.5", "plug_type" to "OTHER_16")) {
            HistoryBackup.validateImportedRow(HistoryBackup.sampleColumns, changed(name,value))
        }
    }

    @Test fun rejectsCombinedOversizeImportRow() {
        val row = sample().toMutableList().apply {
            this[HistoryBackup.sampleColumns.indexOfFirst { it.name == "technology" }] = "a".repeat(600_000)
            this[HistoryBackup.sampleColumns.indexOfFirst { it.name == "power_supply_json" }] = "b".repeat(600_000)
        }
        val bytes = ByteArrayOutputStream().also {
            HistoryBackup.write(it, HistoryBackup.Counts(1,0), sequenceOf(row), emptySequence())
        }.toByteArray()
        assertTrue(runCatching { HistoryBackup.read(ByteArrayInputStream(bytes), {}, {}, {}) }.isFailure)
    }

    @Test fun stagingChecksSpaceBeforeWritingEachChunk() {
        val data = ByteArray(150_000) { 17 }
        val output = ByteArrayOutputStream()
        var calls = 0
        assertTrue(runCatching {
            HistoryBackup.stage(ByteArrayInputStream(data),output) { written,next ->
                assertEquals(output.size().toLong(),written)
                assertTrue(next > 0)
                require(++calls < 2) { "space exhausted" }
            }
        }.isFailure)
        assertEquals(65_536,output.size())
        val complete = ByteArrayOutputStream()
        HistoryBackup.stage(ByteArrayInputStream(data),complete)
        assertArrayEquals(data,complete.toByteArray())
    }

    @Test fun restoreIdsPrecedeLocalRowsAndOverlapIsRejected() {
        assertEquals(1L, HistoryBackup.firstImportedId(null, 2))
        assertEquals(-1L, HistoryBackup.firstImportedId(1, 2))
        assertEquals(-4L, HistoryBackup.firstImportedId(-1, 3))
        HistoryBackup.requireEarlier(100, 101)
        assertTrue(runCatching { HistoryBackup.requireEarlier(100,100) }.isFailure)
    }
}
