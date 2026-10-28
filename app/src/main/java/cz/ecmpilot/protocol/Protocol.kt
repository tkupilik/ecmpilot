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

class ProtocolException(message: String) : IOException(message)
fun newId(): String = UUID.randomUUID().toString().replace("-", "")

data class Request(val id: String, val json: JsonObject) {
    fun bytes(): ByteArray = json.toString().toByteArray(Charsets.UTF_8).also {
        if (it.size > Protocol.MAX_BYTES) throw ProtocolException("Request exceeds UDP size limit")
    }
}
data class Status(
    val gw: String, val boot: String, val replyTo: String, val seq: Long, val nowMs: Long,
    val info: GatewayInfo?, val state: VehicleState?, val command: CommandResult?, val error: String?,
    val receivedMs: Long, val roundTripMs: Long,
)

/** Tree-based Gson parsing avoids reflection and keeps the Android and JVM test codecs identical. */
object Protocol {
    const val MAX_BYTES = 1024
    private val idPattern = Regex("[a-f0-9]{32}")
    private val integer = Regex("0|[1-9][0-9]*")
    fun request(client: String, op: String, gw: String? = null, boot: String? = null, args: JsonObject = JsonObject(), deadline: Long? = null, domainRev: Long? = null): Request {
        val id = newId()
        val json = JsonObject().apply {
            addProperty("v", 0); addProperty("type", "request"); addProperty("client", client); addProperty("id", id)
            if (gw == null) add("gw", JsonNull.INSTANCE) else addProperty("gw", gw)
            if (boot != null) addProperty("boot", boot)
            if (deadline != null) addProperty("deadline_ms", deadline)
            if (domainRev != null) addProperty("domain_rev", domainRev)
            addProperty("op", op); add("args", args)
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
                    "odometer_km" to (0.0..10_000_000.0), "voltage_v" to (0.0..1000.0), "current_a" to (-2000.0..2000.0))
                val readings = metrics.mapNotNull { (key, range) -> s.obj(key)?.let { key to Reading(it.number("value", range), stamp(it)) } }.toMap()
                val v = s.obj("vehicle") ?: JsonObject(); val c = s.obj("climate") ?: JsonObject(); val q = s.obj("charge") ?: JsonObject()
                VehicleState(readings, v.bool("awake"), v.bool("parked"), stamp(v),
                    Climate(c.optionalString("state")?.takeIf { it in setOf("on", "off") } ?: "unknown", c.optionalLong("rev"), stamp(c)),
                    Charge(q.optionalString("state")?.takeIf { it in setOf("charging", "waiting", "complete", "disconnected", "fault") } ?: "unknown",
                        q.optionalString("mode")?.takeIf { it in setOf("immediate", "deferred") } ?: "unknown", q.optionalLong("rev"), q.bool("connected"),
                        q.number("power_kw", 0.0..1000.0), q.number("remaining_min", 0.0..100_000.0), q.optionalString("start_local"), stamp(q)))
            }
            val command = root.obj("command")?.let { c ->
                val phase = c.string("phase").also { require(it in setOf("accepted", "executing", "succeeded", "rejected", "failed", "unknown")) }
                CommandResult(c.string("id").also { require(idPattern.matches(it)) }, c.long("revision"), phase, c.errorCode("error"), c.optionalString("reason"))
            }
            return Status(gw, boot, reply, seq, now, info, state, command, root.errorCode("error"), receivedMs, roundTripMs)
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
    fun accept(status: Status): Boolean {
        if (boot != status.boot) { boot = status.boot; seq = -1; snapshot = null }
        if (status.seq <= seq) return false
        seq = status.seq
        status.state?.let { snapshot = it }
        return true
    }
}
