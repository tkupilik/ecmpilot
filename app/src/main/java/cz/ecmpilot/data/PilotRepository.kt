package cz.ecmpilot.data

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import cz.ecmpilot.BuildConfig
import cz.ecmpilot.model.*
import cz.ecmpilot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.SocketTimeoutException

class PilotRepository(private val context: Context) {
    private val clock = { SystemClock.elapsedRealtime() }
    private val store = SettingsStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initial = store.load()
    private val mutable = MutableStateFlow(PilotState(initial, nowMs = clock(), command = if (initial.source == Source.UDP) store.loadCommand() else null))
    val state = mutable.asStateFlow()
    private data class AutoSave(val settings: AutoRefreshSettings, val gw: String, val boot: String,
        val host: String, val port: Int, val source: Source)
    private val autoSaves = Channel<AutoSave>(1)
    private data class LowSave(val settings: LowPowerSettings, val gw: String, val boot: String,
        val host: String, val port: Int, val source: Source)
    private val lowSaves=Channel<LowSave>(1)
    private data class VehicleRequest(val desired: DesiredCommand, val correlation: Correlation)
    private val vehicleRequests = Channel<VehicleRequest>(1)
    @Volatile private var visible = false
    @Volatile private var refreshRequested = false
    private var worker: Job? = null
    private var ticker: Job? = null
    private var pause: Job? = null
    @Volatile private var connection: GatewayConnection? = null
    @Volatile private var lastReplyMs = 0L

    fun setVisible(value: Boolean) {
        visible = value; pause?.cancel()
        if (value) start() else {
            while (vehicleRequests.tryReceive().isSuccess) Unit
            if(lowSaves.tryReceive().isSuccess)mutable.update {it.copy(lowPowerSaving=false,lowPowerError="Save cancelled before sending.")}
            if (autoSaves.tryReceive().isSuccess) mutable.update { it.copy(autoRefreshSaving = false, autoRefreshError = "Save cancelled before sending.") }
            if (state.value.command?.phase == "preparing") mutable.update { it.copy(command = null) }
            pause = scope.launch { delay(5000); stop() }
        }
    }
    fun setPermission(granted: Boolean) {
        if (mutable.value.permissionGranted == granted) return
        mutable.update { it.copy(permissionGranted = granted) }; restart()
    }
    fun saveSettings(s: Settings) {
        require(s.host.isNotBlank() && s.host.length <= 253 && !s.host.contains(Regex("[\\s/]"))) { "Enter a hostname or IP address, without a URL scheme" }
        require(s.port in 1..65535) { "Port must be between 1 and 65535" }
        require(s.vehicleName.isNotBlank() && s.vehicleName.length <= 40) { "Vehicle name must contain 1–40 characters" }
        require(state.value.command?.pending != true) { "Wait for the pending request before changing connection settings" }
        require(!state.value.autoRefreshSaving) { "Wait for automatic refresh settings to finish saving" }
        require(!state.value.lowPowerSaving) { "Wait for low-power settings to finish saving" }
        stop()
        store.save(s)
        mutable.update { it.copy(settings = s, connected = false, connecting = true, gatewayId = null, boot = null, info = null, vehicle = null, details = null, programs = null, autoRefresh = null, lowPower = null, lowPowerSavedRevision = null, lowPowerError = null, autoRefreshSavedRevision = null, autoRefreshError = null, error = null,
            command = if (s.source == Source.UDP) store.loadCommand() else null) }
        if (visible) start()
    }
    // Reconnect/passive reads must never issue vehicle.refresh (including settings save).
    fun reconnect() { refreshRequested = true; mutable.update { it.copy(autoRefreshError = null, lowPowerError = null) } }
    fun refresh() { submit(DesiredCommand.VehicleRefresh) }
    fun saveAutoRefresh(settings: AutoRefreshSettings) {
        val s = state.value
        if (!visible || !s.connected || s.autoRefresh == null || "auto_refresh" !in (s.info?.fields ?: emptySet()) ||
            s.autoRefreshSaving || s.lowPowerSaving || s.command?.pending == true || !settings.valid() ||
            s.settings.source == Source.UDP && (!s.permissionGranted || s.info?.security != "lab-plain")) return
        val request = AutoSave(settings, s.gatewayId ?: return, s.boot ?: return, s.settings.host, s.settings.port, s.settings.source)
        mutable.update { it.copy(autoRefreshSaving = true, autoRefreshError = null, autoRefreshSavedRevision = null) }
        if (!autoSaves.trySend(request).isSuccess) mutable.update { it.copy(autoRefreshSaving = false, autoRefreshError = "Another settings save is pending.") }
    }
    fun saveLowPower(settings: LowPowerSettings) {
        val s=state.value
        if(!visible || !s.connected || s.lowPower==null || "low_power" !in (s.info?.fields ?: emptySet()) ||
            s.lowPowerSaving || s.autoRefreshSaving || s.command?.pending==true || !settings.valid() ||
            s.settings.source==Source.UDP && (!s.permissionGranted || s.info?.security!="lab-plain")) return
        val request=LowSave(settings,s.gatewayId ?: return,s.boot ?: return,s.settings.host,s.settings.port,s.settings.source)
        mutable.update { it.copy(lowPowerSaving=true,lowPowerError=null,lowPowerSavedRevision=null) }
        if(!lowSaves.trySend(request).isSuccess) mutable.update {it.copy(lowPowerSaving=false,lowPowerError="Another settings save is pending.")}
    }
    fun submit(command: DesiredCommand) {
        if (!visible || !state.value.controlReady(command.domain)) return
        mutable.update { it.copy(command = CommandUi(null, "preparing", "Reading gateway state before sending…")) }
        if (!vehicleRequests.trySend(VehicleRequest(command, Protocol.nextCorrelation())).isSuccess) mutable.update { it.copy(command = CommandUi(null, "failed", "Another request is already pending.")) }
    }
    fun dismissResult() { if (state.value.command?.pending != true) mutable.update { it.copy(command = null) } }
    fun diagnosticText(): String = "eCMPilot ${BuildConfig.VERSION_NAME}\nSource: ${state.value.settings.source}\nGateway: ${state.value.gatewayId ?: "unknown"}\n" + state.value.diagnostics.joinToString("\n")

