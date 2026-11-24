package com.batteryscope

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Process
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var batteryManager: BatteryManager
    private lateinit var batteryText: TextView
    private lateinit var chargerText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var statusText: TextView
    private lateinit var monitorText: TextView
    private lateinit var historyText: TextView
    private lateinit var capacityText: TextView
    private lateinit var deviceText: TextView
    private val historyRefreshGate = RefreshGate(300_000L)
    private val capacityRefreshGate = RefreshGate(0L)
    private lateinit var historyOperationText: TextView
    private var observedRestoreRevision = 0L
    private var pendingImportRequest = 0
    private lateinit var samplingModeButton: Button

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var telemetryThread: HandlerThread
    private lateinit var telemetryHandler: android.os.Handler
    private val telemetryReadInFlight = AtomicBoolean(false)
    private var pendingExportName: String? = null

    private val sampler = object : Runnable {
        override fun run() {
            refreshReadings()
            updateHistoryOperations()
            handler.postDelayed(this, 2000L)
        }
    }

    private data class Reading(
        val percent: Int?,
        val batteryPercentStatus: String,
        val rawVoltageMv: Int?,
        val currentUa: Long?,
        val currentPropertyStatus: String,
        val averageCurrentUa: Long?,
        val averageCurrentPropertyStatus: String,
        val chargeCounterUah: Long?,
        val chargeCounterPropertyStatus: String,
        val energyNwh: Long?,
        val energyPropertyStatus: String,
        val temperatureC: Double?,
        val batteryBroadcastAvailable: Boolean,
        val status: String,
        val plugged: String,
        val health: String,
        val technology: String,
        val maxChargingCurrentUa: Int?,
        val maxChargingVoltageUv: Int?,
        val batteryPresent: Boolean?,
        val invalidCharger: Int?,
        val cycleCount: Int?,
        val supplies: Map<String, String>,
        val supplyRootReadable: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        telemetryThread = HandlerThread("BatteryScopeLiveTelemetry").apply { start() }
        telemetryHandler = android.os.Handler(telemetryThread.looper)
        buildUi()
        pendingExportName = savedInstanceState?.getString(PENDING_EXPORT_NAME)?.takeIf { HistoryTransfer.isReadyName(it) }
        pendingImportRequest = savedInstanceState?.getInt(PENDING_IMPORT_REQUEST, 0) ?: 0
        observedRestoreRevision = HistoryOperations.state.restoreRevision
        HistoryOperations.discover(applicationContext)
        updateHistoryOperations()
        refreshReadings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExportName?.let { outState.putString(PENDING_EXPORT_NAME, it) }
        outState.putInt(PENDING_IMPORT_REQUEST, pendingImportRequest)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(sampler)
        handler.post(sampler)
        updateHistoryOperations()
        ensureRecorderRunning()
        refreshHistoryDisplay(true)
    }

    override fun onPause() {
        handler.removeCallbacks(sampler)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::telemetryThread.isInitialized) telemetryThread.quitSafely()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(8, 13, 22))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 18)
        }

        val title = TextView(this).apply {
            text = "BatteryScope"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "POWER PATH & BATTERY ANALYSER  •  RMX2161"
            textSize = 10f
            setTextColor(Color.rgb(143, 163, 190))
            setPadding(1, 4, 1, 16)
        }
        statusText = makeText(15f, Color.rgb(142, 226, 185), false).apply {
            setPadding(14, 14, 14, 14)
        }
        content.addView(title)
        content.addView(subtitle)
        monitorText = makeText(14f, Color.rgb(142, 226, 185), false)
        monitorText.text = "Monitoring status not yet confirmed"
        statusText.text = "No operation in progress"
        content.addView(monitorText)
        content.addView(statusText)
        historyOperationText = makeText(13f, Color.rgb(155, 170, 191), false)
        content.addView(historyOperationText)

        batteryText = addPanel(content, "01  BATTERY TELEMETRY  •  ANDROID FRAMEWORK")
        chargerText = addPanel(content, "02  POWER SUPPLY NODES  •  KERNEL")
        diagnosticText = addPanel(content, "03  ACCESS, SOURCE & DATA QUALITY")
        historyText = addPanel(content, "04  CHARGE / DISCHARGE HISTORY & APP USAGE")
        historyText.text = "Recorder starts automatically. Local history accumulates across days, weeks and months."
        capacityText = addPanel(content, "05  BATTERY HEALTH & FULL-CHARGE CAPACITY")
        capacityText.text = "Capacity estimate not available yet. Record several charging/discharging sessions with the recorder enabled."
        deviceText = addPanel(content, "06  DEVICE RESOURCES  •  PUBLIC ANDROID APIs")

        val note = makeText(11f, Color.rgb(155, 170, 191), false).apply {
            text = "Measurement rules: battery current is not USB input current. Only USB/charger supply nodes can report charger-side voltage/current. BatteryScope does not decode the proprietary SuperDart wire handshake, alter the selected USB protocol or write charging controls."
            setPadding(2, 12, 2, 16)
        }
        content.addView(note)

        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshReadings() }
        }
        content.addView(refreshButton, LinearLayout.LayoutParams(-1, -2))

        val historyButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val usageButton = Button(this).apply {
            text = "Enable app usage tracking"
            setOnClickListener { openUsageAccessSettings() }
        }
        val exportButton = Button(this).apply {
            text = "Prepare CSV history"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Prepare CSV history")
                    .setMessage("CSV may contain recorded app names and battery history. It is not encrypted. Prepare a private copy, then choose where to save it.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Prepare") { _, _ -> exportRecorderHistory() }.show()
            }
        }
        historyButtons.addView(usageButton, LinearLayout.LayoutParams(0, -2, 1f))
        historyButtons.addView(exportButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(historyButtons)
        val backupButton = Button(this).apply {
            text = "Prepare portable history backup"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Save history backup")
                    .setMessage("The backup contains raw battery records and any recorded app names. Keep it private and save it outside BatteryScope app storage. It is not encrypted.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ -> exportRecorderHistory(true) }.show()
            }
        }
        content.addView(backupButton, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "Save / retry prepared export"
            setOnClickListener { choosePreparedExport(false) }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "Discard prepared copy"
            setOnClickListener { choosePreparedExport(true) }
        }, LinearLayout.LayoutParams(-1, -2))
        val restoreButton = Button(this).apply {
            text = "Restore earlier history backup"
            setOnClickListener { chooseHistoryBackup() }
        }
        content.addView(restoreButton, LinearLayout.LayoutParams(-1, -2))
        val legacyButton = Button(this).apply {
            text = "Import original BatteryScope CSV"
            setOnClickListener { chooseHistoryBackup(true) }
        }
        content.addView(legacyButton, LinearLayout.LayoutParams(-1, -2))
        val batterySettingsButton = Button(this).apply {
            text = "Battery optimisation settings"
            setOnClickListener { openBatteryOptimisationSettings() }
        }
        content.addView(batterySettingsButton, LinearLayout.LayoutParams(-1, -2))
        samplingModeButton = Button(this).apply {
            setOnClickListener { toggleSamplingMode() }
        }
        content.addView(samplingModeButton, LinearLayout.LayoutParams(-1, -2))
        updateSamplingModeButton()

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun makeText(size: Float, color: Int, mono: Boolean): TextView =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.rgb(20, 29, 43))
            setTextIsSelectable(true)
        }

    private fun addPanel(parent: LinearLayout, heading: String): TextView {
        val label = TextView(this).apply {
            text = heading
            textSize = 10f
            setTextColor(Color.rgb(123, 177, 242))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(2, 17, 2, 7)
        }
        val body = makeText(12f, Color.rgb(230, 237, 247), true)
        parent.addView(label)
        parent.addView(body)
        return body
    }

    private fun batteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private data class PropertyRead(val value: Long?, val status: String)

    private fun readLongProperty(id: Int): PropertyRead = try {
        val value = batteryManager.getLongProperty(id)
        if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) {
            PropertyRead(null, "ANDROID_UNAVAILABLE_SENTINEL")
        } else {
            PropertyRead(value, "AVAILABLE")
        }
    } catch (t: SecurityException) {
        PropertyRead(null, "SECURITY_EXCEPTION")
    } catch (t: Throwable) {
        PropertyRead(null, "ERROR:" + t.javaClass.simpleName)
    }

    /** Enumerates read-only kernel power, USB-C, PD, thermal and hwmon class attributes. */
    private fun readSupplyNodes(): Pair<Map<String, String>, Boolean> {
        val fields = HardwareSnapshotCache.snapshot(applicationContext, SYSFS_UI_REFRESH_MS)
        return KernelInventory.dashboard(fields) to (fields["power_supply/@root"] == "VISIBLE")
    }

    private fun buildMeasurementDiagnosis(nodes: Map<String, String>, powerSupplyRootVisible: Boolean): String {
        val lines = mutableListOf<String>()
        lines += "AUTOMATIC MEASUREMENT ACCESS DIAGNOSIS"
        lines += "Power-supply root: " + if (powerSupplyRootVisible) "visible" else "not visible / not enumerable"
        val rootChecks = nodes.filterKeys { it.startsWith("@diagnostic/") && it.endsWith("/root") }
        if (rootChecks.isNotEmpty()) {
            lines += "Kernel class-root access results:"
            rootChecks.toSortedMap().forEach { (key, value) -> lines += "  ${key.removePrefix("@diagnostic/")} = $value" }
        }
        lines += "Foreground-app attribution permission: " + if (hasUsageAccessNow()) "GRANTED; foreground-time correlation available" else "NOT GRANTED; app-specific foreground correlation unavailable"
        lines += "Battery sampling source: ACTION_BATTERY_CHANGED + BatteryManager public properties; unavailable sentinels are retained as unavailable"
        val keepAwake = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
            .getBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, true)
        val recorderPrefs = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
        lines += "Recorder screen-off mode requested: " + if (keepAwake) "continuous (may add app drain)" else "low-impact (screen-off gaps possible)"
        lines += "Last reported wake lock held: " + recorderPrefs.getBoolean(BatteryRecorderService.KEY_WAKE_LOCK_HELD, false) + " (service-reported; not proof of current process liveness)"
        lines += "Process-local recorder health: " + BatteryRecorderService.liveHealthReport()
        val lastCommit = recorderPrefs.getLong(BatteryRecorderService.KEY_LAST_SUCCESS_WALL, 0L)
        lines += "Last reported successful database commit (historical wall time): " +
            if (lastCommit > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastCommit)) else "none recorded"
        recorderPrefs.getString(BatteryRecorderService.KEY_LAST_SAMPLE_FAILURE, null)?.let {
            lines += "Historical sample failure at " + Date(recorderPrefs.getLong(BatteryRecorderService.KEY_LAST_SAMPLE_FAILURE_AT, 0L)) + ": " + it
        }
        recorderPrefs.getString(BatteryRecorderService.KEY_LAST_EVENT_FAILURE, null)?.let {
            lines += "Historical event-write failure at " + Date(recorderPrefs.getLong(BatteryRecorderService.KEY_LAST_EVENT_FAILURE_AT, 0L)) + ": " + it
        }
        recorderPrefs.getString(BatteryRecorderService.KEY_START_FAILURE, null)?.let { lines += "Recorder startup failure: " + it }
        val bootResumeFailure = recorderPrefs.getString(BatteryRecorderService.KEY_BOOT_RESUME_FAILURE, null)
        if (!bootResumeFailure.isNullOrBlank()) {
            val failedAt = recorderPrefs.getLong(BatteryRecorderService.KEY_BOOT_RESUME_FAILURE_AT, 0L)
            lines += "Last automatic boot-resume failure: " + bootResumeFailure +
                if (failedAt > 0L) " at " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(failedAt)) else ""
        } else {
            lines += "Last automatic boot-resume failure: none recorded"
        }
        val nonSupplyRoots = KernelInventory.roots.map { it.name }.toSet() - "power_supply"
        val supplies = nodes.keys.filter { !it.startsWith("@") && it.substringBefore("/") !in nonSupplyRoots }
            .map { it.substringBefore("/") }.distinct()
        val external = supplies.filter { it !in listOf("battery", "bms", "main") }
        lines += "External power-supply entries: " + external.joinToString().ifBlank { "none discovered" }
        var usbPairFound = false
        var nonUsbPairFound = false
        for (name in external) {
            val base = name + "/"
            val voltage = nodes[base + "voltage_now"]?.toLongOrNull()
            val current = nodes[base + "current_now"]?.toLongOrNull()
            val type = nodes[base + "type"]?.uppercase(Locale.US).orEmpty()
            val usbSupply = name.contains("usb", ignoreCase = true) || type.contains("USB")
            lines += "Supply " + name + ": type=" + (nodes[base + "type"] ?: "not exported") +
                "; online=" + (nodes[base + "online"] ?: "not exported")
            if (voltage != null && current != null &&
                voltage in 1_000_000L..30_000_000L && current in -20_000_000L..20_000_000L) {
                if (usbSupply && nodes[base + "online"] == "1") {
                    usbPairFound = true
                    lines += "  USB-source same-supply V/I candidate: " +
                        String.format(Locale.US, "%.3f V / %.3f A", voltage / 1_000_000.0, current / 1_000_000.0)
                    lines += "  Candidate USB input power: " +
                        String.format(Locale.US, "%.3f W", voltage.toDouble() * current.toDouble() / 1.0e12) +
                        "; source path/driver semantics still require handset validation"
                } else {
                    nonUsbPairFound = true
                    lines += "  Same-supply V/I pair lacks confirmed USB identity or online=1; NOT classified as USB input power."
                    lines += "  Raw candidate: " + voltage + " µV / " + current + " µA"
                }
            } else {
                lines += "  No plausible same-supply live pair: voltage_now=" +
                    (nodes[base + "voltage_now"] ?: "NOT EXPORTED") + "; current_now=" +
                    (nodes[base + "current_now"] ?: "NOT EXPORTED")
            }
            val limits = listOf("input_voltage_limit", "input_current_limit", "input_power_limit", "constant_charge_current_max")
                .mapNotNull { key -> nodes[base + key]?.let { key + "=" + it } }
            if (limits.isNotEmpty()) lines += "  CONFIGURED LIMITS (not measurements): " + limits.joinToString()
        }
        if (!usbPairFound) {
            lines += "USB input V/A/W unavailable as a USB-identified source: no external supply with USB name/type exposed a plausible same-supply voltage_now/current_now pair."
        }
        if (nonUsbPairFound) lines += "At least one external supply exposed a numeric pair without confirmed USB identity or online state; no USB claim was made for it."
        val usb = nodes.filterKeys {
            it.startsWith("typec/") || it.startsWith("usb_power_delivery/") ||
                it.startsWith("dual_role_usb/") || it.startsWith("usb_role/")
        }
        lines += "USB-C / Type-C / PD / role attributes discovered: " + usb.size
        if (usb.isEmpty()) lines += "USB-C interface diagnosis: class absent, hidden by access controls, or not enumerable by ordinary app UID."
        val thermal = nodes.filterKeys { it.startsWith("thermal/") || it.startsWith("hwmon/") }
        lines += "Thermal / hwmon attributes discovered: " + thermal.size
        lines += "Android thermal status: " + (nodes["@android_thermal_status"] ?: "not available from PowerManager on this Android build")
        lines += "Raw kernel attributes captured: " + (nodes["@field_count"] ?: nodes.size.toString())
        if (nodes["@field_limit_reached"] == "true") lines += "WARNING: bounded kernel scan reached its configured field cap of " + (nodes["@field_limit"] ?: "unknown") + "; later paths were not scanned."
        val denied = nodes.filterValues { it == "READ_DENIED" || it == "LIST_DENIED" || it.startsWith("ERROR:") }
        lines += "Permission / read errors: " + denied.size
        denied.entries.take(20).forEach { lines += "  " + it.key + " = " + it.value }
        lines += "Root: not invoked automatically; no su prompt or root write attempted."
        lines += "PDO capabilities, configured limits and VOOC flags are not classified as measured USB input power."
        lines += "No kernel attributes were written."
        return lines.joinToString("\n")
    }

    private fun readSnapshot(): Reading {
        val intent = batteryIntent()
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
        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (rawTemp in 0..1000) rawTemp / 10.0 else null
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN"
        }
        val pluggedCode = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_PLUGGED) }?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugged = when (pluggedCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            8 -> "DOCK"
            null, -1 -> "UNKNOWN"
            else -> if (pluggedCode == 0) "NONE" else if (pluggedCode > 0) "OTHER ($pluggedCode)" else "UNKNOWN"
        }
        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "NOT REPORTED"
        val currentRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val averageRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val chargeRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energyRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val (supplies, readable) = readSupplyNodes()
        return Reading(
            percent = percent,
            batteryPercentStatus = percentStatus,
            rawVoltageMv = rawVoltage.takeIf { it > 0 },
            currentUa = currentRead.value,
            currentPropertyStatus = currentRead.status,
            averageCurrentUa = averageRead.value,
            averageCurrentPropertyStatus = averageRead.status,
            chargeCounterUah = chargeRead.value,
            chargeCounterPropertyStatus = chargeRead.status,
            energyNwh = energyRead.value,
            energyPropertyStatus = energyRead.status,
            temperatureC = temp,
            batteryBroadcastAvailable = intent != null,
            status = status,
            plugged = plugged,
            health = health,
            technology = technology,
            maxChargingCurrentUa = intent?.getIntExtra("max_charging_current", -1)?.takeIf { it > 0 },
            maxChargingVoltageUv = intent?.getIntExtra("max_charging_voltage", -1)?.takeIf { it > 0 },
            batteryPresent = intent?.extras?.let { if (it.containsKey("present")) it.getBoolean("present") else null },
            invalidCharger = intent?.extras?.let { if (it.containsKey("invalid_charger")) it.getInt("invalid_charger") else null },
            cycleCount = intent?.getIntExtra("cycle_count", -1)?.takeIf { it >= 0 },
            supplies = supplies,
            supplyRootReadable = readable
        )
    }

    private fun refreshReadings() {
        if (!telemetryReadInFlight.compareAndSet(false, true)) return
        telemetryHandler.post {
        try {
        val reading = readSnapshot()
        val voltageAssessment = BatteryMeasurementQuality.voltage(reading.rawVoltageMv)
        val currentAssessment = BatteryMeasurementQuality.current(reading.currentUa, reading.status, reading.plugged)
        val currentMa = reading.currentUa?.takeIf { currentAssessment.usable }?.div(1000.0)
        val estimatedBatteryPowerW = BatteryMeasurementQuality.batteryPowerW(
            reading.rawVoltageMv, reading.currentUa, reading.status, reading.plugged
        )
        val format = { value: Double?, unit: String ->
            if (value == null || !value.isFinite()) "Unavailable"
            else String.format(Locale.US, "%.3f %s", value, unit)
        }

        val resourceReport = buildString {
            val captured = reading.supplies["@device/captured_elapsed_ms"]?.toLongOrNull()
            if (captured != null) appendLine("Snapshot age: " + ((android.os.SystemClock.elapsedRealtime()-captured).coerceAtLeast(0L)/1000) + " seconds")
            appendLine("Network counters are cumulative bytes since boot, not battery energy or instantaneous speed.")
            appendLine("CPU processor count is runtime availability, not a utilization reading. Sensor list is inventory only.")
            reading.supplies.filterKeys { it.startsWith("@device/") }.toSortedMap().forEach { (key,value) ->
                appendLine(key.removePrefix("@device/") + ": " + value)
            }
        }
        val statusReport = "${reading.status}   •   ${reading.percent?.let { "$it%" } ?: "--"}   •   POWER SOURCE: ${reading.plugged}"
        val external = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            !key.startsWith("@") && node !in listOf("battery", "bms", "main")
        }
        val batteryNodes = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            node in listOf("battery", "bms", "main")
        }
        val gauge = KernelGauge.select(reading.supplies)
        val kernelCycleCount = gauge?.cycles
        val kernelFullChargeUah = gauge?.fullUah
        val kernelDesignChargeUah = gauge?.designUah
        val kernelHealthPercent = gauge?.healthPercent
        val voltageSummary = if (voltageAssessment.usable) {
            "${reading.rawVoltageMv} mV"
        } else {
            "Unavailable — ${voltageAssessment.status}; raw EXTRA_VOLTAGE=${reading.rawVoltageMv ?: "missing"}"
        }
        val currentSummary = if (currentAssessment.usable) {
            "${format(currentMa, "mA")} (raw API ${reading.currentUa} µA)"
        } else {
            "Unavailable — ${currentAssessment.status}; raw API=${reading.currentUa?.let { "$it µA" } ?: reading.currentPropertyStatus}"
        }
        val batteryReport = buildString {
            appendLine("Battery level: ${reading.percent?.let { "$it%" } ?: "Unavailable"}")
            appendLine("State / source: ${reading.status} / ${reading.plugged}")
            appendLine("Battery voltage: $voltageSummary")
            appendLine("Battery current: $currentSummary")
            appendLine("Battery-side power estimate: ${format(estimatedBatteryPowerW, "W")} (signed; battery side only)")
            appendLine("Charger-input power: Unavailable — no verified USB-side voltage/current pair")
            appendLine("Temperature: ${format(reading.temperatureC, "°C")}")
        }

        val chargerReport = buildString {
            appendLine("App process sysfs access: ${if (reading.supplyRootReadable) "directory listed" else "denied / unavailable"}")
            appendLine("External power / charger nodes")
            if (external.isEmpty()) appendLine("No readable external-supply attributes from the ordinary app process.")
            else external.forEach { (key, value) -> appendLine(formatSupplyValue(key, value)) }
            appendLine()
            appendLine("Battery / BMS nodes")
            if (batteryNodes.isEmpty()) append("No readable battery attributes from the ordinary app process.")
            else batteryNodes.forEach { (key, value) -> appendLine(formatSupplyValue(key, value)) }
        }

        val onlineNodes = reading.supplies.filter { (key, value) ->
            key.endsWith("/online") && value == "1" &&
                key.substringBefore('/').lowercase(Locale.US) !in listOf("battery", "bms", "main")
        }.keys
        val diagnosticReport = buildString {
            appendLine("Android battery status: ${reading.status}")
            appendLine("Android plug type: ${reading.plugged}")
            appendLine("Kernel external online flags: ${onlineNodes.joinToString().ifBlank { "not observed by app UID" }}")
            appendLine("Ordinary app sysfs: ${if (reading.supplyRootReadable) "directory visible; individual files may still be denied" else "not visible"}")
            appendLine("ACTION_BATTERY_CHANGED: " + if (reading.batteryBroadcastAvailable) "available" else "NOT AVAILABLE from Android sticky broadcast")
            appendLine("Battery percentage: " + (reading.percent?.let { "available (${it}%)" } ?: "unavailable — " + reading.batteryPercentStatus))
            appendLine("Battery voltage: raw EXTRA_VOLTAGE=${reading.rawVoltageMv ?: "missing"} mV field; quality=${voltageAssessment.status}")
            appendLine("Battery temperature: " + (reading.temperatureC?.let { "available (${it} °C)" } ?: "unavailable — EXTRA_TEMPERATURE missing or outside accepted range"))
            appendLine("Android charge counter: ${reading.chargeCounterUah?.let { "available (${it} µAh)" } ?: "unavailable — " + reading.chargeCounterPropertyStatus}")
            appendLine("Instantaneous battery current: raw API=${reading.currentUa?.let { "$it µA" } ?: reading.currentPropertyStatus}; quality=${currentAssessment.status}")
            appendLine("Average battery current: raw API=${reading.averageCurrentUa?.let { "$it µA" } ?: reading.averageCurrentPropertyStatus}; not used for watt calculation")
            appendLine("Battery energy counter: ${reading.energyNwh?.let { "available (${it} nWh)" } ?: "unavailable — " + reading.energyPropertyStatus}")
            appendLine("Invalid charger status: ${reading.invalidCharger?.let { if (it != 0) "framework reports invalid charger ($it)" else "framework reports no invalid-charger flag" } ?: "extra not supplied by Android/ROM"}")
            appendLine("Charger advertised maximum current/voltage: ${if (reading.maxChargingCurrentUa != null || reading.maxChargingVoltageUv != null) "partially or fully reported in ACTION_BATTERY_CHANGED" else "not supplied by Android/charger; live USB input V/I may require restricted kernel driver nodes"}")
            appendLine("Battery cycle count: ${reading.cycleCount?.let { "Android extra=$it" } ?: kernelCycleCount?.let { "kernel power_supply=$it" } ?: "not exposed by Android extra or readable battery/BMS sysfs nodes"}")
            appendLine("Kernel FCC/design-capacity pair: ${if (kernelFullChargeUah != null && kernelDesignChargeUah != null) "available; health estimate " + String.format(Locale.US, "%.1f%%", kernelHealthPercent) + " from " + gauge?.source else "unavailable — a readable same-supply charge_full / charge_full_design pair is not available"}")
            appendLine("Control writes: NEVER PERFORMED")
            appendLine()
            append(buildMeasurementDiagnosis(reading.supplies, reading.supplyRootReadable))
            append("Refresh: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} • 2-second dashboard refresh")
        }

        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                monitorText.text = statusReport + "\nRecorder: " + BatteryRecorderService.liveHealthReport()
                batteryText.text = batteryReport
                chargerText.text = chargerReport
                diagnosticText.text = diagnosticReport
                deviceText.text = resourceReport
                refreshHistoryDisplay(false)
            }
        }
        } catch (t: Throwable) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) diagnosticText.text =
                    "LIVE TELEMETRY READ FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            }
        } finally {
            telemetryReadInFlight.set(false)
        }
        }
    }

    private fun hasUsageAccessNow(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun formatSupplyValue(key: String, raw: String): String {
        val field = key.substringAfter('/')
        val number = raw.toLongOrNull()
        val vendorRawOnly = field in setOf(
            "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology",
            "adapter_fw_update", "mmi_charging_enable", "stop_charging_enable",
            "chargerid_volt", "charger_ic", "usb_status", "typec_cc_orientation", "typec_sbu_voltage"
        ) || (key.startsWith("battery/") && field == "current_max")
        val converted = if (vendorRawOnly) null else when (field) {
            "voltage_now", "voltage_avg", "voltage_max", "voltage_min",
            "input_voltage_limit", "constant_charge_voltage", "constant_charge_voltage_max", "voltage_limit", "max_voltage" ->
                number?.let { String.format(Locale.US, "  [%.3f V; raw µV]", it / 1_000_000.0) }
            "current_now", "current_avg", "current_max", "input_current_limit",
            "input_current_limit_max", "constant_charge_current", "constant_charge_current_max",
            "charge_control_limit", "charge_control_limit_max", "current_limit", "max_current" ->
                number?.let { String.format(Locale.US, "  [%.1f mA; raw µA]", it / 1000.0) }
            "power_now", "input_power_limit" ->
                number?.let { String.format(Locale.US, "  [%.3f W; raw µW]", it / 1_000_000.0) }
            "charge_counter", "charge_now", "charge_full", "charge_full_design" ->
                number?.let { String.format(Locale.US, "  [%.1f mAh; raw µAh]", it / 1000.0) }
            "capacity", "charge_control_start_threshold", "charge_control_end_threshold" -> number?.let { "  [$it %]" }
            else -> null
        }
        val annotation = when (field) {
            "fastcharger" -> "  [vendor boolean; VOOC UI indicator]"
            "fast_chg_type" -> "  [vendor subtype enum; raw value only]"
            "mmi_charging_enable" -> "  [vendor state; read only; no write]"
            "stop_charging_enable" -> "  [vendor state; read only; no write]"
            "voocchg_ing" -> "  [vendor boolean; VOOC in-progress state]"
            "charge_technology" -> "  [vendor enum; raw value only]"
            "adapter_fw_update" -> "  [vendor state; raw value only]"
            "current_max" -> if (key.startsWith("battery/")) "  [vendor-specific units not assumed]" else (converted ?: "")
            else -> converted ?: ""
        }
        return "$key = $raw$annotation"
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    /** Monitoring is automatic: every foreground app launch repairs a missing recorder request. */
    private fun ensureRecorderRunning() {
        try {
            val intent = Intent(this, BatteryRecorderService::class.java)
                .setAction(BatteryRecorderService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            monitorText.text = "Automatic device monitoring requested • awaiting recorder confirmation"
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), NOTIFICATION_REQUEST_CODE)
            }
            handler.postDelayed({
                monitorText.text = "Recorder: " + BatteryRecorderService.liveHealthReport()
                refreshHistoryDisplay(true)
            }, 1200L)
        } catch (t: Throwable) {
            val failure = t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            monitorText.text = "AUTOMATIC MONITOR START FAILED: " + failure
            getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE).edit()
                .putString(BatteryRecorderService.KEY_START_FAILURE, failure).apply()
        }
        updateSamplingModeButton()
    }

    private fun toggleSamplingMode() {
        val prefs = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
        val keepAwake = prefs.getBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, true)
        val next = !keepAwake
        prefs.edit().putBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, next).apply()
        if (BatteryRecorderService.isRecorderLive()) {
            try {
                startService(Intent(this, BatteryRecorderService::class.java).setAction(BatteryRecorderService.ACTION_RECONFIGURE))
            } catch (t: Throwable) {
                statusText.text = "Sampling mode saved, but recorder reconfiguration failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                updateSamplingModeButton()
                return
            }
        }
        updateSamplingModeButton()
        statusText.text = if (next) {
            "Continuous screen-off sampling requested. A held CPU wake lock improves continuity but increases BatteryScope's own battery use. Check recorder diagnostics for actual state."
        } else {
            "Low-impact sampling requested. Android may suspend sampling when the wake lock is released; check recorder diagnostics for actual state."
        }
    }

    private fun updateSamplingModeButton() {
        if (!::samplingModeButton.isInitialized) return
        val keepAwake = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
            .getBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, true)
        samplingModeButton.text = if (keepAwake) {
            "Requested mode: CONTINUOUS (wake lock requested) — tap for low-impact"
        } else {
            "Requested mode: LOW-IMPACT (wake lock off requested) — tap for continuous"
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (t: Throwable) {
            statusText.text = "Could not open Usage Access settings: " + (t.message ?: "unknown")
        }
    }

    private fun openBatteryOptimisationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            statusText.text = "Could not open battery optimisation settings: " + (t.message ?: "unknown")
        }
    }

    private fun refreshHistoryDisplay(force: Boolean) {
        if (isFinishing || isDestroyed || !::historyText.isInitialized) return
        val now = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (!historyRefreshGate.request(elapsed, force)) return
        refreshCapacityDisplay()
        Thread {
            var database: RecorderDatabase? = null
            val result = try {
                database = RecorderDatabase(applicationContext)
                database.historySummary(now)
            } catch (t: Throwable) {
                "History read failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            } finally {
                try { database?.close() } catch (_: Throwable) { }
            }
            runOnUiThread {
                val followUp = historyRefreshGate.finish()
                if (!isFinishing && !isDestroyed && ::historyText.isInitialized) {
                    historyText.text = result
                    if (followUp) refreshHistoryDisplay(true)
                }
            }
        }.start()
    }


    private fun refreshCapacityDisplay() {
        if (isFinishing || isDestroyed || !::capacityText.isInitialized) return
        if (!capacityRefreshGate.request(android.os.SystemClock.elapsedRealtime(), true)) return
        Thread {
            val report = try {
                val database = RecorderDatabase(applicationContext)
                try { database.capacityEstimateReport() } finally { database.close() }
            } catch (t: Throwable) {
                "Capacity estimate failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            }
            runOnUiThread {
                val followUp = capacityRefreshGate.finish()
                if (!isFinishing && !isDestroyed && ::capacityText.isInitialized) {
                    capacityText.text = report
                    if (followUp) refreshCapacityDisplay()
                }
            }
        }.start()
    }

    private fun updateHistoryOperations() {
        val state = HistoryOperations.state
        historyOperationText.text = state.message + "\nPrepared private exports: " + state.ready.size
        if (state.restoreRevision != observedRestoreRevision) {
            observedRestoreRevision = state.restoreRevision
            refreshHistoryDisplay(true)
        }
    }

    private fun historyBusy(): Boolean {
        if (HistoryOperations.state.busy || pendingExportName != null || pendingImportRequest != 0) {
            statusText.text = "A history operation or document picker is already open."
            return true
        }
        return false
    }

    private fun exportRecorderHistory(backup: Boolean = false) {
        if (historyBusy()) return
        if (!HistoryOperations.prepare(applicationContext, backup)) statusText.text = "Another history operation is in progress."
        updateHistoryOperations()
    }

    private fun choosePreparedExport(discard: Boolean) {
        if (historyBusy()) return
        val names = HistoryOperations.state.ready
        if (names.isEmpty()) {
            statusText.text = "No prepared exports. Prepare a CSV or backup first."
            HistoryOperations.discover(applicationContext)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (discard) "Discard a private prepared copy" else "Choose a prepared export")
            .setItems(names.toTypedArray()) { _, index ->
                val name = names[index]
                if (discard) {
                    AlertDialog.Builder(this)
                        .setTitle("Discard this prepared copy?")
                        .setMessage(name + "\nOnly this staged export is removed. The local history database is not changed.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Discard") { _, _ ->
                            if (!historyBusy()) HistoryOperations.discard(applicationContext, name)
                            updateHistoryOperations()
                        }.show()
                } else launchHistoryExport(name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun launchHistoryExport(name: String) {
        if (historyBusy()) return
        if (!HistoryTransfer.isReadyName(name) || name !in HistoryOperations.state.ready) {
            statusText.text = "Prepared export unavailable. Refresh the prepared-file list."
            HistoryOperations.discover(applicationContext)
            return
        }
        pendingExportName = name
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (name.endsWith(".bsh")) "application/octet-stream" else "text/csv"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, CSV_EXPORT_REQUEST_CODE)
        } catch (t: Exception) {
            pendingExportName = null
            statusText.text = "Could not open document picker: " + t.javaClass.simpleName + ". Prepared copy retained."
        }
    }

    @Deprecated("Handled for Android document-picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BACKUP_IMPORT_REQUEST_CODE || requestCode == LEGACY_IMPORT_REQUEST_CODE) {
            val expected = pendingImportRequest == requestCode
            pendingImportRequest = 0
            if (expected && resultCode == RESULT_OK) data?.data?.let {
                if (!HistoryOperations.restore(applicationContext, it, requestCode == LEGACY_IMPORT_REQUEST_CODE))
                    statusText.text = "Another history operation is in progress. Select the backup again later."
            }
            updateHistoryOperations()
            return
        }
        if (requestCode != CSV_EXPORT_REQUEST_CODE) return
        val name = pendingExportName
        pendingExportName = null
        val destination = data?.data
        if (resultCode != RESULT_OK || destination == null) {
            statusText.text = "Export cancelled. Prepared copy retained for retry."
            return
        }
        if (name == null || !HistoryTransfer.isReadyName(name)) {
            statusText.text = "Export selection could not be recovered. No copy attempted; choose a prepared file again."
            return
        }
        if (!HistoryOperations.save(applicationContext, name, destination))
            statusText.text = "Another history operation is in progress. Prepared copy retained."
        updateHistoryOperations()
    }

    @Suppress("DEPRECATION")
    private fun chooseHistoryBackup(legacy: Boolean = false) {
        if (historyBusy()) return
        AlertDialog.Builder(this)
            .setTitle(if (legacy) "Import original CSV" else "Restore earlier history")
            .setMessage(if (legacy) "Select the original, unedited 28-column BatteryScope CSV from this handset. CSV is not checksummed or lossless; missing fields and flattened text cannot be recovered. Existing history will not be overwritten and overlap is rejected." else "Use only a BatteryScope .bsh backup from this handset. The format cannot prove its original device. Existing history will not be overwritten. Overlapping timestamps are rejected. Large backups may take time and temporary storage.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose backup") { _, _ ->
                if (!historyBusy()) {
                    pendingImportRequest = if (legacy) LEGACY_IMPORT_REQUEST_CODE else BACKUP_IMPORT_REQUEST_CODE
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }, pendingImportRequest)
                    } catch (t: Exception) {
                        pendingImportRequest = 0
                        statusText.text = "Could not open backup picker: " + t.javaClass.simpleName
                    }
                }
            }.show()
    }

    companion object {
        private const val SYSFS_UI_REFRESH_MS = 60_000L
        private const val PENDING_EXPORT_NAME = "pending_export_name"
        private const val PENDING_IMPORT_REQUEST = "pending_import_request"
        private const val CSV_EXPORT_REQUEST_CODE = 5101
        private const val BACKUP_IMPORT_REQUEST_CODE = 5103
        private const val LEGACY_IMPORT_REQUEST_CODE = 5104
        private const val NOTIFICATION_REQUEST_CODE = 5102
    }
}
