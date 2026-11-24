package com.batteryscope

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.TrafficStats
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock

/** Android public-API resource snapshots, with explicit source and status. */
internal object DeviceTelemetry {
    fun collect(context: Context): Map<String,String> {
        val result = linkedMapOf<String,String>()
        fun section(name: String, read: () -> Unit) {
            try {
                read()
                result["@device/$name/status"] = "AVAILABLE"
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
        }
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
        val fields = KernelInventory.scan().toMutableMap()
        fields.putAll(DeviceTelemetry.collect(context.applicationContext))
        completedAt = SystemClock.elapsedRealtime()
        fields["@inventory_completed_elapsed_ms"] = completedAt.toString()
        last = fields.toMap()
        return last
    }
}
