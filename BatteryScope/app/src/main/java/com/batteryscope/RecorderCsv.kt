package com.batteryscope

/** The v4 CSV schema remains stable. Quote each field and flatten embedded line breaks. */
internal object RecorderCsv {
    val sampleFields = listOf(
        "battery_percent", "battery_status", "plug_type", "battery_voltage_mv", "battery_current_ua",
        "battery_average_current_ua", "max_charging_current_ua", "max_charging_voltage_uv",
        "battery_present", "invalid_charger_state", "battery_cycle_count", "charge_counter_uah",
        "energy_counter_nwh", "temperature_c", "battery_health", "technology", "screen_on",
        "foreground_package", "usage_access", "power_supply_json", "usb_voltage_candidate_uv",
        "usb_current_candidate_ua", "boot_count"
    )
    val header = listOf("record_type", "timestamp_ms", "elapsed_realtime_ms") + sampleFields + listOf("event_type", "details")
    private val textSampleFields = setOf("battery_status", "plug_type", "battery_health", "technology", "foreground_package", "power_supply_json")

    fun sampleRow(timestamp: String, elapsed: String, values: List<String>): List<String> {
        require(values.size == sampleFields.size)
        return listOf("SAMPLE", timestamp, elapsed) +
            values.mapIndexed { index, value ->
                if (sampleFields[index] in textSampleFields) spreadsheetSafeText(value) else value
            } + listOf("", "")
    }

    fun eventRow(timestamp: String, type: String, details: String): List<String> =
        listOf("EVENT", timestamp, "") + List(sampleFields.size) { "" } +
            listOf(spreadsheetSafeText(type), spreadsheetSafeText(details))

    /** Prevent text originating from firmware/app metadata from becoming spreadsheet formulas.
     * Numeric telemetry (including signed current and negative temperatures) stays unchanged.
     */
    internal fun spreadsheetSafeText(value: String): String {
        val first = value.trimStart().firstOrNull()
        return if (first in listOf('=', '+', '-', '@', '＝', '＋', '－', '＠') ||
            value.firstOrNull() in listOf('\t', '\r', '\n')) "'" + value else value
    }

    fun encodeRow(fields: List<String>): String =
        fields.joinToString(",") { quote(it) } + "\n"

    private fun quote(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""
}
