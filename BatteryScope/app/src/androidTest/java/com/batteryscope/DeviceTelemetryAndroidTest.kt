package com.batteryscope

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DeviceTelemetryAndroidTest {
    @Test fun publicResourceInventoryIsBoundedAndLocal() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val fields = DeviceTelemetry.collect(app)
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.INTERNET))
        for (section in listOf("memory","storage","storage_volumes","display","connectivity","network","power","sensors")) {
            val status = fields["@device/$section/status"]
            assertNotNull(section,status)
            assertTrue(section,status == "AVAILABLE" || status == "UNAVAILABLE_PERMISSION" || status!!.startsWith("ERROR:"))
        }
        val start = fields.getValue("@device/captured_elapsed_ms").toLong()
        val end = fields.getValue("@device/collection_finished_elapsed_ms").toLong()
        assertTrue(end >= start)
        assertTrue(fields.keys.count { it.startsWith("@device/sensors/details/") } <= 64)
        assertTrue(fields.keys.count { it.startsWith("@device/display/") && it.endsWith("/logical_id") } <= 8)
        assertTrue(fields.keys.count { it.startsWith("@device/storage_volumes/") && it.endsWith("/state") } <= 16)
        assertFalse(fields.keys.any { it.contains("ssid",true) || it.contains("imei",true) || it.contains("uuid",true) })
        if (fields["@device/connectivity/status"] == "AVAILABLE")
            assertNotNull(fields["@device/connectivity/active_network"])
    }
}
