package cz.ecmpilot.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ecmpilot.data.PilotRepository
import cz.ecmpilot.R
import cz.ecmpilot.model.*
import java.util.Locale
import kotlinx.coroutines.delay

private val Amber = Color(0xFF9A4D0B)
private val Green = Color(0xFF32724B)
private val shape = RoundedCornerShape(22.dp)
private enum class Sheet { CLIMATE, CHARGING, CONNECTION, DETAILS, BODY, TRIPS, GATEWAY }

@Composable
fun PilotApp(repository: PilotRepository, onRequestPermission: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.command) {
        val command = state.command
        if (command?.phase == "succeeded") {
            delay(3000)
            if (repository.state.value.command == command) repository.dismissResult()
        }
    }
    val dark = when (state.settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFEFB16C), onPrimary = Color(0xFF30200E), primaryContainer = Color(0xFF403323), onPrimaryContainer = Color(0xFFFFDDBB),
        background = Color(0xFF18181A), onBackground = Color(0xFFECECEE), surface = Color(0xFF222224), onSurface = Color(0xFFECECEE),
        surfaceVariant = Color(0xFF303033), onSurfaceVariant = Color(0xFFBEBEC2), outline = Color(0xFF8C8C91), outlineVariant = Color(0xFF46464B),
        surfaceTint = Color.Transparent, surfaceBright = Color(0xFF39393D), surfaceDim = Color(0xFF18181A),
        surfaceContainerLowest = Color(0xFF121214), surfaceContainerLow = Color(0xFF222224), surfaceContainer = Color(0xFF27272A),
        surfaceContainerHigh = Color(0xFF303033), surfaceContainerHighest = Color(0xFF38383C))
        else lightColorScheme(primary = Amber, onPrimary = Color.White, primaryContainer = Color(0xFFFCF0DF), onPrimaryContainer = Color(0xFF402511), background = Color(0xFFF8F9F6), surface = Color.White, onSurface = Color(0xFF202823), onSurfaceVariant = Color(0xFF626D65), outlineVariant = Color(0xFFE2E5DE))
    val activity = LocalActivity.current
    SideEffect {
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }?.apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors) {
        var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                Home(state, Modifier.widthIn(max = 600.dp).fillMaxWidth(), onSettings = { sheet = Sheet.CONNECTION }, onDetails = { sheet = Sheet.DETAILS },
                    onClimate = { sheet = Sheet.CLIMATE }, onBody = { sheet = Sheet.BODY }, onTrips = { sheet = Sheet.TRIPS },
                    onCharge = { sheet = Sheet.CHARGING }, onRefresh = repository::refresh, onGateway = { sheet = Sheet.GATEWAY })
            }
        }
        when (sheet) {
            Sheet.CLIMATE -> ClimateSheet(state, onClose = { sheet = null },
                onStart = { sheet = null; repository.submit(DesiredCommand.ClimateSet(state.vehicle?.climate?.state != "on")) },
                onPrograms = { sheet = null; repository.submit(it) })
            Sheet.CHARGING -> ChargingSheet(state, onClose = { sheet = null }, onApply = { sheet = null; repository.submit(it) }, onAutoRefresh = repository::saveAutoRefresh, onReload = repository::reconnect)
            Sheet.CONNECTION -> ConnectionSheet(state, onClose = { sheet = null }, onSave = { repository.saveSettings(it) }, onRefresh = repository::reconnect, onPermission = onRequestPermission)
            Sheet.GATEWAY -> GatewaySheet(state, onClose = { sheet = null }, onAutoRefresh = repository::saveAutoRefresh, onReload = repository::reconnect)
            Sheet.DETAILS -> DetailsSheet(state, repository::diagnosticText, onClose = { sheet = null })
            Sheet.BODY -> SheetFrame("Doors & locks", onClose = { sheet = null }) { BodyContent(state) }
            Sheet.TRIPS -> SheetFrame("Trips & consumption", onClose = { sheet = null }) { TripsContent(state) }
            null -> Unit
        }
        state.command?.takeIf { it.hasError }?.let { command ->
            AlertDialog(onDismissRequest = repository::dismissResult,
                modifier = Modifier.testTag("vehicle-action-error"),
                title = { Text(if (command.phase == "unknown") "Vehicle request not confirmed" else "${command.ref?.label ?: "Vehicle request"} failed") },
                text = { Text(command.message) },
                confirmButton = { TextButton(onClick = repository::dismissResult) { Text("Close") } })
        }
    }
}

