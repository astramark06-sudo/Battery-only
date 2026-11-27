package com.batteryscope

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** Explicit opt-in for this process only. Never prompt or reconnect from background. */
internal object ShellChargingDiagnostics {
    @Volatile var enabled = false
        private set
    @Volatile var status = "OFF"
        private set
    @Volatile private var remote: IChargingShell? = null
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null

    private fun permitted(): Boolean = Shizuku.pingBinder() && !Shizuku.isPreV11() &&
        Shizuku.getUid() == 2000 && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    // Called only from the UI thread.
    fun enable(context: Context): String {
        if (enabled) return status
        if (!permitted()) return "Shizuku ADB permission unavailable"
        val request = Shizuku.UserServiceArgs(ComponentName(context, ChargingShellService::class.java))
            .daemon(false).processNameSuffix("charging_diagnostics").tag("charging_read_only").version(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this || !enabled) return
                remote = IChargingShell.Stub.asInterface(service)
                status = "CONNECTED — raw readings saved with inventory"
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                remote = null
                status = "DISCONNECTED — disable then enable after starting Shizuku"
            }
        }
        args = request
        connection = conn
        enabled = true
        status = "CONNECTING"
        return try {
            Shizuku.bindUserService(request, conn)
            status
        } catch (e: Exception) {
            disable()
            "Shizuku bind failed: " + e.javaClass.simpleName
        }
    }

    fun disable() {
        enabled = false
        remote = null
        val request = args
        val conn = connection
        connection = null
        args = null
        if (request != null && conn != null) {
            try { Shizuku.unbindUserService(request, conn, true) } catch (_: Exception) {}
            try { Shizuku.unbindUserService(request, conn, false) } catch (_: Exception) {}
        }
        status = "OFF"
    }

    /** Called by the separate inventory worker; ordinary sampling does not wait for IPC. */
    fun snapshot(): Map<String, String> {
        if (!enabled) return emptyMap()
        val service = remote ?: return mapOf("@shizuku/status" to status)
        return try {
            if (!permitted()) {
                status = "PERMISSION_OR_SERVER_UNAVAILABLE"
                return mapOf("@shizuku/status" to status)
            }
            val json = JSONObject(service.snapshot())
            if (!enabled || remote !== service || !permitted()) return emptyMap()
            val result = linkedMapOf("@shizuku/status" to "RAW_SHELL_UID_2000")
            val keys = json.keys()
            while (keys.hasNext() && result.size < 400) {
                val key = keys.next()
                val value = json.opt(key)
                if (key.length <= 512 && value is String && value.length <= 300) {
                    result["@shizuku/" + key] = value
                }
            }
            status = "CONNECTED — last inventory returned " + (result.size - 1) + " raw fields/diagnostics"
            result
        } catch (e: Exception) {
            status = "READ_FAILED: " + e.javaClass.simpleName
            mapOf("@shizuku/status" to status)
        }
    }
}
