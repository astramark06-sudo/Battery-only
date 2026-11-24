package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class LegacyHistoryCsvTest {
    private fun sample(): List<String> {
        val values = MutableList(RecorderCsv.sampleFields.size) { "" }
        for ((key, value) in mapOf("battery_status" to "DISCHARGING", "plug_type" to "NONE",
            "screen_on" to "0", "usage_access" to "0", "battery_current_ua" to "-123456",
            "power_supply_json" to "{\"raw\":\"a,b\"}")) {
            values[RecorderCsv.sampleFields.indexOf(key)] = value
        }
        return RecorderCsv.sampleRow("100","20",values)
    }

    @Test fun parsesOriginalExportWithNullNumericAndQuotedMetadata() {
        val csv = RecorderCsv.encodeRow(RecorderCsv.header) + RecorderCsv.encodeRow(sample()) +
            RecorderCsv.encodeRow(RecorderCsv.eventRow("101","TEST","comma, and \"quote\""))
        val samples = mutableListOf<List<String?>>()
        val events = mutableListOf<List<String?>>()
        val counts = LegacyHistoryCsv.scan(csv.reader(), { samples.add(it) }, { events.add(it) })
        assertEquals(HistoryBackup.Counts(1,1), counts)
        fun value(name: String) = samples.single()[HistoryBackup.sampleColumns.indexOfFirst { it.name == name }]
        assertEquals("-123456", value("battery_current_ua"))
        assertNull(value("boot_count"))
        assertEquals("{\"raw\":\"a,b\"}", value("power_supply_json"))
        assertEquals("comma, and \"quote\"", events.single().last())
    }

    @Test fun rejectsWrongHeaderTruncationAndInvalidNumber() {
        val header = RecorderCsv.encodeRow(RecorderCsv.header)
        val invalid = sample().toMutableList().apply { this[1] = "not a timestamp" }
        for (bad in listOf("\"wrong\"\n",header + "\"SAMPLE\",\"123",header + RecorderCsv.encodeRow(invalid))) {
            assertTrue(runCatching { LegacyHistoryCsv.scan(bad.reader(), {}, {}) }.isFailure)
        }
    }

    @Test fun rejectsSemanticallyInvalidCsvBeforeConsumer() {
        val header = RecorderCsv.encodeRow(RecorderCsv.header)
        for ((name,value) in listOf("battery_percent" to "101", "screen_on" to "2",
            "boot_count" to "2147483648", "plug_type" to "OTHER_0")) {
            val row = sample().toMutableList().apply { this[RecorderCsv.header.indexOf(name)] = value }
            var imported = false
            assertTrue(name, runCatching {
                LegacyHistoryCsv.scan((header + RecorderCsv.encodeRow(row)).reader(), { imported = true }, {})
            }.isFailure)
            assertFalse(imported)
        }
    }

    @Test fun acceptsBomAndCrLf() {
        val text = "\uFEFF" + (RecorderCsv.encodeRow(RecorderCsv.header) + RecorderCsv.encodeRow(sample())).replace("\n","\r\n")
        assertEquals(HistoryBackup.Counts(1,0),LegacyHistoryCsv.scan(text.reader(),{},{}))
    }
}
