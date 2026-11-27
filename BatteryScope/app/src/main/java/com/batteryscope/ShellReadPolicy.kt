package com.batteryscope

/** Pure acceptance/age checks, independent of Android and the privileged transport. */
internal object ShellReadPolicy {
    const val MAX_AGE_MS = 130_000L
    fun fresh(generation: Long, current: Long, started: Long, now: Long): Boolean =
        generation == current && started >= 0 && now >= started && now - started <= MAX_AGE_MS
    fun acceptsService(uid: Int, protocol: Int) = uid == 2000 && protocol == 2
    private val dumpKeys = setOf("AC powered","USB powered","Wireless powered","Dock powered",
        "Max charging current","Max charging voltage","Charge counter","status","health",
        "present","level","scale","voltage","temperature","technology","cycle count",
        "Charging state","Charging policy")
    val nodes = listOf("battery","bms","usb","ac","charger","main")
    val fields = listOf("type","status","online","voltage_now","current_now","current_avg",
        "voltage_max","current_max","input_current_limit","input_voltage_limit","charge_counter",
        "charge_full","charge_full_design","cycle_count","temp","usb_type","real_type","charge_type","power_now")
    fun parseLegacy(text: String): Map<String,String> {
        val result = linkedMapOf<String,String>()
        for (line in text.lineSequence()) {
            if (result.size >= 180) break
            if (line.startsWith("SYS\t")) {
                val parts = line.split('\t',limit=3)
                if (parts.size != 3) continue
                val path = parts[1].split('/')
                if (path.size == 2 && path[0] in nodes && path[1] in fields)
                    result.putIfAbsent("power_supply/"+parts[1],parts[2].take(160))
            } else {
                val key = line.substringBefore(':').trim()
                if (':' in line && key in dumpKeys)
                    result.putIfAbsent("battery_dump/"+key,line.substringAfter(':').trim().take(160))
            }
        }
        return result
    }
    /** Constant command, never accepts user input or output as executable text. */
    fun legacyCommand(): Array<String> {
        val script = buildString {
            append("/system/bin/dumpsys -t 4 battery; ")
            for (node in nodes) for (field in fields) {
                val path = "/sys/class/power_supply/$node/$field"
                append("if [ -f '$path' ]; then printf 'SYS\\t$node/$field\\t'; ")
                append("if [ -r '$path' ]; then /system/bin/head -c 160 '$path' 2>/dev/null; ")
                append("else printf READ_DENIED; fi; printf '\\n'; fi; ")
            }
        }
        return arrayOf("/system/bin/toybox","timeout","12","/system/bin/sh","-c",script)
    }
}
