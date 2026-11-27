package com.batteryscope

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

class RecorderSchemaTest {
    private fun database(block: (Connection) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use(block)
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }
    private fun Connection.number(sql: String): Long =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> check(rs.next()); rs.getLong(1) } }

    // Independently reconstructed from the historical v1-v4 table definitions.
    private fun Connection.legacy(version: Int) {
        exec("""CREATE TABLE samples (
            timestamp_ms INTEGER PRIMARY KEY, elapsed_realtime_ms INTEGER NOT NULL,
            battery_percent INTEGER, battery_status TEXT NOT NULL, plug_type TEXT NOT NULL,
            battery_voltage_mv INTEGER, battery_current_ua INTEGER, battery_average_current_ua INTEGER,
            charge_counter_uah INTEGER, energy_counter_nwh INTEGER, temperature_c REAL,
            battery_health TEXT, technology TEXT, screen_on INTEGER NOT NULL,
            foreground_package TEXT, usage_access INTEGER NOT NULL, power_supply_json TEXT,
            usb_voltage_candidate_uv INTEGER, usb_current_candidate_ua INTEGER
        )""".trimIndent())
        exec("CREATE INDEX idx_samples_time ON samples(timestamp_ms)")
        exec("CREATE INDEX idx_samples_state_time ON samples(battery_status,timestamp_ms)")
        exec("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp_ms INTEGER NOT NULL,event_type TEXT NOT NULL,details TEXT)")
        exec("CREATE INDEX idx_events_time ON events(timestamp_ms)")
        if (version >= 2) exec("ALTER TABLE samples ADD COLUMN boot_count INTEGER")
        if (version >= 3) {
            listOf("max_charging_current_ua", "max_charging_voltage_uv", "battery_present", "battery_cycle_count")
                .forEach { exec("ALTER TABLE samples ADD COLUMN $it INTEGER") }
        }
        if (version >= 4) exec("ALTER TABLE samples ADD COLUMN invalid_charger_state INTEGER")
        exec("""INSERT INTO samples(timestamp_ms,elapsed_realtime_ms,battery_percent,battery_status,plug_type,
            battery_current_ua,temperature_c,screen_on,usage_access,foreground_package,power_supply_json)
            VALUES(1000,100,62,'DISCHARGING','NONE',-123456,31.5,1,1,'test.app','{}')""".trimIndent())
        if (version >= 2) exec("UPDATE samples SET boot_count=9")
        if (version >= 3) exec("UPDATE samples SET max_charging_current_ua=500000,battery_present=1,battery_cycle_count=7")
        if (version >= 4) exec("UPDATE samples SET invalid_charger_state=0")
        exec("INSERT INTO events(timestamp_ms,event_type,details) VALUES(1000,'TEST','existing event')")
    }

    @Test fun freshSchemaSupportsRepeatedWallClockTimestamps() = database { db ->
        RecorderSchema.create({ sql -> db.exec(sql) })
        repeat(2) { i ->
            db.exec("INSERT INTO samples(timestamp_ms,elapsed_realtime_ms,battery_status,plug_type,screen_on,usage_access) VALUES(1000,${100+i},'DISCHARGING','NONE',0,0)")
        }
        assertEquals(2L, db.number("SELECT COUNT(*) FROM samples WHERE timestamp_ms=1000"))
        assertEquals(2L, db.number("SELECT COUNT(DISTINCT id) FROM samples"))
    }

    @Test fun everyLegacyVersionPreservesRawSamplesAndEvents() {
        for (version in 1..4) database { db ->
            db.legacy(version)
            db.autoCommit = false
            RecorderSchema.upgrade(version, 5, { sql -> db.exec(sql) }, { sql -> db.number(sql) })
            db.commit()
            assertEquals(1L, db.number("SELECT COUNT(*) FROM samples"))
            assertEquals(1L, db.number("SELECT COUNT(*) FROM events WHERE details='existing event'"))
            assertEquals(-123456L, db.number("SELECT battery_current_ua FROM samples"))
            assertEquals(62L, db.number("SELECT battery_percent FROM samples"))
            assertEquals(1L, db.number("SELECT COUNT(*) FROM samples WHERE technology IS NULL AND foreground_package='test.app' AND power_supply_json='{}' AND temperature_c=31.5"))
            assertEquals(if (version >= 2) 1L else 0L, db.number("SELECT COUNT(*) FROM samples WHERE boot_count=9"))
            assertEquals(if (version >= 3) 1L else 0L, db.number("SELECT COUNT(*) FROM samples WHERE battery_cycle_count=7 AND battery_present=1 AND max_charging_current_ua=500000"))
            assertEquals(if (version >= 4) 1L else 0L, db.number("SELECT COUNT(*) FROM samples WHERE invalid_charger_state=0"))
            db.exec("INSERT INTO samples(timestamp_ms,elapsed_realtime_ms,battery_status,plug_type,screen_on,usage_access) VALUES(1000,200,'DISCHARGING','NONE',0,0)")
            assertEquals(2L, db.number("SELECT COUNT(*) FROM samples WHERE timestamp_ms=1000"))
            assertEquals(2L, db.number("SELECT COUNT(DISTINCT id) FROM samples"))
            assertEquals(3L, db.number("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name LIKE 'idx_samples_%'"))
            db.commit()
        }
    }

    @Test fun abortedMigrationRollsBackToOriginalTable() = database { db ->
        db.legacy(4)
        db.autoCommit = false
        try {
            RecorderSchema.upgrade(4,5,{ sql -> db.exec(sql) }) { sql ->
                if (sql.contains("samples_v5")) 0L else db.number(sql)
            }
            fail("Expected row-count mismatch")
        } catch (_: IllegalStateException) {
            db.rollback()
        }
        assertEquals(1L, db.number("SELECT COUNT(*) FROM samples"))
        assertEquals(0L, db.number("SELECT COUNT(*) FROM sqlite_master WHERE name='samples_v5'"))
        assertEquals(-123456L, db.number("SELECT battery_current_ua FROM samples"))
    }
}
