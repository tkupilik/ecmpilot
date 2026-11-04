package cz.ecmpilot.model

import com.google.gson.JsonObject

/** Gateway-owned configuration. The phone never runs the refresh timer. */
data class AutoRefreshSettings(
    val revision: Long = 1,
    val enabled: Boolean = false,
    val standbyMin: Int = 60,
    val chargingMin: Int = 5,
    val minAuxSocPct: Int = 50,
    val chargeLimitEnabled: Boolean = false,
    val chargeLimitSocPct: Int = 80,
) {
    fun valid() = revision in 1..Int.MAX_VALUE.toLong() && standbyMin in 1..1440 && chargingMin in 1..1440 && minAuxSocPct in 0..100 &&
        chargeLimitSocPct in 1..100 && (!chargeLimitEnabled || enabled)
    fun args() = JsonObject().apply {
        require(valid()) { "Periods must be 1–1440 minutes and minimum 12 V SOC 0–100%." }
        addProperty("rev", revision); addProperty("enabled", enabled)
        addProperty("standby_min", standbyMin); addProperty("charging_min", chargingMin)
        addProperty("min_aux_soc_pct", minAuxSocPct)
        addProperty("charge_limit_enabled", chargeLimitEnabled); addProperty("charge_limit_soc_pct", chargeLimitSocPct)
    }
}

data class AutoRefreshStatus(
    val settings: AutoRefreshSettings,
    val capable: Boolean,
    val configValid: Boolean,
    val state: String,
    val remainingMs: Long,
    val requestCount: Long,
    val chargeLimitState: String = "disabled",
    val chargeLimitLastResult: Long = 0,
) {
    val chargeLimitDescription get() = when (chargeLimitState) {
        "disabled" -> "Charge limit is off."
        "monitoring" -> "Monitoring battery level."
        "waiting_data" -> "Waiting for updated vehicle data."
        "busy" -> "Waiting for the current vehicle request."
        "requesting" -> "Requesting delayed charging…"
        "limit_reached" -> "Charge limit reached."
        "retry" -> "Will retry after a later vehicle update."
        "low_aux_battery" -> "Paused: 12 V battery is below the minimum level."
        else -> "Charge limit unavailable."
    }
    val description get() = when (state) {
        "disabled" -> "Automatic refresh is off."
        "low_aux_battery" -> "Updates paused: 12 V battery is below the minimum level."
        "unavailable" -> "Paused: vehicle refresh is unavailable on this gateway."
        "busy" -> "Waiting for the current vehicle request."
        "invalid_config" -> "Paused: invalid gateway configuration."
        "refreshing" -> "Refreshing vehicle data…"
        "counting" -> "Next refresh in ${(remainingMs + 59_999) / 60_000} min."
        else -> "Automatic refresh status unavailable."
    }
}

fun autoRefreshError(code: String?) = when (code) {
    "settings_conflict" -> "Settings changed elsewhere. Reload before applying your changes."
    "storage_error" -> "The gateway could not save the settings."
    "invalid_argument" -> "Enter periods of 1–1440 minutes and minimum 12 V SOC of 0–100%."
    else -> "Could not save automatic refresh settings${code?.let { ": $it" } ?: "."}"
}
