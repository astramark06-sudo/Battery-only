package com.batteryscope

import java.io.File
import java.nio.file.Files

/** Bounded, allowlisted, ordinary-UID read-only inventory. No control commands or writes. */
internal object KernelInventory {
    data class Root(val name: String, val path: String, val depth: Int)
    val roots = listOf(
        Root("power_supply", "/sys/class/power_supply", 2),
        Root("typec", "/sys/class/typec", 3),
        Root("usb_power_delivery", "/sys/class/usb_power_delivery", 4),
        Root("dual_role_usb", "/sys/class/dual_role_usb", 2),
        Root("usb_role", "/sys/class/usb_role", 2),
        Root("thermal", "/sys/class/thermal", 2),
        Root("hwmon", "/sys/class/hwmon", 2),
        Root("devfreq", "/sys/class/devfreq", 2),
        Root("cpu", "/sys/devices/system/cpu", 2),
        Root("devices", "/sys/bus/usb/devices", 2)
    )
    private val powerFields = setOf(
        "type","online","present","status","health","technology","capacity","capacity_level",
        "voltage_now","voltage_avg","voltage_max","voltage_min","current_now","current_avg","current_max","power_now",
        "charge_counter","charge_now","charge_full","charge_full_design","energy_now","energy_full","energy_full_design",
        "cycle_count","temp","temp_alert_min","temp_alert_max","charge_type","usb_type","real_type",
        "input_current_limit","input_current_limit_max","input_voltage_limit","input_voltage_limit_max","input_power_limit",
        "constant_charge_current","constant_charge_voltage","constant_charge_current_max","constant_charge_voltage_max",
        "charge_control_limit","charge_control_limit_max","charge_control_start_threshold","charge_control_end_threshold",
        "current_limit","voltage_limit","max_voltage","max_current","fastcharger","fast_chg_type","voocchg_ing",
        "charge_technology","adapter_fw_update","mmi_charging_enable","stop_charging_enable","chargerid_volt",
        "charger_ic","usb_status","typec_cc_orientation","typec_sbu_voltage"
    )
    private val roleFields = setOf("data_role","power_role","port_type","preferred_role","power_operation_mode",
        "orientation","accessory_mode","usb_power_delivery_revision","usb_typec_revision","supports_usb_power_delivery",
        "mode","role","revision","version","voltage","maximum_current","operational_current","maximum_power","operational_power")
    private val frequencyFields = setOf("name","cur_freq","min_freq","max_freq","available_frequencies",
        "scaling_cur_freq","scaling_min_freq","scaling_max_freq","cpuinfo_cur_freq","cpuinfo_min_freq","cpuinfo_max_freq",
        "scaling_available_frequencies","scaling_governor","governor","affected_cpus","related_cpus","online","present","possible")
    private val usbFields = setOf("idVendor","idProduct","bcdDevice","manufacturer","product","bDeviceClass",
        "bDeviceSubClass","bDeviceProtocol","speed","authorized")
    private val thermalField = Regex("(temp[0-9]+_(input|label|max|min|crit|alarm)|trip_point_[0-9]+_(temp|type))")
    private val skipDirs = setOf("device","subsystem","driver","power","firmware_node","of_node","statistics",
        "debug","debugfs","trace","tracing","wakeup","uevent","of_node")
    internal fun allowed(root: String, name: String): Boolean = when(root) {
        "power_supply" -> name in powerFields
        "typec","usb_power_delivery","dual_role_usb","usb_role" -> name in roleFields
        "thermal","hwmon" -> name in setOf("type","temp","name","cur_state","max_state") || thermalField.matches(name)
        "cpu","devfreq" -> name in frequencyFields
        "devices" -> name in usbFields
        else -> false
    }
    internal fun allowedDirectory(name: String): Boolean = name !in skipDirs
    internal fun inSysfs(path: String): Boolean = path == "/sys" || path.startsWith("/sys/")

