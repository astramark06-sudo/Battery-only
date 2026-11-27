package com.batteryscope

import kotlin.math.abs

/**
 * Uses the persisted boot counter to distinguish same-boot monotonic intervals
 * from wall-clock intervals that cross a reboot.
 */
internal object RecorderTimeMath {
    private const val UNKNOWN_BOOT_TOLERANCE_MS = 120_000L

    /** Numerical duration validation only; this does not establish boot continuity. */
    fun isRateDuration(intervalMs: Long): Boolean = intervalMs in 1_000L..90_000L

    /** Rates require a known shared boot identity. Gap reporting can use wall time. */
    fun isRateInterval(intervalMs: Long, beforeBootCount: Int?, afterBootCount: Int?): Boolean =
        isRateDuration(intervalMs) &&
            beforeBootCount != null && beforeBootCount >= 0 && beforeBootCount == afterBootCount

    /** Endpoint correlation is not proof of continuous foreground occupancy. */
    fun sameForegroundApp(beforeScreen: Boolean, afterScreen: Boolean, beforeApp: String, afterApp: String,
        beforeUsageAccess: Boolean, afterUsageAccess: Boolean): Boolean =
        beforeUsageAccess && afterUsageAccess && beforeScreen && afterScreen && beforeApp.isNotBlank() && beforeApp == afterApp

    fun intervalMs(
        beforeWallMs: Long,
        beforeElapsedMs: Long,
        beforeBootCount: Int?,
        afterWallMs: Long,
        afterElapsedMs: Long,
        afterBootCount: Int?
    ): Long {
        val wallDelta = afterWallMs - beforeWallMs
        val monotonicDelta = afterElapsedMs - beforeElapsedMs
        if (beforeBootCount != null && afterBootCount != null) {
            return if (beforeBootCount == afterBootCount) monotonicDelta else wallDelta
        }
        return if (monotonicDelta > 0L && wallDelta > 0L &&
            abs(monotonicDelta - wallDelta) <= UNKNOWN_BOOT_TOLERANCE_MS) {
            monotonicDelta
        } else {
            wallDelta
        }
    }
}
