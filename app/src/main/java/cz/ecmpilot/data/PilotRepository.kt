package cz.ecmpilot.data

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonObject
import cz.ecmpilot.BuildConfig
import cz.ecmpilot.model.*
import cz.ecmpilot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.ZoneId

class PilotRepository(private val context: Context) {
    private val clock = { SystemClock.elapsedRealtime() }
    private val store = SettingsStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initial = store.load()
    private val mutable = MutableStateFlow(PilotState(initial, nowMs = clock(), command = if (initial.source == Source.UDP) store.loadCommand() else null))
    val state = mutable.asStateFlow()
    private val requests = Channel<DesiredCommand>(1)
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
            while (requests.tryReceive().isSuccess) Unit
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
        stop()
        store.save(s)
        mutable.update { it.copy(settings = s, connected = false, connecting = true, gatewayId = null, boot = null, info = null, vehicle = null, error = null,
            command = if (s.source == Source.UDP) store.loadCommand() else null) }
        if (visible) start()
    }
    fun refresh() { refreshRequested = true }
    fun submit(command: DesiredCommand) {
        if (!visible || !state.value.controlReady(command.domain)) return
        mutable.update { it.copy(command = CommandUi(null, "preparing", "Refreshing vehicle state before sending…")) }
        if (!requests.trySend(command).isSuccess) mutable.update { it.copy(command = CommandUi(null, "failed", "Another request is already pending.")) }
    }
    fun dismissResult() { if (state.value.command?.pending != true) mutable.update { it.copy(command = null) } }
    fun diagnosticText(): String = "eCMPilot ${BuildConfig.VERSION_NAME}\nSource: ${state.value.settings.source}\nGateway: ${state.value.gatewayId ?: "unknown"}\n" + state.value.diagnostics.joinToString("\n")

    private fun start() {
        if (worker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val now = clock()
                mutable.update { it.copy(nowMs = now, connected = it.connected && now - lastReplyMs <= 5000) }
                delay(1000)
            }
        }
        worker = scope.launch { runLoop() }
    }
    private fun stop() {
        worker?.cancel(); ticker?.cancel(); connection?.close(); connection = null; worker = null; ticker = null
        mutable.update { it.copy(connected = false, connecting = false) }
    }
    private fun restart() { stop(); if (visible) start() }
    private fun log(text: String) { mutable.update { it.copy(diagnostics = (it.diagnostics + "${clock()} ms  $text").takeLast(80)) } }
    private fun publishCommand(command: CommandUi) {
        if (state.value.settings.source == Source.UDP) store.saveCommand(command)
        mutable.update { it.copy(command = command) }
    }

    private suspend fun runLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val settings = state.value.settings
            if (settings.source == Source.UDP && !state.value.permissionGranted) {
                mutable.update { it.copy(connecting = false, connected = false, error = "Allow local network access to connect to the gateway.") }
                delay(500); continue
            }
            var local: GatewayConnection? = null
            val tracker = StatusTracker()
            try {
                mutable.update { it.copy(connecting = true) }
                val gateway = if (settings.source == Source.DEMO) DemoConnection(settings.scene, clock) else openLocalConnection(context, settings, clock)
                local = gateway; connection = gateway
                var identity: String? = settings.gatewayId.takeIf { it.isNotBlank() && settings.source == Source.UDP }
                var boot: String? = null
                var lastInfoMs = 0L
                var watchSince = 0L
                suspend fun exchange(request: Request, retry: Boolean = false): Status {
                    val response = gateway.exchange(request, retry)
                    currentCoroutineContext().ensureActive()
                    if (identity != null && response.gw != identity) throw ProtocolException("Gateway identity changed; use Forget gateway in settings")
                    val previousBoot = boot
                    if (previousBoot != null && previousBoot != response.boot) {
                        val old = state.value.command
                        if (old?.pending == true) publishCommand(old.copy(phase = "unknown", message = "Gateway restarted. Previous result unconfirmed."))
                        mutable.update { it.copy(info = null, vehicle = null) }
                    }
                    identity = response.gw; boot = response.boot
                    tracker.accept(response)
                    lastReplyMs = clock()
                    mutable.update { it.copy(connected = true, connecting = false, gatewayId = response.gw, boot = response.boot,
                        info = response.info ?: if (previousBoot != null && previousBoot != response.boot) null else it.info,
                        vehicle = tracker.snapshot, error = null, nowMs = clock()) }
                    val current = state.value.command
                    response.command?.takeIf { it.id == current?.ref?.id && current.ref.boot == response.boot && current.ref.gatewayId == response.gw && it.revision > current.resultRevision }?.let { result ->
                        val message = if (state.value.info?.testMode == true) when (result.phase) {
                            "accepted", "executing" -> "Varstore test: waiting for matching observation."
                            "succeeded" -> "Confirmed by varstore test data. No vehicle command was sent."
                            else -> "Varstore test: ${messageFor(result)}"
                        } else messageFor(result)
                        publishCommand(current!!.copy(phase = result.phase, message = message, resultRevision = result.revision))
                        log("Command ${result.id.take(8)}: ${result.phase}${result.error?.let { ": $it" } ?: ""}")
                    }
                    return response
                }
                suspend fun info() {
                    val response = exchange(Protocol.request(store.clientId, "info.get", identity))
                    if (response.error != null) throw ProtocolException("Gateway info: ${response.error}")
                    requireNotNull(response.info) { "Gateway did not return capability information" }
                    if (settings.source == Source.UDP && settings.gatewayId.isBlank()) {
                        val learned = state.value.settings.copy(gatewayId = response.gw)
                        store.save(learned); mutable.update { it.copy(settings = learned) }
                    }
                    lastInfoMs = clock()
                }
                info()
                log("Connected to ${identity}; ${state.value.info?.firmware}")
                var recover = state.value.command?.let { it.pending || it.phase == "unknown" || it.phase == "sending" } == true
                while (currentCoroutineContext().isActive) {
                    val refreshCommand = refreshRequested
                    refreshRequested = false
                    if (refreshCommand || state.value.info == null || clock() - lastInfoMs > 30_000) info()
                    val desired = requests.tryReceive().getOrNull()
                    if (desired != null) {
                        try {
                            if (!visible) { mutable.update { it.copy(command = null) }; continue }
                            info()
                            val fresh = exchange(Protocol.request(store.clientId, "state.get", identity, boot))
                            require(fresh.state != null && fresh.roundTripMs <= 1000) { "A fresh vehicle reply is required" }
                            val current = state.value.copy(command = null, nowMs = clock())
                            require(current.controlReady(desired.domain)) { "Vehicle state is unavailable, stale, asleep or read-only" }
                            val caps = current.info ?: throw IOException("Capabilities unavailable")
                            val args = JsonObject()
                            val op = when (desired) {
                                is DesiredCommand.ClimateSet -> { require(caps.climate(desired.enabled)) { "Climate operation is not verified by this gateway" }; args.addProperty("enabled", desired.enabled); "climate.set" }
                                is DesiredCommand.ChargeMode -> {
                                    require(caps.charge(desired.mode)) { "Charging mode is not verified by this gateway" }
                                    args.addProperty("mode", desired.mode)
                                    if (desired.mode == "deferred") {
                                        require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(desired.startLocal ?: "")) { "Enter a valid start time" }
                                        val zone = caps.timezone ?: throw IOException("Gateway did not report its timezone")
                                        ZoneId.of(zone)
                                        args.addProperty("start_local", desired.startLocal); args.addProperty("timezone", zone)
                                    }
                                    "charge.mode.set"
                                }
                            }
                            if (!visible) { mutable.update { it.copy(command = null) }; continue }
                            val rev = if (desired.domain == "climate") fresh.state.climate.rev else fresh.state.charge.rev
                            val request = Protocol.request(store.clientId, op, identity, boot, args, fresh.nowMs + 5000, requireNotNull(rev))
                            val ref = CommandRef(request.id, fresh.gw, fresh.boot, desired.label, settings.host, settings.port)
                            publishCommand(CommandUi(ref, "sending", "Sending request…"))
                            log("Send ${request.id.take(8)} $op")
                            val reply = exchange(request, retry = true)
                            if (reply.boot != ref.boot) throw IOException("Gateway restarted during the request")
                            if (reply.command?.id != request.id) throw IOException("Gateway did not return a command receipt")
                            watchSince = clock(); recover = false
                        } catch (cancel: CancellationException) { throw cancel }
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
                            val response = exchange(Protocol.request(store.clientId, "command.get", identity, boot, JsonObject().apply { addProperty("command_id", ref.id) }))
                            if (response.error != null) publishCommand(command.copy(phase = "unknown", message = "Result unconfirmed (${response.error})."))
                            if (state.value.command?.pending == true && clock() - watchSince > 35_000) publishCommand(state.value.command!!.copy(phase = "unknown", message = "Result unconfirmed. Vehicle confirmation timed out."))
                        }
                    }
                    val response = exchange(Protocol.request(store.clientId, "state.get", identity, boot))
                    if (response.error != null || response.state == null) throw ProtocolException("Gateway did not return vehicle observations")
                    failures = 0
                    val wait = if (state.value.command?.pending == true) 500L else 1000L
                    var waited = 0L
                    while (waited < wait && !refreshRequested && state.value.command?.phase != "preparing") { delay(100); waited += 100 }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failures++
                val message = error.message?.take(220) ?: "Gateway connection failed"
                log(message)
                if (state.value.command?.pending == true) publishCommand(state.value.command!!.copy(phase = "unknown", message = "Connection lost. Result unconfirmed; the request will not be replayed."))
                mutable.update { it.copy(connected = false, connecting = false, error = message) }
            } finally { local?.close(); if (connection === local) connection = null }
            val wait = if (failures < 3) 1000L else (1000L shl (failures - 2).coerceAtMost(3))
            var waited = 0L
            while (waited < wait && !refreshRequested) { delay(100); waited += 100 }
            refreshRequested = false
        }
    }
}