    /** Read at most a bounded prefix, never an unbounded line or binary descriptor. */
    internal fun readPrefix(file: File): String = try {
        if (!file.canRead()) "READ_DENIED"
        else file.inputStream().use { input ->
            val bytes = ByteArray(241)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes,count,bytes.size-count)
                if (n <= 0) break
                count += n
            }
            if (count == 0) "EMPTY" else {
                val text = String(bytes,0,minOf(count,240),Charsets.UTF_8)
                if (count > 240) text + " [TRUNCATED]" else text.trimEnd('\r','\n')
            }
        }
    } catch (e: Exception) { "ERROR:" + e.javaClass.simpleName }

    /** Stream directory entries without allocating or sorting the entire listing.
     * The callback may stop enumeration before the next entry. All streams close on exit.
     */
    internal fun visitEntries(dir: File, shouldContinue: () -> Boolean, visit: (File) -> Unit): Boolean {
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (shouldContinue()) {
                if (!iterator.hasNext()) return true
                visit(iterator.next().toFile())
            }
            return false
        }
    }

    fun scan(maxFields: Int = 900, maxEntries: Int = 6000, maxWorkMs: Long = 2_000L): Map<String,String> {
        require(maxFields > 0 && maxEntries > 0 && maxWorkMs in 1L..60_000L)
        val result = linkedMapOf<String,String>()
        val started = System.nanoTime()
        fun expired() = System.nanoTime() - started >= maxWorkMs * 1_000_000L
        var count = 0
        var entries = 0
        var capped = false
        var visible = 0
        for ((index, spec) in roots.withIndex()) {
            val root = File(spec.path)
            if (expired() || count >= maxFields || entries >= maxEntries) {
                result[spec.name + "/@root"] = if (expired()) "NOT_SCANNED_TIME_BUDGET" else "NOT_SCANNED_LIMIT"
                capped = true
                continue
            }
            if (!root.exists()) {
                result[spec.name + "/@root"] = "ABSENT_OR_HIDDEN_BY_SELINUX"
                continue
            }
            // Reserve capacity for subsequent roots. Power supplies get triple weight;
            // unused budget flows to the remaining roots. The total caps still apply.
            val weight = if (index == 0) 3 else 1
            val shares = roots.size - index + weight - 1
            val rootFieldEnd = count + ((maxFields - count).toLong() * weight / shares).toInt().coerceAtLeast(1)
            val rootEntryEnd = entries + ((maxEntries - entries).toLong() * weight / shares).toInt().coerceAtLeast(1)
            val seen = mutableSetOf<String>()
            var rootLimited = false
            var listingFailed = false
            fun available() = !expired() && count < minOf(maxFields,rootFieldEnd) &&
                entries < minOf(maxEntries,rootEntryEnd)
            fun walk(dir: File, depth: Int) {
                if (depth > spec.depth || !available()) return
                val canonical = try { dir.canonicalPath } catch (_: Exception) { return }
                if (!inSysfs(canonical) || !seen.add(canonical)) return
                val relative = dir.path.removePrefix(root.path).trim('/')
                try {
                    val complete = visitEntries(dir, ::available) { child ->
                        entries++
                        if (child.isDirectory) {
                            if (allowedDirectory(child.name)) walk(child,depth+1)
                        } else if (allowed(spec.name,child.name) && child.isFile) {
                            val resolved = try { child.canonicalPath } catch (_: Exception) { null }
                            if (resolved != null && inSysfs(resolved) && !expired()) {
                                val path = child.path.removePrefix(root.path).trim('/')
                                result[spec.name + "/" + path] = readPrefix(child)
                                count++
                            }
                        }
                    }
                    if (!complete) rootLimited = true
                    if (depth == 0) {
                        result[spec.name + "/@root"] = "VISIBLE"
                        visible++
                    }
                } catch (e: Exception) {
                    listingFailed = true
                    result[spec.name + "/" + (if (relative.isEmpty()) "@root" else "$relative/@list")] =
                        "LIST_ERROR:" + e.javaClass.simpleName
                }
            }
            walk(root,0)
            if (rootLimited || !available()) {
                capped = true
                result[spec.name + "/@scan"] = if (expired()) "TIME_BUDGET_REACHED" else "ROOT_BUDGET_REACHED"
            } else result[spec.name + "/@scan"] = if (listingFailed) "PARTIAL_LIST_ERROR" else "FINISHED"
            if (!result.containsKey(spec.name + "/@root")) result[spec.name + "/@root"] = "NOT_ENUMERATED"
        }
        result["@field_count"] = count.toString()
        result["@field_limit"] = maxFields.toString()
        result["@field_limit_reached"] = (count >= maxFields).toString()
        result["@scan_truncated"] = capped.toString()
        result["@entries_examined"] = entries.toString()
        result["@entry_limit"] = maxEntries.toString()
        result["@visible_roots"] = visible.toString()
        result["@scan_work_ms"] = ((System.nanoTime() - started) / 1_000_000L).toString()
        result["@scan_time_budget_ms"] = maxWorkMs.toString()
        result["@scan_time_budget_reached"] = expired().toString()
        result["@inventory_policy"] = "ALLOWLIST_V2"
        return result
    }

    /** Existing dashboard uses short power-supply paths, while persistent JSON keeps full root prefixes. */
    fun dashboard(fields: Map<String,String>): Map<String,String> =
        fields.mapKeys { (key,_) ->
            when {
                key.endsWith("/@root") -> "@diagnostic/" + key.removeSuffix("/@root") + "/root"
                key.startsWith("power_supply/") -> key.removePrefix("power_supply/")
                else -> key
            }
        }
}
