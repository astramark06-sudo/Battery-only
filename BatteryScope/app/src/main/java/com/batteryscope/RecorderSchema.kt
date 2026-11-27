package com.batteryscope

/** Shared SQL used by Android and by the real-SQLite JVM regression tests. */
internal object RecorderSchema {
    const val VERSION = 5

    private val sampleColumns = listOf(
        "timestamp_ms", "elapsed_realtime_ms", "boot_count", "battery_percent", "battery_status", "plug_type",
        "battery_voltage_mv", "battery_current_ua", "battery_average_current_ua",
        "max_charging_current_ua", "max_charging_voltage_uv", "battery_present", "invalid_charger_state", "battery_cycle_count",
        "charge_counter_uah", "energy_counter_nwh", "temperature_c", "battery_health", "technology", "screen_on",
        "foreground_package", "usage_access", "power_supply_json", "usb_voltage_candidate_uv", "usb_current_candidate_ua"
    )
    private val columnList = sampleColumns.joinToString(",")

    private fun sampleTable(name: String): String = """
        CREATE TABLE $name (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp_ms INTEGER NOT NULL, elapsed_realtime_ms INTEGER NOT NULL, boot_count INTEGER,
            battery_percent INTEGER, battery_status TEXT NOT NULL, plug_type TEXT NOT NULL,
            battery_voltage_mv INTEGER, battery_current_ua INTEGER, battery_average_current_ua INTEGER,
            max_charging_current_ua INTEGER, max_charging_voltage_uv INTEGER, battery_present INTEGER,
            invalid_charger_state INTEGER, battery_cycle_count INTEGER, charge_counter_uah INTEGER,
            energy_counter_nwh INTEGER, temperature_c REAL, battery_health TEXT, technology TEXT,
            screen_on INTEGER NOT NULL, foreground_package TEXT, usage_access INTEGER NOT NULL,
            power_supply_json TEXT, usb_voltage_candidate_uv INTEGER, usb_current_candidate_ua INTEGER
        )
    """.trimIndent()

    private val sampleIndexes = listOf(
        "CREATE INDEX idx_samples_time ON samples(timestamp_ms,id)",
        "CREATE INDEX idx_samples_state_time ON samples(battery_status,timestamp_ms)",
        "CREATE INDEX idx_samples_boot_elapsed ON samples(boot_count,elapsed_realtime_ms,id)"
    )

    fun create(exec: (String) -> Unit) {
        exec(sampleTable("samples"))
        sampleIndexes.forEach(exec)
        exec("""CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL,
            event_type TEXT NOT NULL, details TEXT
        )""".trimIndent())
        exec("CREATE INDEX idx_events_time ON events(timestamp_ms)")
    }

    /**
     * Caller must run all operations inside one transaction. The Android SQLiteOpenHelper
     * does that for onUpgrade; the test fixture explicitly begins and commits a transaction.
     * The old table is retained until every historical sample has been copied and counted.
     */
    fun upgrade(oldVersion: Int, newVersion: Int, exec: (String) -> Unit, count: (String) -> Long) {
        require(oldVersion in 1..VERSION && newVersion in oldVersion..VERSION)
        if (oldVersion < 2 && newVersion >= 2) {
            exec("ALTER TABLE samples ADD COLUMN boot_count INTEGER")
        }
        if (oldVersion < 3 && newVersion >= 3) {
            exec("ALTER TABLE samples ADD COLUMN max_charging_current_ua INTEGER")
            exec("ALTER TABLE samples ADD COLUMN max_charging_voltage_uv INTEGER")
            exec("ALTER TABLE samples ADD COLUMN battery_present INTEGER")
            exec("ALTER TABLE samples ADD COLUMN battery_cycle_count INTEGER")
        }
        if (oldVersion < 4 && newVersion >= 4) {
            exec("ALTER TABLE samples ADD COLUMN invalid_charger_state INTEGER")
        }
        if (oldVersion < 5 && newVersion >= 5) {
            val originalCount = count("SELECT COUNT(*) FROM samples")
            exec(sampleTable("samples_v5"))
            exec("INSERT INTO samples_v5 ($columnList) SELECT $columnList FROM samples ORDER BY timestamp_ms")
            check(count("SELECT COUNT(*) FROM samples_v5") == originalCount) {
                "History migration row-count mismatch; aborting to preserve original data"
            }
            exec("DROP TABLE samples")
            exec("ALTER TABLE samples_v5 RENAME TO samples")
            sampleIndexes.forEach(exec)
        }
    }
}
