package com.batteryscope

import android.os.Process
import org.json.JSONObject

/** Fixed, bounded, read-only operation; no paths or commands supplied by clients. */
class ChargingShellService : IChargingShell.Stub() {
    @Synchronized override fun snapshot(): String {
        check(Process.myUid() == 2000) { "ADB shell only" }
        val fields = KernelInventory.scan(maxFields = 300, maxEntries = 3000,
            maxWorkMs = 1500L, chargingOnly = true).toMutableMap()
        // Separate framework battery dump as a cross-check even when sysfs is denied.
        try {
            val process = ProcessBuilder("/system/bin/toybox","timeout","5","/system/bin/dumpsys","-t","4","battery")
                .redirectErrorStream(true).start()
            fields.putAll(ShellReadPolicy.readFixedProcess(process))
            fields["@battery_dump_status"] = "COMPLETED"
        } catch (e: Exception) { fields["@battery_dump_status"] = "FAILED:" + e.javaClass.simpleName }
        return JSONObject(fields).put("@shell_uid",Process.myUid()).put("@protocol",2).toString()
    }
    override fun destroy() { System.exit(0) }
}
