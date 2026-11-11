package cz.ecmpilot.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ChargePause(val enabled: Boolean = true, val days: Int = 127, val from: String = "07:00", val to: String = "09:00") {
    fun valid() = days in 1..127 && TIME.matches(from) && TIME.matches(to) && from != to
    fun json() = JsonObject().apply {
        addProperty("enabled", enabled); addProperty("days", days); addProperty("from", from); addProperty("to", to)
    }
    companion object { val TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]") }
}

data class ChargeScheduleSettings(val revision: Long = 1, val enabled: Boolean = false, val intervals: List<ChargePause> = emptyList()) {
    fun valid() = revision in 1..Int.MAX_VALUE.toLong() && intervals.size <= 8 && intervals.all { it.valid() } && (!enabled || intervals.any { it.enabled })
    fun args() = JsonObject().apply {
        require(valid()) { "Choose different From and To times and at least one day for each period." }
        addProperty("rev", revision); addProperty("enabled", enabled)
        add("blocked_intervals", JsonArray().apply { intervals.forEach { add(it.json()) } })
    }
    companion object {
        fun parse(o: JsonObject): ChargeScheduleSettings {
            fun integer(o: JsonObject, name: String): Long {
                val v = requireNotNull(o[name]); require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber && Regex("0|[1-9][0-9]*").matches(v.asString))
                return v.asLong
            }
            fun boolean(o: JsonObject, name: String): Boolean {
                val v = requireNotNull(o[name]); require(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean); return v.asBoolean
            }
            val slots = o.getAsJsonArray("blocked_intervals").also { require(it.size() <= 8) }.map { value ->
                val p = value.asJsonObject
                require(p["from"].asJsonPrimitive.isString && p["to"].asJsonPrimitive.isString)
                ChargePause(boolean(p,"enabled"), integer(p,"days").also { require(it in 1..127) }.toInt(), p["from"].asString, p["to"].asString)
            }
            return ChargeScheduleSettings(integer(o,"rev"), boolean(o,"enabled"), slots).also { require(it.valid()) }
        }
    }
}

/** A floating vehicle clock. No device timezone or DST conversion is applied. */
data class VehicleClock(val local: String?, val second: Int, val sourceAgeMs: Long?, val receivedMs: Long) {
    init { require(second in 0..59); require(sourceAgeMs==null || sourceAgeMs>=0); if (local != null) LocalDateTime.parse(local) }
    fun display(nowMs: Long, withDate: Boolean = false): String = local?.let {
        LocalDateTime.parse(it).plusSeconds(second.toLong() + (nowMs-receivedMs).coerceAtLeast(0)/1000)
            .format(DateTimeFormatter.ofPattern(if (withDate) "dd.MM.yyyy · HH:mm" else "HH:mm"))
    } ?: "—"
}

data class ChargeScheduleStatus(val settings: ChargeScheduleSettings, val state: String, val active: Boolean,
    val nextLocal: String?, val lastResult: Int, val clock: VehicleClock) {
    val description get() = when(state) {
        "disabled" -> "Off-peak charging is off."
        "waiting_clock" -> "Waiting for the vehicle clock."
        "idle" -> "Waiting for the next high-tariff period."
        "blocked" -> if (active) "Charging paused${nextLocal?.let { " until ${it.takeLast(5)}" } ?: ""}." else "Pause not confirmed."
        "skipped" -> "No pause was requested for this period."
        "pausing" -> "Pausing charging…"
        "resuming" -> "Allowing charging…"
        "retry" -> "Vehicle request unconfirmed."
        "busy" -> "Waiting for the current vehicle request."
        "storage_error" -> "The gateway could not save its pause state."
        else -> "Off-peak charging unavailable."
    }
}

fun chargeScheduleError(code: String?) = when(code) {
    "settings_conflict" -> "Settings changed elsewhere. Reload before applying your changes."
    "storage_error" -> "The gateway could not save the settings."
    "invalid_argument" -> "Choose different From and To times and at least one day for each period."
    else -> "Could not save off-peak charging settings${code?.let { ": $it" } ?: "."}"
}
