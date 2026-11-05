package cz.ecmpilot.protocol

import com.google.gson.JsonParser
import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LowPowerTest {
    private fun packet(seconds: Long? = 172800, state: String = "counting", seq: Long = 1, boot: String = "0".repeat(32)): ByteArray {
        return """{"v":0,"type":"status","gw":"gw","boot":"$boot","reply_to":"${"a".repeat(32)}","seq":$seq,"now_ms":1000,"low_power":{"rev":1,"enabled":true,"idle_hours":48,"capable":true,"config_valid":true,"state":"$state","remaining_s":${seconds ?: "null"},"last_error":0}}""".toByteArray()
    }

    @Test fun twoDayDefaultBoundsAndCountdownAreSafe() {
        val defaults=LowPowerSettings()
        assertTrue(defaults.enabled);assertEquals(48,defaults.idleHours)
        assertTrue(defaults.copy(idleHours=720).valid());assertFalse(defaults.copy(idleHours=721).valid())
        assertFalse(defaults.copy(idleHours=0).valid())
        val status=Protocol.parse(packet(),1000,20).lowPower!!
        assertEquals(172800L,status.remaining(1000))
        assertEquals(86400L,status.remaining(86_401_000))
        assertEquals(0L,status.remaining(500_000_000))
        assertTrue(status.description(1000).contains("2d 0h 0m"))
    }

    @Test fun unknownDataIsNotAZeroTimerAndBootResetDiscardsIt() {
        val tracker=StatusTracker()
        tracker.accept(Protocol.parse(packet(),1000,20))
        tracker.accept(Protocol.parse(packet(null,"waiting_data",2),2000,20))
        assertNull(tracker.lowPower!!.remaining(2000))
        assertTrue(tracker.lowPower!!.description(2000).contains("first valid BSI"))
        val reboot=JsonParser.parseString(String(packet())).asJsonObject.apply {
            remove("low_power");addProperty("boot","f".repeat(32));addProperty("seq",0)
        }
        tracker.accept(Protocol.parse(reboot.toString().toByteArray(),3000,20))
        assertNull(tracker.lowPower)
    }

    @Test fun demoSaveIsPersistentWithinGatewayAndIdempotentWithoutVehicleAction() = runBlocking {
        val demo=DemoConnection(DemoScene.CHARGING) {1000L}
        val client="a".repeat(32)
        val info=demo.exchange(Protocol.request(client,"info.get"),false)
        assertTrue("low_power" in info.info!!.fields)
        val initial=demo.exchange(Protocol.request(client,"low_power.get"),false).lowPower!!
        val desired=initial.settings.copy(idleHours=72)
        fun request(settings:LowPowerSettings, boot:String=info.boot)=Protocol.request(client,"low_power.set",info.gw,boot,settings.args())
        val saved=demo.exchange(request(desired),true)
        assertEquals(72,saved.lowPower!!.settings.idleHours)
        assertNull(saved.command)
        assertEquals(saved.lowPower!!.settings.revision,demo.exchange(request(desired),true).lowPower!!.settings.revision)
        assertEquals("settings_conflict",demo.exchange(request(desired.copy(idleHours=24)),true).error)
        assertEquals("wrong_boot",demo.exchange(request(desired,"f".repeat(32)),true).error)
    }
}
