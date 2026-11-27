package com.batteryscope

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DeviceTelemetryAndroidTest {
    @Test fun packagedPermissionsAndBackupFlagMatchLocalOnlyPolicy() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_PERMISSIONS)
        assertEquals(setOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "moe.shizuku.manager.permission.API_V23"
        ),info.requestedPermissions.orEmpty().toSet())
        val flags = checkNotNull(info.applicationInfo).flags
        assertEquals(0,flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

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
