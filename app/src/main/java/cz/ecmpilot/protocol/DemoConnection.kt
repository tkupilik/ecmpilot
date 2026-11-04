package cz.ecmpilot.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import cz.ecmpilot.model.DemoScene
import cz.ecmpilot.model.AutoRefreshSettings
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
    private var refreshed = false
    private var automatic = AutoRefreshSettings()
    private var programSlots = listOf(0x5801C2, 0x0007FF, 0x0007FF, 0x0007FF)
    private data class Pending(val request: Request, val accepted: Long, val domain: String, val rev: Long, var outcome: String? = null)
    private val commands = mutableMapOf<String, Pending>()
    override suspend fun exchange(request: Request, retry: Boolean): Status {
        delay(70)
        if (scene == DemoScene.OFFLINE) throw IOException("Demo gateway offline")
        val now = clock() - started
        val json = JsonObject().apply {
            addProperty("v", 0); addProperty("type", "status"); addProperty("gw", "demo-corsa")
            addProperty("boot", boot); addProperty("reply_to", request.id); addProperty("seq", ++seq); addProperty("now_ms", now)
            request.correlation?.let { addProperty("correlation_id", it.id) }
        }
        val op = request.json.get("op").asString
        when (op) {
            "info.get" -> json.add("info", info())
            "state.get" -> json.add("state", state())
            "vehicle.details.get" -> json.add("details", details())
            "climate.programs.get" -> json.add("programs", programs())
            "auto_refresh.get" -> json.add("auto_refresh", autoRefresh())
            "auto_refresh.set" -> {
                val args = request.json.getAsJsonObject("args")
                val requested = runCatching {
                    require(args.keySet() == setOf("rev", "enabled", "standby_min", "charging_min", "min_aux_soc_pct", "charge_limit_enabled", "charge_limit_soc_pct"))
                    fun integer(name: String): Long {
                        val v = args.get(name); require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber && Regex("0|[1-9][0-9]*").matches(v.asString)); return v.asLong
                    }
                    require(args.get("enabled").asJsonPrimitive.isBoolean)
                    val rev = integer("rev"); val standby = integer("standby_min"); val charging = integer("charging_min"); val soc = integer("min_aux_soc_pct")
                    require(standby in 1..1440 && charging in 1..1440 && soc in 0..100)
                    require(args.get("charge_limit_enabled").asJsonPrimitive.isBoolean)
                    AutoRefreshSettings(rev, args.get("enabled").asBoolean, standby.toInt(), charging.toInt(), soc.toInt(),
                        args.get("charge_limit_enabled").asBoolean, integer("charge_limit_soc_pct").also { require(it in 1..100) }.toInt()).also { require(it.valid()) }
                }.getOrNull()
                when {
                    request.json.get("boot")?.asString != boot -> json.addProperty("error", "boot_changed")
                    requested == null -> json.addProperty("error", "invalid_argument")
                    requested.copy(revision = automatic.revision) == automatic -> json.add("auto_refresh", autoRefresh())
                    requested.revision != automatic.revision -> json.addProperty("error", "settings_conflict")
                    else -> {
                        automatic = requested.copy(revision = if (automatic.revision == Int.MAX_VALUE.toLong()) 1 else automatic.revision + 1)
                        json.add("auto_refresh", autoRefresh())
                    }
                }
            }
            "command.get" -> {
                val id = request.json.getAsJsonObject("args").get("command_id")?.asString
                val previous = if (id != null) commands[id] else commands.values.firstOrNull {
                    it.request.correlation == request.correlation && request.correlation != null &&
                        it.request.json.get("client") == request.json.get("client") }
                previous?.let { json.add("command", result(it, now)) } ?: json.addProperty("error", "result_unknown")
            }
            else -> {
                val existing = commands[request.id] ?: commands.values.firstOrNull { it.request.correlation != null &&
                    it.request.correlation == request.correlation && it.request.json.get("client") == request.json.get("client") }
                existing?.let {
                    if (listOf("op", "args", "boot", "deadline_ms", "domain_rev").any { key -> it.request.json.get(key) != request.json.get(key) })
                        json.add("command", JsonObject().apply { addProperty("id", request.id); addProperty("revision", 1); addProperty("phase", "rejected"); addProperty("error", "id_conflict") })
                    else json.add("command", result(it, now))
                    return Protocol.parse(json.toString().toByteArray(), clock(), 70)
                }
                val domain = when (op) { "climate.set", "climate.programs.set" -> "climate"; "vehicle.refresh" -> "refresh"; else -> "charge" }
                if (scene == DemoScene.READ_ONLY) throw ProtocolException("Demo operation unavailable")
                val rev = when (domain) { "climate" -> ++climateRev; "charge" -> ++chargeRev; else -> 0L }
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
            if (pending.request.json.get("op").asString == "climate.programs.set") {
                if (args.getAsJsonArray("base").map { it.asInt } == programSlots)
                    programSlots = args.getAsJsonArray("slots").map { it.asInt }
                else { pending.outcome = "rejected"; addProperty("error", "preconditioning_programs_conflict") }
            } else if (pending.domain == "climate") climateOn = args.get("enabled").asBoolean
            else if (pending.domain == "charge") { mode = args.get("mode").asString; startLocal = args.get("start_local")?.asString ?: startLocal }
            else if (pending.domain == "refresh") refreshed = true
        }
        if (phase != "accepted" && pending.outcome == null) pending.outcome = phase
        addProperty("id", pending.request.id); addProperty("revision", if (phase == "accepted") 1 else 2)
        pending.request.correlation?.let { addProperty("correlation_id", it.id); addProperty("client_session", it.session) }
        addProperty("phase", pending.outcome ?: phase)
        if (phase == "rejected") { addProperty("error", "precondition_failed"); addProperty("reason", "not_parked") }
        if (phase == "unknown") addProperty("error", "unconfirmed")
    }
    private fun info() = JsonObject().apply {
        addProperty("firmware", "in-process demo 0.1"); addProperty("security", "demo"); addProperty("timezone", "Europe/Prague")
        add("fields", JsonArray().apply { listOf("soc_pct", "range_km", "cabin_c", "outside_c", "battery_min_c", "battery_max_c", "odometer_km", "vehicle_details", "climate_programs", "auto_refresh", "charge_limit").forEach(::add) })
        add("operations", JsonObject().apply {
            if (scene != DemoScene.READ_ONLY) {
                add("vehicle.refresh", JsonObject().apply { addProperty("verified", true); addProperty("confirmation", true) })
                add("climate.set", JsonObject().apply { addProperty("verified", true); addProperty("confirmation", true); add("enabled", JsonArray().apply { add(true); add(false) }) })
                add("climate.programs.set", JsonObject().apply { addProperty("verified", true); addProperty("confirmation", true); addProperty("persistent_schedule", true) })
                add("charge.mode.set", JsonObject().apply { addProperty("verified", true); addProperty("confirmation", true); addProperty("persistent_schedule", true); add("modes", JsonArray().apply { add("immediate"); add("deferred") }) })
            }
        })
    }
    private fun state() = JsonObject().apply {
        val age = if (scene == DemoScene.ASLEEP && !refreshed) 120_000L else 100L
        fun reading(name: String, value: Number?) { add(name, JsonObject().apply { if (value == null) add("value", JsonNull.INSTANCE) else addProperty("value", value); addProperty("age_ms", age) }) }
        val limited = scene == DemoScene.LIMITED
        reading("soc_pct", 68.2); reading("range_km", if (limited) null else 218)
        reading("cabin_c", if (limited) null else 16); reading("outside_c", 9)
        reading("battery_min_c", 24); reading("battery_max_c", 27); reading("odometer_km", if (limited) null else 42810)
        add("vehicle", JsonObject().apply { addProperty("awake", scene != DemoScene.ASLEEP || refreshed); addProperty("parked", true); addProperty("age_ms", 100) })
        add("bsi", JsonObject().apply { addProperty("power_management", if (scene == DemoScene.ASLEEP && !refreshed) 0 else 1); addProperty("age_ms", 100) })
        add("climate", JsonObject().apply { addProperty("state", if (climateOn) "on" else "off"); addProperty("rev", climateRev); addProperty("age_ms", age) })
        add("charge", JsonObject().apply {
            addProperty("state", if (mode == "immediate") "charging" else "waiting"); addProperty("mode", mode)
            addProperty("rev", chargeRev); addProperty("connected", true); addProperty("power_kw", if (mode == "immediate") 7.2 else 0)
            if (mode == "immediate") addProperty("remaining_min", 135)
            addProperty("start_local", startLocal); addProperty("age_ms", age)
        })
    }
    private fun details() = JsonObject().apply {
        val age = if (scene == DemoScene.ASLEEP && !refreshed) 120_000L else 100L
        add("body", JsonObject().apply {
            addProperty("open_mask", if (scene == DemoScene.DOORS) 21 else 0)
            addProperty("known_mask", if (scene == DemoScene.LIMITED) 3 else 31)
            addProperty("age_ms", age); addProperty("locking_state", if (scene == DemoScene.ASLEEP) 1 else 3)
            addProperty("lock_age_ms", age)
        })
        add("trips", JsonObject().apply {
            for ((key, value) in mapOf("avg1" to 21.5, "avg2" to 20.7, "instant" to 0,
                "distance1" to 164, "distance2" to 2232, "speed1" to 72, "speed2" to 88)) {
                add(key, JsonObject().apply { addProperty("value", value); addProperty("age_ms", age) })
            }
            add("history", JsonArray().apply { listOf(24, 26, 25, 22, 20, 21).forEach(::add) })
            addProperty("history_age_ms", age)
        })
    }
    private fun programs() = JsonObject().apply {
        addProperty("rev", climateRev)
        add("slots", JsonArray().apply { programSlots.forEach { raw ->
            add(JsonObject().apply { addProperty("raw", raw); addProperty("age_ms", if (scene == DemoScene.ASLEEP && !refreshed) 120000 else 100) })
        } })
    }
    private fun autoRefresh() = automatic.args().apply {
        addProperty("capable", scene != DemoScene.READ_ONLY); addProperty("config_valid", true)
        addProperty("state", if (automatic.enabled) "counting" else "disabled")
        addProperty("remaining_ms", if (automatic.enabled) automatic.chargingMin * 60_000L else 0)
        addProperty("request_count", 0); addProperty("last_result", 0)
        addProperty("charge_limit_state", if (automatic.chargeLimitEnabled) "monitoring" else "disabled")
        addProperty("charge_limit_last_result", 0)
    }
    override fun close() = Unit
}
