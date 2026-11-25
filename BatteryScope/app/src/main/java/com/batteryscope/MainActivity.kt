package com.batteryscope

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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
    private lateinit var dashboardScroll: DashboardScrollView
    private lateinit var liveSummary: TextView
    private lateinit var stageSummary: TextView
    private lateinit var recorderSummary: TextView
    private lateinit var batteryText: TextView
    private lateinit var chargerText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var statusText: TextView
    private lateinit var monitorText: TextView
    private lateinit var curvePanel: ChargingCurvePanel
    private val historyRefreshGate = RefreshGate(300_000L)
    private var reportLoading = false
    private var reportDialog: AlertDialog? = null
    private lateinit var historyOperationText: TextView
    private var observedRestoreRevision = 0L
    private var pendingImportRequest = 0
    private lateinit var samplingModeButton: Button
    private lateinit var recordingToggle: Button

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
        val rawTemperatureTenths: Int?,
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
        pendingExportName = savedInstanceState?.getString(PENDING_EXPORT_NAME)?.takeIf { HistoryTransfer.isSaveName(it) }
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
        reportDialog?.dismiss()
        reportDialog = null
        if (::telemetryThread.isInitialized) telemetryThread.quitSafely()
        super.onDestroy()
    }

    private fun buildUi() {
        val margin = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
            setBackgroundColor(Color.rgb(8, 13, 22))
        }
        val scroll = DashboardScrollView(this)
        dashboardScroll = scroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val title = TextView(this).apply {
            text = "BatteryScope"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "POWER PATH & BATTERY ANALYSER  •  " + Build.MODEL + " • Android " + Build.VERSION.RELEASE
            textSize = 12f
            setTextColor(Color.rgb(143, 163, 190))
            setPadding(dp(1), dp(4), dp(1), dp(12))
        }
        statusText = makeText(15f, Color.rgb(142, 226, 185), false).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        content.addView(title)
        content.addView(subtitle)
        liveSummary = makeText(17f, Color.rgb(230,237,247), false).apply {
            text = "Waiting for battery readings…"
        }
        content.addView(liveSummary)
        recorderSummary = makeText(13f,Color.rgb(142,226,185),false).apply {
            text = "Recorder: checking"
        }
        content.addView(recorderSummary)
        stageSummary = makeText(14f, Color.rgb(230,237,247), false).apply {
            text = "Charging stages (last 30 days)\nWaiting for history…"
        }
        content.addView(stageSummary)
        curvePanel = ChargingCurvePanel(this)
        content.addView(curvePanel, LinearLayout.LayoutParams(-1, -2))
        batteryText = addPanel(content, "Battery readings")
        chargerText = addPanel(content, "Charger / phone communication")
        diagnosticText = addPanel(content, "Measurement quality & sources")
        addReportButton(content, "Detailed charging history") { db -> db.historySummary(chargingFocus=true) }
        addReportButton(content, "Battery health & capacity") { db -> db.capacityEstimateReport() }
        monitorText = addPanel(content, "Recorder status")
        monitorText.text = "Monitoring status not yet confirmed"
        addPanel(content, "Measurement rules").text =
            "Battery-side current is not charger input current. Raw charger/BMS/USB data is shown only when accessible. BatteryScope does not capture proprietary SuperDart packets, select a USB protocol or write charging controls."

        val toolsToggle = Button(this).apply { text = "Show history & settings tools" }
        val toolsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        toolsToggle.setOnClickListener {
            val opening = toolsContent.visibility != View.VISIBLE
            toolsContent.visibility = if (opening) View.VISIBLE else View.GONE
            toolsToggle.text = if (opening) "Hide history & settings tools" else "Show history & settings tools"
        }
        content.addView(toolsToggle)
        content.addView(toolsContent)
        statusText.text = "No operation in progress"
        toolsContent.addView(statusText)
        historyOperationText = makeText(13f, Color.rgb(155,170,191),false)
        toolsContent.addView(historyOperationText)

        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener {
                refreshReadings()
                refreshHistoryDisplay(true)
            }
        }
        content.addView(refreshButton, LinearLayout.LayoutParams(-1, -2))

        val exportButton = Button(this).apply {
            text = "Prepare CSV history"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Prepare CSV history")
                    .setMessage("CSV may contain recorded app names and battery history. It is not encrypted. Samples and events are grouped in their original recorded sequence, not sorted by wall time. Use a portable backup for exact typed history. Prepare a private copy, then choose where to save it.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Prepare") { _, _ -> exportRecorderHistory() }.show()
            }
        }
        toolsContent.addView(exportButton, LinearLayout.LayoutParams(-1, -2))
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
        toolsContent.addView(backupButton, LinearLayout.LayoutParams(-1, -2))
        toolsContent.addView(Button(this).apply {
            text = "Save / retry prepared export"
            setOnClickListener { choosePreparedExport(false) }
        }, LinearLayout.LayoutParams(-1, -2))
        toolsContent.addView(Button(this).apply {
            text = "Discard prepared copy"
            setOnClickListener { choosePreparedExport(true) }
        }, LinearLayout.LayoutParams(-1, -2))
        val restoreButton = Button(this).apply {
            text = "Restore earlier history backup"
            setOnClickListener { chooseHistoryBackup() }
        }
        toolsContent.addView(restoreButton, LinearLayout.LayoutParams(-1, -2))
        toolsContent.addView(Button(this).apply {
            text = "Recover earlier history with exact overlap"
            setOnClickListener { chooseHistoryBackup(exactOverlap = true) }
        }, LinearLayout.LayoutParams(-1, -2))
        val legacyButton = Button(this).apply {
            text = "Import original BatteryScope CSV"
            setOnClickListener { chooseHistoryBackup(true) }
        }
        toolsContent.addView(legacyButton, LinearLayout.LayoutParams(-1, -2))
        val batterySettingsButton = Button(this).apply {
            text = "Battery optimisation settings"
            setOnClickListener { openBatteryOptimisationSettings() }
        }
        toolsContent.addView(batterySettingsButton, LinearLayout.LayoutParams(-1, -2))
        samplingModeButton = Button(this).apply {
            setOnClickListener { toggleSamplingMode() }
        }
        toolsContent.addView(samplingModeButton, LinearLayout.LayoutParams(-1, -2))
        updateSamplingModeButton()
        recordingToggle = Button(this).apply { setOnClickListener { toggleRecording() } }
        toolsContent.addView(recordingToggle, LinearLayout.LayoutParams(-1,-2))
        updateRecordingToggle()

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // API 35+ targets edge-to-edge by default. Keep all controls inside
        // system bar and cutout insets; don't add them to older fitted layouts.
        if (Build.VERSION.SDK_INT >= 35) {
            window.insetsController?.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(margin + safe.left, margin + safe.top, margin + safe.right, margin + safe.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35) root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun makeText(size: Float, color: Int, mono: Boolean): TextView =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(20, 29, 43))
            setTextIsSelectable(false)
        }

    private fun addPanel(parent: LinearLayout, heading: String): TextView {
        // Detached text holder. Open dialogs keep their own immutable snapshot.
        val body = makeText(14f,Color.rgb(230,237,247),true)
        parent.addView(Button(this).apply {
            text = heading
            setOnClickListener {
                val snapshot = makeText(14f,Color.rgb(230,237,247),true).apply {
                    text = body.text.toString().ifBlank { "Waiting for data…" }
                    setTextIsSelectable(true)
                }
                val reportScroll = ScrollView(this@MainActivity).apply { addView(snapshot) }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(heading)
                    .setView(reportScroll)
                    .setPositiveButton("Close",null)
                    .show()
            }
        })
        return body
    }

    /** Expensive all-history and 90-day capacity reports are explicit snapshots, not passive work. */
    private fun addReportButton(parent: LinearLayout, heading: String, read: (RecorderDatabase) -> String) {
        parent.addView(Button(this).apply {
            text = heading
            setOnClickListener {
                if (reportLoading) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("A history report is still being read. Open this report again after it finishes.")
                        .setPositiveButton("Close",null).show()
                } else {
                    val snapshot = makeText(14f,Color.rgb(230,237,247),true).apply {
                        text = "Reading recorded history…"
                    }
                    val reportScroll = ScrollView(this@MainActivity).apply { addView(snapshot) }
                    val dialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle(heading).setView(reportScroll).setPositiveButton("Close",null).create()
                    reportDialog = dialog
                    dialog.setOnDismissListener { if (reportDialog === dialog) reportDialog = null }
                    dialog.show()
                    reportLoading = true
                    Thread({
                        val report = try {
                            val db = RecorderDatabase(applicationContext)
                            try { read(db) } finally { db.close() }
                        } catch (e: Exception) {
                            "Report read failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                        }
                        runOnUiThread {
                            reportLoading = false
                            if (!isFinishing && !isDestroyed && dialog.isShowing) {
                                snapshot.setTextIfChanged(report)
                                snapshot.setTextIsSelectable(true)
                                reportScroll.post { reportScroll.scrollTo(0,0) }
                            }
                        }
                    },"BatteryScopeOnDemandReport").start()
                }
            }
        })
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
        return fields to (fields["power_supply/@root"] == "VISIBLE")
    }

    private fun buildMeasurementDiagnosis(nodes: Map<String, String>, powerSupplyRootVisible: Boolean, plug: String): String {
        val lines = mutableListOf<String>()
        lines += "AUTOMATIC MEASUREMENT ACCESS DIAGNOSIS"
        lines += "Power-supply root: " + if (powerSupplyRootVisible) "visible" else "not visible / not enumerable"
        val rootChecks = nodes.filterKeys { it.endsWith("/@root") }
        if (rootChecks.isNotEmpty()) {
            lines += "Kernel class-root access results:"
            rootChecks.toSortedMap().forEach { (key, value) -> lines += "  $key = $value" }
        }
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
        val external = KernelPresentation.supplyPaths(nodes).filter {
            KernelPresentation.group("$it/type",nodes) == KernelPresentation.Group.EXTERNAL
        }
        lines += "External power-supply entries: " + external.joinToString().ifBlank { "none discovered" }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val age = KernelPresentation.ageMs(nodes["@inventory_started_elapsed_ms"]?.toLongOrNull(),elapsed)
        lines += "Kernel inventory age from acquisition start: " + (age?.let { (it / 1000).toString() + " s" } ?: "unknown")
        for (path in external) {
            lines += "Supply " + path + ": type=" + (nodes["$path/type"] ?: "not exported") +
                "; online=" + (nodes["$path/online"] ?: "not exported")
            lines += "  Raw V/I fields: " + (nodes["$path/voltage_now"] ?: "NOT EXPORTED") +
                " / " + (nodes["$path/current_now"] ?: "NOT EXPORTED")
            val limits = listOf("input_voltage_limit","input_current_limit","input_power_limit","constant_charge_current_max")
                .mapNotNull { key -> nodes["$path/$key"]?.let { key + "=" + it } }
            if (limits.isNotEmpty()) lines += "  CONFIGURED LIMITS (not measurements): " + limits.joinToString()
        }
        val assessment = KernelPresentation.usb(nodes,elapsed,plug,SYSFS_UI_REFRESH_MS)
        lines += "USB candidate quality: " + assessment.status
        assessment.candidate?.let {
            lines += "  Cached same-supply raw candidate: " + it.source + " = " + it.voltageUv + " / " + it.currentUa
            lines += "  Driver units and time alignment are unverified; not a live charger-input measurement."
        }
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
        if (nodes["@field_limit_reached"] == "true") lines += "WARNING: bounded kernel scan reached its configured field cap of " + (nodes["@field_limit"] ?: "unknown") + "."
        if (nodes["@scan_truncated"] == "true") lines += "WARNING: one or more inventory roots were truncated or not scanned. Missing fields do not establish lack of support."
        nodes["@scan_work_ms"]?.let { work ->
            lines += "Inventory scan: " + work + " ms elapsed • cooperative budget " + (nodes["@scan_time_budget_ms"] ?: "unknown") + " ms"
        }
        nodes.filterKeys { it.endsWith("/@scan") }.toSortedMap().forEach { (key,value) ->
            lines += "  " + key + " = " + value
        }
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
        val rawVoltage = BatteryBroadcastMetadata.int(intent,BatteryManager.EXTRA_VOLTAGE)
        val rawTemp = BatteryBroadcastMetadata.int(intent,BatteryManager.EXTRA_TEMPERATURE)
        val temp = BatteryBroadcastMetadata.temperatureC(rawTemp)
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
            rawVoltageMv = rawVoltage,
            currentUa = currentRead.value,
            currentPropertyStatus = currentRead.status,
            averageCurrentUa = averageRead.value,
            averageCurrentPropertyStatus = averageRead.status,
            chargeCounterUah = chargeRead.value,
            chargeCounterPropertyStatus = chargeRead.status,
            energyNwh = energyRead.value,
            energyPropertyStatus = energyRead.status,
            temperatureC = temp,
            rawTemperatureTenths = rawTemp,
            batteryBroadcastAvailable = intent != null,
            status = status,
            plugged = plugged,
            health = health,
            technology = technology,
            maxChargingCurrentUa = BatteryBroadcastMetadata.advertised(intent,"max_charging_current"),
            maxChargingVoltageUv = BatteryBroadcastMetadata.advertised(intent,"max_charging_voltage"),
            batteryPresent = BatteryBroadcastMetadata.bool(intent,"present"),
            invalidCharger = BatteryBroadcastMetadata.int(intent,"invalid_charger"),
            cycleCount = BatteryBroadcastMetadata.cycles(intent),
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

        val statusReport = "${reading.status}   •   ${reading.percent?.let { "$it%" } ?: "--"}   •   POWER SOURCE: ${reading.plugged}"
        val grouped = reading.supplies.entries.groupBy { KernelPresentation.group(it.key, reading.supplies) }
        val gauge = KernelGauge.select(reading.supplies, "power_supply/")
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
            appendLine("Charger-input voltage / current / power: Unavailable — no verified calibrated USB-side measurement")
            appendLine("Advertised maximum voltage: " + format(reading.maxChargingVoltageUv?.div(1_000_000.0), "V") + " (limit, not live)")
            appendLine("Advertised maximum current: " + format(reading.maxChargingCurrentUa?.div(1000.0), "mA") + " (limit, not live)")
            appendLine("SuperDart / VOOC active mode: unverified; Android AC status alone does not identify fast charging.")
            appendLine("Temperature: ${format(reading.temperatureC, "°C")}")
        }

        val chargerReport = buildString {
            appendLine("App process sysfs access: ${if (reading.supplyRootReadable) "directory listed" else "denied / unavailable"}")
            val age = KernelPresentation.ageMs(reading.supplies["@inventory_started_elapsed_ms"]?.toLongOrNull(),android.os.SystemClock.elapsedRealtime())
            appendLine("Cached inventory age: " + (age?.let { (it / 1000).toString() + " s" } ?: "unknown") +
                " • individual fields may have different acquisition times")
            val sections = listOf(
                KernelPresentation.Group.EXTERNAL to "External power-supply nodes",
                KernelPresentation.Group.BATTERY to "Battery / BMS nodes",
                KernelPresentation.Group.USB_INTERFACE to "USB-C / PD / role attributes (raw)",
                KernelPresentation.Group.THERMAL to "Thermal / hwmon (raw; sensor identity unverified)"
            )
            for ((group,label) in sections) {
                appendLine()
                appendLine(label)
                val entries = grouped[group].orEmpty()
                if (entries.isEmpty()) appendLine("Unavailable / not observed")
                else entries.forEach { appendLine(KernelPresentation.describe(it.key,it.value,reading.supplies)) }
            }
        }

        val onlineNodes = reading.supplies.filter { (key,value) ->
            key.endsWith("/online") && value == "1" &&
                KernelPresentation.group(key,reading.supplies) == KernelPresentation.Group.EXTERNAL
        }.keys
        val diagnosticReport = buildString {
            appendLine("Android battery status: ${reading.status}")
            appendLine("Android plug type: ${reading.plugged}")
            appendLine("Kernel external online flags: ${onlineNodes.joinToString().ifBlank { "not observed by app UID" }}")
            appendLine("Ordinary app sysfs: ${if (reading.supplyRootReadable) "directory visible; individual files may still be denied" else "not visible"}")
            appendLine("ACTION_BATTERY_CHANGED: " + if (reading.batteryBroadcastAvailable) "available" else "NOT AVAILABLE from Android sticky broadcast")
            appendLine("Battery percentage: " + (reading.percent?.let { "available (${it}%)" } ?: "unavailable — " + reading.batteryPercentStatus))
            appendLine("Battery voltage: raw EXTRA_VOLTAGE=${reading.rawVoltageMv ?: "missing"} mV field; quality=${voltageAssessment.status}")
            appendLine("Battery temperature: " + (reading.temperatureC?.let { "available (${it} °C)" } ?: "unavailable — EXTRA_TEMPERATURE missing or outside accepted range") +
                " • raw tenths °C field=" + (reading.rawTemperatureTenths?.toString() ?: "missing / wrong type"))
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
            append(buildMeasurementDiagnosis(reading.supplies, reading.supplyRootReadable, reading.plugged))
            append("Refresh: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} • 2-second dashboard refresh")
        }

        val compactReport = buildString {
            appendLine("${reading.percent?.let { "$it%" } ?: "—"}  •  ${reading.status}  •  ${reading.plugged}")
            appendLine("Temperature: " + format(reading.temperatureC,"°C"))
            appendLine("Battery: " + (reading.rawVoltageMv?.takeIf { voltageAssessment.usable }?.let { "$it mV" } ?: "voltage unavailable") +
                " / " + format(currentMa,"mA"))
            appendLine("Battery-side power: " + format(estimatedBatteryPowerW,"W"))
            appendLine("Advertised limits: " + format(reading.maxChargingVoltageUv?.div(1_000_000.0),"V") +
                " / " + format(reading.maxChargingCurrentUa?.div(1000.0),"mA"))
            append("Charger input / active protocol: unverified")
        }
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                dashboardScroll.refreshContent {
                    liveSummary.setTextIfChanged(compactReport)
                    val health = BatteryRecorderService.liveHealthReport()
                    monitorText.setTextIfChanged(statusReport + "\nRecorder: " + health)
                    recorderSummary.setTextIfChanged("Recorder: " + when {
                        recordingPaused() -> if (BatteryRecorderService.isRecorderLive()) "Pause requested • stopping" else "Paused by user"
                        else -> when (health.substringBefore(" • ")) {
                        "RECENT_SAMPLE_SAVED" -> "Recording • recent sample saved"
                        "WAITING_FOR_FIRST_SAMPLE" -> "Waiting for first sample"
                        "DEGRADED" -> "Sample failed • open recorder status"
                        "STALE" -> "Recording not confirmed • open recorder status"
                        else -> "Not running • open recorder status"
                        }
                    })
                    batteryText.setTextIfChanged(batteryReport)
                    chargerText.setTextIfChanged(chargerReport)
                    diagnosticText.setTextIfChanged(diagnosticReport)
                }
                refreshHistoryDisplay(false)
            }
        }
        } catch (t: Throwable) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) dashboardScroll.refreshContent {
                    diagnosticText.setTextIfChanged("LIVE TELEMETRY READ FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
                    liveSummary.setTextIfChanged("Live readings unavailable. See measurement quality & sources.")
                }
            }
        } finally {
            telemetryReadInFlight.set(false)
        }
        }
    }

    private fun recordingPaused(): Boolean = RecordingPreference.isPaused(
        getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE))

    private fun updateRecordingToggle() {
        if (::recordingToggle.isInitialized) recordingToggle.setTextIfChanged(
            if (recordingPaused()) "Resume history recording" else "Pause history recording")
    }

    private fun toggleRecording() {
        if (recordingPaused()) {
            val prefs = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
            RecordingPreference.setPaused(prefs,false)
            updateRecordingToggle()
            ensureRecorderRunning()
            refreshReadings()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pause history recording?")
            .setMessage("New background battery samples will stop after the recorder finishes current work. Existing history stays intact. Live dashboard readings remain available. Recording stays paused after reopening the app or restarting the phone until you resume it.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Pause") { _,_ ->
                val prefs = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
                RecordingPreference.setPaused(prefs,true)
                updateRecordingToggle()
                try {
                    stopService(Intent(this,BatteryRecorderService::class.java))
                    statusText.setTextIfChanged("Recording pause requested. Existing history retained.")
                } catch (e: Exception) {
                    statusText.setTextIfChanged("Pause saved, but stop request failed: " + e.javaClass.simpleName)
                }
                refreshReadings()
            }.show()
    }

    /** Automatic recording is the existing default; an explicit pause takes precedence. */
    private fun ensureRecorderRunning() {
        updateRecordingToggle()
        if (recordingPaused()) {
            recorderSummary.setTextIfChanged("Recorder: " +
                if (BatteryRecorderService.isRecorderLive()) "Pause requested • stopping" else "Paused by user")
            monitorText.setTextIfChanged("History recording paused by user. Live readings remain available.")
            // Also repair a previous failed stop request without re-enabling the recorder.
            if (BatteryRecorderService.isRecorderLive()) {
                try { stopService(Intent(this,BatteryRecorderService::class.java)) }
                catch (e: Exception) { statusText.setTextIfChanged("Recorder stop failed: " + e.javaClass.simpleName) }
            }
            updateSamplingModeButton()
            return
        }
        try {
            val intent = Intent(this, BatteryRecorderService::class.java)
                .setAction(BatteryRecorderService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            monitorText.text = "Automatic battery monitoring requested • awaiting recorder confirmation"
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), NOTIFICATION_REQUEST_CODE)
            }
            handler.postDelayed({
                if (!recordingPaused()) {
                    monitorText.text = "Recorder: " + BatteryRecorderService.liveHealthReport()
                    refreshHistoryDisplay(true)
                }
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
        if (!recordingPaused() && BatteryRecorderService.isRecorderLive()) {
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

    private fun openBatteryOptimisationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            statusText.text = "Could not open battery optimisation settings: " + (t.message ?: "unknown")
        }
    }

    private fun refreshHistoryDisplay(force: Boolean) {
        if (isFinishing || isDestroyed || !::stageSummary.isInitialized) return
        val now = System.currentTimeMillis()
        if (!historyRefreshGate.request(android.os.SystemClock.elapsedRealtime(), force)) return
        Thread({
            val result = runCatching {
                val database = RecorderDatabase(applicationContext)
                try { database.chargingOverview(now) } finally { database.close() }
            }
            runOnUiThread {
                val followUp = historyRefreshGate.finish()
                if (!isFinishing && !isDestroyed && ::stageSummary.isInitialized) {
                    dashboardScroll.refreshContent {
                        result.fold(onSuccess = { overview ->
                            stageSummary.setTextIfChanged(compactChargingStages(overview.stages))
                            curvePanel.setCurves(overview.curves)
                        }, onFailure = {
                            stageSummary.setTextIfChanged("Charging history unavailable • " + it.javaClass.simpleName +
                                "\nOpen detailed history for diagnostics.")
                            curvePanel.setCurves(emptyList())
                        })
                    }
                    if (followUp) refreshHistoryDisplay(true)
                }
            }
        },"BatteryScopeChargingOverview").start()
    }

    private fun updateHistoryOperations() {
        val state = HistoryOperations.state
        dashboardScroll.refreshContent {
            historyOperationText.setTextIfChanged(state.message + "\nPrepared private exports: " + state.ready.size +
                " • older unverified copies: " + state.legacy.size + " • interrupted files: " + state.interrupted.size)
        }
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
        val state = HistoryOperations.state
        val names = state.ready + state.legacy + if (discard) state.interrupted else emptyList()
        if (names.isEmpty()) {
            statusText.text = "No matching staging files. Prepare a CSV or backup first."
            HistoryOperations.discover(applicationContext, force = true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (discard) "Discard a private prepared copy" else "Choose a prepared export")
            .setItems(names.map { name ->
                when (HistoryTransfer.classify(name)) {
                    HistoryTransfer.Stage.LEGACY_UNVERIFIED -> "Older / completeness unknown: " + name
                    HistoryTransfer.Stage.INTERRUPTED -> "Interrupted / incomplete: " + name
                    else -> name
                }
            }.toTypedArray()) { _, index ->
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
                } else if (HistoryTransfer.classify(name) == HistoryTransfer.Stage.LEGACY_UNVERIFIED) {
                    AlertDialog.Builder(this)
                        .setTitle("Recover older staging copy?")
                        .setMessage("An older app wrote this file directly, so it may be incomplete. Copy verification cannot establish its original record count. Prefer preparing a new export from local history. The older private copy will be retained.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Copy anyway") { _, _ -> launchHistoryExport(name) }.show()
                } else launchHistoryExport(name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun launchHistoryExport(name: String) {
        if (historyBusy()) return
        val state = HistoryOperations.state
        if (!HistoryTransfer.isSaveName(name) || name !in (state.ready + state.legacy)) {
            statusText.text = "Prepared export unavailable. Refresh the prepared-file list."
            HistoryOperations.discover(applicationContext, force = true)
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
        if (requestCode == BACKUP_IMPORT_REQUEST_CODE || requestCode == LEGACY_IMPORT_REQUEST_CODE || requestCode == OVERLAP_IMPORT_REQUEST_CODE) {
            val expected = pendingImportRequest == requestCode
            pendingImportRequest = 0
            if (expected && resultCode == RESULT_OK) data?.data?.let {
                if (!HistoryOperations.restore(applicationContext, it, requestCode == LEGACY_IMPORT_REQUEST_CODE,
                    exactOverlap = requestCode == OVERLAP_IMPORT_REQUEST_CODE))
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
        if (name == null || !HistoryTransfer.isSaveName(name)) {
            statusText.text = "Export selection could not be recovered. No copy attempted; choose a prepared file again."
            return
        }
        if (!HistoryOperations.save(applicationContext, name, destination))
            statusText.text = "Another history operation is in progress. Prepared copy retained."
        updateHistoryOperations()
    }

    @Suppress("DEPRECATION")
    private fun chooseHistoryBackup(legacy: Boolean = false, exactOverlap: Boolean = false) {
        if (historyBusy()) return
        AlertDialog.Builder(this)
            .setTitle(if (legacy) "Import original CSV" else if (exactOverlap) "Recover earlier history with exact overlap" else "Restore earlier history")
            .setMessage(if (legacy) "Select the original, unedited 28-column BatteryScope CSV from this handset. CSV is not checksummed or lossless; missing fields and flattened text cannot be recovered. Existing history will not be overwritten and overlap is rejected." else if (exactOverlap) "Use only a portable backup from this same handset. Its source identity is not authenticated. Only earlier records can be inserted; overlapping records must match the existing sequence and every typed value exactly. Any conflict rolls back the entire operation. Later or interleaved records cannot be merged." else "Use only a BatteryScope .bsh backup from this handset. The format cannot prove its original device. Existing history will not be overwritten. Overlapping timestamps are rejected. Large backups may take time and temporary storage.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose backup") { _, _ ->
                if (!historyBusy()) {
                    pendingImportRequest = if (legacy) LEGACY_IMPORT_REQUEST_CODE else if (exactOverlap) OVERLAP_IMPORT_REQUEST_CODE else BACKUP_IMPORT_REQUEST_CODE
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
        private const val OVERLAP_IMPORT_REQUEST_CODE = 5105
        private const val NOTIFICATION_REQUEST_CODE = 5102
    }
}
