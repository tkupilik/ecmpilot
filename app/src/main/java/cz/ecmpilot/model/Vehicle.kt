package cz.ecmpilot.model

enum class Source { DEMO, UDP }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DemoScene(val title: String) {
    CHARGING("Connected · AC charging"), RUNNING("Climate running"), ASLEEP("Vehicle asleep"),
    OFFLINE("Gateway offline"), LIMITED("Some readings unavailable"), READ_ONLY("Monitoring only"),
    REJECT("Vehicle rejects commands"), UNCONFIRMED("Confirmation unavailable"), DOORS("Doors open")
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

data class BsiStatus(val powerManagement: Int?, val stamp: Stamp) {
    // Last reported CAN phase; lack of traffic does not imply a sleep phase.
    val label get() = when (powerManagement) {
        0 -> "standby"; 1 -> "normal"; 2 -> "entering standby"; 3 -> "wake-up"; 4 -> "com off"
        else -> "unknown"
    }
}
data class Charge(
    val state: String = "unknown", val mode: String = "unknown", val rev: Long? = null,
    val connected: Boolean? = null, val powerKw: Double? = null, val remainingMin: Double? = null,
    val startLocal: String? = null, val stamp: Stamp,
    val modeStamp: Stamp = stamp, val scheduleAvailable: Boolean? = null,
)
data class VehicleState(
    val readings: Map<String, Reading> = emptyMap(),
    val awake: Boolean? = null, val parked: Boolean? = null, val vehicleStamp: Stamp,
    val climate: Climate, val charge: Charge,
    val bsi: BsiStatus? = null,
) {
    fun reading(name: String) = readings[name]
}

data class BodyStatus(val openMask: Int, val knownMask: Int, val stamp: Stamp,
                      val lockingState: Int?, val lockStamp: Stamp) {
    fun open(index: Int): Boolean? = if (index in 0..4 && knownMask and (1 shl index) != 0)
        openMask and (1 shl index) != 0 else null
    val lockLabel get() = when (lockingState) {
        1 -> "Locked"; 2 -> "Superlocked"; 3 -> "Unlocked"; 4 -> "Driver door unlocked"
        5 -> "Cab unlocked"; 6 -> "Load area unlocked"; else -> "Lock state unavailable"
    }
    val locked get() = lockingState in setOf(1, 2)
}
data class VehicleDetails(val body: BodyStatus?, val trips: Map<String, Reading>, val history: List<Reading>)
data class ClimateProgram(val raw: Int?, val stamp: Stamp) {
    val enabled get() = raw?.let { it and 0x800000 != 0 } == true
    val days get() = raw?.let { (it shr 16) and 127 } ?: 0 // Monday bit6 ... Sunday bit0.
    val minute get() = raw?.let { it and 2047 }?.takeIf { it < 1440 }
    val time get() = minute?.let { String.format(java.util.Locale.UK, "%02d:%02d", it / 60, it % 60) }
    val configured get() = minute != null && days != 0
    fun withEnabled(value: Boolean) = copy(raw = raw?.let { if (value && configured) it or 0x800000 else it and 0x7FFFFF })
    fun withDays(value: Int) = copy(raw = raw?.let { (it and 0x80FFFF) or ((value and 127) shl 16) })
    fun withMinute(value: Int) = copy(raw = raw?.let { (it and 0xFFF800) or value.coerceIn(0, 1439) })
}
data class ClimatePrograms(val slots: List<ClimateProgram>, val rev: Long?) {
    val complete get() = slots.size == 4 && slots.all { it.raw != null }
    val enabled get() = slots.any { it.enabled }
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
    fun climate(enabled: Boolean) = operations["climate.set"]?.let { (it.verified || testMode || experimentalCan) && it.confirmation && enabled in it.enabledValues } == true
    val experimentalCan get() = controlBackend == "info-can-experimental"
    fun refresh() = operations["vehicle.refresh"]?.let { it.confirmation && (it.verified || testMode || experimentalCan) } == true
    fun climatePrograms() = operations["climate.programs.set"]?.let {
        (it.verified || experimentalCan) && it.confirmation && it.persistentSchedule
    } == true
    fun charge(mode: String) = operations["charge.mode.set"]?.let {
        (it.verified || testMode || experimentalCan) && it.confirmation && mode in it.modes && (mode != "deferred" || it.persistentSchedule || testMode)
    } == true
}
data class CommandResult(
    val id: String, val revision: Long, val phase: String, val error: String? = null,
    val reason: String? = null,
    val correlation: Correlation? = null,
) {
    val terminal get() = phase in setOf("succeeded", "rejected", "failed", "unknown")
}
data class CommandRef(val id: String, val gatewayId: String, val boot: String, val label: String, val host: String, val port: Int, val correlation: Correlation? = null)
data class CommandUi(val ref: CommandRef?, val phase: String, val message: String, val resultRevision: Long = -1) {
    val pending get() = phase in setOf("preparing", "sending", "accepted", "executing")
    val hasError get() = phase in setOf("rejected", "failed", "unknown")
}
data class PilotState(
    val settings: Settings,
    val nowMs: Long = 0, val permissionGranted: Boolean = true,
    val connected: Boolean = false, val connecting: Boolean = true,
    val gatewayId: String? = null, val boot: String? = null,
    val info: GatewayInfo? = null, val vehicle: VehicleState? = null,
    val command: CommandUi? = null, val error: String? = null,
    val diagnostics: List<String> = emptyList(),
    val details: VehicleDetails? = null,
    val programs: ClimatePrograms? = null,
    val autoRefresh: AutoRefreshStatus? = null,
    val autoRefreshSaving: Boolean = false,
    val autoRefreshError: String? = null,
    val autoRefreshSavedRevision: Long? = null,
    val lowPower: LowPowerStatus? = null,
    val lowPowerSaving: Boolean = false,
    val lowPowerError: String? = null,
    val lowPowerSavedRevision: Long? = null,
) {
    val readOnly get() = settings.source == Source.UDP && !settings.allowLabControls
    fun controlReady(domain: String): Boolean {
        if (!connected || settings.source == Source.UDP && !permissionGranted || command?.pending == true || autoRefreshSaving || lowPowerSaving) return false
        if (settings.source == Source.UDP && info?.security != "lab-plain") return false
        if (domain == "refresh") return info?.refresh() == true
        if (readOnly) return false
        val vehicle = vehicle ?: return false
        if (domain == "climate" && info?.experimentalCan == true)
            return vehicle.climate.rev != null
        if (domain == "charge" && info?.experimentalCan == true)
            return vehicle.charge.rev != null && vehicle.charge.mode != "unknown" && vehicle.charge.scheduleAvailable == true
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
    data object VehicleRefresh : DesiredCommand {
        override val domain = "refresh"
        override val label = "Refresh vehicle"
    }
    data class ClimateSet(val enabled: Boolean) : DesiredCommand {
        override val domain = "climate"
        override val label get() = if (enabled) "Start preconditioning" else "Stop preconditioning"
    }
    data class ClimateProgramsSet(val base: List<Int>, val slots: List<Int>) : DesiredCommand {
        override val domain = "climate"
        override val label = "Preconditioning schedules"
    }
    data class ChargeMode(val mode: String, val startLocal: String? = null) : DesiredCommand {
        override val domain = "charge"
        override val label get() = if (mode == "deferred") "Delayed charging" else "Immediate charging"
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
        "vehicle_control_failed" -> "Vehicle request could not be sent. Check gateway CAN diagnostics."
        "preconditioning_unavailable" -> "Vehicle reports preconditioning unavailable."
        "preconditioning_rejected" -> "Vehicle did not accept preconditioning. Check the gateway feedback signal."
        "preconditioning_programs_timeout" -> "Vehicle did not provide its stored preconditioning programs."
        "preconditioning_programs_conflict" -> "Vehicle schedules changed elsewhere. Reopen the editor before saving."
        "expired" -> "The request expired before it could be accepted."
        "busy" -> "The gateway is busy. Wait for the current action."
        "boot_changed" -> "Gateway restarted. The previous result is unconfirmed."
        else -> "Vehicle did not accept the request (${result.reason ?: result.error ?: result.phase})."
    }
}
