package com.batteryscope

/** Main-thread-only refresh admission. Coalesces forced requests while a worker is active. */
internal class RefreshGate(private val minIntervalMs: Long) {
    private var lastStartedAt: Long? = null
    private var running = false
    private var pendingForce = false

    fun request(nowElapsedMs: Long, force: Boolean): Boolean {
        if (running) {
            if (force) pendingForce = true
            return false
        }
        val last = lastStartedAt
        if (!force && last != null && nowElapsedMs - last in 0L until minIntervalMs) return false
        running = true
        lastStartedAt = nowElapsedMs
        return true
    }

    /** True means the caller must launch one forced follow-up, if still alive. */
    fun finish(): Boolean {
        check(running)
        running = false
        val followUp = pendingForce
        pendingForce = false
        return followUp
    }
}
