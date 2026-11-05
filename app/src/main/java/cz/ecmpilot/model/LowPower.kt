package cz.ecmpilot.model

import com.google.gson.JsonObject

data class LowPowerSettings(val revision: Long = 1, val enabled: Boolean = true, val idleHours: Int = 48) {
    fun valid() = revision in 1..Int.MAX_VALUE.toLong() && idleHours in 1..720
    fun args() = JsonObject().apply {
        require(valid()) { "Choose 1–720 whole hours." }
        addProperty("rev", revision); addProperty("enabled", enabled); addProperty("idle_hours", idleHours)
    }
}

data class LowPowerStatus(val settings: LowPowerSettings, val capable: Boolean, val configValid: Boolean,
    val state: String, val remainingS: Long?, val receivedMs: Long, val lastError: Long = 0) {
    fun remaining(nowMs: Long): Long? = remainingS?.let {
        (it - if (state == "counting") ((nowMs-receivedMs).coerceAtLeast(0)/1000) else 0).coerceAtLeast(0)
    }
    fun description(nowMs: Long): String = when (state) {
        "disabled" -> "Automatic sleep is off."
        "waiting_data" -> "Waiting for the first valid BSI message."
        "counting" -> remaining(nowMs)?.let { "Sleeping in ${it/86400}d ${it%86400/3600}h ${it%3600/60}m ${it%60}s" } ?: "Waiting for vehicle data."
        "busy" -> "Waiting for the current operation before sleeping."
        "entering" -> "Preparing deep sleep…"
        "retry" -> "Sleep preparation failed; the gateway will retry."
        "invalid_config" -> "Invalid gateway sleep settings."
        else -> "Automatic CAN sleep is unavailable."
    }
}

fun lowPowerError(code: String?) = when (code) {
    "settings_conflict" -> "Settings changed elsewhere. Reload before applying your changes."
    "storage_error" -> "The gateway could not save the settings."
    "invalid_argument" -> "Choose 1–720 whole hours."
    else -> "Could not save low-power settings${code?.let { ": $it" } ?: "."}"
}
