package com.batteryscope

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local explicit opt-in. No read or connection failure can block normal inventory. */
internal object ShellChargingDiagnostics {
    private val main = Handler(Looper.getMainLooper())
    private val primaryExecutor = Executors.newSingleThreadExecutor { Thread(it,"BatteryScopeShellRead").apply { isDaemon = true } }
    private val legacyExecutor = Executors.newSingleThreadExecutor { Thread(it,"BatteryScopeShellCompat").apply { isDaemon = true } }
    private val primaryBusy = AtomicBoolean()
    private val legacyBusy = AtomicBoolean()
    private val refreshQueued = AtomicBoolean()
    @Volatile var enabled = false
        private set
    @Volatile var status = "OFF"
        private set
    @Volatile private var generation = 0L
    @Volatile private var latest: Reading? = null
    private data class Reading(val generation: Long,val started: Long,val finished: Long,val source: String,val data: Map<String,String>)
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var remote: IChargingShell? = null
    private var fallback = false
    private var lastRequest = -1L
    private var timeout: Runnable? = null
    private var fallbackReason = ""
    private val dead = Shizuku.OnBinderDeadListener { main.post { if (enabled) stop("SERVER_STOPPED — restart Shizuku and enable again") } }

    private fun permitted(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getVersion() >= 12 &&
            Shizuku.getUid() == 2000 && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun serverDescription(): String = try {
        if (!Shizuku.pingBinder()) "server not connected"
        else "API ${Shizuku.getVersion()}, patch ${Shizuku.getServerPatchVersion()}, UID ${Shizuku.getUid()}, authorized=${Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED}"
    } catch (e: Exception) { "unavailable: " + e.javaClass.simpleName }

    // All connection transitions run on main. Callbacks are guarded by generation.
    fun enable(context: Context): String {
        if (enabled) return status
        if (!permitted()) return "Shizuku v12+ ADB shell permission unavailable"
        enabled = true
        generation++
        latest = null
        fallback = false
        fallbackReason = ""
        lastRequest = -1L
        Shizuku.addBinderDeadListener(dead)
        val token = generation
        val request = Shizuku.UserServiceArgs(ComponentName(context, ChargingShellService::class.java))
            .daemon(false).processNameSuffix("charging_diagnostics").tag("charging_read_only").version(2)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                main.post {
                    if (!enabled || generation != token || connection !== this || fallback) return@post
                    cancelTimeout()
                    remote = IChargingShell.Stub.asInterface(service)
                    status = "VERIFYING_USER_SERVICE"
                    requestRead(true)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                main.post {
                    if (enabled && generation == token && connection === this && !fallback)
                        switchToFallback("USER_SERVICE_DISCONNECTED")
                }
            }
        }
        args = request
        connection = conn
        status = "CONNECTING_USER_SERVICE"
        armTimeout(35_000L) {
            if (enabled && generation == token && remote == null && !fallback)
                switchToFallback("USER_SERVICE_CONNECT_TIMEOUT")
        }
        try { Shizuku.bindUserService(request,conn) }
        catch (e: Exception) { switchToFallback("BIND_FAILED_" + e.javaClass.simpleName) }
        return status
    }

    private fun cancelTimeout() { timeout?.let { main.removeCallbacks(it) }; timeout = null }
    private fun armTimeout(ms: Long, action: () -> Unit) {
        cancelTimeout()
        val task = Runnable { timeout = null; action() }
        timeout = task
        main.postDelayed(task,ms)
    }

    private fun detach() {
        val request = args
        val conn = connection
        remote = null
        connection = null
        args = null
        if (request != null && conn != null) {
            try { Shizuku.unbindUserService(request,conn,true) } catch (_: Exception) {}
            try { Shizuku.unbindUserService(request,conn,false) } catch (_: Exception) {}
        }
    }

    fun disable() { stop("OFF") }
    private fun stop(reason: String) {
        if (!enabled) { status = reason; return }
        enabled = false
        generation++
        latest = null
        cancelTimeout()
        Shizuku.removeBinderDeadListener(dead)
        detach()
        fallback = false
        status = reason
    }

    private fun switchToFallback(reason: String) {
        if (!enabled || fallback) return
        if (!permitted()) { stop("PERMISSION_OR_SERVER_UNAVAILABLE"); return }
        generation++ // discard all outstanding primary replies and callbacks
        latest = null
        cancelTimeout()
        fallback = true
        fallbackReason = reason
        status = reason + " — TRYING_READ_ONLY_COMPATIBILITY"
        detach()
        lastRequest = -1L
        requestRead(true)
    }

