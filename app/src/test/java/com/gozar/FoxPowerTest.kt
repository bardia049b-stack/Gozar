package com.gozar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxPowerTest {

    @Test
    fun lowUnpluggedBatteryDefersOptionalWork() {
        assertTrue(FoxPower.defer(1, false))
        assertTrue(FoxPower.defer(14, false))
        assertFalse(FoxPower.defer(15, false))
        assertFalse(FoxPower.defer(80, false))
    }

    @Test
    fun chargingOrUnknownBatteryNeverDefers() {
        assertFalse(FoxPower.defer(5, true))
        assertFalse(FoxPower.defer(0, false))
        assertFalse(FoxPower.defer(-1, false))
    }
}
