package com.batteryscope

import java.io.PushbackReader
import java.io.Reader

/** Import only the original, fully quoted 28-column BatteryScope export.
 * CSV is not lossless: empty optional numeric fields become null, empty text stays empty,
 * and any spreadsheet-safety prefix or flattened line break remains as exported.
 */
internal object LegacyHistoryCsv {
    private const val MAX_FIELD_CHARS = 1_048_576
    private const val MAX_ROWS = 10_000_000L

    fun scan(reader: Reader, sample: (List<String?>) -> Unit, event: (List<String?>) -> Unit): HistoryBackup.Counts {
        val input = PushbackReader(reader.buffered(), 1)
        val first = input.read()
        if (first != -1 && first != 0xFEFF) input.unread(first)
        require(readRow(input) == RecorderCsv.header) { "Expected original BatteryScope 28-column CSV header" }
        val indices = RecorderCsv.header.withIndex().associate { it.value to it.index }
        fun values(row: List<String>, columns: List<HistoryBackup.Column>): List<String?> =
            columns.map { column ->
                val raw = row[indices.getValue(column.name)]
                val value = if (raw.isEmpty() && column.type != HistoryBackup.Type.TEXT) null else raw
                if (column.required) require(!raw.isEmpty()) { "Required CSV field missing: " + column.name }
                HistoryBackup.validateValue(column, value)
                value
            }.also { HistoryBackup.validateImportedRow(columns, it) }
        var samples = 0L
        var events = 0L
        while (true) {
            val row = readRow(input) ?: break
            require(row.size == RecorderCsv.header.size) { "Incorrect CSV field count" }
            when (row.first()) {
                "SAMPLE" -> {
                    require(row[26].isEmpty() && row[27].isEmpty()) { "Unexpected event data in sample row" }
                    require(++samples <= MAX_ROWS) { "Too many samples" }
                    sample(values(row, HistoryBackup.sampleColumns))
                }
                "EVENT" -> {
                    require((2..25).all { row[it].isEmpty() }) { "Unexpected sample data in event row" }
                    require(++events <= MAX_ROWS) { "Too many events" }
                    event(values(row, HistoryBackup.eventColumns))
                }
                else -> error("Unrecognized CSV record type")
            }
        }
        return HistoryBackup.Counts(samples, events)
    }

    private fun readRow(input: PushbackReader): List<String>? {
        val first = input.read()
        if (first == -1) return null
        input.unread(first)
        val fields = mutableListOf<String>()
        var rowChars = 0L
        while (true) {
            require(fields.size < RecorderCsv.header.size) { "Too many CSV fields" }
            require(input.read() == '"'.code) { "Original BatteryScope CSV fields must be quoted" }
            val field = StringBuilder()
            var next: Int
            while (true) {
                val c = input.read()
                require(c != -1) { "Truncated quoted CSV field" }
                require(c != '\r'.code && c != '\n'.code) { "Unexpected multiline CSV field" }
                if (c == '"'.code) {
                    next = input.read()
                    if (next != '"'.code) break
                }
                require(field.length < MAX_FIELD_CHARS) { "CSV field exceeds size limit" }
                require(++rowChars <= HistoryBackup.MAX_IMPORT_ROW_BYTES) { "CSV row exceeds safe size limit" }
                field.append(c.toChar())
            }
            fields += field.toString()
            when (next) {
                ','.code -> continue
                '\n'.code, -1 -> return fields
                '\r'.code -> {
                    require(input.read() == '\n'.code) { "Invalid CSV record terminator" }
                    return fields
                }
                else -> error("Unexpected character after quoted CSV field")
            }
        }
    }
}