    private fun start() {
        if (worker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val now = clock()
                mutable.update { it.copy(nowMs = now, connected = it.connected && now - lastReplyMs <= UdpPolicy.OFFLINE_MS) }
                delay(1000)
            }
        }
        worker = scope.launch { runLoop() }
    }
    private fun stop() {
        worker?.cancel(); ticker?.cancel(); connection?.close(); connection = null; worker = null; ticker = null
        while (autoSaves.tryReceive().isSuccess) Unit
        while (lowSaves.tryReceive().isSuccess) Unit
        while (vehicleRequests.tryReceive().isSuccess) Unit
        mutable.update { it.copy(connected = false, connecting = false, autoRefreshSaving = false, lowPowerSaving = false,
            lowPowerError = if (it.lowPowerSaving) "Save interrupted. Reload gateway settings to check the result." else it.lowPowerError,
            autoRefreshError = if (it.autoRefreshSaving) "Save interrupted. Reload gateway settings to check the result." else it.autoRefreshError) }
    }
    private fun restart() { stop(); if (visible) start() }
    private fun log(text: String) { mutable.update { it.copy(diagnostics = (it.diagnostics + "${clock()} ms  $text").takeLast(80)) } }
    private fun publishCommand(command: CommandUi) {
        if (state.value.settings.source == Source.UDP) store.saveCommand(command)
        mutable.update { it.copy(command = command) }
    }

    private suspend fun runLoop() {
        var failures = 0
        val tracker = StatusTracker()
        var watchSince = 0L
        while (currentCoroutineContext().isActive) {
            val settings = state.value.settings
            if (settings.source == Source.UDP && !state.value.permissionGranted) {
                mutable.update { it.copy(connecting = false, connected = false, error = "Allow local network access to connect to the gateway.") }
                delay(500); continue
            }
            var local: GatewayConnection? = null
            try {
                mutable.update { it.copy(connecting = true) }
                val gateway = if (settings.source == Source.DEMO) DemoConnection(settings.scene, clock) else openLocalConnection(context, settings, clock, ::log)
                local = gateway; connection = gateway
                var identity: String? = settings.gatewayId.takeIf { it.isNotBlank() && settings.source == Source.UDP }
                var boot: String? = tracker.boot
                var lastInfoMs = 0L
                var lastDetailsMs = Long.MIN_VALUE / 2
                var lastProgramsMs = Long.MIN_VALUE / 2
                var lastAutoRefreshMs = Long.MIN_VALUE / 2
                var lastLowPowerMs = Long.MIN_VALUE / 2
                suspend fun exchange(request: Request, retry: Boolean = false): Status {
                    val response = gateway.exchange(request, retry)
                    currentCoroutineContext().ensureActive()
                    if (identity != null && response.gw != identity) throw ProtocolException("Gateway identity changed; use Forget gateway in settings")
                    val previousBoot = boot
                    if (previousBoot != null && previousBoot != response.boot) {
                        val old = state.value.command
                        if (old?.pending == true) publishCommand(old.copy(phase = "unknown", message = "Gateway restarted. Previous result unconfirmed."))
                        mutable.update { it.copy(info = null, vehicle = null, details = null, programs = null, autoRefresh = null, lowPower = null, lowPowerSavedRevision = null, lowPowerError = null, autoRefreshSavedRevision = null) }
                    }
                    identity = response.gw; boot = response.boot
                    tracker.accept(response)
                    lastReplyMs = clock()
                    failures = 0
                    mutable.update { it.copy(connected = true, connecting = false, gatewayId = response.gw, boot = response.boot,
                        info = response.info ?: if (previousBoot != null && previousBoot != response.boot) null else it.info,
                        vehicle = tracker.snapshot, details = tracker.details, programs = tracker.programs, autoRefresh = tracker.autoRefresh, lowPower = tracker.lowPower, error = null, nowMs = clock()) }
                    val current = state.value.command
                    response.command?.takeIf { current?.ref != null && current.ref.boot == response.boot && current.ref.gatewayId == response.gw &&
                        (if (current.ref.correlation != null) it.correlation == current.ref.correlation else it.id == current.ref.id && it.correlation == null) &&
                        it.revision > current.resultRevision }?.let { result ->
                        val message = if (state.value.info?.testMode == true) when (result.phase) {
                            "accepted", "executing" -> "Varstore test: waiting for matching observation."
                            "succeeded" -> "Confirmed by varstore test data. No vehicle command was sent."
                            else -> "Varstore test: ${messageFor(result)}"
                        } else messageFor(result)
                        publishCommand(current!!.copy(ref = current.ref!!.copy(id = result.id), phase = result.phase, message = message, resultRevision = result.revision))
                        log("corr=${result.correlation?.label ?: "none"} message=${result.id.take(8)}: ${result.phase}${result.error?.let { ": $it" } ?: ""}")
                    }
                    return response
                }
                suspend fun read(request: Request): Status? = try { exchange(request) }
                catch (timeout: SocketTimeoutException) {
                    log("corr=${request.correlation?.label} ${request.json.get("op").asString}: no reply; keeping cached data")
                    if (clock() - lastReplyMs >= UdpPolicy.OFFLINE_MS) throw timeout
                    null
                }
                suspend fun info(required: Boolean = true, correlation: Correlation = Protocol.nextCorrelation()): Boolean {
                    val request = Protocol.request(store.clientId, "info.get", identity, correlation = correlation)
                    val response = if (required) exchange(request) else read(request) ?: return false
                    if (response.error != null) throw ProtocolException("Gateway info: ${response.error}")
                    requireNotNull(response.info) { "Gateway did not return capability information" }
                    if (settings.source == Source.UDP && settings.gatewayId.isBlank()) {
                        val learned = state.value.settings.copy(gatewayId = response.gw)
                        store.save(learned); mutable.update { it.copy(settings = learned) }
                    }
                    lastInfoMs = clock()
                    return true
                }
                info()
                log("Connected to ${identity}; ${state.value.info?.firmware}")
                var recover = state.value.command?.let { it.pending || it.phase == "unknown" || it.phase == "sending" } == true
                while (currentCoroutineContext().isActive) {
                    val refreshCommand = refreshRequested
                    refreshRequested = false
                    if (refreshCommand || state.value.info == null || clock() - lastInfoMs > 30_000) info(required = false)
                    autoSaves.tryReceive().getOrNull()?.let { save ->
                        try {
                            require(visible && save.gw == identity && save.boot == boot && save.host == settings.host &&
                                save.port == settings.port && save.source == settings.source) { "Gateway session changed. Reload settings before saving." }
                            val response = exchange(Protocol.request(store.clientId, "auto_refresh.set", save.gw, save.boot, save.settings.args()), retry = true)
                            require(response.boot == save.boot) { "Gateway restarted while saving. Reload settings to check the result." }
                            if (response.error != null) throw IOException(autoRefreshError(response.error))
                            val saved = requireNotNull(response.autoRefresh) { "Gateway did not return automatic refresh settings." }
                            mutable.update { it.copy(autoRefreshSaving = false, autoRefreshError = null, autoRefreshSavedRevision = saved.settings.revision) }
                            lastAutoRefreshMs = clock()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) {
                            mutable.update { it.copy(autoRefreshSaving = false, autoRefreshError = error.message ?: "Save unconfirmed. Reload settings to check the result.") }
                        }
                    }
                    lowSaves.tryReceive().getOrNull()?.let { save ->
                        try {
                            require(visible && save.gw==identity && save.boot==boot && save.host==settings.host &&
                                save.port==settings.port && save.source==settings.source) { "Gateway session changed. Reload settings before saving." }
                            val response=exchange(Protocol.request(store.clientId,"low_power.set",save.gw,save.boot,save.settings.args()),retry=true)
                            require(response.boot==save.boot) { "Gateway restarted while saving. Reload settings to check the result." }
                            if(response.error!=null)throw IOException(lowPowerError(response.error))
                            val saved=requireNotNull(response.lowPower) { "Gateway did not return low-power settings." }
                            mutable.update {it.copy(lowPowerSaving=false,lowPowerError=null,lowPowerSavedRevision=saved.settings.revision)}
                            lastLowPowerMs=clock()
                        } catch(cancel:CancellationException){throw cancel}
                        catch(error:Exception){mutable.update {it.copy(lowPowerSaving=false,lowPowerError=error.message ?: "Save unconfirmed. Reload settings to check the result.")}}
                    }
                    val vehicleRequest = vehicleRequests.tryReceive().getOrNull()
                    if (vehicleRequest != null) {
                        val desired = vehicleRequest.desired
                        val correlation = vehicleRequest.correlation
                        try {
                            if (!visible) { mutable.update { it.copy(command = null) }; continue }
                            info(correlation = correlation)
                            val fresh = exchange(Protocol.request(store.clientId, "state.get", identity, boot, correlation = correlation))
                            require(fresh.state != null && fresh.roundTripMs <= UdpPolicy.READ_TIMEOUT_MS) { "A current gateway reply is required" }
                            val current = state.value.copy(command = null, nowMs = clock())
                            require(current.controlReady(desired.domain)) { "Vehicle state is unavailable, stale, asleep or read-only" }
                            val caps = current.info ?: throw IOException("Capabilities unavailable")
                            val args = JsonObject()
                            val op = when (desired) {
                                DesiredCommand.VehicleRefresh -> { require(caps.refresh()) { "Vehicle refresh is unavailable on this gateway" }; "vehicle.refresh" }
                                is DesiredCommand.ClimateSet -> { require(caps.climate(desired.enabled)) { "Preconditioning is unavailable on this gateway" }; args.addProperty("enabled", desired.enabled); "climate.set" }
                                is DesiredCommand.ClimateProgramsSet -> {
                                    require(caps.climatePrograms()) { "Preconditioning schedules are unavailable on this gateway" }
                                    require(desired.base.size == 4 && desired.slots.size == 4 &&
                                        (desired.base + desired.slots).all { it in 0..0xFFFFFF }) { "Invalid preconditioning slots" }
                                    args.add("base", JsonArray().apply { desired.base.forEach(::add) })
                                    args.add("slots", JsonArray().apply { desired.slots.forEach(::add) })
                                    "climate.programs.set"
                                }
                                is DesiredCommand.ChargeMode -> {
                                    require(caps.charge(desired.mode)) { "Charging mode is not verified by this gateway" }
                                    Protocol.chargeModeArgs(desired, caps.timezone).entrySet().forEach { (key, value) -> args.add(key, value) }
                                    "charge.mode.set"
                                }
                            }
                            if (!visible) { mutable.update { it.copy(command = null) }; continue }
                            val rev = when (desired.domain) { "climate" -> fresh.state.climate.rev; "refresh" -> 0L; else -> fresh.state.charge.rev }
                            val request = Protocol.request(store.clientId, op, identity, boot, args, fresh.nowMs + 5000, requireNotNull(rev), correlation)
                            val ref = CommandRef(request.id, fresh.gw, fresh.boot, desired.label, settings.host, settings.port, correlation)
                            publishCommand(CommandUi(ref, "sending", "Sending request…"))
                            log("corr=${correlation.label} message=${request.id.take(8)} send $op")
                            watchSince = clock()
                            val reply = exchange(request, retry = true)
                            if (reply.boot != ref.boot) throw IOException("Gateway restarted during the request")
                            if (reply.command?.correlation != correlation) throw IOException("Gateway did not return the matching operation receipt")
                            recover = false
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (timeout: SocketTimeoutException) {
                            val old = state.value.command
                            if (old?.ref == null) publishCommand(CommandUi(null, "failed", "Gateway did not answer before sending the vehicle request."))
                            else {
                                publishCommand(old.copy(message = "Reply delayed. Checking the original request…"))
                                log("corr=${old.ref.correlation?.label} acknowledgement lost; querying original operation")
                                recover = true
                            }
                        }
                        catch (error: IOException) {
                            val old = state.value.command
                            if (old?.ref != null && old.pending && error !is ProtocolException) {
                                publishCommand(old.copy(message = "Connection interrupted. Checking the original request…"))
                                recover = true
                                log("corr=${old.ref.correlation?.label} transport interrupted: ${error.message}")
                            } else publishCommand(CommandUi(old?.ref, if (old?.ref == null) "failed" else "unknown",
                                error.message ?: "Result unconfirmed", old?.resultRevision ?: -1))
                        }
                        catch (error: Exception) {
                            val old = state.value.command
                            publishCommand(CommandUi(old?.ref, if (old?.ref == null) "failed" else "unknown", error.message ?: "Result unconfirmed", old?.resultRevision ?: -1))
                        }
                    }
                    val command = state.value.command
                    val ref = command?.ref
                    if (ref != null && (command.pending || recover || refreshCommand)) {
                        recover = false
                        if (ref.gatewayId != identity || ref.boot != boot || ref.host != settings.host || ref.port != settings.port) {
                            publishCommand(command.copy(phase = "unknown", message = "Previous request belongs to a different gateway session. It was not replayed."))
                        } else {
                            if (watchSince == 0L) watchSince = clock()
                            val response = read(Protocol.request(store.clientId, "command.get", identity, boot,
                                JsonObject().apply { if (ref.correlation == null) addProperty("command_id", ref.id) }, correlation = ref.correlation))
                            if (response == null) recover = true
                            else if (response.error != null && (response.error != "result_unknown" || !command.pending || clock()-watchSince >= UdpPolicy.RESULT_TIMEOUT_MS))
                                publishCommand(command.copy(phase = "unknown", message = "Result unconfirmed (${response.error})."))
                        }
                    }
                    if (state.value.command?.pending == true && watchSince != 0L && clock()-watchSince >= UdpPolicy.RESULT_TIMEOUT_MS)
                        publishCommand(state.value.command!!.copy(phase = "unknown", message = "Result unconfirmed. Vehicle confirmation timed out."))
                    val response = read(Protocol.request(store.clientId, "state.get", identity, boot,
                        correlation = state.value.command?.takeIf { it.pending }?.ref?.correlation ?: Protocol.nextCorrelation()))
                    if (response == null) { delay(1000); continue }
                    if (response.error != null || response.state == null) throw ProtocolException("Gateway did not return vehicle observations")
                    var extrasOk = true
                    if ("vehicle_details" in (state.value.info?.fields ?: emptySet()) &&
                        state.value.command?.pending != true && clock() - lastDetailsMs >= 2000) {
                        val detail = read(Protocol.request(store.clientId, "vehicle.details.get", identity, boot))
                        if (detail == null) extrasOk = false
                        else if (detail.error != null || detail.details == null) log("Vehicle details unavailable; retaining cached details")
                        lastDetailsMs = clock()
                    }
                    if (extrasOk && "climate_programs" in (state.value.info?.fields ?: emptySet()) &&
                        state.value.command?.pending != true && clock() - lastProgramsMs >= 2000) {
                        val programs = read(Protocol.request(store.clientId, "climate.programs.get", identity, boot))
                        if (programs == null) extrasOk = false
                        else if (programs.error != null || programs.programs == null) log("Preconditioning schedules unavailable; retaining cached schedules")
                        lastProgramsMs = clock()
                    }
                    if (extrasOk && "auto_refresh" in (state.value.info?.fields ?: emptySet()) && state.value.command?.pending != true &&
                        (refreshCommand || clock() - lastAutoRefreshMs >= 5000)) {
                        val automatic = read(Protocol.request(store.clientId, "auto_refresh.get", identity, boot))
                        if (automatic != null && (automatic.error != null || automatic.autoRefresh == null)) log("Automatic refresh settings unavailable; retaining cached settings")
                        lastAutoRefreshMs = clock()
                    }
                    if(extrasOk && "low_power" in (state.value.info?.fields ?: emptySet()) && state.value.command?.pending!=true &&
                        (refreshCommand || clock()-lastLowPowerMs>=5000)) {
                        val low=read(Protocol.request(store.clientId,"low_power.get",identity,boot))
                        if(low!=null && (low.error!=null || low.lowPower==null))log("Low-power settings unavailable; retaining cached settings")
                        lastLowPowerMs=clock()
                    }
                    val wait = if (state.value.command?.pending == true) 500L else 1000L
                    var waited = 0L
                    while (waited < wait && !refreshRequested && state.value.command?.phase != "preparing" && !state.value.autoRefreshSaving && !state.value.lowPowerSaving) { delay(100); waited += 100 }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failures++
                val message = error.message?.take(220) ?: "Gateway connection failed"
                log(message)
                if (state.value.command?.phase == "preparing" && state.value.command?.ref == null) {
                    while (vehicleRequests.tryReceive().isSuccess) Unit
                    publishCommand(CommandUi(null, "failed", "Gateway unavailable. No vehicle request was sent."))
                } else if (state.value.command?.pending == true) {
                    val expired = watchSince != 0L && clock()-watchSince >= UdpPolicy.RESULT_TIMEOUT_MS
                    publishCommand(state.value.command!!.copy(phase = if (expired) "unknown" else state.value.command!!.phase,
                        message = if (expired) "Result unconfirmed. Vehicle confirmation timed out." else "Connection interrupted. Waiting to check the original request…"))
                }
                if (autoSaves.tryReceive().isSuccess) mutable.update { it.copy(autoRefreshSaving = false,
                    autoRefreshError = "Connection interrupted before sending settings. Try again after reconnecting.") }
                if(lowSaves.tryReceive().isSuccess)mutable.update {it.copy(lowPowerSaving=false,lowPowerError="Connection interrupted before sending settings.")}
                mutable.update { it.copy(connected = false, connecting = false, error = message) }
            } finally { local?.close(); if (connection === local) connection = null }
            val wait = if (failures < 3) 1000L else (1000L shl (failures - 2).coerceAtMost(3))
            var waited = 0L
            while (waited < wait && !refreshRequested) { delay(100); waited += 100 }
            refreshRequested = false
        }
    }
}
