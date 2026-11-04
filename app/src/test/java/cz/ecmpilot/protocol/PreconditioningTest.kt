package cz.ecmpilot.protocol

import cz.ecmpilot.model.*
import org.junit.Assert.*
import org.junit.Test

class PreconditioningTest {
    private val caps = GatewayInfo("GW", "lab-plain", null, emptySet(),
        mapOf("climate.set" to Operation(false, true, enabledValues = setOf(true, false))),
        "info-can-experimental")
    private val stale = Stamp(86_400_000, 0)
    private val vehicle = VehicleState(awake = false, vehicleStamp = stale,
        climate = Climate("off", 0, stale), charge = Charge(stamp = stale))
    private val state = PilotState(Settings(source = Source.UDP, allowLabControls = true),
        nowMs = 1000, connected = true, info = caps, vehicle = vehicle)

    @Test fun experimentalPreconditioningRequiresExactBackendAndAdvertisedActions() {
        assertTrue(caps.climate(true)); assertTrue(caps.climate(false))
        assertFalse(caps.copy(controlBackend = "none").climate(true))
        assertFalse(caps.copy(operations = emptyMap()).climate(true))
        assertFalse(caps.copy(operations = mapOf("climate.set" to Operation(false, false, setOf(true)))).climate(true))
    }
    @Test fun explicitRequestCanWakeSleepingVehicleButStillNeedsOptInAndGateway() {
        assertTrue(state.controlReady("climate"))
        assertFalse(state.copy(settings = Settings(source = Source.UDP)).controlReady("climate"))
        assertFalse(state.copy(connected = false).controlReady("climate"))
        assertFalse(state.copy(permissionGranted = false).controlReady("climate"))
        assertFalse(state.copy(vehicle = vehicle.copy(climate = Climate(stamp = stale))).controlReady("climate"))
    }
    @Test fun pendingCommandDisablesButtonBeyondTenSecondsWithoutChangingReportedState() {
        val pending = state.copy(command = CommandUi(null, "accepted", "Waiting for vehicle"))
        assertFalse(pending.controlReady("climate"))
        assertFalse(pending.copy(nowMs = 29_999).controlReady("climate"))
        assertEquals("off", pending.vehicle!!.climate.state)
        assertTrue(pending.copy(command = CommandUi(null, "unknown", "Timed out")).controlReady("climate"))
    }
    @Test fun buttonLabelsDescribeRequestedActionWithoutPredictingOutcome() {
        assertEquals("Start preconditioning", DesiredCommand.ClimateSet(true).label)
        assertEquals("Stop preconditioning", DesiredCommand.ClimateSet(false).label)
        assertEquals("off", state.vehicle!!.climate.state)
    }
}
