package cz.ecmpilot.protocol

import cz.ecmpilot.model.DesiredCommand
import cz.ecmpilot.model.DemoScene
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChargeScheduleTest {
    @Test fun timeIsEncodedWithDelayOnOrOff() {
        for (mode in listOf("immediate", "deferred")) {
            val args = Protocol.chargeModeArgs(DesiredCommand.ChargeMode(mode, "03:17"), "Europe/Prague")
            assertEquals(mode, args["mode"].asString)
            assertEquals("03:17", args["start_local"].asString)
            assertEquals("Europe/Prague", args["timezone"].asString)
        }
        assertEquals(setOf("mode"), Protocol.chargeModeArgs(DesiredCommand.ChargeMode("immediate"), null).keySet())
    }

    @Test fun badOrIncompleteTimeNeverBecomesAModeOnlyRequest() {
        for (time in listOf("24:00", "03:60", "", "3:17")) {
            assertThrows(IllegalArgumentException::class.java) {
                Protocol.chargeModeArgs(DesiredCommand.ChargeMode("immediate", time), "Europe/Prague")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.chargeModeArgs(DesiredCommand.ChargeMode("immediate", "03:17"), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.chargeModeArgs(DesiredCommand.ChargeMode("deferred"), "Europe/Prague")
        }
    }

    @Test fun demoKeepsDelayOffWhenSavingTimeAndLaterRefreshRetainsIt() = runBlocking {
        var now = 1000L
        val gateway = DemoConnection(DemoScene.CHARGING) { now }
        val client = "a".repeat(32)
        val args = Protocol.chargeModeArgs(DesiredCommand.ChargeMode("immediate", "03:17"), "Europe/Prague")
        val command = Protocol.request(client, "charge.mode.set", args = args)
        assertEquals("accepted", gateway.exchange(command, false).command!!.phase)
        now += 1200
        val query = Protocol.request(client, "command.get", args = JsonObject().apply { addProperty("command_id", command.id) })
        assertEquals("succeeded", gateway.exchange(query, false).command!!.phase)
        val refresh = Protocol.request(client, "vehicle.refresh")
        gateway.exchange(refresh, false)
        now += 1200
        gateway.exchange(Protocol.request(client, "command.get", args = JsonObject().apply { addProperty("command_id", refresh.id) }), false)
        val charge = gateway.exchange(Protocol.request(client, "state.get"), false).state!!.charge
        assertEquals("immediate", charge.mode)
        assertEquals("03:17", charge.startLocal)
    }
}
