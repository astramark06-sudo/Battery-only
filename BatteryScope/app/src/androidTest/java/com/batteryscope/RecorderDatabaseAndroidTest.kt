package com.batteryscope

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Disposable randomly named databases; never touch production history. */
class RecorderDatabaseAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun name() = "test-history-" + UUID.randomUUID() + ".db"
    private fun <T> database(block: (RecorderDatabase) -> T): T {
        val name = name()
        val db = RecorderDatabase(context,name)
        try { return block(db) } finally { db.close(); context.deleteDatabase(name) }
    }
    private fun number(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql,null).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
    private fun sample(db: RecorderDatabase, wall: Long, elapsed: Long = wall, percent: Int = 52, charging: Boolean = false) {
        db.insertSample(ContentValues().apply {
            put("timestamp_ms",wall)
            put("elapsed_realtime_ms",elapsed)
            put("boot_count",9)
            put("battery_status",if (charging) "CHARGING" else "DISCHARGING")
            put("plug_type",if (charging) "AC" else "NONE")
            put("battery_percent",percent)
            put("battery_current_ua",-123456L)
            put("temperature_c",31.1234567890123)
            put("screen_on",0)
            put("usage_access",0)
            put("power_supply_json","{\"key\":\"Ω\"}")
            put("technology","")
            putNull("foreground_package")
        })
    }
    private fun backup(db: RecorderDatabase): ByteArray =
        ByteArrayOutputStream().also { db.exportBackup(it) }.toByteArray()

    @Test fun chargingFocusedReportRetainsHistoricalRawDataWithoutUnrelatedPanels() = database { db ->
        sample(db,1000,100,30,true)
        sample(db,31000,30100,31,true)
        db.writableDatabase.execSQL("UPDATE samples SET screen_on=1,usage_access=1,foreground_package='test.example'")
        val before = backup(db)
        val focused = db.historySummary(31000,chargingFocus=true)
        assertTrue(focused.contains("Both positive non-sentinel fields recorded together: 0/2"))
        assertTrue(focused.contains("MOST RECENT CHARGING SESSION"))
        assertTrue(focused.contains("CHARGING MEASUREMENT EVIDENCE"))
        assertTrue(focused.contains("30–40%: 120.00 pp/h"))
        assertTrue(focused.contains("30% → 31%"))
        assertFalse(focused.contains("Foreground app time"))
        assertFalse(focused.contains("DEVICE RESOURCE HISTORY"))
        assertFalse(focused.contains("test.example"))
        assertFalse(focused.contains("Average battery-side charge rate from counter"))
        assertFalse(focused.contains("Charge-counter observed interval rate:"))
        assertArrayEquals(before,backup(db))
    }

    @Test fun advertisedHistoryAndChartUseSameReadOnlySnapshot() = database { db ->
        sample(db,1000,100,30,true)
        sample(db,31000,30100,31,true)
        db.writableDatabase.execSQL("UPDATE samples SET max_charging_current_ua=3000000,max_charging_voltage_uv=5000000 WHERE timestamp_ms=1000")
        val before = backup(db)
        val (report, curves) = db.chargingDashboard(31000)
        assertTrue(report.contains("Both positive non-sentinel fields recorded together: 1/2"))
        assertTrue(report.contains("Connected: 0.50 min"))
        assertEquals(1,curves.size)
        assertEquals(listOf(30,31),curves.first().points().map { it.percent })
        assertEquals(1000L,curves.first().first.wallMs!!)
        assertEquals(3_000_000L,curves.first().first.advertisedCurrentUa!!)
        assertEquals(5_000_000L,curves.first().first.advertisedVoltageUv!!)
        assertNull(curves.first().last.advertisedCurrentUa)
        assertArrayEquals(before,backup(db))
    }

    @Test fun exactOverlapRecoveryPreservesDuplicatesAndRejectsConflictsAtomically() = database { source ->
        sample(source,1000,100,70)
        sample(source,2000,200,69)
        sample(source,2000,200,69)
        source.insertEvent(1100,"TEST","earlier")
        source.insertEvent(2100,"TEST","shared")
        val file = File.createTempFile("overlap-recovery",".bsh",context.cacheDir)
        try {
            file.writeBytes(backup(source))
            database { target ->
                sample(target,2000,200,69)
                sample(target,2000,200,69)
                sample(target,3000,300,68)
                target.insertEvent(2100,"TEST","shared")
                target.insertEvent(3100,"TEST","later")
                val recovered = target.recoverEarlierWithExactOverlap(file)
                assertEquals(HistoryBackup.Counts(1,1),recovered.inserted)
                assertEquals(HistoryBackup.Counts(2,1),recovered.matched)
                val after = backup(target)
                val replay = target.recoverEarlierWithExactOverlap(file)
                assertEquals(HistoryBackup.Counts(0,0),replay.inserted)
                assertEquals(HistoryBackup.Counts(3,2),replay.matched)
                assertArrayEquals(after,backup(target))
                assertEquals(4L,number(target.readableDatabase,"SELECT COUNT(*) FROM samples"))
            }
            database { conflict ->
                sample(conflict,2000,200,69)
                sample(conflict,2000,200,68)
                conflict.insertEvent(2100,"TEST","shared")
                val before = backup(conflict)
                assertTrue(runCatching { conflict.recoverEarlierWithExactOverlap(file) }.isFailure)
                assertArrayEquals(before,backup(conflict))
            }
        } finally { file.delete() }
    }

    @Test fun usageImportCheckpointIsAtomicDeduplicatedAndPortable() = database { source ->
        val first = UsageHistory.Window(1000,2000,"INITIAL_BOUNDED_LOOKBACK")
        val row = UsageHistory.Transition(1500,1,"com.example")
        assertNull(source.usageCheckpoint())
        assertEquals(1,source.commitUsageWindow(null,first,listOf(row,row)) { true })
        assertEquals(2000L,source.usageCheckpoint()!!)
        val second = UsageHistory.Window(1500,3000,"CONTINUATION")
        val later = UsageHistory.Transition(2500,2,"com.example")
        var checks = 0
        assertTrue(runCatching {
            source.commitUsageWindow(2000,second,listOf(later)) { ++checks == 1 }
        }.isFailure)
        assertEquals(2000L,source.usageCheckpoint()!!)
        assertEquals(1L,number(source.readableDatabase,"SELECT COUNT(*) FROM events WHERE event_type='USAGE_EVENT_V1'"))
        assertEquals(1,source.commitUsageWindow(2000,second,listOf(row,later)) { true })
        assertTrue(runCatching { source.commitUsageWindow(2000,second,listOf(later)) { true } }.isFailure)
        assertEquals(3000L,source.usageCheckpoint()!!)
        val bytes = backup(source)
        database { target ->
            assertEquals(HistoryBackup.Counts(0,4),target.restoreBackup(ByteArrayInputStream(bytes)))
            assertEquals(3000L,target.usageCheckpoint()!!)
            assertEquals(2L,number(target.readableDatabase,"SELECT COUNT(*) FROM events WHERE event_type='USAGE_EVENT_V1'"))
        }
    }

    @Test fun portableBackupRoundTripPreservesTypedValuesAndDuplicateTimestamps() = database { source ->
        sample(source,1000,100)
        sample(source,1000,200)
        source.insertEvent(1100,"TEST","Ω\n=raw, quoted \"value\"")
        val bytes = backup(source)
        database { target ->
            assertEquals(HistoryBackup.Counts(2,1),target.restoreBackup(ByteArrayInputStream(bytes)))
            assertEquals(2L,number(target.readableDatabase,"SELECT COUNT(*) FROM samples WHERE timestamp_ms=1000"))
            target.readableDatabase.rawQuery("SELECT battery_current_ua,temperature_c,technology,foreground_package,power_supply_json FROM samples ORDER BY id LIMIT 1",null).use {
                assertTrue(it.moveToFirst())
                assertEquals(-123456L,it.getLong(0))
                assertEquals(31.1234567890123,it.getDouble(1),0.0)
                assertEquals("",it.getString(2))
                assertTrue(it.isNull(3))
                assertEquals("{\"key\":\"Ω\"}",it.getString(4))
            }
            assertArrayEquals(bytes,backup(target))
        }
    }

    @Test fun overlapAndCorruptionRollbackPreviouslyInsertedRows() = database { source ->
        sample(source,1000)
        sample(source,3000)
        source.insertEvent(2000,"TEST","event")
        val bytes = backup(source)
        database { target ->
            sample(target,2500)
            val before = backup(target)
            assertTrue(runCatching { target.restoreBackup(ByteArrayInputStream(bytes)) }.isFailure)
            assertArrayEquals(before,backup(target))
        }
        database { target ->
            sample(target,5000)
            val before = backup(target)
            val corrupt = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertTrue(runCatching { target.restoreBackup(ByteArrayInputStream(corrupt)) }.isFailure)
            assertArrayEquals(before,backup(target))
            assertEquals(HistoryBackup.Counts(2,1),target.restoreBackup(ByteArrayInputStream(bytes)))
            assertEquals(3L,number(target.readableDatabase,"SELECT COUNT(*) FROM samples"))
            assertEquals(1000L,number(target.readableDatabase,"SELECT timestamp_ms FROM samples ORDER BY id LIMIT 1"))
            sample(target,6000)
            assertEquals(6000L,number(target.readableDatabase,"SELECT timestamp_ms FROM samples ORDER BY id DESC LIMIT 1"))
        }
    }

    @Test fun stagedBackupPrevalidationAndSecondPassPreserveExistingRows() = database { source ->
        sample(source,1000)
        val valid = backup(source)
        val file = File.createTempFile("backup-test",".bsh",context.cacheDir)
        try {
            database { target ->
                sample(target,5000)
                val before = backup(target)
                file.writeBytes(valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
                assertTrue(runCatching { target.restoreBackup(file) }.isFailure)
                assertArrayEquals(before,backup(target))
                file.writeBytes(valid)
                assertEquals(HistoryBackup.Counts(1,0),target.restoreBackup(file))
                assertEquals(2L,number(target.readableDatabase,"SELECT COUNT(*) FROM samples"))
            }
        } finally { file.delete() }
    }

    @Test fun invalidNormalizedBackupRollsBackEarlierValidRow() = database { source ->
        sample(source,1000)
        sample(source,2000,percent=999)
        val invalid = backup(source)
        database { target ->
            sample(target,5000)
            val before = backup(target)
            assertTrue(runCatching { target.restoreBackup(ByteArrayInputStream(invalid)) }.isFailure)
            assertArrayEquals(before,backup(target))
        }
    }

    @Test fun legacyCsvRoundTripAndEmptyExport() = database { source ->
        val empty = StringWriter()
        assertEquals(0L,source.exportCsv(empty))
        sample(source,1000)
        source.insertEvent(1100,"TEST","quoted \"value\"")
        val output = StringWriter()
        assertEquals(2L,source.exportCsv(output))
        val file = File.createTempFile("legacy-test",".csv",context.cacheDir)
        try {
            file.writeText(output.toString())
            database { target ->
                sample(target,3000)
                assertEquals(HistoryBackup.Counts(1,1),target.restoreLegacyCsv(file))
                assertEquals(2L,number(target.readableDatabase,"SELECT COUNT(*) FROM samples"))
                assertEquals(-123456L,number(target.readableDatabase,"SELECT battery_current_ua FROM samples ORDER BY id LIMIT 1"))
            }
        } finally { file.delete() }
    }

    @Test fun csvRoundTripPreservesRecordSequenceAcrossWallClockRollback() = database { source ->
        sample(source,1000,100,70)
        sample(source,900,200,69)
        sample(source,1000,300,68)
        source.insertEvent(1100,"TEST","first")
        source.insertEvent(800,"TEST","second")
        val output = StringWriter()
        assertEquals(5L,source.exportCsv(output))
        val file = File.createTempFile("csv-sequence",".csv",context.cacheDir)
        try {
            file.writeText(output.toString())
            database { target ->
                sample(target,5000,400,67)
                assertEquals(HistoryBackup.Counts(3,2),target.restoreLegacyCsv(file))
                val sampleTimes = mutableListOf<Long>()
                val elapsedTimes = mutableListOf<Long>()
                target.readableDatabase.rawQuery("SELECT timestamp_ms,elapsed_realtime_ms FROM samples ORDER BY id",null).use {
                    while(it.moveToNext()) { sampleTimes.add(it.getLong(0)); elapsedTimes.add(it.getLong(1)) }
                }
                assertEquals(listOf(1000L,900L,1000L,5000L),sampleTimes)
                assertEquals(listOf(100L,200L,300L,400L),elapsedTimes)
                val eventTimes = mutableListOf<Long>()
                target.readableDatabase.rawQuery("SELECT timestamp_ms FROM events ORDER BY id",null).use {
                    while(it.moveToNext()) eventTimes.add(it.getLong(0))
                }
                assertEquals(listOf(1100L,800L),eventTimes)
            }
        } finally { file.delete() }
    }

    @Test fun reportExcludesFutureRowsButPreservesRawHistory() = database { db ->
        val now = 2_000_000_000_000L
        sample(db, now - 30_000L, 100_000L, 52)
        sample(db, now, 130_000L, 51)
        sample(db, now + 30_000L, 160_000L, 99)
        val report = db.historySummary(now)
        assertTrue(report.contains("Stored samples: 3"))
        assertTrue(report.contains("=== 24 HOURS • 2 samples ==="))
        assertTrue(report.contains("=== ALL TIME • 2 samples ==="))
        assertTrue(report.contains("From last eligible recorded 51%"))
        assertFalse(report.contains("From last eligible recorded 99%"))
        assertTrue(report.contains("Last sample: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",java.util.Locale.US).format(java.util.Date(now))))
        assertEquals(3L, number(db.readableDatabase, "SELECT COUNT(*) FROM samples"))
    }

    @Test fun continuityReportsEmptyAndStaleWindowsWithoutClaimingCoverage() = database { db ->
        val now = 2_000_000_000_000L
        assertTrue(db.historySummary(now).contains("no eligible samples; coverage unavailable"))
        sample(db, now - 600_000L, 100_000L)
        val stale = db.historySummary(now)
        assertTrue(stale.contains("=== 24 HOURS • 1 samples ==="))
        assertTrue(stale.contains("latest timestamp age 600s"))
        assertTrue(stale.contains("leading unobserved edge 85800s"))
        assertTrue(stale.contains("rate-eligible adjacent interval duration 0s"))
        assertTrue(stale.contains("not a continuous coverage percentage"))
    }

    @Test fun predictedThresholdIncludesElapsedSessionTime() = database { db ->
        val start = 2_000_000_000_000L
        for (i in 0..60) sample(db, start + i * 30_000L, 100_000L + i * 30_000L, 60 + i / 6, true)
        val report = db.historySummary(start + 1_800_000L)
        assertTrue(report.contains("80%: 1h 0m estimated total from session start; 30m estimated remaining from last sample"))
    }

    @Test fun walReaderSeesCommittedRowsDuringSeparateWriterTransaction() {
        val name = name()
        val writer = RecorderDatabase(context,name)
        val reader = RecorderDatabase(context,name)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            sample(writer,1000)
            val writable = writer.writableDatabase
            assertTrue(writable.isWriteAheadLoggingEnabled)
            assertTrue(reader.readableDatabase.isWriteAheadLoggingEnabled)
            assertEquals(1L,number(reader.readableDatabase,"SELECT COUNT(*) FROM samples"))
            writable.beginTransactionNonExclusive()
            try {
                sample(writer,2000)
                val observed = executor.submit<Long> {
                    number(reader.readableDatabase,"SELECT COUNT(*) FROM samples")
                }.get(10,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(1L,observed)
                writable.setTransactionSuccessful()
            } finally { writable.endTransaction() }
            assertEquals(2L,number(reader.readableDatabase,"SELECT COUNT(*) FROM samples"))
            assertEquals(5,writable.version)
        } finally {
            executor.shutdownNow()
            reader.close()
            writer.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun combinedWindowsPreserveIndependentBoundsAndInsertionOrder() = database { db ->
        val now = 2_000_000_000_000L
        val day = 86_400_000L
        // A row outside the narrow window appears between eligible rows.
        // The previous eligible row must remain independent for each window.
        sample(db,now - 2 * day,100_000,54)
        sample(db,now - day,130_000,53)
        sample(db,now - day - 1,160_000,52)
        sample(db,now - day + 60_000,190_000,51)
        sample(db,now,220_000,50)
        sample(db,now,250_000,49)
        sample(db,now + 1,280_000,48)
        val report = db.historySummary(now)
        fun section(label: String): String = report.substringAfter("=== " + label + " • ").substringBefore("\n===")
        val narrow = section("24 HOURS")
        assertTrue(narrow.startsWith("4 samples ==="))
        assertTrue(narrow.contains("rate-eligible adjacent interval duration 120s"))
        assertTrue(narrow.contains("Discharging: 0.03 h • -4.00 percentage points"))
        for (label in listOf("7 DAYS","30 DAYS","90 DAYS","365 DAYS","ALL TIME")) {
            val broad = section(label)
            assertTrue(label,broad.startsWith("6 samples ==="))
            assertTrue(label,broad.contains("rate-eligible adjacent interval duration 150s"))
            assertTrue(label,broad.contains("Discharging: 0.04 h • -5.00 percentage points"))
        }
        assertTrue(report.contains("Stored samples: 7"))
        assertEquals(7L,number(db.readableDatabase,"SELECT COUNT(*) FROM samples"))
    }

    @Test fun recentSessionsKeepOnlyEightAndIncludeFirstFullEndpoint() = database { db ->
        val start = 2_000_000_000_000L
        for (i in 0..9) {
            val wall = start + i * 300_000L
            val elapsed = 100_000L + i * 300_000L
            val pct = 40 + i
            sample(db,wall,elapsed,pct,true)
            sample(db,wall+30_000,elapsed+30_000,pct+1,true)
            sample(db,wall+60_000,elapsed+60_000,pct+2,true)
            sample(db,wall+90_000,elapsed+90_000,pct+3,true)
            db.writableDatabase.execSQL("UPDATE samples SET battery_status='FULL' WHERE timestamp_ms IN (?,?)", arrayOf(wall+60_000,wall+90_000))
            sample(db,wall+120_000,elapsed+120_000,pct+2)
            sample(db,wall+150_000,elapsed+150_000,pct+1)
        }
        val report = db.historySummary(start + 9 * 300_000L + 150_000L)
        val recent = report.substringAfter("=== RECENT CHARGING SESSIONS (LAST 30 DAYS) ===\n").substringBefore("\n\n===")
        assertEquals(8,recent.lines().count { it.isNotBlank() })
        assertTrue(recent.lines().first().contains("49%→51%"))
        assertTrue(recent.lines().last().contains("42%→44%"))
        assertFalse(recent.contains("41%→43%"))
        assertTrue(recent.lines().all { it.endsWith(" • full") })
        val mostRecent = report.substringAfter("=== MOST RECENT CHARGING SESSION (LAST 30 DAYS) ===").substringBefore("\n\n===")
        assertTrue(mostRecent.contains("49% → 51%"))
        assertTrue(mostRecent.contains("elapsed: 1m"))
        assertTrue(mostRecent.contains("50%: 0m observed"))
        assertEquals(60L,number(db.readableDatabase,"SELECT COUNT(*) FROM samples"))
    }

    @Test fun missingSessionPercentageAndReachedThresholdAreReportedAccurately() = database { db ->
        val start = 2_000_000_000_000L
        sample(db,start,100_000,99,true)
        sample(db,start+30_000,130_000,100,true)
        db.writableDatabase.execSQL("UPDATE samples SET battery_percent=NULL WHERE timestamp_ms=?",arrayOf(start))
        val report = db.historySummary(start+30_000)
        val recent = report.substringAfter("=== RECENT CHARGING SESSIONS (LAST 30 DAYS) ===").substringBefore("\n\n===")
        assertTrue(recent.contains("endpoint battery percentage unavailable"))
        assertFalse(recent.contains("one sample only"))
        assertTrue(report.contains("estimated time to 100% at screen-ON charging rate: already at threshold"))
        assertEquals(2L,number(db.readableDatabase,"SELECT COUNT(*) FROM samples"))
    }

    @Test fun historyRatesRequireKnownBootAndAppCorrelationRequiresAccess() = database { db ->
        val start = 2_000_000_000_000L
        for (i in 0..4) sample(db,start + i*30_000L,100_000L + i*30_000L,90-i)
        val sql = db.writableDatabase
        sql.execSQL("UPDATE samples SET screen_on=1,foreground_package='test.example',usage_access=1")
        sql.execSQL("UPDATE samples SET usage_access=0 WHERE timestamp_ms=?",arrayOf(start+30_000L))
        sql.execSQL("UPDATE samples SET boot_count=NULL WHERE timestamp_ms=?",arrayOf(start+90_000L))
        val report = db.historySummary(start+120_000L)
        val day = report.substringAfter("=== 24 HOURS • ").substringBefore("\n===")
        assertTrue(day.startsWith("5 samples ==="))
        assertTrue(day.contains("rate-eligible adjacent interval duration 60s"))
        assertTrue(day.contains("2 unknown boot identity"))
        assertTrue(day.contains("Discharging: 0.02 h • -2.00 percentage points"))
        assertFalse(day.contains("test.example:"))
        assertEquals(5L,number(db.readableDatabase,"SELECT COUNT(*) FROM samples"))
        sql.execSQL("UPDATE samples SET usage_access=1 WHERE timestamp_ms=?",arrayOf(start+30_000L))
        val allowed = db.historySummary(start+120_000L).substringAfter("=== 24 HOURS • ").substringBefore("\n===")
        assertTrue(allowed.contains("test.example:"))
        assertTrue(allowed.contains("rate-eligible adjacent interval duration 60s"))
    }

    @Test fun rawMetadataAndAcquisitionWindowsSurvivePortableBackup() = database { source ->
        val now = 2_000_000_000_000L
        val day = 86_400_000L
        val intent = android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
            .putExtra(android.os.BatteryManager.EXTRA_VOLTAGE,0)
            .putExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,-12)
            .putExtra(android.os.BatteryManager.EXTRA_STATUS,999)
            .putExtra(android.os.BatteryManager.EXTRA_SCALE,"wrong type")
        val raw = BatteryBroadcastMetadata.capture(intent)
        assertEquals(0,raw.getJSONObject("values").getInt("voltage"))
        assertEquals(999,raw.getJSONObject("values").getInt("status"))
        assertFalse(raw.getJSONObject("values").has("level"))
        assertEquals("String",raw.getJSONObject("invalid_types").getString("scale"))
        assertEquals(-1.2,BatteryBroadcastMetadata.temperatureC(-12)!!,0.00001)
        assertNull(BatteryBroadcastMetadata.temperatureC(null))
        assertNull(BatteryBroadcastMetadata.temperatureC(1001))
        assertFalse(BatteryBroadcastMetadata.capture(null).getBoolean("available"))
        fun resource(wall: Long, elapsed: Long, ram: Long): String = org.json.JSONObject()
            .put("@device/captured_wall_ms",wall).put("@device/captured_elapsed_ms",elapsed)
            .put("@device/memory/total_bytes",400_000_000L)
            .put("@device/memory/available_bytes",ram).toString()
        sample(source,now-2*day)
        sample(source,now-60_000)
        sample(source,now-30_000)
        source.writableDatabase.execSQL("UPDATE samples SET power_supply_json=? WHERE timestamp_ms=?",arrayOf(resource(now-2*day,1000,100_000_000),now-2*day))
        source.writableDatabase.execSQL("UPDATE samples SET power_supply_json=? WHERE timestamp_ms=?",arrayOf(resource(now-60_000,2000,200_000_000),now-60_000))
        val metadata = org.json.JSONObject().put("@battery_raw_v1",raw).toString()
        source.writableDatabase.execSQL("UPDATE samples SET power_supply_json=?,battery_voltage_mv=0,temperature_c=-1.2 WHERE timestamp_ms=?",arrayOf(metadata,now-30_000))
        val bytes = backup(source)
        database { target ->
            assertEquals(HistoryBackup.Counts(3,0),target.restoreBackup(ByteArrayInputStream(bytes)))
            assertArrayEquals(bytes,backup(target))
            val report = target.historySummary(now)
            val daily = report.substringAfter("=== DEVICE RESOURCE HISTORY (LAST 24 HOURS) ===").substringBefore("===")
            val weekly = report.substringAfter("=== DEVICE RESOURCE HISTORY (LAST 7 DAYS) ===").substringBefore("===")
            assertTrue("Resource window output: " + daily, daily.contains("Distinct resource snapshots: 1"))
            assertTrue(daily.contains("valid snapshots 1/1"))
            assertTrue(weekly.contains("Distinct resource snapshots: 2"))
            assertEquals(1L,number(target.readableDatabase,"SELECT COUNT(*) FROM samples WHERE battery_voltage_mv=0 AND temperature_c<0"))
        }
    }

    @Test fun actualAndroidOpenHelperMigratesV4WithoutDroppingRawRecords() {
        val name = name()
        try {
            context.openOrCreateDatabase(name,0,null).use { db ->
                db.execSQL("""CREATE TABLE samples (
                    timestamp_ms INTEGER PRIMARY KEY, elapsed_realtime_ms INTEGER NOT NULL, boot_count INTEGER,
                    battery_percent INTEGER, battery_status TEXT NOT NULL, plug_type TEXT NOT NULL,
                    battery_voltage_mv INTEGER,battery_current_ua INTEGER,battery_average_current_ua INTEGER,
                    max_charging_current_ua INTEGER,max_charging_voltage_uv INTEGER,battery_present INTEGER,
                    invalid_charger_state INTEGER,battery_cycle_count INTEGER,charge_counter_uah INTEGER,
                    energy_counter_nwh INTEGER,temperature_c REAL,battery_health TEXT,technology TEXT,
                    screen_on INTEGER NOT NULL,foreground_package TEXT,usage_access INTEGER NOT NULL,
                    power_supply_json TEXT,usb_voltage_candidate_uv INTEGER,usb_current_candidate_ua INTEGER
                )""".trimIndent())
                db.execSQL("CREATE INDEX idx_samples_time ON samples(timestamp_ms)")
                db.execSQL("CREATE INDEX idx_samples_state_time ON samples(battery_status,timestamp_ms)")
                db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp_ms INTEGER NOT NULL,event_type TEXT NOT NULL,details TEXT)")
                db.execSQL("CREATE INDEX idx_events_time ON events(timestamp_ms)")
                db.execSQL("INSERT INTO samples(timestamp_ms,elapsed_realtime_ms,boot_count,battery_status,plug_type,battery_current_ua,screen_on,usage_access,invalid_charger_state) VALUES(1000,100,9,'DISCHARGING','NONE',-123456,0,0,0)")
                db.execSQL("INSERT INTO events(timestamp_ms,event_type,details) VALUES(1000,'TEST','original')")
                db.version = 4
            }
            val migrated = RecorderDatabase(context,name)
            try {
                assertEquals(5,migrated.writableDatabase.version)
                assertEquals(-123456L,number(migrated.readableDatabase,"SELECT battery_current_ua FROM samples"))
                assertEquals(1L,number(migrated.readableDatabase,"SELECT COUNT(*) FROM events WHERE details='original'"))
                sample(migrated,1000,200)
                assertEquals(2L,number(migrated.readableDatabase,"SELECT COUNT(*) FROM samples WHERE timestamp_ms=1000"))
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
