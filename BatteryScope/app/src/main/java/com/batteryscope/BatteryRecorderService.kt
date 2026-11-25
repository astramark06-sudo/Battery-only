package com.batteryscope

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/** Automatically requested persistent foreground recorder. No activity lifecycle owns this sampler. */
class BatteryRecorderService : Service() {
    private lateinit var handler: Handler
    private lateinit var worker: HandlerThread
    private lateinit var db: RecorderDatabase
    private val databaseLock = Any()
    private var databaseAvailable = true
    private lateinit var batteryManager: BatteryManager
    @Volatile private var batteryIntent: Intent? = null
    private val chargerLabels = ChargerLabelTracker()
    private var lastSampleState: String? = null
    private var lastSamplePlug: String? = null
    private var lastBatteryPercentStatus: String? = null
    private val lastBatteryPropertyStatus = mutableMapOf<Int, String>()
    private var lastCurrentMeasurementQuality: String? = null
    private var lastVoltageMeasurementQuality: String? = null
    private lateinit var inventoryWorker: HandlerThread
    private lateinit var inventoryHandler: Handler
    private val inventoryHandoff = InventoryHandoff<Map<String, String>>(SYSFS_INTERVAL_MS)
    private var nextDeadlineElapsedMs = 0L
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var lastNotificationState: String? = null
    private var receiverRegistered = false
    @Volatile private var recorderRunning = false
    private var samplingWakeLock: PowerManager.WakeLock? = null
    @Volatile private var sampleHealth = RecorderHealth(SystemClock.elapsedRealtime())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    batteryIntent = intent
                    chargerLabels.observeSource(plugName(intent))
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    chargerLabels.boundary()
                    event("POWER_CONNECTED", "Android power-connected broadcast")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    chargerLabels.boundary()
                    event("POWER_DISCONNECTED", "Android power-disconnected broadcast")
                }
                Intent.ACTION_SCREEN_ON -> event("SCREEN_ON", "Display became interactive")
                Intent.ACTION_SCREEN_OFF -> event("SCREEN_OFF", "Display became non-interactive")
                Intent.ACTION_BATTERY_LOW -> event("BATTERY_LOW", "Android battery-low broadcast")
                Intent.ACTION_BATTERY_OKAY -> event("BATTERY_OKAY", "Battery-low condition cleared")
            }
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (!recorderRunning) return
            val started = SystemClock.elapsedRealtime()
            // Handler uses uptime; elapsed-realtime deadlines also reflect suspend.
            if (started < nextDeadlineElapsedMs) {
                if (!handler.postDelayed(this, nextDeadlineElapsedMs - started))
                    recordOutcome(started, SystemClock.elapsedRealtime(), "Recorder worker rejected next sample")
                return
            }
            var failure: String? = null
            try { sample(started) }
            catch (t: Exception) { failure = t.javaClass.simpleName + ": " + (t.message ?: "unknown") }
            catch (fatal: Error) {
                recordOutcome(started, SystemClock.elapsedRealtime(), "Fatal sample failure: " + fatal.javaClass.simpleName)
                throw fatal // Never claim a successful sample or retry a fatal process error.
            }
            val finished = SystemClock.elapsedRealtime()
            recordOutcome(started, finished, failure)
            failure?.let { event("SAMPLE_ERROR", it) }
            if (recorderRunning) {
                val next = RecorderCadence.nextSlot(nextDeadlineElapsedMs, SystemClock.elapsedRealtime(), SAMPLE_INTERVAL_MS)
                nextDeadlineElapsedMs = next.elapsedMs
                sampleHealth = sampleHealth.skipped(next.skipped)
                val delay = (next.elapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                if (!handler.postDelayed(this, delay)) {
                    recordOutcome(started, SystemClock.elapsedRealtime(), "Recorder worker rejected next sample")
                }
            }
        }
    }

    // Main-thread observation of stale state also works while a worker is blocked.
    private val notificationWatch = object : Runnable {
        override fun run() {
            if (!recorderRunning) return
            refreshRecorderNotification()
            notificationHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = RecorderDatabase(this)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        worker = HandlerThread("BatteryScopeRecorder").apply { start() }
        handler = Handler(worker.looper)
        inventoryWorker = HandlerThread("BatteryScopeInventory").apply { start() }
        inventoryHandler = Handler(inventoryWorker.looper)
        batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        chargerLabels.observeSource(plugName(batteryIntent))
        createChannel()
        registerEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Honor an explicit pause even if a delayed start/reconfigure or sticky restart arrives.
        if (RecordingPreference.isPaused(prefs)) {
            recorderRunning = false
            if (activeRecorder === this) activeRecorder = null
            handler.removeCallbacks(sampler)
            notificationHandler.removeCallbacksAndMessages(null)
            releaseSamplingWakeLock()
            // A raced startForegroundService request must still meet the foreground deadline.
            try { startAsForeground() }
            catch (e: Exception) {
                prefs.edit().putString(KEY_START_FAILURE,"Paused start: " + e.javaClass.simpleName).apply()
            } finally { stopSelf(startId) }
            return START_NOT_STICKY
        }
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
                nextDeadlineElapsedMs = SystemClock.elapsedRealtime()
                sampleHealth = RecorderHealth(nextDeadlineElapsedMs)
                recorderRunning = true
                activeRecorder = this
                notificationHandler.post(notificationWatch)
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
            if (activeRecorder === this) activeRecorder = null
            notificationHandler.removeCallbacksAndMessages(null)
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
        val state = sampleHealth.state(SystemClock.elapsedRealtime())
        val notification = recorderNotification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        lastNotificationState = state
    }

    private fun refreshRecorderNotification() {
        if (activeRecorder !== this || !recorderRunning) return
        val state = sampleHealth.state(SystemClock.elapsedRealtime())
        if (lastNotificationState == state) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, recorderNotification(state))
            lastNotificationState = state
        } catch (_: Exception) { /* Live health still remains available in the UI. */ }
    }

    private fun recorderNotification(health: String): Notification {
        val requested = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_KEEP_CPU_AWAKE, true)
        val held = samplingWakeLock?.isHeld == true
        val status = when {
            held -> "30-second target • CPU wake lock held • adds app drain"
            requested -> "30-second target • wake lock requested but not held"
            else -> "30-second target • wake lock off • screen-off gaps possible"
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (health) {
            "DEGRADED" -> "BatteryScope: sample failed; retrying"
            "STALE" -> "BatteryScope: recording not confirmed"
            "RECENT_SAMPLE_SAVED" -> "BatteryScope: last sample saved"
            else -> "BatteryScope: waiting for first sample"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    /** Independent of SQLite: an unwritable DB must not hide sample failures. */
    private fun recordOutcome(started: Long, finished: Long, failure: String?) {
        sampleHealth = sampleHealth.completed(started, finished, failure?.take(300))
        if (activeRecorder !== this || !recorderRunning) return
        try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val wall = System.currentTimeMillis()
            val edit = prefs.edit().putLong(KEY_LAST_ATTEMPT_WALL, wall)
                .putLong(KEY_LAST_ATTEMPT_DURATION, finished - started)
            if (failure == null) edit.putLong(KEY_LAST_SUCCESS_WALL, wall)
            else edit.putString(KEY_LAST_SAMPLE_FAILURE, failure.take(300))
                .putLong(KEY_LAST_SAMPLE_FAILURE_AT, wall)
            // Best effort on disk; process-local health remains available if persistence fails.
            edit.apply()
        } catch (_: Exception) { /* Persistence must not stop the next sample. */ }
        notificationHandler.post { refreshRecorderNotification() }
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

    private fun plugName(intent: Intent?): String {
        val plugCode = BatteryBroadcastMetadata.int(intent,BatteryManager.EXTRA_PLUGGED)
        return when (plugCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            8 -> "DOCK"
            null, -1 -> "UNKNOWN"
            else -> if (plugCode == 0) "NONE" else if (plugCode > 0) "OTHER_" + plugCode else "UNKNOWN"
        }
    }

    private fun sample(elapsed: Long) {
        val now = System.currentTimeMillis()
        val inventory = inventoryHandoff.readyFor(elapsed)
        requestInventory(elapsed)
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
        val voltage = BatteryBroadcastMetadata.int(intent,BatteryManager.EXTRA_VOLTAGE)
        val temperature = BatteryBroadcastMetadata.int(intent,BatteryManager.EXTRA_TEMPERATURE)
        val broadcastMetadata = BatteryBroadcastMetadata.capture(intent)
        val broadcastReadElapsed = SystemClock.elapsedRealtime()
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val plug = plugName(intent)
        val chargerLabel = chargerLabels.snapshot(plug,elapsed)
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
        val voltageQuality = BatteryMeasurementQuality.voltage(voltage).status
        if (voltageQuality != lastVoltageMeasurementQuality) {
            event("BATTERY_VOLTAGE_QUALITY", "quality=" + voltageQuality + "; raw_extra_voltage=" + (voltage?.toString() ?: "UNAVAILABLE") + "; expected_unit=mV")
            lastVoltageMeasurementQuality = voltageQuality
        }
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val inventoryJson = inventory?.let {
            JSONObject(it.value)
                .put("@inventory_request_started_elapsed_ms", it.startedElapsedMs)
                .put("@inventory_request_finished_elapsed_ms", it.finishedElapsedMs)
                .put("@inventory_associated_sample_elapsed_ms", elapsed)
                .put("@inventory_associated_sample_wall_ms", now)
        }
        val usbAssessment = inventory?.let { KernelPresentation.usb(it.value, elapsed, plug, SYSFS_INTERVAL_MS) }
        if (inventoryJson != null && usbAssessment != null) {
            inventoryJson.put("@usb_candidate_quality", usbAssessment.status)
            usbAssessment.candidate?.let { inventoryJson.put("@usb_candidate_source", it.source) }
        }
        val usb = usbAssessment?.candidate
        val row = ContentValues().apply {
            put("timestamp_ms", now)
            put("elapsed_realtime_ms", elapsed)
            if (bootCount != null) put("boot_count", bootCount)
            if (percent != null) put("battery_percent", percent)
            put("battery_status", status)
            put("plug_type", plug)
            if (voltage != null) put("battery_voltage_mv", voltage)
            BatteryBroadcastMetadata.advertised(intent,"max_charging_current")?.let { put("max_charging_current_ua",it) }
            BatteryBroadcastMetadata.advertised(intent,"max_charging_voltage")?.let { put("max_charging_voltage_uv",it) }
            BatteryBroadcastMetadata.bool(intent,"present")?.let { put("battery_present",if (it) 1 else 0) }
            BatteryBroadcastMetadata.int(intent,"invalid_charger")?.let { put("invalid_charger_state",it) }
            BatteryBroadcastMetadata.cycles(intent)?.let { put("battery_cycle_count",it) }
            BatteryBroadcastMetadata.temperatureC(temperature)?.let { put("temperature_c",it) }
            if (current != null) put("battery_current_ua", current)
            if (average != null) put("battery_average_current_ua", average)
            if (charge != null) put("charge_counter_uah", charge)
            if (energy != null) put("energy_counter_nwh", energy)
            put("battery_health", health)
            put("technology", intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "NOT_REPORTED")
            put("screen_on", if (screenOn) 1 else 0)
            // Keep legacy columns for compatible export/restore; no new app tracking.
            put("foreground_package","")
            put("usage_access",0)
            // Use the existing extensible JSON field, retaining the v5 schema and
            // portable backup/CSV columns. Metadata-only rows are not inventories.
            val metadata = inventoryJson ?: JSONObject()
            metadata.put("@battery_raw_v1",broadcastMetadata)
            metadata.put("@battery_broadcast_read_elapsed_ms",broadcastReadElapsed)
            metadata.put("@battery_property_status",JSONObject(lastBatteryPropertyStatus.mapKeys { it.key.toString() }))
            chargerLabel?.let { metadata.put(ChargerLabelJson.KEY,ChargerLabelJson.encode(it)) }
            metadata.put("@recorder/sample_collection_finished_elapsed_ms",SystemClock.elapsedRealtime())
            put("power_supply_json",metadata.toString())
            if (usb != null) {
                put("usb_voltage_candidate_uv", usb.voltageUv)
                put("usb_current_candidate_ua", usb.currentUa)
            }
        }
        insertSample(row)
        // A failed insert leaves this exact snapshot pending for the next sample.
        if (inventory != null) inventoryHandoff.committed(inventory)
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

    private fun requestInventory(nowElapsedMs: Long) {
        if (!inventoryHandoff.request(nowElapsedMs)) return
        if (!inventoryHandler.post {
            var completed = false
            try {
                if (recorderRunning) {
                    val started = SystemClock.elapsedRealtime()
                    val fields = HardwareSnapshotCache.snapshot(applicationContext, SYSFS_INTERVAL_MS)
                    val finished = SystemClock.elapsedRealtime()
                    if (recorderRunning) {
                        inventoryHandoff.complete(fields, started, finished)
                        completed = true
                    }
                }
            } catch (e: Exception) {
                if (recorderRunning) event("INVENTORY_ERROR", e.javaClass.simpleName)
            } finally {
                if (!completed) inventoryHandoff.failed()
            }
        }) {
            inventoryHandoff.failed()
            event("INVENTORY_ERROR", "WORKER_QUEUE_REJECTED")
        }
    }

    private fun insertSample(values: ContentValues) {
        synchronized(databaseLock) {
            check(databaseAvailable) { "Recorder database is not available" }
            db.insertSample(values) // Propagate to sampler health even when diagnostic inserts also fail.
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
            if (!handler.post { writeEventOnWorker(timestampMs, type, details) }) {
                getSharedPreferences(PREFS,MODE_PRIVATE).edit()
                    .putString(KEY_LAST_EVENT_FAILURE,"Event worker rejected " + type)
                    .putLong(KEY_LAST_EVENT_FAILURE_AT,System.currentTimeMillis()).apply()
            }
        } else {
            writeEventOnWorker(timestampMs, type, details)
        }
    }

    private fun writeEventOnWorker(timestampMs: Long, type: String, details: String) {
        synchronized(databaseLock) {
            if (!databaseAvailable) return
            try {
                db.insertEvent(timestampMs, type, details)
            } catch (e: Exception) {
                // Store the failure separately from the database, where possible.
                if (activeRecorder === this) {
                    try {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_LAST_EVENT_FAILURE, e.javaClass.simpleName)
                            .putLong(KEY_LAST_EVENT_FAILURE_AT, System.currentTimeMillis()).apply()
                    } catch (_: Exception) { /* Best effort only; no recursive diagnostic write. */ }
                }
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
        chargerLabels.boundary()
        if (activeRecorder === this) activeRecorder = null
        notificationHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(sampler)
        if (::inventoryHandler.isInitialized) inventoryHandler.removeCallbacksAndMessages(null)
        if (::inventoryWorker.isInitialized) inventoryWorker.quitSafely()
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
        const val KEY_LAST_SUCCESS_WALL = "last_sample_commit_wall"
        const val KEY_LAST_ATTEMPT_WALL = "last_sample_attempt_wall"
        const val KEY_LAST_ATTEMPT_DURATION = "last_sample_attempt_duration"
        const val KEY_LAST_SAMPLE_FAILURE = "last_sample_failure"
        const val KEY_LAST_SAMPLE_FAILURE_AT = "last_sample_failure_at"
        const val KEY_LAST_EVENT_FAILURE = "last_event_failure"
        const val KEY_LAST_EVENT_FAILURE_AT = "last_event_failure_at"

        @Volatile private var activeRecorder: BatteryRecorderService? = null
        fun isRecorderLive(): Boolean = activeRecorder?.recorderRunning == true
        internal fun currentChargerLabel(): ChargerLabel? =
            activeRecorder?.takeIf { it.recorderRunning }?.chargerLabels?.current()
        internal fun labelCurrentConnection(raw: String): Boolean {
            val recorder = activeRecorder?.takeIf { it.recorderRunning } ?: return false
            val prefs = recorder.getSharedPreferences(PREFS,MODE_PRIVATE)
            if (RecordingPreference.isPaused(prefs)) return false
            return recorder.chargerLabels.assign(raw,SystemClock.elapsedRealtime()) != null
        }
        internal fun clearChargerLabel() { activeRecorder?.chargerLabels?.clear() }
        fun liveHealthReport(): String {
            val recorder = activeRecorder ?: return "NOT_RUNNING_IN_THIS_PROCESS"
            if (!recorder.recorderRunning) return "NOT_RUNNING_IN_THIS_PROCESS"
            return recorder.sampleHealth.report(SystemClock.elapsedRealtime())
        }
        const val KEY_BOOT_RESUME_FAILURE = "boot_resume_failure"
        const val KEY_BOOT_RESUME_FAILURE_AT = "boot_resume_failure_at"
        const val ACTION_RECONFIGURE = "com.batteryscope.action.RECONFIGURE_RECORDER"
        const val SAMPLE_INTERVAL_MS = 30_000L
        private const val SYSFS_INTERVAL_MS = 120_000L
        private const val CHANNEL_ID = "batteryscope_recorder"
        private const val NOTIFICATION_ID = 301
    }
}
