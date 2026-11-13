package cz.ecmpilot.protocol

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import cz.ecmpilot.model.*
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.time.ZoneId

class ProtocolException(message: String) : IOException(message)
fun newId(): String = UUID.randomUUID().toString().replace("-", "")

data class Request(val id: String, val json: JsonObject) {
    val correlation get() = json.get("correlation_id")?.let { Correlation(json.get("client_session").asString, it.asLong) }
    fun bytes(): ByteArray = json.toString().toByteArray(Charsets.UTF_8).also {
        if (it.size > Protocol.MAX_BYTES) throw ProtocolException("Request exceeds UDP size limit")
    }
}
data class Status(
    val gw: String, val boot: String, val replyTo: String, val seq: Long, val nowMs: Long,
    val info: GatewayInfo?, val state: VehicleState?, val command: CommandResult?, val error: String?,
    val receivedMs: Long, val roundTripMs: Long,
    val details: VehicleDetails? = null,
    val programs: ClimatePrograms? = null,
    val autoRefresh: AutoRefreshStatus? = null,
    val correlationId: Long? = null,
    val lowPower: LowPowerStatus? = null,
    val chargeSchedule: ChargeScheduleStatus? = null,
    val elm: ElmStatus? = null,
    val obd: ObdPage? = null,
)

