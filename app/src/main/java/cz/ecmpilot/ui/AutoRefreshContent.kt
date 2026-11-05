package cz.ecmpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.ecmpilot.model.*
import kotlin.math.roundToInt

@Composable
internal fun AutoRefreshContent(s: PilotState, onSave: (AutoRefreshSettings) -> Unit, onReload: () -> Unit) {
    val status = s.autoRefresh
    var enabled by remember(s.gatewayId, s.boot) { mutableStateOf(false) }
    var standby by remember(s.gatewayId, s.boot) { mutableStateOf("60") }
    var charging by remember(s.gatewayId, s.boot) { mutableStateOf("5") }
    var soc by remember(s.gatewayId, s.boot) { mutableStateOf("50") }
    var chargeLimit by remember(s.gatewayId, s.boot) { mutableStateOf(false) }
    var chargeLimitSoc by remember(s.gatewayId, s.boot) { mutableIntStateOf(80) }
    var revision by remember(s.gatewayId, s.boot) { mutableLongStateOf(1) }
    var dirty by remember(s.gatewayId, s.boot) { mutableStateOf(false) }
    var submitted by remember(s.gatewayId, s.boot) { mutableStateOf(false) }
    var error by remember(s.gatewayId, s.boot) { mutableStateOf<String?>(null) }
    fun load(value: AutoRefreshSettings) {
        enabled = value.enabled; standby = value.standbyMin.toString(); charging = value.chargingMin.toString()
        soc = value.minAuxSocPct.toString(); revision = value.revision; dirty = false; error = null
        chargeLimit = value.chargeLimitEnabled; chargeLimitSoc = value.chargeLimitSocPct
    }
    LaunchedEffect(status?.settings, s.autoRefreshSaving, s.autoRefreshSavedRevision, s.autoRefreshError) {
        if (status != null && (!dirty || submitted && !s.autoRefreshSaving && s.autoRefreshError == null && s.autoRefreshSavedRevision == status.settings.revision)) load(status.settings)
        if (submitted && !s.autoRefreshSaving) submitted = false
    }
    Text("Automatic vehicle refresh", fontWeight = FontWeight.SemiBold)
    if ("auto_refresh" !in (s.info?.fields ?: emptySet())) {
        Text("Connect to a gateway with automatic refresh support.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val editable = s.connected && status != null && !s.autoRefreshSaving && !s.lowPowerSaving && s.command?.pending != true &&
        (s.settings.source != Source.UDP || s.permissionGranted && s.info?.security == "lab-plain")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Enable automatic refresh", Modifier.weight(1f))
        Switch(enabled, { enabled = it; if (!it) chargeLimit = false; dirty = true }, enabled = editable, modifier = Modifier.testTag("auto-refresh-enabled"))
    }
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    OutlinedTextField(standby, { standby = it.filter(Char::isDigit).take(5); dirty = true; error = null },
        label = { Text("When not charging (min)") }, keyboardOptions = keyboard, singleLine = true, enabled = editable,
        modifier = Modifier.fillMaxWidth().testTag("auto-refresh-standby"))
    OutlinedTextField(charging, { charging = it.filter(Char::isDigit).take(5); dirty = true; error = null },
        label = { Text("While charging (min)") }, keyboardOptions = keyboard, singleLine = true, enabled = editable,
        modifier = Modifier.fillMaxWidth().testTag("auto-refresh-charging"))
    OutlinedTextField(soc, { soc = it.filter(Char::isDigit).take(3); dirty = true; error = null },
        label = { Text("Minimum 12 V battery level (%)") }, keyboardOptions = keyboard, singleLine = true, enabled = editable,
        modifier = Modifier.fillMaxWidth().testTag("auto-refresh-soc"))
    Text("Keep battery and charging information up to date, even when the app is closed. Choose how often to update when the car is not charging and while it is charging. Updates pause if the 12 V battery falls below your chosen level. If its level is unknown, updates are still allowed.", style = MaterialTheme.typography.bodySmall)
    if ("charge_limit" in (s.info?.fields ?: emptySet())) {
        HorizontalDivider(Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Charge limit", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(chargeLimit, { chargeLimit = it; dirty = true }, enabled = editable && enabled && status?.capable == true,
                modifier = Modifier.testTag("charge-limit-enabled"))
        }
        Text("Requires automatic refresh. Choose a target of 30–100% in Charging. Each time updated vehicle data arrives, the gateway checks the battery level and uses delayed charging to pause at your target. The car can resume charging at its scheduled start time.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("charge-limit-help"))
    }
    (error ?: s.autoRefreshError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("auto-refresh-error")) }
    if (!dirty && s.autoRefreshSavedRevision != null && s.autoRefreshError == null)
        Text("Saved on gateway.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("auto-refresh-saved"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { status?.let { load(it.settings) }; onReload() }, enabled = editable,
            modifier = Modifier.weight(1f).testTag("auto-refresh-reload")) { Text("Reload") }
        Button(onClick = {
            val draft = AutoRefreshSettings(revision, enabled, standby.toIntOrNull() ?: 0, charging.toIntOrNull() ?: 0, soc.toIntOrNull() ?: -1, chargeLimit, chargeLimitSoc)
            if (!draft.valid()) error = "Periods must be 1–1440 minutes; minimum 12 V SOC must be 0–100%."
            else { submitted = true; error = null; onSave(draft) }
        }, enabled = editable && dirty, modifier = Modifier.weight(1f).testTag("auto-refresh-apply")) {
            if (s.autoRefreshSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Apply")
        }
    }
}

@Composable
internal fun ChargeLimitContent(s: PilotState, onSave: (AutoRefreshSettings) -> Unit, onReload: () -> Unit) {
    val status = s.autoRefresh ?: return
    val settings = status.settings
    if (!settings.enabled || !settings.chargeLimitEnabled) return
    var target by remember(s.gatewayId, s.boot, settings.chargeLimitSocPct) { mutableFloatStateOf(settings.chargeLimitSocPct.toFloat()) }
    HorizontalDivider(Modifier.padding(top = 10.dp))
    Text("Charge limit · ${target.roundToInt()}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("charge-limit-target"))
    Slider(value = target, onValueChange = { target = it }, valueRange = 30f..100f, steps = 69,
        onValueChangeFinished = { if (target.roundToInt() != settings.chargeLimitSocPct) onSave(settings.copy(chargeLimitSocPct = target.roundToInt())) },
        enabled = s.connected && !s.autoRefreshSaving && !s.lowPowerSaving && s.command?.pending != true,
        modifier = Modifier.fillMaxWidth().testTag("charge-limit-slider"))
    if (s.autoRefreshSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(status.chargeLimitDescription, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("charge-limit-status"))
    s.autoRefreshError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onReload, enabled = !s.autoRefreshSaving) { Text("Reload gateway settings") }
    }
}
