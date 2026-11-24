package com.batteryscope

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class KernelInventoryTest {
    @Test fun allowlistsRejectPrivateAndUnrelatedAttributes() {
        assertTrue(KernelInventory.allowed("power_supply","voltage_now"))
        assertTrue(KernelInventory.allowed("thermal","temp1_input"))
        assertTrue(KernelInventory.allowed("cpu","scaling_cur_freq"))
        for (root in KernelInventory.roots) {
            assertFalse(KernelInventory.allowed(root.name,"serial"))
            assertFalse(KernelInventory.allowed(root.name,"uevent"))
            assertFalse(KernelInventory.allowed(root.name,"descriptors"))
        }
        assertFalse(KernelInventory.allowedDirectory("driver"))
        assertFalse(KernelInventory.inSysfs("/system"))
        assertTrue(KernelInventory.inSysfs("/sys/devices/abc"))
    }
    @Test fun directoryEnumerationStopsAtBudgetAndClosesStream() {
        val dir = java.nio.file.Files.createTempDirectory("inventory-entries").toFile()
        try {
            repeat(200) { File(dir,"entry-" + it).writeText("test") }
            var visited = 0
            assertFalse(KernelInventory.visitEntries(dir,{ visited < 7 }) { visited++ })
            assertEquals(7,visited)
            visited = 0
            assertTrue(KernelInventory.visitEntries(dir,{ true }) { visited++ })
            assertEquals(200,visited)
            assertFalse(KernelInventory.visitEntries(dir,{ false }) { error("No visit permitted") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun readIsBoundedAndDashboardKeepsGaugeKeys() {
        val f = File.createTempFile("inventory",".txt")
        try {
            f.writeText("x".repeat(10000))
            assertEquals("x".repeat(240) + " [TRUNCATED]", KernelInventory.readPrefix(f))
            f.writeText("123\n")
            assertEquals("123",KernelInventory.readPrefix(f))
        } finally { f.delete() }
        val fields = KernelInventory.dashboard(mapOf("power_supply/battery/charge_full" to "4000000",
            "power_supply/@root" to "VISIBLE","thermal/thermal_zone0/temp" to "32000"))
        assertEquals("4000000", fields["battery/charge_full"])
        assertEquals("VISIBLE", fields["@diagnostic/power_supply/root"])
        assertEquals("32000",fields["thermal/thermal_zone0/temp"])
    }
}
