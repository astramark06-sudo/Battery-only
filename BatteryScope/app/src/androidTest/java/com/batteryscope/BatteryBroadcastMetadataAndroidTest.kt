package com.batteryscope

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class BatteryBroadcastMetadataAndroidTest {
    @Test fun optionalFieldsRejectWrongTypesAndSentinelsWhilePreservingRawEvidence() {
        val intent = Intent().putExtra("max_charging_current",Int.MAX_VALUE)
            .putExtra("max_charging_voltage",5_000_000)
            .putExtra("cycle_count",-1)
            .putExtra("present","wrong type")
            .putExtra("invalid_charger","wrong type")
        assertNull(BatteryBroadcastMetadata.advertised(intent,"max_charging_current"))
        assertEquals(5_000_000,BatteryBroadcastMetadata.advertised(intent,"max_charging_voltage"))
        assertNull(BatteryBroadcastMetadata.cycles(intent))
        assertNull(BatteryBroadcastMetadata.bool(intent,"present"))
        assertNull(BatteryBroadcastMetadata.int(intent,"invalid_charger"))
        val captured = BatteryBroadcastMetadata.capture(intent)
        assertEquals(Int.MAX_VALUE,captured.getJSONObject("values").getInt("max_charging_current"))
        assertTrue(captured.getJSONObject("invalid_types").has("present"))
        assertTrue(captured.getJSONObject("invalid_types").has("invalid_charger"))
        assertEquals(false,BatteryBroadcastMetadata.bool(Intent().putExtra("present",false),"present"))
    }
}
