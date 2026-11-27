package com.batteryscope

import android.content.Intent
import android.os.BatteryManager
import org.json.JSONObject

/** Allowlisted scalar extras only; never serialize arbitrary broadcast extras. */
internal object BatteryBroadcastMetadata {
    private val integerKeys = listOf(BatteryManager.EXTRA_LEVEL, BatteryManager.EXTRA_SCALE,
        BatteryManager.EXTRA_VOLTAGE, BatteryManager.EXTRA_TEMPERATURE, BatteryManager.EXTRA_STATUS,
        BatteryManager.EXTRA_PLUGGED, BatteryManager.EXTRA_HEALTH, "max_charging_current",
        "max_charging_voltage", "invalid_charger", "cycle_count")

    @Suppress("DEPRECATION")
    private fun raw(intent: Intent?, key: String): Any? = intent?.extras?.get(key)

    fun int(intent: Intent?, key: String): Int? = raw(intent,key) as? Int
    fun bool(intent: Intent?, key: String): Boolean? = raw(intent,key) as? Boolean
    fun advertised(intent: Intent?, key: String): Int? =
        int(intent,key)?.takeIf { it in 1 until Int.MAX_VALUE }
    fun cycles(intent: Intent?): Int? = int(intent,"cycle_count")?.takeIf { it in 0 until Int.MAX_VALUE }

    // Broad rejection-only range, not a claim that this is a safe operating temperature.
    fun temperatureC(rawTenths: Int?): Double? = rawTenths?.takeIf { it in -1000..1000 }?.div(10.0)

    fun capture(intent: Intent?): JSONObject {
        val values = JSONObject()
        val invalidTypes = JSONObject()
        for (key in integerKeys + listOf("present","technology")) {
            val value = raw(intent,key)
            val valid = when (key) {
                "present" -> value is Boolean
                "technology" -> value is String
                else -> value is Int
            }
            if (valid) values.put(key,value)
            else if (intent?.hasExtra(key) == true)
                invalidTypes.put(key,value?.javaClass?.simpleName ?: "NULL")
        }
        return JSONObject().put("available",intent != null)
            .put("values",values).put("invalid_types",invalidTypes)
    }
}
