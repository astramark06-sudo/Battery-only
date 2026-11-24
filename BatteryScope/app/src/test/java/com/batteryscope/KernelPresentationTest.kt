package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class KernelPresentationTest {
    private fun fixture() = linkedMapOf(
        "@inventory_started_elapsed_ms" to "1000",
        "power_supply/usb/type" to "USB",
        "power_supply/usb/online" to "1",
        "power_supply/usb/voltage_now" to "5000000",
        "power_supply/usb/current_now" to "1000000",
        "power_supply/cpu/type" to "Battery",
        "power_supply/cpu/charge_full" to "4000000",
        "cpu/cpu0/online" to "1",
        "thermal/thermal_zone0/temp" to "32000",
        "power_supply/@root" to "VISIBLE",
        "power_supply/usb/fastcharger" to "7"
    )

    @Test fun fullRootPathsPreventCollisionsAndVendorGuesses() {
        val f = fixture()
        assertEquals(KernelPresentation.Group.BATTERY,KernelPresentation.group("power_supply/cpu/charge_full",f))
        assertEquals(KernelPresentation.Group.COMPUTE,KernelPresentation.group("cpu/cpu0/online",f))
        assertEquals(KernelPresentation.Group.THERMAL,KernelPresentation.group("thermal/thermal_zone0/temp",f))
        assertEquals(KernelPresentation.Group.METADATA,KernelPresentation.group("power_supply/@root",f))
        assertEquals(listOf("power_supply/cpu","power_supply/usb"),KernelPresentation.supplyPaths(f))
        assertTrue(KernelPresentation.describe("power_supply/usb/voltage_now","5000000",f).contains("if standard"))
        assertTrue(KernelPresentation.describe("power_supply/usb/fastcharger","7",f).contains("not verified"))
        assertEquals("cpu/cpu0/online = 1",KernelPresentation.describe("cpu/cpu0/online","1",f))
        assertFalse(KernelPresentation.describe("power_supply/usb/charge_control_limit","5",f).contains("mA"))
    }

    @Test fun requiresFreshUniqueWiredSameSupplyCandidate() {
        val f = fixture()
        val good = KernelPresentation.usb(f,2000,"AC",60_000)
        assertEquals("CACHED_UNCALIBRATED_CANDIDATE",good.status)
        assertEquals(KernelPresentation.Candidate("power_supply/usb",5000000,1000000),good.candidate)
        for (plug in listOf("NONE","UNKNOWN","WIRELESS","DOCK"))
            assertNull(KernelPresentation.usb(f,2000,plug,60_000).candidate)
        assertEquals("STALE_INVENTORY",KernelPresentation.usb(f,61001,"AC",60_000).status)
        assertEquals("CAPTURE_TIME_UNKNOWN",KernelPresentation.usb(f,999,"AC",60_000).status)
        f["power_supply/usb2/type"]="USB"
        f["power_supply/usb2/online"]="1"
        f["power_supply/usb2/voltage_now"]="5000000"
        f["power_supply/usb2/current_now"]="2000000"
        assertEquals("AMBIGUOUS_MULTIPLE_USB_SUPPLIES",KernelPresentation.usb(f,2000,"AC",60_000).status)
        f["power_supply/usb2/online"]="0"
        f.remove("power_supply/usb/current_now")
        assertNull(KernelPresentation.usb(f,2000,"AC",60_000).candidate)
        f.remove("@inventory_started_elapsed_ms")
        assertNull(KernelPresentation.usb(f,2000,"AC",60_000).candidate)
    }
}
