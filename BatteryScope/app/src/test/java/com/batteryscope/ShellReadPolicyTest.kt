package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class ShellReadPolicyTest {
    @Test fun freshnessRejectsWrongGenerationClockResetAndExpiredData() {
        assertTrue(ShellReadPolicy.fresh(1,1,100,130100))
        assertFalse(ShellReadPolicy.fresh(1,2,100,101))
        assertFalse(ShellReadPolicy.fresh(1,1,100,99))
        assertFalse(ShellReadPolicy.fresh(1,1,100,130101))
        assertFalse(ShellReadPolicy.fresh(1,1,-1,0))
    }
    @Test fun serviceProtocolRejectsRootAppAndOldService() {
        assertTrue(ShellReadPolicy.acceptsService(2000,2))
        assertFalse(ShellReadPolicy.acceptsService(0,2))
        assertFalse(ShellReadPolicy.acceptsService(10001,2))
        assertFalse(ShellReadPolicy.acceptsService(2000,1))
    }
    @Test fun onlyKnownBatteryFieldsAndExactSysfsPathsAreRetained() {
        val fields = ShellReadPolicy.parseLegacy("level: 82\nvoltage: 3\nserial: hidden\nSYS\tbattery/current_now\t-123\nSYS\t../../etc/passwd\tbad\nSYS\tusb/serial\tbad\nSYS\tbattery/current_now\tother")
        assertEquals(mapOf("battery_dump/level" to "82","battery_dump/voltage" to "3","power_supply/battery/current_now" to "-123"),fields)
    }
    @Test fun readableCountExcludesScanMetadataAndErrors() {
        val fields = mapOf("@shell" to "2000","power_supply/@root" to "VISIBLE",
            "power_supply/@scan" to "FINISHED","power_supply/battery/current_now" to "READ_DENIED",
            "power_supply/battery/voltage_now" to "ERROR:IOException","battery_dump/voltage" to "3",
            "battery_dump/level" to "82","power_supply/battery/temp" to "")
        assertEquals(2,ShellReadPolicy.readableCount(fields))
    }
    @Test fun fixedCommandHasNoUserArgumentAndIsBounded() {
        val command = ShellReadPolicy.legacyCommand()
        assertEquals(listOf("/system/bin/toybox","timeout","12","/system/bin/sh","-c"),command.take(5))
        assertTrue(command.last().contains("/system/bin/dumpsys -t 4 battery"))
        assertTrue(command.last().contains("/sys/class/power_supply/battery/current_now"))
        assertFalse(command.last().contains("dumpsys battery set"))
        assertFalse(command.last().contains("/data/"))
    }
}
