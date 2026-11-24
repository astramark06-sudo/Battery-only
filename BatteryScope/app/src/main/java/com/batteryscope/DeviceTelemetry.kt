package com.batteryscope

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.TrafficStats
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.display.DisplayManager
import android.os.storage.StorageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock

/** Android public-API resource snapshots, with explicit source and status. */
internal object DeviceTelemetry {
    private const val MAX_SENSORS = 64
    private const val MAX_DISPLAYS = 8
    private const val MAX_VOLUMES = 16

    fun collect(context: Context): Map<String,String> {
        val result = linkedMapOf<String,String>()
        fun section(name: String, read: () -> Unit) {
            try {
                read()
                result["@device/$name/status"] = "AVAILABLE"
            } catch (e: SecurityException) {
                result["@device/$name/status"] = "UNAVAILABLE_PERMISSION"
            } catch (e: Exception) {
                result["@device/$name/status"] = "ERROR:" + e.javaClass.simpleName
            }
        }
        fun put(name: String, value: Any) { result["@device/$name"] = value.toString() }
        fun counter(name: String, value: Long) { put(name, if(value >= 0) value else "UNAVAILABLE") }
        put("captured_elapsed_ms",SystemClock.elapsedRealtime())
        put("captured_wall_ms",System.currentTimeMillis())
        put("sdk_int",Build.VERSION.SDK_INT)
        put("model",Build.MODEL)
        put("available_processors",Runtime.getRuntime().availableProcessors())
        put("supported_abis",Build.SUPPORTED_ABIS.joinToString(","))
        put("timezone_offset_ms",java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()))
        section("memory") {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            counter("memory/total_bytes",info.totalMem)
            counter("memory/available_bytes",info.availMem)
            counter("memory/low_threshold_bytes",info.threshold)
            put("memory/low_memory",info.lowMemory)
        }
        section("storage") {
            val stats = StatFs(context.filesDir.absolutePath)
            counter("storage/total_bytes",stats.totalBytes)
            counter("storage/available_bytes",stats.availableBytes)
        }
        section("storage_volumes") {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = manager.storageVolumes
            put("storage_volumes/count",volumes.size)
            put("storage_volumes/truncated",volumes.size > MAX_VOLUMES)
            volumes.take(MAX_VOLUMES).forEachIndexed { index, volume ->
                val path = "storage_volumes/" + index
                // No UUID, label, user-named path or persistent identifier.
                put("$path/state",volume.state)
                put("$path/primary",volume.isPrimary)
                put("$path/removable",volume.isRemovable)
                put("$path/emulated",volume.isEmulated)
            }
        }
        section("display") {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displays = manager.displays.sortedBy { it.displayId }
            put("display/count",displays.size)
            put("display/truncated",displays.size > MAX_DISPLAYS)
            displays.take(MAX_DISPLAYS).forEachIndexed { index, display ->
                val path = "display/" + index
                val mode = display.mode
                put("$path/logical_id",display.displayId)
                put("$path/state_code",display.state)
                put("$path/mode_width_px",mode.physicalWidth)
                put("$path/mode_height_px",mode.physicalHeight)
                put("$path/mode_refresh_hz",mode.refreshRate.takeIf { it.isFinite() && it > 0f } ?: "UNAVAILABLE")
                put("$path/supported_mode_count",display.supportedModes.size)
            }
            put("display/meaning","configuration snapshot, not measured panel power")
        }
        section("connectivity") {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = manager.activeNetwork
            if (active == null) put("connectivity/active_network","NONE_OBSERVED")
            else {
                val caps = manager.getNetworkCapabilities(active)
                if (caps == null) put("connectivity/active_network","CAPABILITIES_UNAVAILABLE")
                else {
                    put("connectivity/active_network","OBSERVED")
                    val transportTypes = mutableListOf(
                        NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
                        NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
                        NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
                        NetworkCapabilities.TRANSPORT_VPN to "VPN",
                        NetworkCapabilities.TRANSPORT_BLUETOOTH to "BLUETOOTH",
                        NetworkCapabilities.TRANSPORT_WIFI_AWARE to "WIFI_AWARE")
                    if (Build.VERSION.SDK_INT >= 31) transportTypes.add(NetworkCapabilities.TRANSPORT_USB to "USB")
                    put("connectivity/transports",transportTypes.filter { caps.hasTransport(it.first) }
                        .joinToString(",") { it.second }.ifBlank { "OTHER_OR_UNREPORTED" })
                    put("connectivity/validated",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    put("connectivity/not_metered",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                    put("connectivity/internet_capability",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            }
            // Snapshot only: no DNS, IP, SSID, BSSID, traffic contents, or active probes.
            put("connectivity/meaning","OS-reported snapshot; may change immediately")
        }
        section("network") {
            counter("network/total_rx_bytes_since_boot",TrafficStats.getTotalRxBytes())
            counter("network/total_tx_bytes_since_boot",TrafficStats.getTotalTxBytes())
            counter("network/mobile_rx_bytes_since_boot",TrafficStats.getMobileRxBytes())
            counter("network/mobile_tx_bytes_since_boot",TrafficStats.getMobileTxBytes())
            counter("network/own_uid_rx_bytes_since_boot",TrafficStats.getUidRxBytes(android.os.Process.myUid()))
            counter("network/own_uid_tx_bytes_since_boot",TrafficStats.getUidTxBytes(android.os.Process.myUid()))
        }
        section("power") {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            put("power/power_save",pm.isPowerSaveMode)
            put("power/device_idle",pm.isDeviceIdleMode)
            put("power/interactive",pm.isInteractive)
            if(Build.VERSION.SDK_INT >= 29) {
                put("power/thermal_status",pm.currentThermalStatus)
                result["@android_thermal_status"] = pm.currentThermalStatus.toString()
            } else put("power/thermal_status","UNAVAILABLE_API")
            if(Build.VERSION.SDK_INT >= 30) {
                val headroom = pm.getThermalHeadroom(0)
                put("power/thermal_headroom",if(headroom.isFinite() && headroom >= 0f) headroom else "UNAVAILABLE")
            } else put("power/thermal_headroom","UNAVAILABLE_API")
        }
        section("sensors") {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensors = sm.getSensorList(Sensor.TYPE_ALL)
            put("sensors/count",sensors.size)
            // Inventory only; no sensor listeners or physical sensor sampling.
            put("sensors/types",sensors.map { it.stringType }.distinct().sorted().joinToString(",").take(2048))
            put("sensors/details_truncated",sensors.size > MAX_SENSORS)
            sensors.take(MAX_SENSORS).forEachIndexed { index, sensor ->
                // Compact inventory, not sensor measurements or an individual sensor's live drain.
                val info = org.json.JSONObject()
                    .put("name",sensor.name.take(80)).put("vendor",sensor.vendor.take(80))
                    .put("type",sensor.stringType.take(100)).put("version",sensor.version)
                    .put("range",sensor.maximumRange.takeIf { it.isFinite() } ?: "UNAVAILABLE")
                    .put("resolution",sensor.resolution.takeIf { it.isFinite() } ?: "UNAVAILABLE")
                    .put("declared_power_ma",sensor.power.takeIf { it.isFinite() && it >= 0f } ?: "UNAVAILABLE")
                    .put("min_delay_us",sensor.minDelay).put("wake_up",sensor.isWakeUpSensor)
                put("sensors/details/" + index,info.toString())
            }
            put("sensors/meaning","descriptor inventory only; no sensor streams")
        }
        put("collection_finished_elapsed_ms",SystemClock.elapsedRealtime())
        put("collection_finished_wall_ms",System.currentTimeMillis())
        return result
    }
}

/** Reuse bounded inventories across the Activity and Service within this process. */
internal object HardwareSnapshotCache {
    private var last = emptyMap<String,String>()
    private var completedAt = -1L
    @Synchronized fun snapshot(context: Context, maxAgeMs: Long): Map<String,String> {
        val now = SystemClock.elapsedRealtime()
        if(completedAt >= 0 && now - completedAt in 0 until maxAgeMs) return last
        val startedWall = System.currentTimeMillis()
        val fields = KernelInventory.scan().toMutableMap()
        fields.putAll(DeviceTelemetry.collect(context.applicationContext))
        completedAt = SystemClock.elapsedRealtime()
        fields["@inventory_started_elapsed_ms"] = now.toString()
        fields["@inventory_started_wall_ms"] = startedWall.toString()
        fields["@inventory_completed_elapsed_ms"] = completedAt.toString()
        fields["@inventory_completed_wall_ms"] = System.currentTimeMillis().toString()
        last = fields.toMap()
        return last
    }
}
