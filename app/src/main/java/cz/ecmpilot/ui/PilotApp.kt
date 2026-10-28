package cz.ecmpilot.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.ecmpilot.data.PilotRepository
import cz.ecmpilot.model.*
import java.util.Locale

private val Amber = Color(0xFF9A4D0B)
private val Green = Color(0xFF32724B)
private val PaleGreen = Color(0xFFEAF4EC)
private val shape = RoundedCornerShape(22.dp)
private enum class Sheet { CLIMATE, CHARGING, CONNECTION, DETAILS }

@Composable
fun PilotApp(repository: PilotRepository, onRequestPermission: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val dark = when (state.settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFEFB16C), onPrimary = Color(0xFF30200E), primaryContainer = Color(0xFF403323), onPrimaryContainer = Color(0xFFFFDDBB), background = Color(0xFF171E1B), surface = Color(0xFF222B26), onSurface = Color(0xFFE9EFE9), onSurfaceVariant = Color(0xFFB1BFB5), outlineVariant = Color(0xFF3D4941))
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
                    onClimate = { if (state.vehicle?.climate?.state == "on") repository.submit(DesiredCommand.ClimateSet(false)) else sheet = Sheet.CLIMATE },
                    onCharge = { sheet = Sheet.CHARGING }, onRefresh = repository::refresh, onDismiss = repository::dismissResult)
            }
        }
        when (sheet) {
            Sheet.CLIMATE -> ClimateSheet(state, onClose = { sheet = null }, onStart = { sheet = null; repository.submit(DesiredCommand.ClimateSet(true)) })
            Sheet.CHARGING -> ChargingSheet(state, onClose = { sheet = null }, onApply = { sheet = null; repository.submit(it) })
            Sheet.CONNECTION -> ConnectionSheet(state, onClose = { sheet = null }, onSave = { repository.saveSettings(it) }, onRefresh = repository::refresh, onPermission = onRequestPermission)
            Sheet.DETAILS -> DetailsSheet(state, repository::diagnosticText, onClose = { sheet = null })
            null -> Unit
        }
    }
}

@Composable
private fun Home(s: PilotState, modifier: Modifier, onSettings: () -> Unit, onDetails: () -> Unit, onClimate: () -> Unit, onCharge: () -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val v = s.vehicle
    val climateOn = v?.climate?.state == "on"
    val climateSupported = s.info?.climate(!climateOn) == true
    val chargeSupported = s.info?.charge("immediate") == true || s.info?.charge("deferred") == true
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("eCMPilot", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Spacer(Modifier.width(10.dp)); Badge(if (s.settings.source == Source.DEMO) "DEMO" else if (s.info?.testMode == true) "TEST" else "LOCAL")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSettings, contentPadding = PaddingValues(10.dp)) { Text("Settings", fontSize = 12.sp) }
            }
            Text(s.settings.vehicleName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.info?.testMode == true) SecondaryText("Varstore test data · No CAN or vehicle commands")
            val label = when {
                s.settings.source == Source.UDP && !s.permissionGranted -> "Local network access needed"
                s.connecting -> "Connecting…"
                !s.connected -> "Offline · ${ageText(v?.reading("soc_pct")?.stamp?.age(s.nowMs))}"
                v?.awake == false -> "Gateway connected · Vehicle asleep"
                v == null || !v.vehicleStamp.fresh(s.nowMs) -> "Gateway connected · No recent vehicle data"
                else -> "Connected · ${ageText(v.reading("soc_pct")?.stamp?.age(s.nowMs))}"
            }
            TextButton(onClick = onRefresh, contentPadding = PaddingValues(0.dp)) {
                Text("●  $label", fontSize = 12.sp, color = if (s.connected) greenTone() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        s.command?.let { command -> item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp).fillMaxWidth()) {
                    Text(when (command.phase) { "preparing", "sending" -> "Sending request…"; "accepted", "executing" -> "Waiting for vehicle…"; "succeeded" -> "${command.ref?.label ?: "Request"} · Confirmed"; "unknown" -> "Result unconfirmed"; else -> "Request not completed" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(command.message, fontSize = 12.sp)
                    if (!command.pending) Row { TextButton(onClick = onRefresh) { Text("Refresh") }; TextButton(onClick = onDismiss) { Text("Dismiss") } }
                }
            }
        } }
        item { BatteryCard(s) }
        item {
            Panel {
                SectionTitle("Climate", "°")
                Row(Modifier.fillMaxWidth()) {
                    Temperature("Cabin", v?.reading("cabin_c"), s.nowMs, Modifier.weight(1f))
                    Temperature("Outside", v?.reading("outside_c"), s.nowMs, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                val climateFresh = s.connected && v?.climate?.stamp?.fresh(s.nowMs) == true
                val observed = when (v?.climate?.state) { "on" -> "Running"; "off" -> "Off"; else -> "State unavailable" }
                SecondaryText((if (!climateFresh && v != null) "Last known: " else "") + observed + " · Vehicle preset")
                Button(onClick = onClimate, enabled = s.controlReady("climate") && climateSupported, modifier = Modifier.testTag("home-climate").fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) {
                    Text(if (s.command?.pending == true) "Waiting for vehicle…" else if (climateOn) "Stop climate" else "Start climate")
                }
                controlReason(s, "climate", climateSupported)?.let { SecondaryText(it, Modifier.padding(top = 8.dp)) }
            }
        }
        item {
            Panel {
                SectionTitle("Charging", "ϟ")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(chargeLabel(s), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Badge(when (v?.charge?.connected) { true -> "Cable connected"; false -> "Unplugged"; null -> "Cable unknown" })
                }
                OutlinedButton(onClick = onCharge, enabled = s.controlReady("charge") && chargeSupported, modifier = Modifier.testTag("charging-options").fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) { Text("Charging options") }
                controlReason(s, "charge", chargeSupported)?.let { SecondaryText(it, Modifier.padding(top = 8.dp)) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val min = v?.reading("battery_min_c"); val max = v?.reading("battery_max_c")
                ValueRow("Battery temperature", if (min?.value != null && max?.value != null) "${number(min.value)}–${number(max.value)} °C" else "—", min?.fresh(s.nowMs) != true || max?.fresh(s.nowMs) != true)
                ValueRow("Odometer", v?.reading("odometer_km")?.value?.let { "${String.format(Locale.UK, "%,.0f", it)} km" } ?: "—", v?.reading("odometer_km")?.fresh(s.nowMs, 60_000) != true)
            }
            TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) { Text("Vehicle details") }
            if (s.error != null) SecondaryText(s.error)
        }
    }
}

