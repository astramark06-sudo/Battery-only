package com.batteryscope

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Portable history only, not executable SQL, device identity, or app settings. */
internal object HistoryBackup {
    const val MAX_BYTES = 1_073_741_824L
    private const val MAX_ROWS = 10_000_000L
    private const val MAX_FIELD_BYTES = 1_048_576
    private const val MAGIC = "BatteryScopeHistory"
    private const val VERSION = 1

    enum class Type { INTEGER, REAL, TEXT }
    data class Column(val name: String, val type: Type, val required: Boolean = false)
    data class Counts(val samples: Long, val events: Long) {
        val total: Long get() = samples + events
    }

    private fun integer(name: String, required: Boolean = false) = Column(name, Type.INTEGER, required)
    private fun text(name: String, required: Boolean = false) = Column(name, Type.TEXT, required)
    val sampleColumns = listOf(
        integer("timestamp_ms", true), integer("elapsed_realtime_ms", true), integer("boot_count"),
        integer("battery_percent"), text("battery_status", true), text("plug_type", true),
        integer("battery_voltage_mv"), integer("battery_current_ua"), integer("battery_average_current_ua"),
        integer("max_charging_current_ua"), integer("max_charging_voltage_uv"), integer("battery_present"),
        integer("invalid_charger_state"), integer("battery_cycle_count"), integer("charge_counter_uah"),
        integer("energy_counter_nwh"), Column("temperature_c", Type.REAL), text("battery_health"), text("technology"),
        integer("screen_on", true), text("foreground_package"), integer("usage_access", true),
        text("power_supply_json"), integer("usb_voltage_candidate_uv"), integer("usb_current_candidate_ua")
    )
    val eventColumns = listOf(integer("timestamp_ms", true), text("event_type", true), text("details"))

    private fun validateCounts(c: Counts) {
        require(c.samples in 0..MAX_ROWS && c.events in 0..MAX_ROWS) { "Unsupported backup row count" }
    }
    private fun validateValue(column: Column, value: String?) {
        if (value == null) {
            require(!column.required) { "Required backup field missing: ${column.name}" }
            return
        }
        when (column.type) {
            Type.INTEGER -> require(value.toLongOrNull() != null) { "Invalid integer: ${column.name}" }
            Type.REAL -> require(value.toDoubleOrNull()?.isFinite() == true) { "Invalid real: ${column.name}" }
            Type.TEXT -> Unit
        }
    }

    private class Budget {
        // Includes field lengths; reserve space for the fixed header and digest.
        private var used = 128L
        fun field(size: Int) {
            require(size in -1..MAX_FIELD_BYTES) { "Backup field exceeds limit" }
            used += 4L + size.coerceAtLeast(0)
            require(used <= MAX_BYTES) { "Backup exceeds supported size" }
        }
    }

    fun write(output: OutputStream, counts: Counts, samples: Sequence<List<String?>>, events: Sequence<List<String?>>) {
        validateCounts(counts)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = DigestOutputStream(output.buffered(), md)
        val out = DataOutputStream(digest)
        val budget = Budget()
        out.writeUTF(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(counts.samples)
        out.writeLong(counts.events)
        fun rows(expected: Long, columns: List<Column>, source: Sequence<List<String?>>) {
            var written = 0L
            for (row in source) {
                require(written < expected && row.size == columns.size) { "Backup snapshot row mismatch" }
                row.forEachIndexed { index, value ->
                    validateValue(columns[index], value)
                    val bytes = value?.toByteArray(Charsets.UTF_8)
                    val size = bytes?.size ?: -1
                    budget.field(size)
                    out.writeInt(size)
                    if (bytes != null) out.write(bytes)
                }
                written++
            }
            require(written == expected) { "Backup snapshot count changed" }
        }
        rows(counts.samples, sampleColumns, samples)
        rows(counts.events, eventColumns, events)
        digest.on(false)
        out.write(md.digest())
        out.flush()
    }

    /** The caller must commit restored records only after this method returns successfully. */
    fun read(input: InputStream, header: (Counts) -> Unit, sample: (List<String?>) -> Unit, event: (List<String?>) -> Unit): Counts {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = DigestInputStream(input.buffered(), md)
        val data = DataInputStream(digest)
        val budget = Budget()
        require(data.readUTF() == MAGIC && data.readInt() == VERSION) { "Unsupported BatteryScope backup" }
        val counts = Counts(data.readLong(), data.readLong())
        validateCounts(counts)
        header(counts)
        fun rows(count: Long, columns: List<Column>, consumer: (List<String?>) -> Unit) {
            var remaining = count
            while (remaining > 0L) {
                val row = columns.map { column ->
                    val size = data.readInt()
                    budget.field(size)
                    val value = if (size == -1) null else {
                        val bytes = ByteArray(size)
                        data.readFully(bytes)
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }
                    validateValue(column, value)
                    value
                }
                consumer(row)
                remaining--
            }
        }
        rows(counts.samples, sampleColumns, sample)
        rows(counts.events, eventColumns, event)
        digest.on(false)
        val expected = ByteArray(32)
        data.readFully(expected)
        require(MessageDigest.isEqual(expected, md.digest())) { "Backup checksum mismatch" }
        require(data.read() == -1) { "Unexpected data after backup checksum" }
        return counts
    }

    fun stage(input: InputStream, output: OutputStream) {
        val bytes = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            size += count
            require(size <= MAX_BYTES) { "Backup exceeds supported size" }
            output.write(bytes, 0, count)
        }
    }

    /** Restore prepends an earlier history. Existing data is never replaced. */
    fun firstImportedId(existingMinId: Long?, count: Long): Long {
        require(count in 0..MAX_ROWS)
        return if (existingMinId == null) 1L else Math.subtractExact(existingMinId, count)
    }

    fun requireEarlier(timestamp: Long, earliestExisting: Long?) {
        require(earliestExisting == null || timestamp < earliestExisting) {
            "Backup overlaps existing history. Restore requires an earlier, non-overlapping backup."
        }
    }
}