@Composable
private fun Home(s: PilotState, modifier: Modifier, onSettings: () -> Unit, onDetails: () -> Unit, onClimate: () -> Unit, onBody: () -> Unit, onTrips: () -> Unit, onCharge: () -> Unit, onRefresh: () -> Unit, onGateway: () -> Unit) {
    val v = s.vehicle
    val climateOn = v?.climate?.state == "on"
    val climateSupported = s.info?.climate(!climateOn) == true
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("eCMPilot", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Spacer(Modifier.width(10.dp)); Badge(if (s.settings.source == Source.DEMO) "DEMO" else if (s.info?.testMode == true) "TEST" else "LOCAL")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
            }
            val label = when {
                s.settings.source == Source.UDP && !s.permissionGranted -> "Local network access needed"
                s.connecting -> "Connecting…"
                !s.connected -> "Gateway offline"
                else -> "Gateway connected"
            }
            val socAge = v?.reading("soc_pct")?.takeIf { it.value != null }?.stamp?.age(s.nowMs)
            val bsi = v?.bsi?.label ?: "unknown"
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("●  $label · BSI $bsi", fontSize = 12.sp, lineHeight = 18.sp,
                    minLines = 1, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag("connection-status"),
                    color = if (s.connected) greenTone() else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ageText(socAge), fontSize = 12.sp, lineHeight = 18.sp,
                    minLines = 1, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag("vehicle-data-age"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.settings.vehicleName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRefresh, enabled = s.controlReady("refresh"), modifier = Modifier.widthIn(min = 88.dp).testTag("refresh-vehicle")) {
                    if (s.command?.pending == true)
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)
                            .testTag("vehicle-action-progress").semantics { contentDescription = "Vehicle request in progress" })
                    else Text("Refresh")
                }
            }
            if (s.info?.testMode == true) SecondaryText("Varstore test data · No CAN or vehicle commands")
        }
        item { VehicleOverview(s, onClimate, onCharge, onBody) }
        item { HomeMenuRow("Trips & consumption", "Trip 1 · Trip 2 · Consumption history", onTrips, "menu-trips") }
        item { HomeMenuRow("Vehicle details", v?.reading("odometer_km")?.value?.let { "${String.format(Locale.UK, "%,.0f", it)} km" } ?: "Vehicle readings & diagnostics", onDetails, "menu-details") }
        item { HomeMenuRow("Gateway", "Automatic vehicle refresh", onGateway, "menu-gateway") }
        if (s.error != null) item { SecondaryText(s.error) }
    }
}

