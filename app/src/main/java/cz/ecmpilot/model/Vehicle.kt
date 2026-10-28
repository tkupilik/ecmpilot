package cz.ecmpilot.model

enum class Source { DEMO, UDP }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DemoScene(val title: String) {
    CHARGING("Connected · AC charging"), RUNNING("Climate running"), ASLEEP("Vehicle asleep"),
    OFFLINE("Gateway offline"), LIMITED("Some readings unavailable"), READ_ONLY("Monitoring only"),
    REJECT("Vehicle rejects commands"), UNCONFIRMED("Confirmation unavailable")
}

data class Settings(
    val source: Source = Source.DEMO,
    val host: String = "ecmpilot-gw.lan",
    val port: Int = 42840,
    val vehicleName: String = "Opel Corsa-e",
    val gatewayId: String = "",
    val allowLabControls: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val scene: DemoScene = DemoScene.CHARGING,
)

/** Ages always describe the vehicle observation; transport receipts cannot refresh old data. */
data class Stamp(val ageMs: Long?, val receivedMs: Long, val roundTripMs: Long = 0) {
    fun age(nowMs: Long): Long? = ageMs?.let {
        val elapsed = (nowMs - receivedMs).coerceAtLeast(0)
        if (it > Long.MAX_VALUE - roundTripMs || it + roundTripMs > Long.MAX_VALUE - elapsed) Long.MAX_VALUE else it + roundTripMs + elapsed
    }
    fun fresh(nowMs: Long, limitMs: Long = 5000) = age(nowMs)?.let { it <= limitMs } == true
}
data class Reading(val value: Double?, val stamp: Stamp) {
    fun fresh(nowMs: Long, limitMs: Long = 5000) = value != null && stamp.fresh(nowMs, limitMs)
}
data class Climate(val state: String = "unknown", val rev: Long? = null, val stamp: Stamp)
data class Charge(
    val state: String = "unknown", val mode: String = "unknown", val rev: Long? = null,
    val connected: Boolean? = null, val powerKw: Double? = null, val remainingMin: Double? = null,
    val startLocal: String? = null, val stamp: Stamp,
)
data class VehicleState(
    val readings: Map<String, Reading> = emptyMap(),
    val awake: Boolean? = null, val parked: Boolean? = null, val vehicleStamp: Stamp,
    val climate: Climate, val charge: Charge,
) {
    fun reading(name: String) = readings[name]
}
data class Operation(
    val verified: Boolean, val confirmation: Boolean, val enabledValues: Set<Boolean> = emptySet(),
    val modes: Set<String> = emptySet(), val persistentSchedule: Boolean = false,
)
data class GatewayInfo(
    val firmware: String, val security: String, val timezone: String?,
    val fields: Set<String>, val operations: Map<String, Operation>,
    val controlBackend: String? = null,
) {
    val testMode get() = controlBackend == "varstore-test"
    fun climate(enabled: Boolean) = operations["climate.set"]?.let { (it.verified || testMode) && it.confirmation && enabled in it.enabledValues } == true
    fun charge(mode: String) = operations["charge.mode.set"]?.let {
        (it.verified || testMode) && it.confirmation && mode in it.modes && (mode != "deferred" || it.persistentSchedule || testMode)
    } == true
}
data class CommandResult(
    val id: String, val revision: Long, val phase: String, val error: String? = null,
    val reason: String? = null,
) {
    val terminal get() = phase in setOf("succeeded", "rejected", "failed", "unknown")
}
data class CommandRef(val id: String, val gatewayId: String, val boot: String, val label: String, val host: String, val port: Int)
data class CommandUi(val ref: CommandRef?, val phase: String, val message: String, val resultRevision: Long = -1) {
    val pending get() = phase in setOf("preparing", "sending", "accepted", "executing")
}
data class PilotState(
    val settings: Settings,
    val nowMs: Long = 0, val permissionGranted: Boolean = true,
    val connected: Boolean = false, val connecting: Boolean = true,
    val gatewayId: String? = null, val boot: String? = null,
    val info: GatewayInfo? = null, val vehicle: VehicleState? = null,
    val command: CommandUi? = null, val error: String? = null,
    val diagnostics: List<String> = emptyList(),
) {
    val readOnly get() = settings.source == Source.UDP && !settings.allowLabControls
    fun controlReady(domain: String): Boolean {
        val vehicle = vehicle ?: return false
        if (!connected || settings.source == Source.UDP && !permissionGranted || readOnly || command?.pending == true) return false
        if (settings.source == Source.UDP && info?.security != "lab-plain") return false
        if (vehicle.awake != true || !vehicle.vehicleStamp.fresh(nowMs) || vehicle.parked == false) return false
        return when (domain) {
            "climate" -> vehicle.climate.rev != null && vehicle.climate.stamp.fresh(nowMs)
            "charge" -> vehicle.charge.rev != null && vehicle.charge.stamp.fresh(nowMs)
            else -> false
        }
    }
}
sealed interface DesiredCommand {
    val domain: String
    val label: String
    data class ClimateSet(val enabled: Boolean) : DesiredCommand {
        override val domain = "climate"
        override val label get() = if (enabled) "Start climate" else "Stop climate"
    }
    data class ChargeMode(val mode: String, val startLocal: String? = null) : DesiredCommand {
        override val domain = "charge"
        override val label get() = if (mode == "deferred") "Deferred charging" else "Immediate charging"
    }
}

fun messageFor(result: CommandResult): String = when (result.phase) {
    "accepted", "executing" -> "Waiting for vehicle confirmation…"
    "succeeded" -> "Confirmed by vehicle status."
    "unknown" -> "Result unconfirmed. Refresh to check the original request."
    else -> when (result.reason ?: result.error) {
        "cable_disconnected" -> "Charging cable not connected."
        "vehicle_moving", "not_parked" -> "Vehicle is not parked."
        "low_battery" -> "Battery charge is too low for this action."
        "unsupported_operation", "unsupported_argument" -> "This function is unavailable on the gateway."
        "conflict" -> "Another request changed the setting. Refresh before trying again."
        "expired" -> "The request expired before it could be accepted."
        "busy" -> "The gateway is busy. Wait for the current action."
        "boot_changed" -> "Gateway restarted. The previous result is unconfirmed."
        else -> "Vehicle did not accept the request (${result.reason ?: result.error ?: result.phase})."
    }
}
