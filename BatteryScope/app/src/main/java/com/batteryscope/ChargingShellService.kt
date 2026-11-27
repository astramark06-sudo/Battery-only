package com.batteryscope

import android.os.Process
import org.json.JSONObject

/** Fixed, bounded, read-only operation; no paths or commands supplied by clients. */
class ChargingShellService : IChargingShell.Stub() {
    @Synchronized override fun snapshot(): String {
        check(Process.myUid() == 2000) { "ADB shell only" }
        return JSONObject(KernelInventory.scan(maxFields = 300, maxEntries = 3000,
            maxWorkMs = 1500L, chargingOnly = true)).put("@shell_uid",Process.myUid()).put("@protocol",2).toString()
    }
    override fun destroy() { System.exit(0) }
}
