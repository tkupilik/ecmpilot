package cz.ecmpilot.protocol

import com.google.gson.JsonObject
import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VehicleRefreshTest {
    @Test fun requestFeedbackCoversEveryActionAndFailuresWithoutAReceipt() {
        for (ref in listOf(null, CommandRef("id", "gw", "boot", "Refresh vehicle", "localhost", 42840),
            CommandRef("id", "gw", "boot", "Start preconditioning", "localhost", 42840),
            CommandRef("id", "gw", "boot", "Delayed charging", "localhost", 42840))) {
            val preparing = CommandUi(ref, "preparing", "Reading…")
            assertTrue(preparing.pending); assertFalse(preparing.hasError)
            assertTrue(preparing.copy(phase = "failed").hasError)
            assertTrue(preparing.copy(phase = "unknown").hasError)
            assertTrue(preparing.copy(phase = "rejected").hasError)
            assertFalse(preparing.copy(phase = "succeeded").hasError)
        }
    }
    private val info = GatewayInfo("test", "lab-plain", "Europe/Prague", emptySet(), mapOf(
        "vehicle.refresh" to Operation(false, true),
        "charge.mode.set" to Operation(false, true, modes = setOf("immediate", "deferred"), persistentSchedule = true)
    ), "info-can-experimental")
    private fun state() = PilotState(Settings(source = Source.UDP), nowMs = 7_200_000,
        connected = true, info = info, vehicle = null)

    @Test fun explicitRefreshWorksWithoutFreshVehicleDataButNeedsAdvertisedCapability() {
        assertTrue(state().controlReady("refresh"))
        assertFalse(state().controlReady("charge"))
        assertFalse(state().copy(connected = false).controlReady("refresh"))
        assertFalse(state().copy(permissionGranted = false).controlReady("refresh"))
        assertFalse(state().copy(info = info.copy(operations = emptyMap())).controlReady("refresh"))
        assertFalse(state().copy(command = CommandUi(null, "sending", "")).controlReady("refresh"))
        assertFalse(state().copy(info = info.copy(controlBackend = "unknown")).controlReady("refresh"))
    }

    @Test fun chargeMayWakeSleepingCarWithCachedScheduleAndExplicitControlOptIn() {
        val stale = Stamp(7_200_000, 0)
        val v = VehicleState(awake = false, vehicleStamp = stale,
            climate = Climate(stamp = stale), charge = Charge(mode = "deferred", rev = 0,
                startLocal = "03:00", stamp = stale, modeStamp = stale, scheduleAvailable = true))
        val s = state().copy(vehicle = v, settings = Settings(source = Source.UDP, allowLabControls = true))
        assertTrue(s.controlReady("charge")); assertFalse(s.controlReady("climate"))
        assertFalse(s.copy(settings = s.settings.copy(allowLabControls = false)).controlReady("charge"))
        assertFalse(s.copy(vehicle = v.copy(charge = v.charge.copy(scheduleAvailable = false))).controlReady("charge"))
        assertTrue(info.charge("immediate")); assertTrue(info.charge("deferred"))
        assertFalse(info.climate(true))
    }

    @Test fun scheduleAgeAndSocAgeAreIndependentOfUnknownChargingFlow() {
        val packet = """{"v":0,"type":"status","gw":"test","boot":"00000000000000000000000000000001","reply_to":"00000000000000000000000000000002","seq":1,"now_ms":10000,"state":{"soc_pct":{"value":65,"age_ms":7200000},"charge":{"state":"unknown","mode":"deferred","rev":1,"start_local":"03:00","age_ms":null,"mode_age_ms":20,"schedule_available":true}}}"""
        val v = Protocol.parse(packet.toByteArray(), 1000, 10).state!!
        assertEquals(7_200_010L, v.reading("soc_pct")!!.stamp.age(1000))
        assertFalse(v.reading("soc_pct")!!.fresh(1000))
        assertFalse(v.charge.stamp.fresh(1000)); assertTrue(v.charge.modeStamp.fresh(1000))
        assertEquals("03:00", v.charge.startLocal); assertEquals(true, v.charge.scheduleAvailable)
    }

    @Test fun demoRefreshDoesNotBecomeChargeModeCommand() = runBlocking {
        var now = 1000L
        val gateway = DemoConnection(DemoScene.CHARGING) { now }
        val before = gateway.exchange(Protocol.request("a".repeat(32), "state.get"), false).state!!
        val refresh = Protocol.request("a".repeat(32), "vehicle.refresh", args = JsonObject())
        assertEquals("accepted", gateway.exchange(refresh, false).command!!.phase)
        now += 1200
        val query = Protocol.request("a".repeat(32), "command.get", args = JsonObject().apply { addProperty("command_id", refresh.id) })
        assertEquals("succeeded", gateway.exchange(query, false).command!!.phase)
        val after = gateway.exchange(Protocol.request("a".repeat(32), "state.get"), false).state!!
        assertEquals(before.charge.mode, after.charge.mode); assertEquals(before.charge.startLocal, after.charge.startLocal)
    }
}
