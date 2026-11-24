package com.batteryscope

import org.junit.Assert.*
import org.junit.Test

class KernelGaugeTest {
    @Test fun refusesCrossSupplyRatio() {
        val fields = mapOf("battery/charge_full" to "3900000", "bms/charge_full_design" to "4500000")
        assertNull(KernelGauge.select(fields)?.healthPercent)
    }
    @Test fun selectsCoherentReadablePair() {
        val fields = mapOf("power_supply/battery/charge_full" to "READ_DENIED",
            "power_supply/bms/charge_full" to "3600000", "power_supply/bms/charge_full_design" to "4500000",
            "power_supply/bms/cycle_count" to "120")
        val gauge = requireNotNull(KernelGauge.select(fields, "power_supply/"))
        assertEquals("power_supply/bms", gauge.source)
        assertEquals(80.0, gauge.healthPercent!!, 0.001)
        assertEquals(120L, gauge.cycles!!)
    }
    @Test fun ignoresUsbAndNegativeCycles() {
        val fields = mapOf("usb/charge_full" to "3900000", "usb/charge_full_design" to "4500000",
            "battery/cycle_count" to "-1")
        val gauge = requireNotNull(KernelGauge.select(fields))
        assertNull(gauge.healthPercent)
        assertNull(gauge.cycles)
    }
}
