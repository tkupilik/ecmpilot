package cz.ecmpilot.protocol

import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.google.gson.JsonArray
import com.google.gson.JsonObject

class ClimateProgramsTest {
    private val stamp = Stamp(100, 1000)
    @Test fun capturedVehicleSlotMatches0730MondayWednesdayThursdayAndIsDisabled() {
        val slot = ClimateProgram(0x5801C2, stamp)
        assertEquals("07:30", slot.time); assertEquals(0x58, slot.days)
        assertFalse(slot.enabled); assertTrue(slot.configured)
        assertEquals(0xD801C2, slot.withEnabled(true).raw)
        assertEquals(slot, slot.withEnabled(true).withEnabled(false))
    }
    @Test fun editingPreservesReservedBitsAndUnknownIsNotMidnightOrClosed() {
        val slot = ClimateProgram(0x58A1C2, stamp).withMinute(480).withDays(0x60).withEnabled(true)
        assertEquals(0xE0A1E0, slot.raw)
        val unused = ClimateProgram(0x7FF, stamp)
        assertNull(unused.time); assertFalse(unused.configured); assertFalse(unused.withEnabled(true).enabled)
        assertNull(ClimateProgram(null, stamp).withEnabled(true).raw)
    }
    @Test fun missingProgramsAndBootChangesCannotReuseAnotherGatewaySchedule() {
        fun packet(programs: String, boot: String = "0".repeat(32), seq: Int = 1) =
            """{"v":0,"type":"status","gw":"test","boot":"$boot","reply_to":"${"a".repeat(32)}","seq":$seq,"now_ms":1000,$programs}"""
        val p = """"programs":{"rev":0,"slots":[{"raw":5767618,"age_ms":100},{"raw":2047,"age_ms":100},{"raw":2047,"age_ms":100},{"raw":2047,"age_ms":100}]}"""
        val tracker = StatusTracker()
        assertTrue(tracker.accept(Protocol.parse(packet(p).toByteArray(), 1000, 0)))
        assertTrue(tracker.programs!!.complete)
        assertTrue(tracker.accept(Protocol.parse(packet("\"error\":null", "1".repeat(32)).toByteArray(), 1000, 0)))
        assertNull(tracker.programs)
        assertNull(Protocol.parse(packet("\"error\":null").toByteArray(), 1000, 0).programs)
    }
    @Test fun schedulesRequireAdvertisedConfirmationAndPersistence() {
        val caps = GatewayInfo("test", "lab-plain", null, emptySet(),
            mapOf("climate.programs.set" to Operation(false, true, persistentSchedule = true)), "info-can-experimental")
        assertTrue(caps.climatePrograms())
        assertFalse(caps.copy(controlBackend = "none").climatePrograms())
        assertFalse(caps.copy(operations = emptyMap()).climatePrograms())
    }
    @Test fun demoScheduleSaveIsConfirmedSeparatelyWithoutStartingClimate() = runBlocking {
        var now = 1000L
        val demo = DemoConnection(DemoScene.CHARGING) { now }
        val original = demo.exchange(Protocol.request("a".repeat(32), "climate.programs.get")).programs!!
        val args = JsonObject().apply {
            add("base", JsonArray().apply { original.slots.forEach { add(it.raw) } })
            add("slots", JsonArray().apply { original.slots.forEach { add(it.withEnabled(true).raw) } })
        }
        val request = Protocol.request("a".repeat(32), "climate.programs.set", args = args)
        assertEquals("accepted", demo.exchange(request).command!!.phase)
        assertFalse(demo.exchange(Protocol.request("a".repeat(32), "climate.programs.get")).programs!!.enabled)
        now += 1500
        val result = demo.exchange(Protocol.request("a".repeat(32), "command.get", args = JsonObject().apply { addProperty("command_id", request.id) }))
        assertEquals("succeeded", result.command!!.phase)
        assertTrue(demo.exchange(Protocol.request("a".repeat(32), "climate.programs.get")).programs!!.enabled)
        assertEquals("off", demo.exchange(Protocol.request("a".repeat(32), "state.get")).state!!.climate.state)
    }
}
