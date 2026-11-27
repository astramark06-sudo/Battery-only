package com.batteryscope

/** Skip missed slots instead of accumulating processing time or generating catch-up samples. */
internal object RecorderCadence {
    data class Slot(val elapsedMs: Long, val skipped: Long)

    /** Keep one monotonic phase even if a Handler callback runs late. */
    fun nextSlot(scheduledElapsedMs: Long, finishedElapsedMs: Long, periodMs: Long): Slot =
        Slot(Math.addExact(finishedElapsedMs, nextDelayMs(scheduledElapsedMs, finishedElapsedMs, periodMs)),
            (finishedElapsedMs - scheduledElapsedMs) / periodMs)

    fun nextDelayMs(startElapsedMs: Long, finishedElapsedMs: Long, periodMs: Long): Long {
        require(startElapsedMs >= 0 && finishedElapsedMs >= startElapsedMs && periodMs > 0)
        val duration = finishedElapsedMs - startElapsedMs
        return periodMs - duration % periodMs
    }
}

/** Clocks from this service session; difference estimates suspend, not its cause. */
internal data class RecorderClock(val elapsedMs: Long, val uptimeMs: Long) {
    fun suspendSince(before: RecorderClock): Long? {
        if (elapsedMs < 0 || uptimeMs < 0 || before.elapsedMs < 0 || before.uptimeMs < 0 ||
            elapsedMs < before.elapsedMs || uptimeMs < before.uptimeMs) return null
        return ((elapsedMs - before.elapsedMs) - (uptimeMs - before.uptimeMs)).coerceAtLeast(0L)
    }

    companion object {
        fun isDue(nowElapsedMs: Long, deadlineElapsedMs: Long): Boolean =
            deadlineElapsedMs >= 0L && nowElapsedMs >= deadlineElapsedMs
    }
}

/** Process-local health. Elapsed timestamps never cross a process/boot boundary. */
internal data class RecorderHealth(
    val startedElapsedMs: Long,
    val lastSuccessElapsedMs: Long? = null,
    val lastFinishedElapsedMs: Long? = null,
    val lastDurationMs: Long? = null,
    val error: String? = null,
    val skippedDeadlines: Long = 0L,
    val sampleAttempts: Long = 0L,
    val successfulSamples: Long = 0L,
    val totalSampleWallMs: Long = 0L,
    val maxSampleWallMs: Long = 0L,
    val measuredCpuAttempts: Long = 0L,
    val totalSamplerCpuMs: Long = 0L
) {
    private fun saturatedAdd(a: Long,b: Long): Long =
        if (b > Long.MAX_VALUE-a) Long.MAX_VALUE else a+b
    fun completed(start: Long, finished: Long, failure: String?, cpuMs: Long? = null): RecorderHealth {
        require(start >= startedElapsedMs && finished >= start)
        val duration = finished-start
        val validCpu = cpuMs?.takeIf { it >= 0L }
        return copy(
            sampleAttempts = saturatedAdd(sampleAttempts,1),
            successfulSamples = saturatedAdd(successfulSamples,if (failure == null) 1 else 0),
            totalSampleWallMs = saturatedAdd(totalSampleWallMs,duration),
            maxSampleWallMs = maxOf(maxSampleWallMs,duration),
            measuredCpuAttempts = saturatedAdd(measuredCpuAttempts,if (validCpu != null) 1 else 0),
            totalSamplerCpuMs = saturatedAdd(totalSamplerCpuMs,validCpu ?: 0L),
            lastSuccessElapsedMs = if (failure == null) finished else lastSuccessElapsedMs,
            lastFinishedElapsedMs = finished,
            lastDurationMs = finished - start,
            error = failure
        )
    }

    /** Scheduling rejection is not a second sample attempt or a successful commit. */
    fun schedulingFailed(failure: String): RecorderHealth = copy(error=failure)

    fun skipped(count: Long): RecorderHealth {
        require(count >= 0L)
        return copy(skippedDeadlines = if (count > Long.MAX_VALUE - skippedDeadlines) Long.MAX_VALUE else skippedDeadlines + count)
    }

    fun state(now: Long, staleAfterMs: Long = 90_000L): String {
        require(staleAfterMs > 0)
        val reference = lastFinishedElapsedMs ?: startedElapsedMs
        if (now < reference || now - reference > staleAfterMs) return "STALE"
        return when {
            error != null -> "DEGRADED"
            lastSuccessElapsedMs != null -> "RECENT_SAMPLE_SAVED"
            else -> "WAITING_FOR_FIRST_SAMPLE"
        }
    }

    fun report(now: Long): String = buildString {
        append(state(now))
        lastSuccessElapsedMs?.let {
            if (now >= it) append(" • last successful commit " + (now - it) / 1000 + " s ago")
        }
        lastDurationMs?.let { append(" • last attempt " + it + " ms") }
        append(" • skipped sampling deadlines this service session " + skippedDeadlines)
        error?.let { append(" • " + it) }
        append("\nThis service session: $successfulSamples/$sampleAttempts successful sample attempts")
        if (sampleAttempts > 0L) {
            append(" • mean wall duration " + String.format(java.util.Locale.US,"%.1f",totalSampleWallMs.toDouble()/sampleAttempts) + " ms")
            append(" • max " + maxSampleWallMs + " ms")
        }
        append("\nMeasured sampler-thread CPU time: ")
        if (measuredCpuAttempts == 0L) append("unavailable")
        else append(totalSamplerCpuMs.toString() + " ms across " + measuredCpuAttempts + " attempts")
        append(". Excludes separate inventory worker, wake-lock impact, other threads and system work; not battery drain, energy or whole-app CPU.")
    }
}