@Composable private fun BatteryCard(s: PilotState) {
    val v = s.vehicle; val soc = v?.reading("soc_pct"); val range = v?.reading("range_km")
    val fresh = s.connected && soc?.freshForDisplay(s.nowMs) == true
    val deferred = v?.charge?.mode == "deferred"
    val tone = if (!fresh) MaterialTheme.colorScheme.onSurfaceVariant else if (deferred) Color(0xFF438AD4) else greenTone()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = shape) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("BATTERY & CHARGING", color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f).alpha(if (fresh) 1f else .55f), verticalAlignment = Alignment.Bottom) {
                    Text(soc?.value?.let { number(it) } ?: "—", fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-4).sp, fontWeight = FontWeight.SemiBold, color = tone)
                    if (soc?.value != null) Text(" %", fontSize = 25.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 7.dp).alpha(if (range?.freshForDisplay(s.nowMs) == true && s.connected) 1f else .55f)) {
                    Text(range?.value?.let { "${number(it)} km" } ?: "—", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    SecondaryText("Estimated range")
                }
            }
            SocBar(soc?.value, tone, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            val charge = v?.charge
            val validCharge = s.connected && charge?.stamp?.freshForDisplay(s.nowMs) == true
            Text((if (!validCharge && charge != null) "Last known: " else "") + chargeLabel(s) + (charge?.powerKw?.takeIf { charge.state == "charging" }?.let { " · ${number(it, 1)} kW" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            val eta = charge?.remainingMin?.takeIf { charge.state == "charging" && validCharge }
            if (eta != null) SecondaryText("About ${eta.toInt() / 60} h ${eta.toInt() % 60} min remaining")
            val schedule = charge?.startLocal
            if (schedule != null) ValueRow("Delayed charging", schedule, !s.connected || charge.modeStamp.freshForDisplay(s.nowMs) != true)
        }
    }
}

@Composable private fun SecondaryText(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
@Composable private fun Badge(text: String) { Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun greenTone() = if (MaterialTheme.colorScheme.background.red < .3f) Color(0xFFA2D9B0) else Green
@Composable private fun ValueRow(label: String, value: String, stale: Boolean = false) {
    Row(Modifier.fillMaxWidth().alpha(if (stale) .55f else 1f), verticalAlignment = Alignment.CenterVertically) { SecondaryText(label, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}
private fun number(value: Double, decimals: Int = 0) = String.format(Locale.UK, "%.${decimals}f", value)
private fun ageText(ms: Long?) = when { ms == null -> "No vehicle reading yet"; ms < 2000 -> "Updated just now"; ms < 60_000 -> "Last vehicle data ${ms / 1000} s ago"; else -> "Last vehicle data ${ms / 60_000} min ago" }
private fun chargeLabel(s: PilotState) = when (s.vehicle?.charge?.state) { "charging" -> "Charging now"; "complete" -> "Charge complete"; "disconnected" -> "Unplugged"; "fault" -> "Charging fault"; "waiting" -> if (s.vehicle.charge.mode == "deferred") "Delayed charging" else "Waiting to charge"; else -> "Charging state unavailable" }
private fun controlReason(s: PilotState, domain: String, supported: Boolean): String? = when {
    s.readOnly -> "Monitoring only · Enable experimental local controls in Settings to send requests."
    !s.connected -> "Connect to the gateway to control the vehicle."
    s.info?.experimentalCan == true && domain == "climate" && s.controlReady(domain) -> null
    s.info?.experimentalCan == true && s.vehicle?.charge?.scheduleAvailable != true && domain == "charge" -> "Use Refresh vehicle to obtain the charging setting."
    s.vehicle?.awake == false -> "Vehicle asleep · Use Refresh vehicle to request current data."
    !supported -> "Control is not available from this gateway."
    !s.controlReady(domain) && s.command?.pending != true -> "Fresh vehicle status is required."
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SheetFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Top clearance must not depend on the sheet offset: changing a wrapped
    // content inset changes its height and moves the Expanded anchor again.
    // The modal itself already handles IME padding around the sheet.
    val density = LocalDensity.current
    val distance = { 100f }
    // Dismiss by travel only; a short fast flick must not bypass the threshold.
    val velocity = { Float.POSITIVE_INFINITY }
    val sheetState = rememberSaveable(density, saver = SheetState.Saver(
        skipPartiallyExpanded = true, positionalThreshold = distance,
        velocityThreshold = velocity, confirmValueChange = { true }, skipHiddenState = false)) {
        SheetState(skipPartiallyExpanded = true, positionalThreshold = distance, velocityThreshold = velocity)
    }
    ModalBottomSheet(onDismissRequest = onClose,
        modifier = Modifier.statusBarsPadding().testTag("dialog-sheet"),
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) }) {
        Column(Modifier.fillMaxWidth().testTag("dialog-scroll").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold); TextButton(onClick = onClose) { Text("Close") } }
            content()
        }
    }
}
@Composable private fun ClimateSheet(s: PilotState, onClose: () -> Unit, onStart: () -> Unit, onPrograms: (DesiredCommand.ClimateProgramsSet) -> Unit) {
    val running = s.vehicle?.climate?.state == "on"
    SheetFrame("Preconditioning", onClose) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (running) "Preconditioning running" else "Prepare the cabin", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val outside = s.vehicle?.reading("outside_c")
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.alpha(if (s.connected && outside?.freshForDisplay(s.nowMs) == true) 1f else .55f).testTag("preconditioning-outside")) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
                    SecondaryText("Outside")
                    Text(outside?.value?.let { "${number(it, 1)} °C" } ?: "—", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        SecondaryText("Heat or cool using the vehicle preset.")
        Spacer(Modifier.height(5.dp)); ValueRow("Temperature", "Vehicle preset"); HorizontalDivider(); ValueRow("Duration", "Vehicle cycle"); HorizontalDivider()
        Button(onClick = onStart, enabled = s.controlReady("climate") && s.info?.climate(!running) == true,
            modifier = Modifier.testTag("confirm-climate").fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) {
            Text(if (running) "Stop preconditioning" else "Start preconditioning")
        }
        HorizontalDivider(Modifier.padding(top = 10.dp))
        PreconditioningProgramsEditor(s, onPrograms)
    }
}
@Composable private fun ChargingSheet(s: PilotState, onClose: () -> Unit, onApply: (DesiredCommand.ChargeMode) -> Unit, onAutoRefresh: (AutoRefreshSettings) -> Unit, onReload: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(s.vehicle?.charge?.mode?.takeIf { it == "deferred" } ?: "immediate") }
    var time by rememberSaveable { mutableStateOf(s.vehicle?.charge?.startLocal ?: "23:00") }
    val timeValid = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time)
    SheetFrame("Charging", onClose) {
        BatteryCard(s)
        ChargeLimitContent(s, onAutoRefresh, onReload)
        Text("Charging mode", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = mode == "deferred", onCheckedChange = { mode = if (it) "deferred" else "immediate" },
                enabled = s.controlReady("charge") && s.info?.charge(if (mode == "deferred") "immediate" else "deferred") == true,
                modifier = Modifier.testTag("deferred-charging"),
                colors = scheduleSwitchColors())
            Text("Delayed charging", modifier = Modifier.padding(start = 12.dp))
        }
        OutlinedTextField(value = time, onValueChange = { time = it.take(5) }, label = { Text("Start time (HH:mm)") }, singleLine = true, isError = !timeValid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
        SecondaryText("Vehicle local time · ${s.info?.timezone ?: "Timezone unavailable"}")
        SecondaryText(if (s.info?.testMode == true) "Test only: this checks a varstore start-time value. It does not save or run a vehicle schedule."
            else "Vehicle stores the start time. The gateway waits for BSI readback; immediate mode still needs vehicle validation.")
        Button(onClick = { onApply(DesiredCommand.ChargeMode(mode, if (mode == "deferred") time else null)) },
            enabled = s.controlReady("charge") && s.info?.charge(mode) == true && (mode != "deferred" || timeValid && s.info.timezone != null),
            modifier = Modifier.testTag("apply-charge").fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) { Text("Apply") }
    }
}

