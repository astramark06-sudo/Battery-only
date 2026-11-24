package com.batteryscope

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DeviceTelemetryAndroidTest {
    @Test fun chargingInventoryExcludesUnrelatedSourcesAndPermissions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val fields = HardwareSnapshotCache.snapshot(app,0L)
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission(Manifest.permission.INTERNET))
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS))
        assertEquals("CHARGING_ONLY_V1",fields["@inventory_policy"])
        assertNotNull(fields["@charging/thermal_status_quality"])
        assertFalse(fields.keys.any { it.startsWith("@device/") || it.startsWith("cpu/") || it.startsWith("devfreq/") || it.startsWith("devices/") })
        assertTrue(fields.keys.all { it.startsWith("@") || it.substringBefore('/') in KernelInventory.chargingRoots.map { root -> root.name } })
    }
}