/** Tree-based Gson parsing avoids reflection and keeps the Android and JVM test codecs identical. */
object Protocol {
    const val MAX_BYTES = 1024
    private val correlations = CorrelationCounter()
    fun nextCorrelation() = correlations.next()
    private val idPattern = Regex("[a-f0-9]{32}")
    private val integer = Regex("0|[1-9][0-9]*")
    fun chargeModeArgs(command: DesiredCommand.ChargeMode, timezone: String?): JsonObject = JsonObject().apply {
        require(command.mode in setOf("immediate", "deferred")) { "Invalid charging mode" }
        addProperty("mode", command.mode)
        if (command.startLocal != null || command.mode == "deferred") {
            require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(command.startLocal ?: "")) { "Enter a valid start time" }
            val zone = requireNotNull(timezone) { "Gateway did not report its timezone" }
            ZoneId.of(zone)
            addProperty("start_local", command.startLocal)
            addProperty("timezone", zone)
        }
    }
    fun request(client: String, op: String, gw: String? = null, boot: String? = null, args: JsonObject = JsonObject(), deadline: Long? = null, domainRev: Long? = null, correlation: Correlation? = nextCorrelation()): Request {
        val id = newId()
        val json = JsonObject().apply {
            addProperty("v", 0); addProperty("type", "request"); addProperty("client", client); addProperty("id", id)
            if (gw == null) add("gw", JsonNull.INSTANCE) else addProperty("gw", gw)
            if (boot != null) addProperty("boot", boot)
            if (deadline != null) addProperty("deadline_ms", deadline)
            if (domainRev != null) addProperty("domain_rev", domainRev)
            addProperty("op", op); add("args", args)
            correlation?.let { addProperty("client_session", it.session); addProperty("correlation_id", it.id) }
        }
        return Request(id, json)
    }

    fun parse(bytes: ByteArray, receivedMs: Long, roundTripMs: Long): Status {
        if (bytes.size > MAX_BYTES) throw ProtocolException("Oversized UDP response")
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
            val root = reader.use {
                val value = readTree(it, 0)
                require(it.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON data" }
                value.asJsonObject
            }
            require(root.long("v") == 0L) { "Unsupported protocol version" }
            require(root.string("type") == "status") { "Expected status message" }
            val gw = root.string("gw").also { require(it.length in 1..80) }
            val boot = root.string("boot").also { require(idPattern.matches(it)) }
            val reply = root.string("reply_to").also { require(idPattern.matches(it)) }
            val seq = root.long("seq"); val now = root.long("now_ms")
            val stamp: (JsonObject) -> Stamp = { Stamp(it.optionalLong("age_ms"), receivedMs, roundTripMs) }
            val info = root.obj("info")?.let { i ->
                val operations = i.obj("operations")?.entrySet()?.associate { (key, value) ->
                    val o = value.asJsonObject
                    key to Operation(o.bool("verified") == true, o.bool("confirmation") == true,
                        o.array("enabled")?.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean }?.toSet() ?: emptySet(),
                        o.array("modes")?.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }?.toSet() ?: emptySet(),
                        o.bool("persistent_schedule") == true)
                } ?: emptyMap()
                GatewayInfo(i.optionalString("firmware") ?: "Unknown", i.optionalString("security") ?: "unknown",
                    i.optionalString("timezone"), i.array("fields")?.map { require(it.asJsonPrimitive.isString); it.asString }?.toSet() ?: emptySet(), operations, i.optionalString("control_backend"))
            }
            val state = root.obj("state")?.let { s ->
                val metrics = mapOf("soc_pct" to (0.0..100.0), "range_km" to (0.0..2000.0), "cabin_c" to (-60.0..100.0),
                    "outside_c" to (-60.0..100.0), "battery_min_c" to (-60.0..150.0), "battery_max_c" to (-60.0..150.0),
                    "odometer_km" to (0.0..10_000_000.0), "voltage_v" to (0.0..1000.0), "current_a" to (-2000.0..2000.0),
                    "aux_battery_soc_pct" to (0.0..100.0), "aux_battery_voltage_v" to (0.0..32.0),
                    "climate_left_setpoint_c" to (0.0..60.0), "climate_right_setpoint_c" to (0.0..60.0),
                    "evaporator_c" to (-40.0..85.0))
                val readings = metrics.mapNotNull { (key, range) -> s.obj(key)?.let { key to Reading(it.number("value", range), stamp(it)) } }.toMap()
                val v = s.obj("vehicle") ?: JsonObject(); val c = s.obj("climate") ?: JsonObject(); val q = s.obj("charge") ?: JsonObject()
                VehicleState(readings, v.bool("awake"), v.bool("parked"), stamp(v),
                    Climate(c.optionalString("state")?.takeIf { it in setOf("on", "off") } ?: "unknown", c.optionalLong("rev"), stamp(c)),
                    Charge(q.optionalString("state")?.takeIf { it in setOf("charging", "waiting", "complete", "disconnected", "fault") } ?: "unknown",
                        q.optionalString("mode")?.takeIf { it in setOf("immediate", "deferred") } ?: "unknown", q.optionalLong("rev"), q.bool("connected"),
                        q.number("power_kw", 0.0..1000.0), q.number("remaining_min", 0.0..100_000.0), q.optionalString("start_local"), stamp(q),
                        q.optionalLong("mode_age_ms")?.let { Stamp(it, receivedMs, roundTripMs) } ?: stamp(q), q.bool("schedule_available"),
                        q.optionalLong("remaining_age_ms")?.let { Stamp(it, receivedMs, roundTripMs) } ?: stamp(q)),
                    s.obj("bsi")?.let { BsiStatus(it.optionalLong("power_management")?.takeIf { phase -> phase in 0L..4L }?.toInt(), stamp(it)) })
            }
            val details = root.obj("details")?.let { d ->
                val b = d.obj("body")
                val body = b?.let {
                    val open = it.optionalLong("open_mask")?.takeIf { m -> m in 0..31 }?.toInt()
                    val known = if (open == null) 0 else it.optionalLong("known_mask")?.takeIf { m -> m in 0..31 }?.toInt() ?: 0
                    BodyStatus((open ?: 0) and known, known, stamp(it),
                        it.optionalLong("locking_state")?.takeIf { n -> n in 1..6 }?.toInt(),
                        Stamp(it.optionalLong("lock_age_ms"), receivedMs, roundTripMs))
                }
                val t = d.obj("trips") ?: JsonObject()
                val ranges = mapOf("avg1" to (0.0..50.0), "avg2" to (0.0..50.0), "instant" to (0.0..50.0),
                    "distance1" to (0.0..65534.0), "distance2" to (0.0..65534.0),
                    "speed1" to (0.0..254.0), "speed2" to (0.0..254.0))
                val trips = ranges.mapNotNull { (key, range) -> t.obj(key)?.let { key to Reading(it.number("value", range), stamp(it)) } }.toMap()
                val historyStamp = Stamp(t.optionalLong("history_age_ms"), receivedMs, roundTripMs)
                val history = t.array("history")?.let { a ->
                    require(a.size() == 6) { "Expected six consumption slots" }
                    a.map { v ->
                        val value = if (v.isJsonNull) null else {
                            require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber)
                            v.asDouble.takeIf { n -> n.isFinite() && n in 0.0..50.0 }
                        }
                        Reading(value, historyStamp)
                    }
                } ?: emptyList()
                VehicleDetails(body, trips, history)
            }
            val programs = root.obj("programs")?.let { p ->
                val slots = requireNotNull(p.array("slots")) { "Missing program slots" }
                require(slots.size() == 4) { "Expected four preconditioning slots" }
                ClimatePrograms(slots.map { v ->
                    val slot = v.asJsonObject
                    ClimateProgram(slot.optionalLong("raw")?.takeIf { it in 0..0xFFFFFF }?.toInt(), stamp(slot))
                }, p.optionalLong("rev"))
            }
            val autoRefresh = root.obj("auto_refresh")?.let { a ->
                val settings = AutoRefreshSettings(a.long("rev"), requireNotNull(a.bool("enabled")),
                    a.long("standby_min").also { require(it in 1..1440) }.toInt(),
                    a.long("charging_min").also { require(it in 1..1440) }.toInt(),
                    a.long("min_aux_soc_pct").also { require(it in 0..100) }.toInt(),
                    a.bool("charge_limit_enabled") ?: false,
                    (a.optionalLong("charge_limit_soc_pct") ?: 80).also { require(it in 1..100) }.toInt())
                require(settings.valid()) { "Invalid automatic refresh configuration" }
                AutoRefreshStatus(settings, requireNotNull(a.bool("capable")), requireNotNull(a.bool("config_valid")),
                    a.string("state"), a.long("remaining_ms"), a.long("request_count"),
                    a.optionalString("charge_limit_state") ?: "disabled", a.optionalLong("charge_limit_last_result") ?: 0)
            }
            val lowPower = root.obj("low_power")?.let { p ->
                val settings=LowPowerSettings(p.long("rev"),requireNotNull(p.bool("enabled")),p.long("idle_hours").also { require(it in 1..720) }.toInt())
                require(settings.valid()) { "Invalid low-power settings" }
                LowPowerStatus(settings,requireNotNull(p.bool("capable")),requireNotNull(p.bool("config_valid")),p.string("state"),
                    p.optionalLong("remaining_s")?.also { require(it in 0..2592000) },receivedMs,
                    p.number("last_error",-2147483648.0..2147483647.0)?.toLong() ?: 0)
            }
            val chargeSchedule = root.obj("charge_schedule")?.let { p ->
                val settings = ChargeScheduleSettings.parse(p)
                val clock = requireNotNull(p.obj("clock"))
                val local = clock.optionalString("local")
                val next = p.optionalString("next_local")?.also { java.time.LocalDateTime.parse(it) }
                ChargeScheduleStatus(settings,p.string("state"),requireNotNull(p.bool("active")),next,
                    (p.optionalLong("last_result") ?: 0).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt(),
                    VehicleClock(local,(clock.optionalLong("second") ?: 0).also { require(it in 0..59) }.toInt(),
                        clock.optionalLong("age_ms"),receivedMs))
            }
            val elm = root.obj("elm327")?.let { e ->
                fun adapter(address: String, type: String, rssi: Int? = null): ObdAdapter {
                    require(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(address))
                    require(type in setOf("public", "random"))
                    return ObdAdapter(address.uppercase(java.util.Locale.ROOT), type, rssi)
                }
                val address = e.string("address")
                val found = requireNotNull(e.array("scan")).also { require(it.size() <= 6) }.map { value ->
                    val row = value.asJsonArray.also { require(it.size() == 3) }
                    require(row[0].isJsonPrimitive && row[0].asJsonPrimitive.isString)
                    require(row[1].isJsonPrimitive && row[1].asJsonPrimitive.isNumber && row[1].asString in setOf("0", "1"))
                    require(row[2].isJsonPrimitive && row[2].asJsonPrimitive.isNumber && Regex("-?[0-9]+").matches(row[2].asString))
                    require(row[2].asDouble in -127.0..20.0)
                    adapter(row[0].asString, if (row[1].asInt == 0) "public" else "random", row[2].asInt.also { require(it in -127..20) })
                }
                ElmStatus(e.long("rev").also { require(it in 1..Int.MAX_VALUE.toLong()) },
                    if (address.isBlank()) null else adapter(address,e.string("address_type")), requireNotNull(e.bool("busy")),
                    e.long("generation").also { require(it in 0..Int.MAX_VALUE.toLong()) },
                    e.long("completed").also { require(it in 0..Int.MAX_VALUE.toLong()) },
                    e.long("operation").also { require(it in 0..4) }.toInt(), e.long("result").also { require(it in 0..12) }.toInt(), found)
            }
            val obd = root.obj("obd")?.let { o ->
                require(o.long("pages") == ObdFields.PAGES.toLong())
                val page = o.long("page").also { require(it in 0L until ObdFields.PAGES.toLong()) }.toInt()
                val allowed = ObdFields.all.drop(page * 6).take(6).associateBy { it.key }
                val readings = requireNotNull(o.obj("readings")).entrySet().associate { (key,value) ->
                    val field = requireNotNull(allowed[key]) { "Unexpected OBD field" }
                    val reading = value.asJsonObject
                    key to Reading(reading.number("value",field.range), stamp(reading))
                }
                require(readings.keys == allowed.keys) { "Incomplete OBD page" }
                ObdPage(page,readings)
            }
            val command = root.obj("command")?.let { c ->
                val phase = c.string("phase").also { require(it in setOf("accepted", "executing", "succeeded", "rejected", "failed", "unknown")) }
                val correlation = c.optionalLong("correlation_id")?.let { Correlation(c.string("client_session"), it) }
                require(correlation != null || c.value("client_session") == null)
                val preconditioningFeedback = c.optionalLong("preconditioning_feedback")?.also { require(it in 0..15) }?.toInt()
                CommandResult(c.string("id").also { require(idPattern.matches(it)) }, c.long("revision"), phase, c.errorCode("error"), c.optionalString("reason"), correlation, preconditioningFeedback)
            }
            val correlationId = root.optionalLong("correlation_id")?.also { require(it in 1..0xffff_ffffL) }
            return Status(gw, boot, reply, seq, now, info, state, command, root.errorCode("error"), receivedMs, roundTripMs, details, programs, autoRefresh, correlationId, lowPower, chargeSchedule, elm, obd)
        } catch (error: Exception) {
            if (error is ProtocolException) throw error
            throw ProtocolException("Invalid gateway response: ${error.message?.take(140) ?: "JSON error"}")
        }
    }

    private fun readTree(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 12) { "JSON nesting too deep" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) { val name = reader.nextName(); require(!has(name)) { "Duplicate JSON key" }; add(name, readTree(reader, depth + 1)) }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply { reader.beginArray(); while (reader.hasNext()) add(readTree(reader, depth + 1)); reader.endArray() }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Unexpected JSON token")
        }
    }
    private fun JsonObject.value(name: String) = get(name)?.takeUnless { it.isJsonNull }
    private fun JsonObject.string(name: String): String = optionalString(name) ?: throw IllegalArgumentException("Missing $name")
    private fun JsonObject.optionalString(name: String): String? = value(name)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
    private fun JsonObject.long(name: String) = optionalLong(name) ?: throw IllegalArgumentException("Missing $name")
    private fun JsonObject.optionalLong(name: String): Long? = value(name)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && integer.matches(it.asString)); it.asString.toLong() }
    private fun JsonObject.bool(name: String): Boolean? = value(name)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean }
    private fun JsonObject.obj(name: String): JsonObject? = value(name)?.let { require(it.isJsonObject); it.asJsonObject }
    private fun JsonObject.array(name: String): JsonArray? = value(name)?.let { require(it.isJsonArray); it.asJsonArray }
    private fun JsonObject.number(name: String, range: ClosedFloatingPointRange<Double>): Double? = value(name)?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        it.asDouble.takeIf { n -> n.isFinite() && n in range }
    }
    private fun JsonObject.errorCode(name: String): String? = value(name)?.let {
        if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else it.asJsonObject.optionalString("code")
    }
}

