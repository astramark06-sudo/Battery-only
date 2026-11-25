package com.batteryscope

import android.content.SharedPreferences

/** New preference separate from historical liveness/user-enabled flags.
 * Preserve automatic recording for existing and new installs until explicitly paused.
 */
internal object RecordingPreference {
    private const val PAUSED = "recording_paused_by_user"
    fun isPaused(prefs: SharedPreferences): Boolean = prefs.getBoolean(PAUSED,false)
    fun setPaused(prefs: SharedPreferences, paused: Boolean) {
        // Persist immediately; caller decides whether to start/stop the service.
        prefs.edit().putBoolean(PAUSED,paused).apply()
    }
}