@Composable private fun GatewaySheet(s: PilotState, onClose: () -> Unit, onAutoRefresh: (AutoRefreshSettings) -> Unit, onReload: () -> Unit) {
    SheetFrame("Gateway", onClose) {
        val gateway = if (s.settings.source == Source.DEMO) "Demo gateway" else s.settings.host
        val refreshStatus = s.autoRefresh?.description ?: "Reading gateway settings…"
        SecondaryText("$gateway, ${refreshStatus.replaceFirstChar { it.lowercase() }.trimEnd('.')}",
            Modifier.testTag("auto-refresh-status"))
        AutoRefreshContent(s, onAutoRefresh, onReload)
    }
}

@Composable private fun ConnectionSheet(s: PilotState, onClose: () -> Unit, onSave: (Settings) -> Unit, onRefresh: () -> Unit, onPermission: () -> Unit) {
    var source by rememberSaveable { mutableStateOf(s.settings.source) }
    var host by rememberSaveable { mutableStateOf(s.settings.host) }
    var port by rememberSaveable { mutableStateOf(s.settings.port.toString()) }
    var name by rememberSaveable { mutableStateOf(s.settings.vehicleName) }
    var lab by rememberSaveable { mutableStateOf(s.settings.allowLabControls) }
    var theme by rememberSaveable { mutableStateOf(s.settings.theme) }
    var scene by rememberSaveable { mutableStateOf(s.settings.scene) }
    var forget by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    SheetFrame("Connection", onClose) {
        Text("Data source", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(source == Source.DEMO, { source = Source.DEMO }, label = { Text("Demo") }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(source == Source.UDP, { source = Source.UDP }, label = { Text("Local gateway") }, modifier = Modifier.testTag("local-source").heightIn(min = 48.dp))
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Vehicle name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (source == Source.UDP) {
            OutlinedTextField(host, { host = it.trim() }, label = { Text("Gateway hostname or IP") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.testTag("gateway-host").fillMaxWidth())
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Local UDP port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("gateway-port").fillMaxWidth())
            if (!s.permissionGranted) Button(onClick = onPermission, modifier = Modifier.fillMaxWidth()) { Text("Allow local network access") }
            SecondaryText("Phone and gateway must share a local Wi-Fi or Ethernet network.")
            Row(Modifier.fillMaxWidth().clickable { lab = !lab }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(lab, { lab = it }, modifier = Modifier.testTag("allow-controls")); Column(Modifier.weight(1f)) { Text("Allow experimental local controls", fontSize = 14.sp); SecondaryText("Unencrypted lab UDP. Only gateway-advertised, confirmed operations are enabled.") }
            }
            if (s.settings.gatewayId.isNotEmpty()) {
                SecondaryText("Saved gateway: ${s.settings.gatewayId}")
                TextButton(onClick = { forget = true; message = "Gateway identity will be learned again when settings are saved." }) { Text(if (forget) "Gateway will be forgotten" else "Forget saved gateway") }
            }
        } else {
            SecondaryText("Simulated data only. Demo mode creates no network connection.")
            DemoScene.entries.forEach { item -> Row(Modifier.fillMaxWidth().selectable(selected = scene == item, onClick = { scene = item }, role = Role.RadioButton).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(scene == item, null); Text(item.title, fontSize = 13.sp) } }
        }
        Text("Appearance", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemeMode.entries.forEach { item -> FilterChip(theme == item, { theme = item }, label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) }, modifier = Modifier.heightIn(min = 48.dp)) } }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        s.error?.let { SecondaryText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            Button(onClick = {
                runCatching {
                    val parsedPort = port.toIntOrNull() ?: error("Enter a valid UDP port")
                    onSave(Settings(source, host.trim(), parsedPort, name.trim(), if (forget) "" else s.settings.gatewayId, lab, theme, scene))
                }.onSuccess { onRefresh(); onClose() }.onFailure { message = it.message ?: "Could not save settings" }
            }, enabled = s.command?.pending != true && !s.autoRefreshSaving, modifier = Modifier.testTag("save-connection").weight(2f).heightIn(min = 48.dp)) { Text("Save & connect") }
        }
    }
}