/** Snapshot order and command-result order are intentionally independent. */
class StatusTracker {
    var boot: String? = null; private set
    private var seq = -1L
    var snapshot: VehicleState? = null; private set
    var details: VehicleDetails? = null; private set
    var programs: ClimatePrograms? = null; private set
    var autoRefresh: AutoRefreshStatus? = null; private set
    var lowPower: LowPowerStatus? = null; private set
    var chargeSchedule: ChargeScheduleStatus? = null; private set
    var elm: ElmStatus? = null; private set
    var obd: Map<String, Reading> = emptyMap(); private set
    fun accept(status: Status): Boolean {
        if (boot != status.boot) { boot = status.boot; seq = -1; snapshot = null; details = null; programs = null; autoRefresh = null; lowPower = null; chargeSchedule = null; elm = null; obd = emptyMap() }
        if (status.seq <= seq) return false
        seq = status.seq
        status.state?.let { snapshot = it }
        status.details?.let { details = it }
        status.programs?.let { programs = it }
        status.autoRefresh?.let { autoRefresh = it }
        status.lowPower?.let { lowPower = it }
        status.chargeSchedule?.let { chargeSchedule = it }
        status.elm?.let { elm = it }
        status.obd?.let { obd = obd + it.readings }
        return true
    }
}
