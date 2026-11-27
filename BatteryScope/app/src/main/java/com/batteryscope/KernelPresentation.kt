package com.batteryscope

import java.util.Locale

/** Preserve inventory root identity; only direct power_supply attributes form candidates. */
internal object KernelPresentation {
    enum class Group { BATTERY, EXTERNAL, USB_INTERFACE, THERMAL, COMPUTE, USB_DEVICE, OTHER, METADATA }
    data class Candidate(val source: String, val voltageUv: Long, val currentUa: Long)
    data class UsbAssessment(val status: String, val candidate: Candidate? = null)

    private val batteryNames = setOf("battery", "bms", "main")
    fun supplyPaths(fields: Map<String, String>): List<String> =
        fields.keys.mapNotNull { key ->
            val parts = key.split('/')
            if (parts.size == 3 && parts[0] == "power_supply" && parts[1].isNotBlank() &&
                !parts[1].startsWith("@") && !parts[2].startsWith("@")) parts.take(2).joinToString("/")
            else null
        }.distinct().sorted()

    fun group(key: String, fields: Map<String, String>): Group {
        if (key.startsWith("@") || key.split('/').any { it.startsWith("@") }) return Group.METADATA
        return when (key.substringBefore('/')) {
            "power_supply" -> {
                val parts = key.split('/')
                if (parts.size != 3) Group.OTHER
                else if (parts[1].lowercase(Locale.US) in batteryNames ||
                    fields["power_supply/" + parts[1] + "/type"]?.equals("Battery", true) == true) Group.BATTERY
                else Group.EXTERNAL
            }
            "typec", "usb_power_delivery", "dual_role_usb", "usb_role" -> Group.USB_INTERFACE
            "thermal", "hwmon" -> Group.THERMAL
            "cpu", "devfreq" -> Group.COMPUTE
            "devices" -> Group.USB_DEVICE
            else -> Group.OTHER
        }
    }

    fun ageMs(captured: Long?, now: Long): Long? =
        captured?.takeIf { it >= 0L && now >= it }?.let { now - it }

    fun usb(fields: Map<String, String>, now: Long, plug: String, maxAgeMs: Long): UsbAssessment {
        require(maxAgeMs > 0)
        val age = ageMs(fields["@inventory_started_elapsed_ms"]?.toLongOrNull(), now)
            ?: return UsbAssessment("CAPTURE_TIME_UNKNOWN")
        if (age > maxAgeMs) return UsbAssessment("STALE_INVENTORY")
        if (plug !in setOf("AC", "USB")) return UsbAssessment("FRAMEWORK_WIRED_SOURCE_NOT_CONFIRMED")
        val candidates = supplyPaths(fields).mapNotNull { path ->
            if (group("$path/type", fields) != Group.EXTERNAL) return@mapNotNull null
            val name = path.substringAfterLast('/')
            val type = fields["$path/type"].orEmpty()
            if ((!name.contains("usb", true) && !type.contains("USB", true)) || fields["$path/online"] != "1")
                return@mapNotNull null
            val v = fields["$path/voltage_now"]?.toLongOrNull()?.takeIf { it in 1_000_000L..30_000_000L }
                ?: return@mapNotNull null
            val i = fields["$path/current_now"]?.toLongOrNull()?.takeIf { it in -20_000_000L..20_000_000L }
                ?: return@mapNotNull null
            Candidate(path, v, i)
        }
        return when (candidates.size) {
            0 -> UsbAssessment("NO_PLAUSIBLE_SAME_SUPPLY_PAIR")
            1 -> UsbAssessment("CACHED_UNCALIBRATED_CANDIDATE", candidates.single())
            else -> UsbAssessment("AMBIGUOUS_MULTIPLE_USB_SUPPLIES")
        }
    }

    private val voltage = setOf("voltage_now","voltage_avg","voltage_max","voltage_min","input_voltage_limit",
        "input_voltage_limit_max","constant_charge_voltage","constant_charge_voltage_max")
    private val current = setOf("current_now","current_avg","input_current_limit","constant_charge_current","constant_charge_current_max")
    private val charge = setOf("charge_counter","charge_now","charge_full","charge_full_design")
    private val vendor = setOf("fastcharger","fast_chg_type","voocchg_ing","charge_technology","adapter_fw_update",
        "mmi_charging_enable","stop_charging_enable","chargerid_volt","charger_ic","usb_status",
        "typec_cc_orientation","typec_sbu_voltage","charge_control_limit","charge_control_limit_max",
        "current_max","input_current_limit_max","current_limit","voltage_limit","max_voltage","max_current")

    fun describe(key: String, raw: String, fields: Map<String, String>): String {
        val group = group(key,fields)
        if (group != Group.BATTERY && group != Group.EXTERNAL) return "$key = $raw"
        val field = key.substringAfterLast('/')
        val n = raw.toLongOrNull()
        val annotation = when {
            field in vendor -> " [raw; driver-specific units/meaning not verified]"
            n == null -> ""
            field in voltage -> String.format(Locale.US," [%.3f V if standard µV]",n/1_000_000.0)
            field in current -> String.format(Locale.US," [%.1f mA if standard µA]",n/1000.0)
            field in charge -> String.format(Locale.US," [%.1f mAh if standard µAh]",n/1000.0)
            field in setOf("power_now","input_power_limit") -> String.format(Locale.US," [%.3f W if standard µW]",n/1_000_000.0)
            field == "capacity" && n in 0L..100L -> " [standard percent field; unverified]"
            else -> ""
        }
        return "$key = $raw$annotation"
    }
}
