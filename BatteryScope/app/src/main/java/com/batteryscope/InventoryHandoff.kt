package com.batteryscope

/** One bounded, asynchronous inventory request or pending result; no scan or DB I/O under this lock. */
internal class InventoryHandoff<T>(private val periodMs: Long) {
    init { require(periodMs > 0L) }

    data class Completed<T>(val value: T, val startedElapsedMs: Long, val finishedElapsedMs: Long)

    private var requestedAt: Long? = null
    private var inFlight = false
    private var pending: Completed<T>? = null

    @Synchronized fun request(nowElapsedMs: Long): Boolean {
        require(nowElapsedMs >= 0L)
        val last = requestedAt
        if (inFlight || pending != null || (last != null && nowElapsedMs - last < periodMs)) return false
        requestedAt = nowElapsedMs
        inFlight = true
        return true
    }

    @Synchronized fun complete(value: T, startedElapsedMs: Long, finishedElapsedMs: Long) {
        check(inFlight && pending == null)
        require(startedElapsedMs >= requireNotNull(requestedAt) && finishedElapsedMs >= startedElapsedMs)
        pending = Completed(value, startedElapsedMs, finishedElapsedMs)
        inFlight = false
    }

    @Synchronized fun failed() {
        check(inFlight)
        inFlight = false
    }

    /** Never attach an inventory that completed after the battery row's acquisition started. */
    @Synchronized fun readyFor(sampleElapsedMs: Long): Completed<T>? =
        pending?.takeIf { it.finishedElapsedMs <= sampleElapsedMs }

    /** Only acknowledge the exact snapshot after the database insert succeeds. */
    @Synchronized fun committed(result: Completed<T>) {
        if (pending === result) pending = null
    }
}
