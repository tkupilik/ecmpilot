package cz.ecmpilot.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val boot = "8a14f3041ff548b7ae74634061270b42"
    private val id = "df3e684db5284a7ab1799c1f08f54233"
    private fun base(seq: Int = 1) = JsonParser.parseString("""{"v":0,"type":"status","gw":"test-gateway","boot":"$boot","reply_to":"$id","seq":$seq,"now_ms":123000}""").asJsonObject
    private fun parse(o: JsonObject) = Protocol.parse(o.toString().toByteArray(), 1000, 50)
    private fun invalid(text: String) {
        try { Protocol.parse(text.toByteArray(), 1000, 50); fail("Expected protocol rejection") } catch (_: ProtocolException) { }
    }
    @Test fun rejectsDuplicateKeysAtAnyDepth() {
        invalid(base().toString().replace("\"seq\":1", "\"seq\":1,\"seq\":2"))
        invalid(base().toString().dropLast(1) + ",\"state\":{\"soc_pct\":{\"value\":10,\"value\":100,\"age_ms\":0}}}")
    }
    @Test fun rejectsWrongVersionTrailingJsonAndFractionalSequence() {
        invalid(base().apply { addProperty("v", 1) }.toString())
        invalid(base().toString() + "{}")
        invalid(base().apply { addProperty("seq", 1.5) }.toString())
    }
    @Test fun rejectsOversizedAndMalformedUtf8() {
        try { Protocol.parse(ByteArray(1025), 0, 0); fail() } catch (_: ProtocolException) { }
        try { Protocol.parse(byteArrayOf(0xc3.toByte(), 0x28), 0, 0); fail() } catch (_: ProtocolException) { }
    }
    @Test fun nullAndOutOfRangeAreNotZeroOrFresh() {
        val packet = base().apply { add("state", JsonParser.parseString("""{"soc_pct":{"value":null,"age_ms":0},"cabin_c":{"value":999,"age_ms":0},"vehicle":{"awake":null,"age_ms":null}}""")) }
        val state = parse(packet).state!!
        assertNull(state.awake); assertNull(state.reading("soc_pct")!!.value); assertNull(state.reading("cabin_c")!!.value)
        assertFalse(state.reading("soc_pct")!!.fresh(1000))
    }
    @Test fun stringBooleansAndNegativeAgesAreRejected() {
        invalid(base().toString().dropLast(1) + ",\"state\":{\"vehicle\":{\"awake\":\"true\",\"age_ms\":0}}}")
        invalid(base().toString().dropLast(1) + ",\"state\":{\"soc_pct\":{\"value\":60,\"age_ms\":-1}}}")
    }
    @Test fun packetReceiptCannotRefreshOldObservation() {
        val packet = base().apply { add("state", JsonParser.parseString("""{"soc_pct":{"value":60,"age_ms":120000}}""")) }
        val reading = parse(packet).state!!.reading("soc_pct")!!
        assertEquals(120_050L, reading.stamp.age(1000))
        assertFalse(reading.fresh(1000))
        assertEquals(Long.MAX_VALUE, Stamp(Long.MAX_VALUE, 0, 50).age(1000))
    }
    @Test fun snapshotSequenceAndBootAreHandledSeparately() {
        val tracker = StatusTracker()
        fun snapshot(seq: Int, value: Int, bootId: String = boot) = parse(base(seq).apply {
            addProperty("boot", bootId); add("state", JsonParser.parseString("""{"soc_pct":{"value":$value,"age_ms":0}}"""))
        })
        assertTrue(tracker.accept(snapshot(20, 70)))
        assertFalse(tracker.accept(snapshot(19, 20)))
        assertEquals(70.0, tracker.snapshot!!.reading("soc_pct")!!.value!!, .001)
        assertTrue(tracker.accept(snapshot(1, 60, "118f52f2a3b445849a6318a6a7664f01")))
        assertEquals(60.0, tracker.snapshot!!.reading("soc_pct")!!.value!!, .001)
    }
    @Test fun unsupportedGatewayCapabilitiesDoNotEnableControls() {
        val info = GatewayInfo("x", "lab-plain", "Europe/Prague", emptySet(), mapOf("climate.set" to Operation(false, true, setOf(true))))
        assertFalse(info.climate(true)); assertFalse(info.charge("deferred"))
        val reading = Stamp(0, 1000)
        val vehicle = VehicleState(vehicleStamp = reading, awake = true, climate = Climate("off", 1, reading), charge = Charge(stamp = reading))
        val state = PilotState(Settings(source = Source.UDP), nowMs = 1000, connected = true, info = info, vehicle = vehicle)
        assertFalse(state.controlReady("climate")) // lab controls are an explicit opt-in
        assertFalse(state.copy(settings = state.settings.copy(allowLabControls = true), info = info.copy(security = "dtls-psk")).controlReady("climate"))
        assertTrue(state.copy(settings = Settings(), permissionGranted = false).controlReady("climate")) // demo never needs LAN permission
    }
    @Test fun explicitVarstoreBackendEnablesOnlyAdvertisedTestOperations() {
        val packet = base().apply { add("info", JsonParser.parseString("""{"firmware":"0.3.0","security":"lab-plain","control_backend":"varstore-test","timezone":"Europe/Prague","fields":[],"operations":{"climate.set":{"verified":false,"confirmation":true,"enabled":[true,false]},"charge.mode.set":{"verified":false,"confirmation":true,"modes":["immediate","deferred"],"persistent_schedule":false}}}""")) }
        val info = parse(packet).info!!
        assertTrue(info.testMode); assertTrue(info.climate(true)); assertTrue(info.charge("deferred"))
        assertFalse(info.operations.getValue("climate.set").verified)
        assertFalse(info.copy(controlBackend = "unknown").climate(true))
        assertFalse(info.copy(controlBackend = null).charge("deferred"))
        assertFalse(info.copy(operations = emptyMap()).climate(true))
        assertFalse(info.copy(operations = mapOf("climate.set" to Operation(false, false, setOf(true)))).climate(true))
    }
    @Test fun parsesPacketsGeneratedByFirmwareCCodec() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/gateway/$name.json")!!.use {
            Protocol.parse(it.readBytes(), 1000, 10)
        }
        val info = fixture("info")
        assertEquals("ecmpilot-test", info.gw); assertTrue(info.info!!.testMode)
        assertTrue(info.info!!.climate(true)); assertTrue(info.info!!.charge("deferred"))
        val state = fixture("state").state!!
        assertEquals(68.2, state.reading("soc_pct")!!.value!!, .001)
        assertEquals(true, state.awake); assertEquals("off", state.climate.state)
        assertTrue(state.climate.stamp.fresh(1000))
        assertEquals("accepted", fixture("accepted").command!!.phase)
        val aux = fixture("aux-battery").state!!
        assertEquals(12.6, aux.reading("aux_battery_voltage_v")!!.value!!, .001)
        assertEquals(81.5, aux.reading("aux_battery_soc_pct")!!.value!!, .001)
        assertNull(aux.reading("voltage_v")?.value)
        val climate = fixture("climate-setpoints").state!!
        assertEquals(20.0, climate.reading("climate_left_setpoint_c")!!.value!!, .001)
        assertEquals(21.0, climate.reading("climate_right_setpoint_c")!!.value!!, .001)
        assertEquals(5.5, climate.reading("evaporator_c")!!.value!!, .001)
        assertNull(climate.reading("cabin_c")!!.value)
    }
    @Test fun auxiliaryBatteryIsSeparateFromHvAndRetainsFreshness() {
        val packet = base().apply { add("state", JsonParser.parseString("""{"aux_battery_soc_pct":{"value":81.5,"age_ms":1200},"aux_battery_voltage_v":{"value":12.6,"age_ms":1300},"voltage_v":{"value":416.25,"age_ms":25}}""")) }
        val readings = parse(packet).state!!
        assertEquals(81.5, readings.reading("aux_battery_soc_pct")!!.value!!, .001)
        assertEquals(12.6, readings.reading("aux_battery_voltage_v")!!.value!!, .001)
        assertEquals(416.25, readings.reading("voltage_v")!!.value!!, .001)
        assertTrue(readings.reading("aux_battery_soc_pct")!!.fresh(1000))
        assertFalse(readings.reading("aux_battery_soc_pct")!!.fresh(6000))
        val invalid = base().apply { add("state", JsonParser.parseString("""{"aux_battery_soc_pct":{"value":101,"age_ms":0},"aux_battery_voltage_v":{"value":99,"age_ms":0}}""")) }
        assertNull(parse(invalid).state!!.reading("aux_battery_soc_pct")!!.value)
        assertNull(parse(invalid).state!!.reading("aux_battery_voltage_v")!!.value)
        assertNull(parse(base()).state?.reading("aux_battery_soc_pct"))
    }
    @Test fun climateSetpointsAndEvaporatorStaySeparateFromCabinAndState() {
        val packet = base().apply { add("state", JsonParser.parseString("""{"climate_left_setpoint_c":{"value":20,"age_ms":250},"climate_right_setpoint_c":{"value":21,"age_ms":500},"evaporator_c":{"value":5.5,"age_ms":500},"cabin_c":{"value":19,"age_ms":10},"climate":{"state":"unknown"}}""")) }
        val state = parse(packet).state!!
        assertEquals(20.0, state.reading("climate_left_setpoint_c")!!.value!!, .001)
        assertEquals(21.0, state.reading("climate_right_setpoint_c")!!.value!!, .001)
        assertEquals(5.5, state.reading("evaporator_c")!!.value!!, .001)
        assertEquals(19.0, state.reading("cabin_c")!!.value!!, .001)
        assertEquals("unknown", state.climate.state)
        assertTrue(state.reading("climate_left_setpoint_c")!!.fresh(1000))
        assertFalse(state.reading("climate_left_setpoint_c")!!.fresh(6000))
        packet.add("state", JsonParser.parseString("""{"climate_left_setpoint_c":{"value":99,"age_ms":0},"evaporator_c":{"value":100,"age_ms":0}}"""))
        assertNull(parse(packet).state!!.reading("climate_left_setpoint_c")!!.value)
        assertNull(parse(packet).state!!.reading("evaporator_c")!!.value)
    }
    @Test fun demoAcceptanceDoesNotChangeObservedClimate() = runBlocking {
        var now = 0L
        val demo = DemoConnection(DemoScene.CHARGING) { now }
        val info = demo.exchange(Protocol.request(newId(), "info.get"))
        val req = Protocol.request(newId(), "climate.set", info.gw, info.boot, JsonObject().apply { addProperty("enabled", true) }, 5000, 0)
        assertEquals("accepted", demo.exchange(req).command!!.phase)
        assertEquals("off", demo.exchange(Protocol.request(newId(), "state.get")).state!!.climate.state)
        now = 1500
        val query = Protocol.request(newId(), "command.get", args = JsonObject().apply { addProperty("command_id", req.id) })
        assertEquals("succeeded", demo.exchange(query).command!!.phase)
        assertEquals("on", demo.exchange(Protocol.request(newId(), "state.get")).state!!.climate.state)
        demo.close()
    }
}