@Composable private fun DetailsSheet(s: PilotState, diagnostics: () -> String, onClose: () -> Unit) {
    val context = LocalContext.current
    var exportError by remember { mutableStateOf<String?>(null) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(diagnostics()) } ?: error("Could not open document") }.onFailure { exportError = it.message }
    }
    SheetFrame("Vehicle details", onClose) {
        val units = listOf("soc_pct" to ("SOC" to "%"), "range_km" to ("Estimated range" to "km"),
            "outside_c" to ("Outside" to "°C"), "odometer_km" to ("Odometer" to "km"),
            "aux_battery_voltage_v" to ("12 V battery voltage" to "V"), "aux_battery_soc_pct" to ("12 V battery SOC" to "%"),
            "climate_left_setpoint_c" to ("Front left setpoint" to "°C"), "climate_right_setpoint_c" to ("Front right setpoint" to "°C"))
        units.forEach { (key, names) ->
            val reading = s.vehicle?.reading(key)
            ValueRow(names.first, reading?.value?.let { "${number(it, 1)} ${names.second}" } ?: "—", reading?.freshForDisplay(s.nowMs) != true)
        }
        HorizontalDivider()
        ValueRow("Gateway", s.gatewayId ?: "Unavailable"); ValueRow("Firmware", s.info?.firmware ?: "Unavailable"); ValueRow("Transport profile", s.info?.security ?: "Unknown")
        s.command?.let { SecondaryText("Last request: ${it.ref?.label ?: "Request"} · ${it.phase}\n${it.message}") }
        Text("Recent diagnostics", fontWeight = FontWeight.SemiBold)
        SecondaryText(s.diagnostics.takeLast(12).joinToString("\n").ifBlank { "No connection events yet." })
        exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { createDocument.launch("ecmpilot-diagnostics.txt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save diagnostics…") }
    }
}
