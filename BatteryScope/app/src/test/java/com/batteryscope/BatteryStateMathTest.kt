package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class BatteryStateMathTest {
    @Test fun handlesUnpluggedNotChargingWithoutMisclassifyingUnknownOrPlugged() {
        assertTrue(BatteryStateMath.discharging("NOT_CHARGING", "NONE"))
        assertTrue(BatteryStateMath.discharging("DISCHARGING", "NONE"))
        assertFalse(BatteryStateMath.discharging("NOT_CHARGING", "USB"))
        assertFalse(BatteryStateMath.discharging("DISCHARGING", "UNKNOWN"))
        assertFalse(BatteryStateMath.connected("UNKNOWN"))
        assertTrue(BatteryStateMath.connected("DOCK"))
    }
}
