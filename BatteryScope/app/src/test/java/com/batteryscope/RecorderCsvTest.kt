package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecorderCsvTest {
    @Test
    fun stableSchemaAlignsSampleAndEventRows() {
        val values = List(RecorderCsv.sampleFields.size) { "" }.toMutableList()
        values[RecorderCsv.sampleFields.indexOf("battery_current_ua")] = "-123"
        values[RecorderCsv.sampleFields.indexOf("technology")] = "=1+1"
        val sample = RecorderCsv.sampleRow("1", "2", values)
        val event = RecorderCsv.eventRow("3", "TEST", " +1+1")
        assertEquals(28, RecorderCsv.header.size)
        assertEquals(28, sample.size)
        assertEquals(28, event.size)
        assertEquals("-123", sample[RecorderCsv.header.indexOf("battery_current_ua")])
        assertEquals("'=1+1", sample[RecorderCsv.header.indexOf("technology")])
        assertEquals("' +1+1", event.last())
        assertEquals("TEST", event[26])
    }

    @Test
    fun protectsFormulaLikeTextWithoutChangingOrdinaryText() {
        assertEquals("'@SUM(A1)", RecorderCsv.spreadsheetSafeText("@SUM(A1)"))
        assertEquals("'\tordinary", RecorderCsv.spreadsheetSafeText("\tordinary"))
        assertEquals("Li-ion", RecorderCsv.spreadsheetSafeText("Li-ion"))
        assertEquals("", RecorderCsv.spreadsheetSafeText(""))
    }

    @Test
    fun emitsRealRecordTerminatorAndQuotesFields() {
        val encoded = RecorderCsv.encodeRow(listOf("SAMPLE", "a,b", "quote\"value", "line\nbreak"))
        assertEquals("\"SAMPLE\",\"a,b\",\"quote\"\"value\",\"line break\"\n", encoded)
        assertEquals(1, encoded.count { it == '\n' })
        assertFalse(encoded.contains("\\n"))
    }

    @Test
    fun emptyFieldsRemainExplicitCsvColumns() {
        assertEquals("\"EVENT\",\"123\",\"\",\"\",\"details\"\n",
            RecorderCsv.encodeRow(listOf("EVENT", "123", "", "", "details")))
    }
}
