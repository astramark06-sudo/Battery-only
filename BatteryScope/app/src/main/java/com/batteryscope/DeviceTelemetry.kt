package com.batteryscope

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Only charging-relevant thermal status; no unrelated device resource collection. */
internal object DeviceTelemetry {
    fun collect(context: Context): Map<String,String> {
        val result = linkedMapOf<String,String>()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                result["@android_thermal_status"] = pm.currentThermalStatus.toString()
                result["@charging/thermal_status_quality"] = "ANDROID_FRAMEWORK_STATUS_NOT_TEMPERATURE"
            } catch (e: Exception) {
                result["@charging/thermal_status_quality"] = "ERROR:" + e.javaClass.simpleName
            }
        } else result["@charging/thermal_status_quality"] = "UNAVAILABLE_API"
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
        val fields = KernelInventory.scan(chargingOnly = true).toMutableMap()
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
