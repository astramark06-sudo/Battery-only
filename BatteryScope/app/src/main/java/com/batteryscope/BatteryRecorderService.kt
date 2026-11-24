package com.batteryscope

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.Writer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Automatically requested persistent foreground recorder. No activity lifecycle owns this sampler. */
class BatteryRecorderService : Service() {
    private lateinit var handler: Handler
    private lateinit var worker: HandlerThread
    private lateinit var db: RecorderDatabase
    private val databaseLock = Any()
    private var databaseAvailable = true
    private lateinit var batteryManager: BatteryManager
    @Volatile private var batteryIntent: Intent? = null
    private var lastUsageQueryMs = 0L
    private var foregroundPackage: String? = null
    private var lastSampleState: String? = null
    private var lastSamplePlug: String? = null
    private var lastBatteryPercentStatus: String? = null
    private val lastBatteryPropertyStatus = mutableMapOf<Int, String>()
    private var lastCurrentMeasurementQuality: String? = null
    private var lastVoltageMeasurementQuality: String? = null
    private var lastSysfsReadMs = 0L
    private var lastSysfsJson = JSONObject()
    private var receiverRegistered = false
    @Volatile private var recorderRunning = false
    private var samplingWakeLock: PowerManager.WakeLock? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> batteryIntent = intent
                Intent.ACTION_POWER_CONNECTED -> event("POWER_CONNECTED", "Android power-connected broadcast")
                Intent.ACTION_POWER_DISCONNECTED -> event("POWER_DISCONNECTED", "Android power-disconnected broadcast")
                Intent.ACTION_SCREEN_ON -> event("SCREEN_ON", "Display became interactive")
                Intent.ACTION_SCREEN_OFF -> event("SCREEN_OFF", "Display became non-interactive")
                Intent.ACTION_BATTERY_LOW -> event("BATTERY_LOW", "Android battery-low broadcast")
                Intent.ACTION_BATTERY_OKAY -> event("BATTERY_OKAY", "Battery-low condition cleared")
            }
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            try { sample() }
            catch (t: Throwable) { event("SAMPLE_ERROR", t.javaClass.simpleName + ": " + (t.message ?: "unknown")) }
            finally { if (recorderRunning) handler.postDelayed(this, SAMPLE_INTERVAL_MS) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = RecorderDatabase(this)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        worker = HandlerThread("BatteryScopeRecorder").apply { start() }
        handler = Handler(worker.looper)
        batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        createChannel()
        registerEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val wasRunning = recorderRunning
        val keepAwake = prefs.getBoolean(KEY_KEEP_CPU_AWAKE, true)
        try {
            // Every start path, including a stale preference causing RECONFIGURE,
            // must establish the foreground service before doing any other work.
            startAsForeground()
            if (keepAwake) acquireSamplingWakeLock() else releaseSamplingWakeLock()
            val held = samplingWakeLock?.isHeld == true
            startAsForeground()
            prefs.edit().putBoolean(KEY_ACTIVE, true).putBoolean(KEY_USER_ENABLED, true)
                .putBoolean(KEY_WAKE_LOCK_HELD, held)
                .remove(KEY_BOOT_RESUME_FAILURE).remove(KEY_BOOT_RESUME_FAILURE_AT)
                .remove(KEY_START_FAILURE).apply()
            if (!wasRunning) {
                recorderRunning = true
                event(if (intent == null) "RECORDER_RESTARTED" else "RECORDER_STARTED",
                    "Foreground recorder; 30-second target; wake lock requested=$keepAwake held=$held")
                handler.removeCallbacks(sampler)
                if (!handler.post(sampler)) throw IllegalStateException("Recorder worker not accepting samples")
            } else if (intent?.action == ACTION_RECONFIGURE) {
                event("SAMPLING_MODE_CHANGED", "Continuous mode requested=$keepAwake; CPU wake lock held=$held")
            }
            return START_STICKY
        } catch (t: Throwable) {
            recorderRunning = false
            handler.removeCallbacks(sampler)
            releaseSamplingWakeLock()
            val failure = t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            event("RECORDER_START_FAILED", failure)
            prefs.edit().putBoolean(KEY_ACTIVE, false).putBoolean(KEY_WAKE_LOCK_HELD, false)
                .putString(KEY_START_FAILURE, failure).apply()
            stopSelf(startId)
            return START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val requested = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_KEEP_CPU_AWAKE, true)
        val held = samplingWakeLock?.isHeld == true
        val status = when {
            held -> "30-second target • CPU wake lock held • adds app drain"
            requested -> "30-second target • wake lock requested but not held"
            else -> "30-second target • wake lock off • screen-off gaps possible"
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("BatteryScope is recording")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireSamplingWakeLock() {
        try {
            if (samplingWakeLock?.isHeld == true) return
            val power = getSystemService(POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryScope::ContinuousRecorder")
            lock.setReferenceCounted(false)
            if (!lock.isHeld) lock.acquire()
            samplingWakeLock = lock
        } catch (t: Throwable) {
            event("WAKE_LOCK_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    private fun releaseSamplingWakeLock() {
        try {
            val lock = samplingWakeLock
            if (lock != null && lock.isHeld) lock.release()
        } catch (t: Throwable) {
            event("WAKE_LOCK_RELEASE_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        } finally {
            samplingWakeLock = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Battery telemetry recorder", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Visible notification while BatteryScope records telemetry"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerEvents() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val bootCount = try { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 } } catch (_: Throwable) { null }
        val intent = batteryIntent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)).also { batteryIntent = it }
        val level = intent?.extras?.let {
            if (it.containsKey(BatteryManager.EXTRA_LEVEL)) it.getInt(BatteryManager.EXTRA_LEVEL, -1) else -1
        } ?: -1
        val scale = intent?.extras?.let {
            if (it.containsKey(BatteryManager.EXTRA_SCALE)) it.getInt(BatteryManager.EXTRA_SCALE, -1) else -1
        } ?: -1
        val percent = BatteryPercentMath.percent(level, scale)
        val percentStatus = when {
            intent == null -> "ACTION_BATTERY_CHANGED_UNAVAILABLE"
            !intent.hasExtra(BatteryManager.EXTRA_LEVEL) -> "EXTRA_LEVEL_MISSING"
            !intent.hasExtra(BatteryManager.EXTRA_SCALE) -> "EXTRA_SCALE_MISSING"
            level < 0 -> "EXTRA_LEVEL_INVALID"
            scale <= 0 -> "EXTRA_SCALE_NON_POSITIVE"
            level > scale -> "EXTRA_LEVEL_EXCEEDS_SCALE"
            else -> "AVAILABLE"
        }
        if (lastBatteryPercentStatus != percentStatus) {
            event("BATTERY_PERCENT_METADATA", percentStatus)
            lastBatteryPercentStatus = percentStatus
        }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val plugCode = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_PLUGGED) }?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plug = when (plugCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            8 -> "DOCK"
            null, -1 -> "UNKNOWN"
            else -> if (plugCode == 0) "NONE" else if (plugCode > 0) "OTHER_" + plugCode else "UNKNOWN"
        }
        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        if (status != lastSampleState) {
            event("BATTERY_STATE_CHANGED", "from=" + (lastSampleState ?: "UNKNOWN") + " to=" + status)
            lastSampleState = status
        }
        if (plug != lastSamplePlug) {
            event("POWER_SOURCE_CHANGED", "from=" + (lastSamplePlug ?: "UNKNOWN") + " to=" + plug)
            lastSamplePlug = plug
        }
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
        val current = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val average = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val charge = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energy = longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val currentQuality = BatteryMeasurementQuality.current(current, status, plug).status
        val currentQualityKey = status + "|" + plug + "|" + currentQuality
        if (currentQualityKey != lastCurrentMeasurementQuality) {
            event("BATTERY_CURRENT_QUALITY", "quality=" + currentQuality + "; status=" + status + "; plug=" + plug + "; raw_current_ua=" + (current?.toString() ?: "UNAVAILABLE"))
            lastCurrentMeasurementQuality = currentQualityKey
        }
        val voltageQuality = BatteryMeasurementQuality.voltage(voltage.takeIf { it > 0 }).status
        if (voltageQuality != lastVoltageMeasurementQuality) {
            event("BATTERY_VOLTAGE_QUALITY", "quality=" + voltageQuality + "; raw_extra_voltage=" + (voltage.takeIf { it > 0 }?.toString() ?: "UNAVAILABLE") + "; expected_unit=mV")
            lastVoltageMeasurementQuality = voltageQuality
        }
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val activeApp = updateForegroundPackage(now)
        val refreshSysfs = lastSysfsReadMs == 0L || elapsed - lastSysfsReadMs >= SYSFS_INTERVAL_MS
        if (refreshSysfs) {
            lastSysfsJson = readHardwareTrees()
            lastSysfsReadMs = elapsed
        }
        val usb = findUsbCandidate(lastSysfsJson)
        val row = ContentValues().apply {
            put("timestamp_ms", now)
            put("elapsed_realtime_ms", elapsed)
            if (bootCount != null) put("boot_count", bootCount)
            if (percent != null) put("battery_percent", percent)
            put("battery_status", status)
            put("plug_type", plug)
            if (voltage > 0) put("battery_voltage_mv", voltage)
            val maxChargingCurrent = intent?.getIntExtra("max_charging_current", -1) ?: -1
            val maxChargingVoltage = intent?.getIntExtra("max_charging_voltage", -1) ?: -1
            if (maxChargingCurrent > 0) put("max_charging_current_ua", maxChargingCurrent)
            if (maxChargingVoltage > 0) put("max_charging_voltage_uv", maxChargingVoltage)
            val present = intent?.extras?.let { if (it.containsKey("present")) it.getBoolean("present") else null }
            if (present != null) put("battery_present", if (present) 1 else 0)
            val invalidCharger = intent?.extras?.let { if (it.containsKey("invalid_charger")) it.getInt("invalid_charger") else null }
            if (invalidCharger != null) put("invalid_charger_state", invalidCharger)
            val cycles = intent?.getIntExtra("cycle_count", -1) ?: -1
            if (cycles >= 0) put("battery_cycle_count", cycles)
            if (temperature in 0..1000) put("temperature_c", temperature / 10.0)
            if (current != null) put("battery_current_ua", current)
            if (average != null) put("battery_average_current_ua", average)
            if (charge != null) put("charge_counter_uah", charge)
            if (energy != null) put("energy_counter_nwh", energy)
            put("battery_health", health)
            put("technology", intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "NOT_REPORTED")
            put("screen_on", if (screenOn) 1 else 0)
            put("foreground_package", activeApp ?: "")
            put("usage_access", if (hasUsageAccess()) 1 else 0)
            if (refreshSysfs) put("power_supply_json", lastSysfsJson.toString())
            if (refreshSysfs && usb != null) {
                put("usb_voltage_candidate_uv", usb.first)
                put("usb_current_candidate_ua", usb.second)
            }
        }
        insertSample(row)
    }

    private fun longProperty(id: Int): Long? {
        val result = try {
            val value = batteryManager.getLongProperty(id)
            if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) {
                null to "ANDROID_UNAVAILABLE_SENTINEL"
            } else {
                value to "AVAILABLE"
            }
        } catch (t: SecurityException) {
            null to "SECURITY_EXCEPTION"
        } catch (t: Throwable) {
            null to "ERROR:" + t.javaClass.simpleName
        }
        val propertyName = when (id) {
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW -> "CURRENT_NOW"
            BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE -> "CURRENT_AVERAGE"
            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER -> "CHARGE_COUNTER"
            BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER -> "ENERGY_COUNTER"
            else -> "PROPERTY_$id"
        }
        val previous = lastBatteryPropertyStatus.put(id, result.second)
        if (previous != result.second) {
            event("BATTERY_PROPERTY_ACCESS", propertyName + "=" + result.second)
        }
        return result.first
    }

    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun updateForegroundPackage(now: Long): String? {
        if (!hasUsageAccess()) {
            if (foregroundPackage != null) event("USAGE_ACCESS_REVOKED", "Usage Access unavailable; clearing previous foreground app attribution")
            foregroundPackage = null
            lastUsageQueryMs = 0L
            return null
        }
        val manager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        if (lastUsageQueryMs > now) {
            lastUsageQueryMs = 0L
            foregroundPackage = null
        }
        val from = if (lastUsageQueryMs > 0L) lastUsageQueryMs else (now - 60_000L).coerceAtLeast(0L)
        return try {
            val events = manager.queryEvents(from, now) ?: throw IllegalStateException("Usage events unavailable")
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        val next = event.packageName
                        if (next != foregroundPackage) {
                            if (foregroundPackage != null) this.eventAt(event.timeStamp, "APP_FOREGROUND_END", foregroundPackage!!)
                            foregroundPackage = next
                            this.eventAt(event.timeStamp, "APP_FOREGROUND_START", next)
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (event.packageName == foregroundPackage) {
                            this.eventAt(event.timeStamp, "APP_FOREGROUND_END", event.packageName)
                            foregroundPackage = null
                        }
                    }
                }
            }
            lastUsageQueryMs = now
            foregroundPackage
        } catch (t: Throwable) {
            this.event("USAGE_QUERY_ERROR", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
            lastUsageQueryMs = 0L
            foregroundPackage = null
            null
        }
    }

    private fun readHardwareTrees(): JSONObject =
        JSONObject(HardwareSnapshotCache.snapshot(applicationContext, SYSFS_INTERVAL_MS))

    private fun findUsbCandidate(json: JSONObject): Pair<Long, Long>? {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.startsWith("power_supply/") || !key.endsWith("/voltage_now")) continue
            val supplyPath = key.substringBeforeLast('/')
            val supplyName = supplyPath.substringAfterLast('/')
            if (supplyName.lowercase(Locale.US) in setOf("battery", "bms", "main")) continue
            val voltage = json.optString(key).toLongOrNull() ?: continue
            val current = json.optString(supplyPath + "/current_now").toLongOrNull() ?: continue
            val type = json.optString(supplyPath + "/type").uppercase(Locale.US)
            val looksUsb = supplyName.contains("usb", true) || type.contains("USB")
            if (looksUsb && json.optString(supplyPath + "/online") == "1" && voltage in 1_000_000L..30_000_000L && current in -20_000_000L..20_000_000L) {
                return voltage to current
            }
        }
        return null
    }

    private fun insertSample(values: ContentValues) {
        synchronized(databaseLock) {
            if (!databaseAvailable) return
            try {
                db.insertSample(values)
            } catch (t: Throwable) {
                try { db.insertEvent(System.currentTimeMillis(), "DATABASE_SAMPLE_WRITE_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown")) }
                catch (_: Throwable) { }
            }
        }
    }

    /**
     * Broadcast receivers and Service lifecycle callbacks run on the main thread.
     * Queue their SQLite writes on the recorder worker so storage latency cannot
     * stall Android's main thread. Calls already on the worker stay ordered inline.
     */
    private fun event(type: String, details: String) =
        eventAt(System.currentTimeMillis(), type, details)

    /** Preserve source timestamps for UsageStats events instead of stamping poll time. */
    private fun eventAt(timestampMs: Long, type: String, details: String) {
        if (::handler.isInitialized && Looper.myLooper() != worker.looper) {
            handler.post { writeEventOnWorker(timestampMs, type, details) }
        } else {
            writeEventOnWorker(timestampMs, type, details)
        }
    }

    private fun writeEventOnWorker(timestampMs: Long, type: String, details: String) {
        synchronized(databaseLock) {
            if (!databaseAvailable) return
            try {
                db.insertEvent(timestampMs, type, details)
            } catch (_: Throwable) {
                // A failed diagnostic write must never crash the recorder or UI.
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep recording after the UI task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recorderRunning = false
        handler.removeCallbacks(sampler)
        releaseSamplingWakeLock()
        if (receiverRegistered) try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).putBoolean(KEY_WAKE_LOCK_HELD, false).apply()
        // Drain already-queued event/sample work before closing SQLite. Closing
        // the database here on the main thread could block while a write is active.
        val closeDatabase = Runnable {
            synchronized(databaseLock) {
                databaseAvailable = false
                try { db.close() } catch (_: Throwable) { }
            }
            try { worker.quitSafely() } catch (_: Throwable) { }
        }
        if (!::handler.isInitialized || !handler.post(closeDatabase)) {
            synchronized(databaseLock) {
                databaseAvailable = false
                try { db.close() } catch (_: Throwable) { }
            }
            try { worker.quitSafely() } catch (_: Throwable) { }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.batteryscope.action.START_RECORDER"
        const val PREFS = "batteryscope_recorder"
        const val KEY_ACTIVE = "recorder_active"
        const val KEY_USER_ENABLED = "recorder_user_enabled"
        const val KEY_KEEP_CPU_AWAKE = "keep_cpu_awake"
        const val KEY_WAKE_LOCK_HELD = "wake_lock_held"
        const val KEY_START_FAILURE = "recorder_start_failure"
        const val KEY_BOOT_RESUME_FAILURE = "boot_resume_failure"
        const val KEY_BOOT_RESUME_FAILURE_AT = "boot_resume_failure_at"
        const val ACTION_RECONFIGURE = "com.batteryscope.action.RECONFIGURE_RECORDER"
        const val SAMPLE_INTERVAL_MS = 30_000L
        private const val SYSFS_INTERVAL_MS = 120_000L
        private const val CHANNEL_ID = "batteryscope_recorder"
        private const val NOTIFICATION_ID = 301
    }
}

/** SQLite keeps raw samples/events until the user deletes app data. */
class RecorderDatabase(context: Context, databaseName: String = "batteryscope_history.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, RecorderSchema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) = RecorderSchema.create { sql -> db.execSQL(sql) }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        RecorderSchema.upgrade(oldVersion, newVersion, { sql -> db.execSQL(sql) }) { sql ->
            db.rawQuery(sql, null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
    }

    fun insertSample(values: ContentValues) {
        // Independent sample ID preserves even repeated wall-clock timestamps.
        writableDatabase.insertOrThrow("samples", null, values)
    }

    fun insertEvent(timestamp: Long, type: String, details: String) {
        val values = ContentValues().apply {
            put("timestamp_ms", timestamp)
            put("event_type", type)
            put("details", details.take(1000))
        }
        writableDatabase.insertOrThrow("events", null, values)
    }


    fun capacityEstimateReport(nowMs: Long = System.currentTimeMillis()): String {
        val from = nowMs - 90L * 24L * 60L * 60L * 1000L
        val range = snapshot(readableDatabase).samples
        val empiricalReport = readableDatabase.rawQuery(
            """SELECT timestamp_ms,elapsed_realtime_ms,boot_count,battery_percent,battery_status,plug_type,charge_counter_uah
               FROM samples WHERE timestamp_ms BETWEEN ? AND ? AND id BETWEEN ? AND ? ORDER BY boot_count,elapsed_realtime_ms,id""".trimIndent(),
            windowArgs(from, nowMs, range)
        ).use { cursor ->
            val time = cursor.getColumnIndexOrThrow("timestamp_ms")
            val elapsed = cursor.getColumnIndexOrThrow("elapsed_realtime_ms")
            val boot = cursor.getColumnIndexOrThrow("boot_count")
            val percent = cursor.getColumnIndexOrThrow("battery_percent")
            val status = cursor.getColumnIndexOrThrow("battery_status")
            val plug = cursor.getColumnIndexOrThrow("plug_type")
            val counter = cursor.getColumnIndexOrThrow("charge_counter_uah")
            BatteryCapacityEstimator.reportOrdered(sequence {
                while (cursor.moveToNext()) yield(CapacitySample(
                    timestampMs = cursor.getLong(time),
                    bootCount = if (cursor.isNull(boot)) null else cursor.getInt(boot),
                    percent = if (cursor.isNull(percent)) null else cursor.getInt(percent),
                    status = cursor.getString(status) ?: "UNKNOWN",
                    plug = cursor.getString(plug) ?: "NONE",
                    chargeCounterUah = if (cursor.isNull(counter)) null else cursor.getLong(counter),
                    elapsedRealtimeMs = cursor.getLong(elapsed)
                ))
            })
        }
        val latestHardwareJson = readableDatabase.rawQuery(
            "SELECT power_supply_json FROM samples WHERE timestamp_ms BETWEEN ? AND ? AND id BETWEEN ? AND ? AND power_supply_json IS NOT NULL ORDER BY id DESC LIMIT 1",
            windowArgs(0L, nowMs, range)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val kernelReport = buildString {
            appendLine("KERNEL FUEL-GAUGE CAPACITY / CYCLE REPORT")
            if (latestHardwareJson == null) {
                appendLine("No hardware-tree sample recorded yet. Start the recorder and wait for its first 120-second hardware inventory.")
            } else {
                val json = try { JSONObject(latestHardwareJson) } catch (_: Throwable) { JSONObject() }
                val fields = json.keys().asSequence().associateWith { json.optString(it) }
                val gauge = KernelGauge.select(fields, "power_supply/")
                fun field(name: String, value: String?): Pair<String, String>? =
                    if (gauge == null || value == null) null else (gauge.source + "/" + name) to value
                val full = field("charge_full", gauge?.fullRaw)
                val design = field("charge_full_design", gauge?.designRaw)
                val cycles = field("cycle_count", gauge?.cyclesRaw)
                val fullUah = gauge?.fullUah
                val designUah = gauge?.designUah
                if (fullUah != null) appendLine("Kernel full-charge capacity: ${String.format(Locale.US, "%.1f mAh", fullUah / 1000.0)} (${full!!.first})")
                else appendLine("Kernel full-charge capacity: unavailable — " + kernelFieldReason(full, "charge_full", json))
                if (designUah != null) appendLine("Kernel design capacity: ${String.format(Locale.US, "%.1f mAh", designUah / 1000.0)} (${design!!.first})")
                else appendLine("Kernel design capacity: unavailable — " + kernelFieldReason(design, "charge_full_design", json))
                val health = gauge?.healthPercent
                if (health != null) {
                    appendLine("Kernel gauge-reported health: ${String.format(Locale.US, "%.1f%%", health)} (${gauge.source})")
                } else {
                    appendLine("Kernel gauge-reported health: unavailable — a readable same-supply charge_full and charge_full_design pair is required")
                }
                val cycleValue = gauge?.cycles
                if (cycleValue != null) appendLine("Kernel charge cycle count: $cycleValue (${cycles!!.first})")
                else appendLine("Kernel charge cycle count: unavailable — " + kernelFieldReason(cycles, "cycle_count", json))
            }
            append("Kernel gauge values are vendor/driver exports; verify units and meaning on the RMX2161 before treating them as calibrated capacity.")
        }
        return empiricalReport + "\n\n" + kernelReport
    }

    private fun kernelFieldReason(
        field: Pair<String, String>?,
        name: String,
        json: JSONObject
    ): String {
        if (field != null) return when {
            field.second == "READ_DENIED" -> "attribute exists but ordinary app read was denied"
            field.second.startsWith("ERROR:") -> field.second
            else -> "attribute value is not numeric: " + field.second
        }
        val root = json.optString("power_supply/@root")
        return when (root) {
            "READ_DENIED" -> "power_supply root is present but access was denied"
            "ABSENT_OR_HIDDEN_BY_SELINUX" -> "power_supply root is absent or hidden by SELinux"
            else -> "driver does not export " + name + ", or the bounded inventory did not reach it"
        }
    }

    private fun resourceHistoryReport(now: Long, range: SnapshotRange): String {
        val summary = ResourceHistory()
        val from = now - 24L * 60L * 60L * 1000L
        readableDatabase.rawQuery(
            "SELECT boot_count,power_supply_json FROM samples WHERE timestamp_ms BETWEEN ? AND ? AND id BETWEEN ? AND ? AND power_supply_json IS NOT NULL ORDER BY id",
            windowArgs(from, now, range)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val json = try { JSONObject(cursor.getString(1)) } catch (_: Exception) { continue }
                fun number(key: String) = json.optString("@device/" + key).toLongOrNull()?.takeIf { it >= 0L }
                val wall = number("captured_wall_ms") ?: continue
                val elapsed = number("captured_elapsed_ms") ?: continue
                if (wall !in from..now) continue
                summary.accept(ResourceObservation(wall,elapsed,
                    if(cursor.isNull(0)) null else cursor.getInt(0),
                    number("memory/total_bytes"),number("memory/available_bytes"),number("storage/available_bytes"),
                    number("power/thermal_status")?.takeIf { it in 0L..6L }?.toInt(),
                    ResourceHistory.counterNames.map { number("network/" + it + "_bytes_since_boot") }
                ))
            }
        }
        fun mib(value: Long?) = value?.let { String.format(Locale.US,"%.1f MiB",it/1048576.0) } ?: "unavailable"
        return buildString {
            appendLine("=== DEVICE RESOURCE HISTORY (LAST 24 HOURS) ===")
            appendLine("Distinct resource snapshots: " + summary.snapshots)
            appendLine("Available RAM observed range: " + mib(summary.lowestAvailableRam) + " — " + mib(summary.highestAvailableRam))
            appendLine("Lowest available internal storage: " + mib(summary.lowestAvailableStorage))
            appendLine("Highest Android thermal status: " + (summary.highestThermalStatus?.toString() ?: "unavailable"))
            appendLine("Network traffic across valid observed intervals only:")
            ResourceHistory.counterNames.forEachIndexed { i, name ->
                val counter = summary.counters[i]
                appendLine("  " + name + ": " + if(counter.intervals == 0) "unavailable" else
                    mib(counter.bytes) + " • coverage " + durationText(counter.coveredMs) + " • " + counter.intervals + " intervals")
            }
            append("Network totals are incomplete observations, not continuous full-day totals or per-app electrical measurements. Counter resets, reboots, clock discontinuities and gaps over 5 minutes are excluded.")
        }
    }

    fun historySummary(now: Long = System.currentTimeMillis()): String {
        val windows = listOf(
            "24 HOURS" to 24L * 60 * 60 * 1000,
            "7 DAYS" to 7L * 24 * 60 * 60 * 1000,
            "30 DAYS" to 30L * 24 * 60 * 60 * 1000,
            "90 DAYS" to 90L * 24 * 60 * 60 * 1000,
            "365 DAYS" to 365L * 24 * 60 * 60 * 1000,
            "ALL TIME" to Long.MAX_VALUE
        )
        // Fix row bounds across all report queries. New samples/restores are visible on the next refresh.
        val range = snapshot(readableDatabase).samples
        // Only recent session detail is materialised; rolling statistics stream directly from SQLite.
        val recentRows = readWindow(now - 30L * 24 * 60 * 60 * 1000, now, range)
        val latestReading = readableDatabase.rawQuery(
            "SELECT battery_percent,timestamp_ms FROM samples WHERE timestamp_ms BETWEEN ? AND ? AND id BETWEEN ? AND ? AND battery_percent BETWEEN 0 AND 100 ORDER BY id DESC LIMIT 1",
            windowArgs(0L, now, range)
        ).use { if (it.moveToFirst()) it.getInt(0) to it.getLong(1) else null }
        val latestPercent = latestReading?.first
        return buildString {
            val total = range.count
            appendLine("BATTERYSCOPE LOCAL HISTORY • RAW SAMPLES RETAINED UNTIL USER DELETES APP DATA")
            appendLine("Stored samples: " + total + " • target sample interval: 30 seconds")
            appendLine("Report excludes samples dated after its reference time; original records remain stored.")
            if (latestReading != null) appendLine("Last eligible battery level sample age: " + durationText(now - latestReading.second))
            appendLine("App foreground correlation is not a direct per-app electrical measurement.")
            appendLine("Counter-derived averages may reflect coarse gauge steps (45,000 µAh observed on RMX2161), not instantaneous current. Implausible counter values and interval jumps are excluded from interval averages; raw readings are retained.")
            for ((label, duration) in windows) {
                val from = if (duration == Long.MAX_VALUE) 0L else now - duration
                val stats = summariseSince(from, now, range, duration != Long.MAX_VALUE)
                appendLine()
                appendLine("=== " + label + " • " + stats.count + " samples ===")
                appendLine("Sampling continuity: " + stats.gapReport)
                appendLine("Charging: " + stats.chargeHours + " h • +" + stats.chargedPct + " percentage points • " + stats.chargeRate + " %/h")
                appendLine("Discharging: " + stats.dischargeHours + " h • -" + stats.dischargedPct + " percentage points • " + stats.dischargeRate + " %/h")
                appendLine("Charge-counter gain/loss: +" + stats.chargeGainMah + " / -" + stats.chargeLossMah + " mAh (Android estimate)")
                appendLine("Average battery-side charge rate from counter: " + stats.chargeCounterRateMa + " mA • discharge rate: " + stats.dischargeCounterRateMa + " mA (not USB input current)")
                appendLine("Counter interval duration: " + stats.counterCoverage)
                appendLine("Charging screen ON: " + stats.chargeOnHours + " h • +" + stats.chargeOnPct + "% • " + stats.chargeOnRate + " %/h")
                appendLine("Charging screen OFF: " + stats.chargeOffHours + " h • +" + stats.chargeOffPct + "% • " + stats.chargeOffRate + " %/h")
                appendLine("Charging rate comparison: screen ON " + stats.chargeOnRate + " %/h vs screen OFF " + stats.chargeOffRate + " %/h (historical averages)")
                appendLine("Discharging screen ON: " + stats.drainOnHours + " h • -" + stats.drainOnPct + "% • " + stats.drainOnRate + " %/h")
                appendLine("Discharging screen OFF: " + stats.drainOffHours + " h • -" + stats.drainOffPct + "% • " + stats.drainOffRate + " %/h")
                appendLine("Discharge rate comparison: screen ON " + stats.drainOnRate + " %/h vs screen OFF " + stats.drainOffRate + " %/h (historical averages)")
                if (latestPercent != null) {
                    appendLine("From last eligible recorded " + latestPercent + "%, estimated time to 100% at screen-ON charging rate: " +
                        forecastDuration(100.0 - latestPercent, stats.chargeOnRate) + " • screen-OFF rate: " +
                        forecastDuration(100.0 - latestPercent, stats.chargeOffRate))
                    appendLine("From last eligible recorded " + latestPercent + "%, estimated time to 0% at screen-ON discharge rate: " +
                        forecastDuration(latestPercent.toDouble(), stats.drainOnRate) + " • screen-OFF rate: " +
                        forecastDuration(latestPercent.toDouble(), stats.drainOffRate))
                }
                appendLine("Battery decline while Android reports CHARGING or both samples show a connected source: " + stats.pluggedDrainHours + " h • -" + stats.pluggedDrainPct + "% • " + stats.pluggedDrainRate + " %/h")
                appendLine("  Plugged decline screen ON: " + stats.pluggedDrainOnHours + " h • -" + stats.pluggedDrainOnPct + "% • " + stats.pluggedDrainOnRate + " %/h")
                appendLine("  Plugged decline screen OFF: " + stats.pluggedDrainOffHours + " h • -" + stats.pluggedDrainOffPct + "% • " + stats.pluggedDrainOffRate + " %/h")
                appendLine("Screen state samples: ON " + stats.screenOn + " • OFF " + stats.screenOff)
                appendLine("Foreground app time + coincident battery change (approx.):")
                if (stats.apps.isEmpty()) appendLine("  unavailable / Usage Access not granted / not yet recorded")
                else stats.apps.take(8).forEach { app ->
                    appendLine("  " + app.name + ": " + app.minutes + " min foreground • +" + app.chargePct + "% charging (counter " + app.counter(app.chargeCounterMah) + ") • -" + app.drainPct + "% unplugged (counter " + app.counter(app.drainCounterMah) + ") • -" + app.pluggedDrainPct + "% connected (counter " + app.counter(app.pluggedDrainCounterMah) + ")")
                }
            }
            appendLine()
            appendLine(resourceHistoryReport(now, range))
            appendLine()
            appendLine("=== MOST RECENT CHARGING SESSION (LAST 30 DAYS) ===")
            appendLine(sessionReport(recentRows, true))
            appendLine()
            appendLine("=== MOST RECENT DISCHARGING SESSION (LAST 30 DAYS) ===")
            appendLine(sessionReport(recentRows, false))
            appendLine()
            appendLine("=== RECENT CHARGING SESSIONS (LAST 30 DAYS) ===")
            appendLine(sessionHistoryReport(recentRows, true, 8))
            appendLine()
            appendLine("=== RECENT DISCHARGING SESSIONS (LAST 30 DAYS) ===")
            appendLine(sessionHistoryReport(recentRows, false, 8))
            appendLine()
            appendLine("Rate intervals outside 1–90 seconds and known reboot boundaries are excluded. Screen-state rates omit mixed-state endpoints; counter rates use valid counter intervals only. Threshold times are resolved to the sample interval, not exact hardware event times.")
            val last = readableDatabase.rawQuery(
                "SELECT timestamp_ms,battery_percent,battery_status,plug_type,foreground_package FROM samples WHERE timestamp_ms BETWEEN ? AND ? AND id BETWEEN ? AND ? ORDER BY id DESC LIMIT 1",
                windowArgs(0L, now, range)
            ).use { cursor ->
                if (!cursor.moveToFirst()) "No sample recorded yet"
                else "Last sample: " + cursor.getLong(0) + " • " +
                    (if (cursor.isNull(1)) "--" else cursor.getInt(1).toString() + "%") + " • " +
                    cursor.getString(2) + " • " + cursor.getString(3) + " • app=" + cursor.getString(4)
            }
            appendLine(last)
        }
    }

    private data class Row(
        val time: Long, val elapsed: Long, val pct: Int?, val state: String, val plug: String,
        val chargeCounter: Long?, val screen: Boolean, val app: String, val bootCount: Int?
    )
    private data class AppMetric(
        val name: String,
        val ms: Long,
        val charge: Double,
        val drain: Double,
        val pluggedDrain: Double,
        val chargeCounterMah: Double?,
        val drainCounterMah: Double?,
        val pluggedDrainCounterMah: Double?
    ) {
        val minutes: Long get() = ms / 60_000L
        val chargePct: String get() = String.format(Locale.US, "%.1f", charge)
        val drainPct: String get() = String.format(Locale.US, "%.1f", drain)
        val pluggedDrainPct: String get() = String.format(Locale.US, "%.1f", pluggedDrain)
        fun counter(value: Double?): String = value?.let { String.format(Locale.US, "%.1f mAh", it) } ?: "n/a"
    }
    private data class Bucket(var ms: Long = 0L, var pct: Double = 0.0, val counter: CounterRateAccumulator = CounterRateAccumulator())
    private data class Stats(
        val count: Int, val chargeHours: String, val dischargeHours: String,
        val chargedPct: String, val dischargedPct: String, val chargeRate: String, val dischargeRate: String,
        val chargeGainMah: String, val chargeLossMah: String,
        val chargeOnHours: String, val chargeOnPct: String, val chargeOnRate: String,
        val chargeOffHours: String, val chargeOffPct: String, val chargeOffRate: String,
        val drainOnHours: String, val drainOnPct: String, val drainOnRate: String,
        val drainOffHours: String, val drainOffPct: String, val drainOffRate: String,
        val pluggedDrainHours: String, val pluggedDrainPct: String, val pluggedDrainRate: String,
        val pluggedDrainOnHours: String, val pluggedDrainOnPct: String, val pluggedDrainOnRate: String,
        val pluggedDrainOffHours: String, val pluggedDrainOffPct: String, val pluggedDrainOffRate: String,
        val screenOn: Int, val screenOff: Int, val apps: List<AppMetric>,
        val chargeCounterRateMa: String, val dischargeCounterRateMa: String,
        val gapReport: String, val counterCoverage: String
    )

    private fun readWindow(from: Long, now: Long, range: SnapshotRange): List<Row> {
        val rows = mutableListOf<Row>()
        readableDatabase.rawQuery(
            "SELECT timestamp_ms,elapsed_realtime_ms,battery_percent,battery_status,plug_type,charge_counter_uah,screen_on,foreground_package,boot_count FROM samples WHERE timestamp_ms BETWEEN ? AND ? AND id BETWEEN ? AND ? ORDER BY id",
            windowArgs(from, now, range)
        ).use { c ->
            while (c.moveToNext()) rows += Row(
                c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getInt(2),
                c.getString(3) ?: "UNKNOWN", c.getString(4) ?: "NONE",
                if (c.isNull(5)) null else c.getLong(5), c.getInt(6) == 1, c.getString(7) ?: "",
                if (c.isNull(8)) null else c.getInt(8)
            )
        }
        return rows
    }

    private fun sampleIntervalMs(before: Row, after: Row): Long =
        RecorderTimeMath.intervalMs(
            before.time, before.elapsed, before.bootCount,
            after.time, after.elapsed, after.bootCount
        )

    private fun isRateInterval(before: Row, after: Row, interval: Long): Boolean =
        RecorderTimeMath.isRateInterval(interval, before.bootCount, after.bootCount)

    private fun observedDurationMs(rows: List<Row>): Long {
        var total = 0L
        for (i in 1 until rows.size) {
            val interval = sampleIntervalMs(rows[i - 1], rows[i])
            if (interval > 0L) total += interval
        }
        return total
    }

    private fun charging(row: Row) = row.state == "CHARGING"
    private fun discharging(row: Row) = BatteryStateMath.discharging(row.state, row.plug)

    private fun add(bucket: Bucket, duration: Long, pct: Double, counter: Long?) {
        bucket.ms += duration
        bucket.pct += pct
        bucket.counter.add(counter, duration)
    }

    private fun summariseSince(from: Long, now: Long, range: SnapshotRange, finiteWindow: Boolean): Stats {
        val charge = Bucket(); val drain = Bucket()
        val chargeOn = Bucket(); val chargeOff = Bucket()
        val drainOn = Bucket(); val drainOff = Bucket()
        val pluggedDrain = Bucket(); val pluggedDrainOn = Bucket(); val pluggedDrainOff = Bucket()
        val appTime = linkedMapOf<String, Long>()
        val appCharge = linkedMapOf<String, Double>()
        val appDrain = linkedMapOf<String, Double>()
        val appPluggedDrain = linkedMapOf<String, Double>()
        val appChargeCounterMah = linkedMapOf<String, Double>()
        val appDrainCounterMah = linkedMapOf<String, Double>()
        val appPluggedDrainCounterMah = linkedMapOf<String, Double>()
        var count = 0
        var screenOn = 0
        var screenOff = 0
        var previous: Row? = null
        var earliestWall: Long? = null
        var latestWall: Long? = null
        val gap = SamplingGapAccumulator(BatteryRecorderService.SAMPLE_INTERVAL_MS)
        readableDatabase.rawQuery(
            """SELECT timestamp_ms,elapsed_realtime_ms,battery_percent,battery_status,plug_type,charge_counter_uah,screen_on,foreground_package,boot_count
               FROM samples WHERE timestamp_ms BETWEEN ? AND ? AND id BETWEEN ? AND ? ORDER BY id""".trimIndent(),
            windowArgs(from, now, range)
        ).use { cursor ->
            val iTime = cursor.getColumnIndexOrThrow("timestamp_ms")
            val iElapsed = cursor.getColumnIndexOrThrow("elapsed_realtime_ms")
            val iPct = cursor.getColumnIndexOrThrow("battery_percent")
            val iState = cursor.getColumnIndexOrThrow("battery_status")
            val iPlug = cursor.getColumnIndexOrThrow("plug_type")
            val iCounter = cursor.getColumnIndexOrThrow("charge_counter_uah")
            val iScreen = cursor.getColumnIndexOrThrow("screen_on")
            val iApp = cursor.getColumnIndexOrThrow("foreground_package")
            val iBootCount = cursor.getColumnIndexOrThrow("boot_count")
            while (cursor.moveToNext()) {
                val after = Row(
                    cursor.getLong(iTime),
                    cursor.getLong(iElapsed),
                    if (cursor.isNull(iPct)) null else cursor.getInt(iPct),
                    cursor.getString(iState) ?: "UNKNOWN",
                    cursor.getString(iPlug) ?: "NONE",
                    if (cursor.isNull(iCounter)) null else cursor.getLong(iCounter),
                    cursor.getInt(iScreen) == 1,
                    cursor.getString(iApp) ?: "",
                    if (cursor.isNull(iBootCount)) null else cursor.getInt(iBootCount)
                )
                count++
                earliestWall = minOf(earliestWall ?: after.time, after.time)
                latestWall = maxOf(latestWall ?: after.time, after.time)
                if (after.screen) screenOn++ else screenOff++
                val before = previous
                if (before != null) {
                    val dt = sampleIntervalMs(before, after)
                    val eligible = isRateInterval(before, after, dt)
                    val knownReboot = before.bootCount != null && after.bootCount != null && before.bootCount != after.bootCount
                    gap.accept(dt, eligible, knownReboot)
                    if (eligible) {
                        val sameApp = RecorderTimeMath.sameForegroundApp(before.screen, after.screen, before.app, after.app)
                        if (sameApp) appTime[after.app] = (appTime[after.app] ?: 0L) + dt
                        if (before.pct != null && after.pct != null) {
                            val delta = (after.pct - before.pct).toDouble()
                            val counterDelta = CounterDelta.forInterval(before.chargeCounter, after.chargeCounter, dt)
                            val counterGain = CounterDelta.gain(counterDelta)
                            val counterLoss = CounterDelta.loss(counterDelta)
                            val bothPlugged = BatteryStateMath.connected(before.plug) && BatteryStateMath.connected(after.plug)
                            val androidReportsCharging = charging(before) && charging(after)
                            if ((bothPlugged || androidReportsCharging) && delta < 0.0) {
                                // Some ROMs report EXTRA_PLUGGED=NONE while BATTERY_STATUS remains CHARGING.
                                add(pluggedDrain, dt, -delta, counterLoss)
                                if (before.screen == after.screen) add(if (after.screen) pluggedDrainOn else pluggedDrainOff, dt, -delta, counterLoss)
                                if (sameApp) {
                                    appPluggedDrain[after.app] = (appPluggedDrain[after.app] ?: 0.0) - delta
                                    if (counterLoss != null) appPluggedDrainCounterMah[after.app] =
                                        (appPluggedDrainCounterMah[after.app] ?: 0.0) + counterLoss / 1000.0
                                }
                            } else if (charging(before) && charging(after) && delta >= 0.0) {
                                add(charge, dt, delta, counterGain)
                                if (before.screen == after.screen) add(if (after.screen) chargeOn else chargeOff, dt, delta, counterGain)
                                if (sameApp) {
                                    appCharge[after.app] = (appCharge[after.app] ?: 0.0) + delta
                                    if (counterGain != null) appChargeCounterMah[after.app] =
                                        (appChargeCounterMah[after.app] ?: 0.0) + counterGain / 1000.0
                                }
                            } else if (discharging(before) && discharging(after) && !bothPlugged && delta <= 0.0) {
                                add(drain, dt, -delta, counterLoss)
                                if (before.screen == after.screen) add(if (after.screen) drainOn else drainOff, dt, -delta, counterLoss)
                                if (sameApp) {
                                    appDrain[after.app] = (appDrain[after.app] ?: 0.0) - delta
                                    if (counterLoss != null) appDrainCounterMah[after.app] =
                                        (appDrainCounterMah[after.app] ?: 0.0) + counterLoss / 1000.0
                                }
                            }
                        }
                    }
                }
                previous = after
            }
        }
        fun hours(bucket: Bucket) = bucket.ms / 3_600_000.0
        fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
        fun duration(bucket: Bucket) = fmt(hours(bucket))
        fun rate(bucket: Bucket) = if (bucket.ms > 0L) fmt(bucket.pct / hours(bucket)) else "n/a"
        val firstWall = earliestWall
        val lastWall = latestWall
        val edges = if (firstWall == null || lastWall == null) "no eligible samples; coverage unavailable"
            else (if (finiteWindow) "leading unobserved edge " + ((firstWall - from).coerceAtLeast(0L) / 1000L) + "s • " else "") +
                "latest timestamp age " + ((now - lastWall).coerceAtLeast(0L) / 1000L) + "s"
        return Stats(
            count, duration(charge), duration(drain), fmt(charge.pct), fmt(drain.pct), rate(charge), rate(drain),
            if (charge.counter.observedMs > 0L) fmt(charge.counter.deltaUah / 1000.0) else "n/a", if (drain.counter.observedMs > 0L) fmt(drain.counter.deltaUah / 1000.0) else "n/a",
            duration(chargeOn), fmt(chargeOn.pct), rate(chargeOn), duration(chargeOff), fmt(chargeOff.pct), rate(chargeOff),
            duration(drainOn), fmt(drainOn.pct), rate(drainOn), duration(drainOff), fmt(drainOff.pct), rate(drainOff),
            duration(pluggedDrain), fmt(pluggedDrain.pct), rate(pluggedDrain),
            duration(pluggedDrainOn), fmt(pluggedDrainOn.pct), rate(pluggedDrainOn),
            duration(pluggedDrainOff), fmt(pluggedDrainOff.pct), rate(pluggedDrainOff),
            screenOn, screenOff,
            appTime.entries.sortedByDescending { it.value }.map {
                AppMetric(
                    it.key, it.value,
                    appCharge[it.key] ?: 0.0,
                    appDrain[it.key] ?: 0.0,
                    appPluggedDrain[it.key] ?: 0.0,
                    appChargeCounterMah[it.key],
                    appDrainCounterMah[it.key],
                    appPluggedDrainCounterMah[it.key]
                )
            },
            charge.counter.rateMa()?.let { fmt(it) } ?: "n/a",
            drain.counter.rateMa()?.let { fmt(it) } ?: "n/a",
            gap.gaps.toString() + " gaps >90s • estimated uncovered time within long gaps " + durationText(gap.missingMs) +
                " • longest long gap " + durationText(gap.longestMs) + " • average positive interval " +
                (gap.averageMs?.let { "$it ms" } ?: "n/a") +
                " • " + gap.intervalsAtLeastDoubleTarget + " intervals >=60s • " + gap.invalidRateIntervals +
                " rate-ineligible intervals (" + gap.nonPositiveIntervals + " nonpositive; " + gap.knownReboots + " known reboots)" +
                " • rate-eligible adjacent interval duration " + (gap.validIntervalMs / 1000L) + "s • " + edges +
                " (not a continuous coverage percentage; edge times use wall timestamps; gap causes unknown)",
            "charge " + (charge.counter.observedMs / 1000L) + "s accepted of " + (charge.ms / 1000L) +
                "s classified • discharge " + (drain.counter.observedMs / 1000L) + "s accepted of " + (drain.ms / 1000L) +
                "s classified • plugged decline " + (pluggedDrain.counter.observedMs / 1000L) + "s accepted of " +
                (pluggedDrain.ms / 1000L) + "s classified"
        )
    }

    private fun sessionHistoryReport(rows: List<Row>, wantCharge: Boolean, limit: Int): String {
        if (rows.isEmpty()) return "No samples recorded."
        fun matches(row: Row) = if (wantCharge) charging(row) else discharging(row)
        val sessions = mutableListOf<List<Row>>()
        var current = mutableListOf<Row>()
        for (row in rows) {
            val firstFull = wantCharge && row.state == "FULL" && BatteryStateMath.connected(row.plug)
            if (matches(row)) {
                if (current.isNotEmpty()) {
                    val gap = sampleIntervalMs(current.last(), row)
                    if (!isRateInterval(current.last(), row, gap)) {
                        sessions += current
                        current = mutableListOf()
                    }
                }
                current += row
            } else if (firstFull) {
                if (current.isNotEmpty()) {
                    val gap = sampleIntervalMs(current.last(), row)
                    if (isRateInterval(current.last(), row, gap)) current += row
                    sessions += current
                    current = mutableListOf()
                }
            } else if (current.isNotEmpty()) {
                sessions += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) sessions += current
        if (sessions.isEmpty()) return "No recorded sessions."
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return sessions.takeLast(limit).asReversed().joinToString("\n") { run ->
            val first = run.first()
            val last = run.last()
            val start = formatter.format(Date(first.time))
            if (run.size < 2 || first.pct == null || last.pct == null) {
                start + " • one sample only; elapsed time and rate unavailable"
            } else {
                val elapsed = observedDurationMs(run).coerceAtLeast(0L)
                val delta = last.pct - first.pct
                val rate = if (elapsed > 0L) delta.toDouble() / (elapsed / 3_600_000.0) else 0.0
                val onMs = run.zipWithNext().sumOf { (a, b) ->
                    val dt = sampleIntervalMs(a, b)
                    if (isRateInterval(a, b, dt) && a.screen && b.screen) dt else 0L
                }
                val offMs = run.zipWithNext().sumOf { (a, b) ->
                    val dt = sampleIntervalMs(a, b)
                    if (isRateInterval(a, b, dt) && !a.screen && !b.screen) dt else 0L
                }
                val counter = CounterDelta.between(first.chargeCounter, last.chargeCounter)?.div(1000.0)
                val state = first.pct.toString() + "%→" + last.pct + "%"
                val counterText = if (counter == null) "endpoint mAh n/a" else String.format(Locale.US, "%+.1f mAh endpoint change (not interval-validated)", counter)
                start + " • " + state + " • " + durationText(elapsed) +
                    " • " + String.format(Locale.US, "%+.2f pp/h", rate) +
                    " • screen ON " + durationText(onMs) +
                    " / OFF " + durationText(offMs) + " / mixed " + durationText((elapsed - onMs - offMs).coerceAtLeast(0L)) +
                    " • " + counterText +
                    if (wantCharge && last.state == "FULL") " • full" else ""
            }
        }
    }

    private fun sessionReport(rows: List<Row>, wantCharge: Boolean): String {
        if (rows.isEmpty()) return "No samples recorded."
        fun matches(row: Row) = if (wantCharge) charging(row) else discharging(row)
        var end = rows.lastIndex
        while (end >= 0 && !matches(rows[end])) end--
        if (end < 0) return "No recorded session."
        // Include the first FULL sample after a charging run, but not later full/plugged idle time.
        if (wantCharge && end < rows.lastIndex) {
            val next = rows[end + 1]
            val gap = sampleIntervalMs(rows[end], next)
            if (next.state == "FULL" && BatteryStateMath.connected(next.plug) &&
                isRateInterval(rows[end], next, gap)) end++
        }
        var start = end
        while (start > 0) {
            val gap = sampleIntervalMs(rows[start - 1], rows[start])
            val prev = rows[start - 1]
            if (!matches(prev) || !isRateInterval(prev, rows[start], gap)) break
            start--
        }
        val run = rows.subList(start, end + 1)
        if (run.size < 2) return "Session has only one sample; rate and duration are not yet measurable."
        val first = run.first(); val last = run.last()
        val initial = first.pct ?: return "Battery percentage unavailable."
        val current = last.pct ?: return "Battery percentage unavailable."
        val elapsed = observedDurationMs(run)
        if (elapsed <= 0L) return "Session duration unavailable."
        val change = current - initial
        val directionMatches = if (wantCharge) change > 0 else change < 0
        val rate = if (directionMatches) kotlin.math.abs(change.toDouble()) / (elapsed / 3_600_000.0) else 0.0
        val counterDelta = CounterDelta.between(first.chargeCounter, last.chargeCounter)
        val sessionCounter = CounterRateAccumulator()
        var onMs = 0L
        var offMs = 0L
        var onSocChange = 0.0
        var offSocChange = 0.0
        var mixedMs = 0L
        var onSocMs = 0L
        var offSocMs = 0L
        val onCounter = CounterRateAccumulator()
        val offCounter = CounterRateAccumulator()
        for ((before, after) in run.zipWithNext()) {
            val dt = sampleIntervalMs(before, after)
            if (!isRateInterval(before, after, dt)) continue
            val deltaCounter = CounterDelta.forInterval(before.chargeCounter, after.chargeCounter, dt)
            sessionCounter.add(deltaCounter, dt)
            if (before.screen != after.screen) {
                mixedMs += dt
                continue
            }
            val screenBucketOn = after.screen
            if (screenBucketOn) onMs += dt else offMs += dt
            val deltaSoc = if (before.pct != null && after.pct != null) after.pct - before.pct else null
            val socDirectionValid = deltaSoc != null && if (wantCharge) deltaSoc >= 0 else deltaSoc <= 0
            if (socDirectionValid) {
                if (screenBucketOn) {
                    onSocChange += kotlin.math.abs(deltaSoc!!.toDouble()); onSocMs += dt
                } else {
                    offSocChange += kotlin.math.abs(deltaSoc!!.toDouble()); offSocMs += dt
                }
            }
            val directionCounter = deltaCounter?.takeIf { if (wantCharge) it >= 0L else it <= 0L }
            if (screenBucketOn) onCounter.add(directionCounter, dt) else offCounter.add(directionCounter, dt)
        }
        val onRate = if (onSocMs > 0L) onSocChange / (onSocMs / 3_600_000.0) else null
        val offRate = if (offSocMs > 0L) offSocChange / (offSocMs / 3_600_000.0) else null
        val onCounterRate = onCounter.rateMa()
        val offCounterRate = offCounter.rateMa()
        val out = StringBuilder()
        out.appendLine("Observed window: " + first.time + " → " + last.time)
        out.appendLine("Level: " + initial + "% → " + current + "% • elapsed: " + durationText(elapsed))
        out.appendLine("Average rate: " + String.format(Locale.US, "%.2f", rate) + " percentage points/hour")
        if (!directionMatches) out.appendLine("Battery percentage did not move in the expected direction; no session forecast is valid.")
        if (counterDelta != null) out.appendLine("Charge-counter endpoint delta (not interval-validated): " + String.format(Locale.US, "%+.1f mAh", counterDelta / 1000.0))
        val measuredRate = sessionCounter.rateMa()
        if (measuredRate != null) out.appendLine("Charge-counter observed interval rate: " + String.format(Locale.US, "%+.1f mA", measuredRate) + " • accepted interval time " + (sessionCounter.observedMs / 1000L) + "s of " + (elapsed / 1000L) + "s (quantized Android estimate)")
        else out.appendLine("Charge-counter rate: unavailable")
        out.appendLine("Screen ON: " + durationText(onMs) + " • screen OFF: " + durationText(offMs) + " • mixed endpoints: " + durationText(mixedMs))
        val onRateText = onRate?.let { String.format(Locale.US, "%.2f pp/h", it) } ?: "n/a"
        val offRateText = offRate?.let { String.format(Locale.US, "%.2f pp/h", it) } ?: "n/a"
        val onCounterText = onCounterRate?.let { String.format(Locale.US, "%+.0f mA battery-side", it) } ?: "n/a"
        val offCounterText = offCounterRate?.let { String.format(Locale.US, "%+.0f mA battery-side", it) } ?: "n/a"
        val remaining = if (wantCharge) (100 - current).coerceAtLeast(0).toDouble() else current.coerceAtLeast(0).toDouble()
        val thresholdName = if (wantCharge) "100%" else "0%"
        val onForecast = when {
            !directionMatches -> "not projected: session direction inconsistent"
            remaining <= 0.0 -> "already at threshold"
            onRate == null -> "not enough screen-ON data"
            else -> forecastDuration(remaining, String.format(Locale.US, "%.6f", onRate))
        }
        val offForecast = when {
            !directionMatches -> "not projected: session direction inconsistent"
            remaining <= 0.0 -> "already at threshold"
            offRate == null -> "not enough screen-OFF data"
            else -> forecastDuration(remaining, String.format(Locale.US, "%.6f", offRate))
        }
        out.appendLine("Screen ON (display-on proxy): " + onRateText + " • " + onCounterText +
            " • time to " + thresholdName + ": " + onForecast)
        out.appendLine("Screen OFF (display-off proxy): " + offRateText + " • " + offCounterText +
            " • time to " + thresholdName + ": " + offForecast)
        val thresholds = if (wantCharge) listOf(50, 80, 90, 100) else listOf(80, 50, 20, 10, 0)
        out.appendLine(if (wantCharge) "Time from first recorded sample in this session to charge thresholds:" else "Time from first recorded sample in this session to discharge thresholds:")
        for (threshold in thresholds) {
            val crossing = thresholdTime(run, threshold, wantCharge)
            val status = when {
                (wantCharge && initial >= threshold) || (!wantCharge && initial <= threshold) -> "already at threshold at session start"
                crossing != null -> durationText(crossing) + " observed"
                !directionMatches -> if (wantCharge) "not projected: battery level did not increase during this charging-state segment" else "not projected: battery level did not decrease during this discharging-state segment"
                rate > 0.0 && ((wantCharge && current < threshold) || (!wantCharge && current > threshold)) -> {
                    val remainingMs = BatteryRateMath.forecastDurationMs(kotlin.math.abs(threshold - current).toDouble(), rate)
                    if (remainingMs == null || remainingMs > Long.MAX_VALUE - elapsed) "not projected: duration unavailable"
                    else durationText(elapsed + remainingMs) + " estimated total from session start; " +
                        durationText(remainingMs) + " estimated remaining from last sample"
                }
                else -> "not reached / insufficient data"
            }
            out.appendLine("  " + threshold + "%: " + status)
        }
        if (wantCharge && last.state == "FULL") out.appendLine("Android full state observed at final sample.")
        return out.toString().trim()
    }

    /** Returns elapsed duration from the first row, not an absolute wall/boot timestamp. */
    private fun thresholdTime(rows: List<Row>, threshold: Int, upward: Boolean): Long? {
        var elapsedFromStart = 0L
        for (i in 1 until rows.size) {
            elapsedFromStart += sampleIntervalMs(rows[i - 1], rows[i]).coerceAtLeast(0L)
            val before = rows[i - 1].pct ?: continue
            val after = rows[i].pct ?: continue
            if (upward && before < threshold && after >= threshold) return elapsedFromStart
            if (!upward && before > threshold && after <= threshold) return elapsedFromStart
        }
        return null
    }

    private fun forecastDuration(remainingPercentagePoints: Double, rateText: String): String {
        val rate = rateText.toDoubleOrNull()
        if (rate == null || rate <= 0.0 || remainingPercentagePoints <= 0.0) return "not enough matching history"
        val ms = BatteryRateMath.forecastDurationMs(remainingPercentagePoints, rate) ?: return "not enough matching history"
        return durationText(ms) + " estimated from historical average"
    }

    private fun durationText(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60_000L
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val remaining = minutes % 60
        return if (days > 0) days.toString() + "d " + hours + "h " + remaining + "m"
        else if (hours > 0) hours.toString() + "h " + remaining + "m" else remaining.toString() + "m"
    }

    private data class SnapshotRange(val minId: Long, val maxId: Long, val count: Long)
    private data class HistorySnapshot(val samples: SnapshotRange, val events: SnapshotRange)

    private fun windowArgs(from: Long, now: Long, range: SnapshotRange): Array<String> =
        arrayOf(from.toString(), now.toString(), range.minId.toString(), range.maxId.toString())

    /** One SQL statement captures both immutable row ranges at a single point in time.
     * Recorder inserts append above the upper bound; restore prepends below the lower bound.
     */
    private fun snapshot(db: SQLiteDatabase): HistorySnapshot =
        db.rawQuery("""SELECT
            (SELECT COALESCE(MIN(id),1) FROM samples),(SELECT COALESCE(MAX(id),0) FROM samples),(SELECT COUNT(*) FROM samples),
            (SELECT COALESCE(MIN(id),1) FROM events),(SELECT COALESCE(MAX(id),0) FROM events),(SELECT COUNT(*) FROM events)
        """.trimIndent(), null).use { c ->
            check(c.moveToFirst())
            HistorySnapshot(SnapshotRange(c.getLong(0),c.getLong(1),c.getLong(2)), SnapshotRange(c.getLong(3),c.getLong(4),c.getLong(5)))
        }

    private fun snapshotCursor(db: SQLiteDatabase, table: String, range: SnapshotRange, columns: List<HistoryBackup.Column>): Cursor {
        val names = columns.joinToString(",") { it.name }
        return db.rawQuery("SELECT $names FROM $table WHERE id BETWEEN ? AND ? ORDER BY id",
            arrayOf(range.minId.toString(), range.maxId.toString()))
    }

    private fun backupRows(cursor: Cursor, columns: List<HistoryBackup.Column>): Sequence<List<String?>> = sequence {
        while (cursor.moveToNext()) yield(columns.mapIndexed { i, column ->
            if (cursor.isNull(i)) null else when (column.type) {
                HistoryBackup.Type.INTEGER -> cursor.getLong(i).toString()
                HistoryBackup.Type.REAL -> cursor.getDouble(i).toString()
                HistoryBackup.Type.TEXT -> cursor.getString(i)
            }
        })
    }

    internal fun exportBackup(output: OutputStream): HistoryBackup.Counts {
        val db = readableDatabase
        val snap = snapshot(db)
        val counts = HistoryBackup.Counts(snap.samples.count, snap.events.count)
        snapshotCursor(db,"samples",snap.samples,HistoryBackup.sampleColumns).use { samples ->
            snapshotCursor(db,"events",snap.events,HistoryBackup.eventColumns).use { events ->
                HistoryBackup.write(output, counts,
                    backupRows(samples,HistoryBackup.sampleColumns), backupRows(events,HistoryBackup.eventColumns))
            }
        }
        return counts
    }

    /** Transactional non-destructive restore of earlier history into this installation. */
    internal fun restoreBackup(input: InputStream): HistoryBackup.Counts =
        restoreHistory { header, sample, event -> HistoryBackup.read(input, header, sample, event) }

    /** Validate and count staged legacy CSV before the database transaction. */
    internal fun restoreLegacyCsv(file: File): HistoryBackup.Counts {
        val expected = file.reader(Charsets.UTF_8).use { LegacyHistoryCsv.scan(it, {}, {}) }
        return restoreHistory { header, sample, event ->
            header(expected)
            val actual = file.reader(Charsets.UTF_8).use { LegacyHistoryCsv.scan(it, sample, event) }
            check(actual == expected) { "CSV record count changed" }
            actual
        }
    }

    private fun restoreHistory(
        read: ((HistoryBackup.Counts) -> Unit, (List<String?>) -> Unit, (List<String?>) -> Unit) -> HistoryBackup.Counts
    ): HistoryBackup.Counts {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val earliest = db.rawQuery("SELECT MIN(t) FROM (SELECT MIN(timestamp_ms) AS t FROM samples UNION ALL SELECT MIN(timestamp_ms) AS t FROM events)", null).use {
                check(it.moveToFirst()); if (it.isNull(0)) null else it.getLong(0)
            }
            fun minId(table: String): Long? = db.rawQuery("SELECT MIN(id) FROM $table",null).use {
                check(it.moveToFirst()); if (it.isNull(0)) null else it.getLong(0)
            }
            var sampleId = 1L
            var eventId = 1L
            fun insert(table: String, id: Long, columns: List<HistoryBackup.Column>, row: List<String?>) {
                HistoryBackup.requireEarlier(requireNotNull(row.first()).toLong(), earliest)
                val values = ContentValues().apply {
                    put("id", id)
                    columns.forEachIndexed { index, column ->
                        val value = row[index]
                        if (value == null) putNull(column.name) else when(column.type) {
                            HistoryBackup.Type.INTEGER -> put(column.name, value.toLong())
                            HistoryBackup.Type.REAL -> put(column.name, value.toDouble())
                            HistoryBackup.Type.TEXT -> put(column.name, value)
                        }
                    }
                }
                db.insertOrThrow(table, null, values)
            }
            val counts = read({ header ->
                require(header.total > 0L) { "Backup has no history" }
                sampleId = HistoryBackup.firstImportedId(minId("samples"), header.samples)
                eventId = HistoryBackup.firstImportedId(minId("events"), header.events)
            }, { row -> insert("samples",sampleId++,HistoryBackup.sampleColumns,row) },
               { row -> insert("events",eventId++,HistoryBackup.eventColumns,row) })
            db.setTransactionSuccessful()
            return counts
        } finally {
            db.endTransaction()
        }
    }

    /** Returns the number of actual data records, excluding the header. */
    fun exportCsv(writer: Writer): Long {
        writer.write(RecorderCsv.encodeRow(RecorderCsv.header))
        var count = 0L
        val db = readableDatabase
        val snap = snapshot(db)
        db.rawQuery("SELECT * FROM samples WHERE id BETWEEN ? AND ? ORDER BY timestamp_ms,id", arrayOf(snap.samples.minId.toString(),snap.samples.maxId.toString())).use { samples ->
            db.rawQuery("SELECT timestamp_ms,event_type,details FROM events WHERE id BETWEEN ? AND ? ORDER BY timestamp_ms,id", arrayOf(snap.events.minId.toString(),snap.events.maxId.toString())).use { events ->
                val timestampColumn = samples.getColumnIndexOrThrow("timestamp_ms")
                var hasSample = samples.moveToFirst()
                var hasEvent = events.moveToFirst()
                while (hasSample || hasEvent) {
                    val useSample = hasSample && (!hasEvent || samples.getLong(timestampColumn) <= events.getLong(0))
                    if (useSample) {
                        val row = RecorderCsv.sampleRow(
                            samples.getLong(timestampColumn).toString(),
                            value(samples, "elapsed_realtime_ms"),
                            RecorderCsv.sampleFields.map { value(samples, it) }
                        )
                        writer.write(RecorderCsv.encodeRow(row))
                        hasSample = samples.moveToNext()
                    } else {
                        val row = RecorderCsv.eventRow(events.getLong(0).toString(), events.getString(1), events.getString(2) ?: "")
                        writer.write(RecorderCsv.encodeRow(row))
                        hasEvent = events.moveToNext()
                    }
                    count++
                }
            }
        }
        check(count == snap.samples.count + snap.events.count) { "CSV snapshot row-count mismatch" }
        writer.flush()
        return count
    }
    private fun value(c: Cursor, column: String): String {
        val index = c.getColumnIndexOrThrow(column)
        return if (c.isNull(index)) "" else c.getString(index) ?: ""
    }

}