@Composable private fun BatteryCard(s: PilotState) {
    val v = s.vehicle; val soc = v?.reading("soc_pct"); val range = v?.reading("range_km")
    val fresh = s.connected && soc?.fresh(s.nowMs) == true
    val background = if (MaterialTheme.colorScheme.background.red < .3f) Color(0xFF293E30) else PaleGreen
    Surface(color = background, shape = shape) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("BATTERY", color = greenTone(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f).alpha(if (fresh) 1f else .55f), verticalAlignment = Alignment.Bottom) {
                    Text(soc?.value?.let { number(it) } ?: "—", fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-4).sp, fontWeight = FontWeight.SemiBold)
                    if (soc?.value != null) Text(" %", fontSize = 25.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 7.dp).alpha(if (range?.fresh(s.nowMs) == true && s.connected) 1f else .55f)) {
                    Text(range?.value?.let { "${number(it)} km" } ?: "—", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    SecondaryText("Estimated range")
                }
            }
            Box(Modifier.fillMaxWidth().height(7.dp).background(greenTone().copy(alpha = .16f), RoundedCornerShape(4.dp)).semantics { contentDescription = soc?.value?.let { "Battery ${number(it)} percent" } ?: "Battery state unavailable" }) {
                soc?.value?.let { Box(Modifier.fillMaxWidth((it / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(greenTone(), RoundedCornerShape(4.dp))) }
            }
            Spacer(Modifier.height(14.dp))
            val charge = v?.charge
            val validCharge = s.connected && charge?.stamp?.fresh(s.nowMs) == true
            Text((if (!validCharge && charge != null) "Last known: " else "") + chargeLabel(s) + (charge?.powerKw?.takeIf { charge.state == "charging" }?.let { " · ${number(it, 1)} kW" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            val eta = charge?.remainingMin?.takeIf { charge.state == "charging" && validCharge }
            SecondaryText(when { eta != null -> "About ${eta.toInt() / 60} h ${eta.toInt() % 60} min remaining"; !fresh -> ageText(soc?.stamp?.age(s.nowMs)); charge?.mode == "deferred" && charge.startLocal != null -> "Start time · ${charge.startLocal}"; else -> "Updated from vehicle observations" })
        }
    }
}

@Composable private fun Temperature(label: String, reading: Reading?, now: Long, modifier: Modifier) {
    Column(modifier.alpha(if (reading?.fresh(now) == true) 1f else .55f)) { SecondaryText(label); Text(reading?.value?.let { "${number(it)}°C" } ?: "—", fontSize = 27.sp, fontWeight = FontWeight.Medium) }
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.fillMaxWidth().padding(18.dp), content = content) }
}
@Composable private fun SectionTitle(title: String, glyph: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text(glyph, Modifier.size(28.dp).wrapContentHeight(), color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, fontSize = 21.sp) }
        Spacer(Modifier.width(9.dp)); Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