    private fun requestRead(force: Boolean = false) {
        if (!enabled) return
        if (!permitted()) { stop("PERMISSION_OR_SERVER_UNAVAILABLE"); return }
        val useLegacy = fallback
        val service = remote
        if (!useLegacy && service == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastRequest >= 0 && now-lastRequest < 60_000L) return
        val busy = if (useLegacy) legacyBusy else primaryBusy
        if (!busy.compareAndSet(false,true)) {
            if (force) {
                if (!useLegacy) switchToFallback("PREVIOUS_USER_SERVICE_READ_BUSY")
                else status = "COMPATIBILITY_WORKER_BUSY — restart app to retry"
            }
            return
        }
        lastRequest = now
        val token = generation
        val source = if (useLegacy) "LEGACY_FIXED_READ_ONLY" else "USER_SERVICE_UID_2000"
        val executor = if (useLegacy) legacyExecutor else primaryExecutor
        armTimeout(if (useLegacy) 18_000L else 8_000L) {
            if (enabled && generation == token) {
                if (useLegacy) {
                    // Invalidates a late result; busy remains true until the actual call returns.
                    generation++
                    latest = null
                    status = fallbackReason + " — COMPATIBILITY_READ_TIMEOUT"
                } else switchToFallback("USER_SERVICE_READ_TIMEOUT")
            }
        }
        executor.execute {
            val start = SystemClock.elapsedRealtime()
            val outcome = try {
                check(permitted()) { "Permission unavailable" }
                val fields = if (useLegacy) readLegacy() else readUserService(checkNotNull(service))
                check(permitted()) { "Permission lost" }
                Result.success(fields)
            } catch (e: Exception) { Result.failure<Map<String,String>>(e) }
            val finished = SystemClock.elapsedRealtime()
            busy.set(false)
            main.post {
                if (!enabled || generation != token || fallback != useLegacy) return@post
                cancelTimeout()
                if (!permitted()) { stop("PERMISSION_OR_SERVER_UNAVAILABLE"); return@post }
                outcome.fold(onSuccess = { fields ->
                    latest = Reading(token,start,finished,source,fields)
                    val available = fields.filterKeys { !it.startsWith("@") }.values.count {
                        it.isNotBlank() && it != "READ_DENIED" && it != "EMPTY" && !it.startsWith("ERROR:")
                    }
                    status = (if (useLegacy) fallbackReason + " — COMPATIBILITY_CONNECTED" else "USER_SERVICE_CONNECTED") +
                        " — $available readable raw fields"
                },onFailure = {
                    latest = null
                    if (useLegacy) status = fallbackReason + " — COMPATIBILITY_UNAVAILABLE: " + it.javaClass.simpleName
                    else switchToFallback("USER_SERVICE_READ_FAILED_" + it.javaClass.simpleName)
                })
            }
        }
    }

    private fun readUserService(service: IChargingShell): Map<String,String> {
        val raw = service.snapshot()
        require(raw.length <= 200_000) { "Oversized reply" }
        val json = JSONObject(raw)
        check(ShellReadPolicy.acceptsService(json.optInt("@shell_uid",-1),json.optInt("@protocol",-1))) { "Invalid service identity" }
        val result = linkedMapOf<String,String>()
        val keys = json.keys()
        while (keys.hasNext() && result.size < 400) {
            val key = keys.next()
            val value = json.opt(key)
            if (key.length <= 512 && value is String && value.length <= 300 &&
                (key.startsWith("@") || key.substringBefore('/') in KernelInventory.chargingRoots.map { it.name }))
                result[key] = value
        }
        return result
    }

    /** Compatibility only: v13's deprecated process API may be missing. Never bypass authorization.
     * Fixed read-only command, no user input, external timeout, capped output, separate worker. */
    private fun readLegacy(): Map<String,String> {
        check(Shizuku.getVersion() == 13) { "Compatibility backend supported only on API 13" }
        val method = Shizuku::class.java.getDeclaredMethod("newProcess",Array<String>::class.java,Array<String>::class.java,String::class.java)
        method.isAccessible = true
        val proc = method.invoke(null,ShellReadPolicy.legacyCommand(),null,null) as Process
        try {
            val out = proc.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (buffer.size() < 65_536) {
                    val n = input.read(chunk,0,minOf(chunk.size,65_536-buffer.size()))
                    if (n < 0) break
                    if (n == 0) continue
                    buffer.write(chunk,0,n)
                }
                check(buffer.size() < 65_536) { "Output limit" }
                buffer.toString("UTF-8")
            }
            check(proc.waitFor() == 0) { "Compatibility command failed" }
            return ShellReadPolicy.parseLegacy(out).also { check(it.isNotEmpty()) { "No allowlisted output" } }
        } finally { try { proc.destroy() } catch (_: Exception) {} }
    }

    /** Returns immediately; source age is explicit and stale generation results are discarded. */
    fun snapshot(): Map<String,String> {
        if (!enabled) return emptyMap()
        if (refreshQueued.compareAndSet(false,true)) {
            main.post {
                refreshQueued.set(false)
                requestRead()
            }
        }
        val now = SystemClock.elapsedRealtime()
        val token = generation
        val reading = latest
        val fields = linkedMapOf("@shizuku/status" to status,"@shizuku/server" to serverDescription())
        if (reading != null && ShellReadPolicy.fresh(reading.generation,token,reading.started,now)) {
            fields["@shizuku/source"] = reading.source
            fields["@shizuku/read_started_elapsed_ms"] = reading.started.toString()
            fields["@shizuku/read_finished_elapsed_ms"] = reading.finished.toString()
            reading.data.forEach { (key,value) -> fields["@shizuku/"+key] = value }
        }
        return if (enabled && generation == token) fields else emptyMap()
    }
}
