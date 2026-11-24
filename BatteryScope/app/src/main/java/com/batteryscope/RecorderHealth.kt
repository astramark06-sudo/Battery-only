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

/** Process-local health. Elapsed timestamps never cross a process/boot boundary. */
internal data class RecorderHealth(
    val startedElapsedMs: Long,
    val lastSuccessElapsedMs: Long? = null,
    val lastFinishedElapsedMs: Long? = null,
    val lastDurationMs: Long? = null,
    val error: String? = null,
    val skippedDeadlines: Long = 0L
) {
    fun completed(start: Long, finished: Long, failure: String?): RecorderHealth {
        require(start >= startedElapsedMs && finished >= start)
        return copy(
            lastSuccessElapsedMs = if (failure == null) finished else lastSuccessElapsedMs,
            lastFinishedElapsedMs = finished,
            lastDurationMs = finished - start,
            error = failure
        )
    }

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
    }
}