private fun chargeLabel(s: PilotState) = when (s.vehicle?.charge?.state) { "charging" -> "Charging now"; "complete" -> "Charge complete"; "disconnected" -> "Unplugged"; "fault" -> "Charging fault"; "waiting" -> if (s.vehicle.charge.mode == "deferred") "Deferred charging" else "Waiting to charge"; else -> "Charging state unavailable" }
private fun controlReason(s: PilotState, domain: String, supported: Boolean): String? = when {
    s.readOnly -> "Monitoring only · Enable experimental local controls in Settings to send requests."
    !s.connected -> "Connect to the gateway to control the vehicle."
    s.vehicle?.awake == false -> "Vehicle asleep · Passive refresh will not wake it."
    !supported -> "Control is not available from this gateway."
    !s.controlReady(domain) && s.command?.pending != true -> "Fresh vehicle status is required."
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SheetFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold); TextButton(onClick = onClose) { Text("Close") } }
            content()
        }
    }
}
@Composable private fun ClimateSheet(s: PilotState, onClose: () -> Unit, onStart: () -> Unit) {
    SheetFrame("Climate", onClose) {
        SecondaryText("Cabin ${s.vehicle?.reading("cabin_c")?.value?.let { "${number(it)}°C" } ?: "unavailable"} · Outside ${s.vehicle?.reading("outside_c")?.value?.let { "${number(it)}°C" } ?: "unavailable"}")
        Text("Prepare the cabin", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        SecondaryText("Heat or cool using the vehicle preset.")
        Spacer(Modifier.height(5.dp)); ValueRow("Temperature", "Vehicle preset"); HorizontalDivider(); ValueRow("Duration", "Vehicle cycle"); HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            Button(onClick = onStart, enabled = s.controlReady("climate") && s.info?.climate(true) == true, modifier = Modifier.testTag("confirm-climate").weight(2f).heightIn(min = 48.dp)) { Text("Start climate") }
        }
    }
}
@Composable private fun ChargingSheet(s: PilotState, onClose: () -> Unit, onApply: (DesiredCommand.ChargeMode) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(s.vehicle?.charge?.mode?.takeIf { it == "deferred" } ?: "immediate") }
    var time by rememberSaveable { mutableStateOf(s.vehicle?.charge?.startLocal ?: "23:00") }
    val timeValid = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time)
    SheetFrame("Charging", onClose) {
        SecondaryText(chargeLabel(s))
        Text("Charging mode", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = mode == "immediate", onClick = { mode = "immediate" }, label = { Text("Now") }, enabled = s.info?.charge("immediate") == true, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            FilterChip(selected = mode == "deferred", onClick = { mode = "deferred" }, label = { Text("Later") }, enabled = s.info?.charge("deferred") == true, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
        }
        if (mode == "deferred") {
            OutlinedTextField(value = time, onValueChange = { time = it.take(5) }, label = { Text("Start time (HH:mm)") }, singleLine = true, isError = !timeValid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            SecondaryText("Vehicle local time · ${s.info?.timezone ?: "Timezone unavailable"}")
            SecondaryText(if (s.info?.testMode == true) "Test only: this checks a varstore start-time value. It does not save or run a vehicle schedule."
                else "The native saved setting works without your phone. This is a start time, not a completion time.")
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            Button(onClick = { onApply(DesiredCommand.ChargeMode(mode, if (mode == "deferred") time else null)) }, enabled = s.controlReady("charge") && s.info?.charge(mode) == true && (mode != "deferred" || timeValid && s.info.timezone != null), modifier = Modifier.testTag("apply-charge").weight(2f).heightIn(min = 48.dp)) { Text("Apply") }
        }
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
            }, enabled = s.command?.pending != true, modifier = Modifier.testTag("save-connection").weight(2f).heightIn(min = 48.dp)) { Text("Save & connect") }
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
        val units = listOf("soc_pct" to ("Driver SOC" to "%"), "range_km" to ("Estimated range" to "km"), "cabin_c" to ("Cabin" to "°C"), "outside_c" to ("Outside" to "°C"), "battery_min_c" to ("Battery minimum" to "°C"), "battery_max_c" to ("Battery maximum" to "°C"), "odometer_km" to ("Odometer" to "km"), "voltage_v" to ("Battery voltage" to "V"), "current_a" to ("Battery current" to "A"))
        units.forEach { (key, names) ->
            val reading = s.vehicle?.reading(key)
            ValueRow(names.first, reading?.value?.let { "${number(it, 1)} ${names.second}" } ?: "—", reading?.fresh(s.nowMs, if (key == "odometer_km") 60_000 else 5000) != true)
            SecondaryText(ageText(reading?.stamp?.age(s.nowMs)))
        }
        HorizontalDivider(); SecondaryText("Charging power is net power into the HV battery. Positive battery current, when supplied, means discharge.")
        ValueRow("Gateway", s.gatewayId ?: "Unavailable"); ValueRow("Firmware", s.info?.firmware ?: "Unavailable"); ValueRow("Transport profile", s.info?.security ?: "Unknown")
        s.command?.let { SecondaryText("Last request: ${it.ref?.label ?: "Request"} · ${it.phase}\n${it.message}") }
        Text("Recent diagnostics", fontWeight = FontWeight.SemiBold)
        SecondaryText(s.diagnostics.takeLast(12).joinToString("\n").ifBlank { "No connection events yet." })
        exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { createDocument.launch("ecmpilot-diagnostics.txt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save diagnostics…") }
    }
}
