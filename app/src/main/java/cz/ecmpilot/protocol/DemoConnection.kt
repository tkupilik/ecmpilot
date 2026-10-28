package cz.ecmpilot.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import cz.ecmpilot.model.DemoScene
import kotlinx.coroutines.delay
import java.io.IOException

/** In-process fake gateway. No sockets are created by demo mode. */
class DemoConnection(private val scene: DemoScene, private val clock: () -> Long) : GatewayConnection {
    private val boot = newId()
    private val started = clock()
    private var seq = 0L
    private var climateOn = scene == DemoScene.RUNNING
    private var climateRev = 0L
    private var chargeRev = 0L
    private var mode = "immediate"
    private var startLocal = "23:00"
    private data class Pending(val request: Request, val accepted: Long, val domain: String, val rev: Long, var outcome: String? = null)
    private val commands = mutableMapOf<String, Pending>()
    override suspend fun exchange(request: Request, retry: Boolean): Status {
        delay(70)
        if (scene == DemoScene.OFFLINE) throw IOException("Demo gateway offline")
        val now = clock() - started
        val json = JsonObject().apply {
            addProperty("v", 0); addProperty("type", "status"); addProperty("gw", "demo-corsa")
            addProperty("boot", boot); addProperty("reply_to", request.id); addProperty("seq", ++seq); addProperty("now_ms", now)
        }
        val op = request.json.get("op").asString
        when (op) {
            "info.get" -> json.add("info", info())
            "state.get" -> json.add("state", state())
            "command.get" -> {
                val id = request.json.getAsJsonObject("args").get("command_id").asString
                commands[id]?.let { json.add("command", result(it, now)) } ?: json.addProperty("error", "result_unknown")
            }
            else -> {
                commands[request.id]?.let { json.add("command", result(it, now)); return Protocol.parse(json.toString().toByteArray(), clock(), 70) }
                val domain = if (op == "climate.set") "climate" else "charge"
                if (scene == DemoScene.READ_ONLY) throw ProtocolException("Demo operation unavailable")
                val rev = if (domain == "climate") ++climateRev else ++chargeRev
                val pending = Pending(request, now, domain, rev)
                commands[request.id] = pending
                json.add("command", result(pending, now))
            }
        }
        return Protocol.parse(json.toString().toByteArray(), clock(), 70)
    }
    private fun result(pending: Pending, now: Long): JsonObject = JsonObject().apply {
        val ready = now - pending.accepted >= 1100
        val phase = pending.outcome ?: when {
            scene == DemoScene.REJECT -> "rejected"
            !ready -> "accepted"
            scene == DemoScene.UNCONFIRMED -> "unknown"
            else -> "succeeded"
        }
        if (phase == "succeeded" && pending.outcome == null) {
            val args = pending.request.json.getAsJsonObject("args")
            if (pending.domain == "climate") climateOn = args.get("enabled").asBoolean
            else { mode = args.get("mode").asString; startLocal = args.get("start_local")?.asString ?: startLocal }
        }
        if (phase != "accepted") pending.outcome = phase
        addProperty("id", pending.request.id); addProperty("revision", if (phase == "accepted") 1 else 2)
        addProperty("phase", phase)
        if (phase == "rejected") { addProperty("error", "precondition_failed"); addProperty("reason", "not_parked") }
        if (phase == "unknown") addProperty("error", "unconfirmed")
    }
    private fun info() = JsonObject().apply {
        addProperty("firmware", "in-process demo 0.1"); addProperty("security", "demo"); addProperty("timezone", "Europe/Prague")
        add("fields", JsonArray().apply { listOf("soc_pct", "range_km", "cabin_c", "outside_c", "battery_min_c", "battery_max_c", "odometer_km").forEach(::add) })
        add("operations", JsonObject().apply {
            if (scene != DemoScene.READ_ONLY) {
                add("climate.set", JsonObject().apply { addProperty("verified", true); addProperty("confirmation", true); add("enabled", JsonArray().apply { add(true); add(false) }) })
                add("charge.mode.set", JsonObject().apply { addProperty("verified", true); addProperty("confirmation", true); addProperty("persistent_schedule", true); add("modes", JsonArray().apply { add("immediate"); add("deferred") }) })
            }
        })
    }
    private fun state() = JsonObject().apply {
        val age = if (scene == DemoScene.ASLEEP) 120_000L else 100L
        fun reading(name: String, value: Number?) { add(name, JsonObject().apply { if (value == null) add("value", JsonNull.INSTANCE) else addProperty("value", value); addProperty("age_ms", age) }) }
        val limited = scene == DemoScene.LIMITED
        reading("soc_pct", 68.2); reading("range_km", if (limited) null else 218)
        reading("cabin_c", if (limited) null else 16); reading("outside_c", 9)
        reading("battery_min_c", 24); reading("battery_max_c", 27); reading("odometer_km", if (limited) null else 42810)
        add("vehicle", JsonObject().apply { addProperty("awake", scene != DemoScene.ASLEEP); addProperty("parked", true); addProperty("age_ms", 100) })
        add("climate", JsonObject().apply { addProperty("state", if (climateOn) "on" else "off"); addProperty("rev", climateRev); addProperty("age_ms", age) })
        add("charge", JsonObject().apply {
            addProperty("state", if (mode == "immediate") "charging" else "waiting"); addProperty("mode", mode)
            addProperty("rev", chargeRev); addProperty("connected", true); addProperty("power_kw", if (mode == "immediate") 7.2 else 0)
            if (mode == "immediate") addProperty("remaining_min", 135)
            addProperty("start_local", startLocal); addProperty("age_ms", age)
        })
    }
    override fun close() = Unit
}
