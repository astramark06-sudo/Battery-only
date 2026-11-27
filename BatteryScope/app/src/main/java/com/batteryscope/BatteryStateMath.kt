package com.batteryscope

internal object BatteryStateMath {
    /** UNKNOWN is not proof of an external power source. */
    fun connected(plug: String): Boolean = plug in setOf("AC", "USB", "WIRELESS", "DOCK") ||
        plug.startsWith("OTHER_")

    /** Some firmware reports NOT_CHARGING during ordinary unplugged use. */
    fun discharging(status: String, plug: String): Boolean =
        (status == "DISCHARGING" || status == "NOT_CHARGING") && plug == "NONE"
}
