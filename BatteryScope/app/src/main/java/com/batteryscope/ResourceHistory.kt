package com.batteryscope

/** Resource history uses acquisition time (not the battery row's time) and cumulative counters. */
internal data class ResourceObservation(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int?,
    val totalRam: Long?,
    val availableRam: Long?,
    val availableStorage: Long?,
    val thermalStatus: Int?,
    val counters: List<Long?>
)

internal class ResourceHistory {
    companion object {
        val counterNames = listOf("total_rx","total_tx","mobile_rx","mobile_tx","own_uid_rx","own_uid_tx")
        const val MAX_INTERVAL_MS = 300_000L
        const val MAX_CLOCK_DIFFERENCE_MS = 30_000L
    }
    class CounterTotal {
        var bytes: Long = 0L
            private set
        var coveredMs: Long = 0L
            private set
        var intervals: Int = 0
            private set
        fun accept(before: Long?, after: Long?, duration: Long) {
            if (before == null || after == null || before < 0L || after < before || duration <= 0L) return
            val delta = after - before
            if (Long.MAX_VALUE - bytes < delta || Long.MAX_VALUE - coveredMs < duration) return
            bytes += delta
            coveredMs += duration
            intervals++
        }
    }
    private var previous: ResourceObservation? = null
    var snapshots = 0
        private set
    var lowestAvailableRam: Long? = null
        private set
    var highestAvailableRam: Long? = null
        private set
    var lowestAvailableStorage: Long? = null
        private set
    var highestThermalStatus: Int? = null
        private set
    var ramObservations = 0L
        private set
    var storageObservations = 0L
        private set
    var thermalObservations = 0L
        private set
    val counters = counterNames.map { CounterTotal() }

    fun accept(current: ResourceObservation) {
        require(current.counters.size == counters.size)
        if (current.wallMs < 0L || current.elapsedMs < 0L) return
        val before = previous
        // Shared cache can make successive battery rows carry identical resource observations.
        if (before != null && before.bootCount == current.bootCount &&
            before.elapsedMs == current.elapsedMs && before.wallMs == current.wallMs) return
        previous = current
        snapshots++
        val ram = current.availableRam?.takeIf { it >= 0L && (current.totalRam == null || it <= current.totalRam) }
        if (ram != null) {
            ramObservations++
            lowestAvailableRam = minOf(lowestAvailableRam ?: ram,ram)
            highestAvailableRam = maxOf(highestAvailableRam ?: ram,ram)
        }
        val storage = current.availableStorage?.takeIf { it >= 0L }
        if (storage != null) {
            storageObservations++
            lowestAvailableStorage = minOf(lowestAvailableStorage ?: storage,storage)
        }
        val thermal = current.thermalStatus?.takeIf { it in 0..6 }
        if (thermal != null) {
            thermalObservations++
            highestThermalStatus = maxOf(highestThermalStatus ?: thermal,thermal)
        }
        if (before == null || before.bootCount == null || before.bootCount < 0 || before.bootCount != current.bootCount) return
        val elapsed = current.elapsedMs - before.elapsedMs
        if (elapsed !in 1L..MAX_INTERVAL_MS) return
        val wall = current.wallMs - before.wallMs
        if (wall <= 0L || kotlin.math.abs(wall - elapsed) > MAX_CLOCK_DIFFERENCE_MS) return
        counters.forEachIndexed { i, counter -> counter.accept(before.counters[i],current.counters[i],elapsed) }
    }
}
