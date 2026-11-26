package nl.tapo.deurbel.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTest {
    @Test fun batteryCharging() {
        val p = PowerInfo.from(JSONObject("""{"basic_info":{"battery_percent":"85"},"battery_status":{"charging":"on"},"power_mode":{"power_mode":"adapter"}}"""))
        assertEquals(85, p.percent); assertEquals(true, p.charging); assertEquals(true, p.external)
        assertEquals("85% · laadt op", p.label())
    }

    @Test fun onBatteryLow() {
        val p = PowerInfo.from(JSONObject("""{"basic_info":{"battery_percent":15,"power":"BATTERY"},"battery_status":{"charging":"off"}}"""))
        assertEquals(15, p.percent); assertEquals(false, p.external); assertTrue(p.low)
        assertEquals("15% · op batterij", p.label())
    }

    @Test fun wiredWithoutBattery() {
        val p = PowerInfo.from(JSONObject("""{"basic_info":{"device_model":"D235","sw_version":"1.0"}}"""))
        assertEquals(null, p.percent); assertEquals("Netvoeding", p.label())
    }

    @Test fun full() {
        val p = PowerInfo.from(JSONObject("""{"battery_status":{"percent":100,"charge_status":"full"}}"""))
        assertEquals("100% · vol", p.label())
    }
}
