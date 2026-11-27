package com.batteryscope

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecordingPreferenceAndroidTest {
    @Test fun pausePersistsSeparatelyFromLegacyRecorderState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-recorder-preference-" + UUID.randomUUID()
        try {
            val prefs = context.getSharedPreferences(name,Context.MODE_PRIVATE)
            assertFalse(RecordingPreference.isPaused(prefs))
            prefs.edit().putBoolean(BatteryRecorderService.KEY_USER_ENABLED,true).apply()
            RecordingPreference.setPaused(prefs,true)
            assertTrue(RecordingPreference.isPaused(context.getSharedPreferences(name,Context.MODE_PRIVATE)))
            prefs.edit().putBoolean(BatteryRecorderService.KEY_ACTIVE,false)
                .putBoolean(BatteryRecorderService.KEY_USER_ENABLED,true).apply()
            assertTrue(RecordingPreference.isPaused(prefs))
            RecordingPreference.setPaused(prefs,false)
            assertFalse(RecordingPreference.isPaused(prefs))
        } finally { context.deleteSharedPreferences(name) }
    }
}
