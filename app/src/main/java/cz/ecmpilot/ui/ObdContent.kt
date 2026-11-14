package cz.ecmpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.ecmpilot.model.*
import java.util.Locale

@Composable
internal fun ObdAdapterContent(s: PilotState, onScan: () -> Unit, onSave: (ObdAdapter) -> Unit, onReload: () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 10.dp))
    Text("OBD adapter", fontWeight = FontWeight.SemiBold)
    if ("elm327" !in (s.info?.fields ?: emptySet())) {
        Text("Requires gateway firmware with Bluetooth OBD support.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val status = s.elm
    var selected by remember(s.gatewayId, s.boot, status?.revision) { mutableStateOf(status?.adapter) }
    val editable = s.connected && status != null && !status.busy && !s.elmPending &&
        (s.settings.source != Source.UDP || s.connectionPermitted && s.info?.security == "lab-plain")
    Text("Saved adapter: ${status?.adapter?.address ?: "None"}", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("obd-saved-adapter"))
    Text("The gateway reads OBD data after a vehicle refresh. Supports compatible Bluetooth Low Energy adapters; disconnect other OBD apps before use.",
        style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onScan, enabled = editable, modifier = Modifier.weight(1f).testTag("obd-scan")) { Text("Find adapters") }
        TextButton(onClick = onReload, enabled = s.connected && !s.elmPending, modifier = Modifier.testTag("obd-reload")) { Text("Reload") }
    }
    if (s.elmPending || status?.busy == true) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when { s.elmPending -> "Sending request…"; status?.scanning == true -> "Searching for adapters…"; else -> "Reading OBD data…" },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("obd-progress"))
    } else if (status?.operation == 1 && status.generation == status.completed && status.result == 2 && status.adapters.isEmpty()) {
        Text("No compatible adapters found. Check adapter power and move closer.", style = MaterialTheme.typography.bodySmall)
    }
    if (status?.scanning != true) status?.adapters?.forEach { adapter ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected?.address == adapter.address && selected?.addressType == adapter.addressType,
                onClick = { selected = adapter }, enabled = editable, modifier = Modifier.testTag("obd-adapter-${adapter.address}"))
            Column(Modifier.weight(1f)) {
                Text(adapter.address)
                Text("${adapter.rssi ?: "-"} dBm", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Button(onClick = { selected?.let(onSave) }, enabled = editable && selected != null &&
        (selected?.address != status?.adapter?.address || selected?.addressType != status?.adapter?.addressType),
        modifier = Modifier.fillMaxWidth().testTag("obd-save")) { Text("Save adapter") }
    val scanError = status?.takeIf { it.operation == 1 && !it.busy }?.let { elmScanError(it.result) }
    (s.elmError ?: scanError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("obd-error")) }
    s.elmMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("obd-saved")) }
}

@Composable
internal fun ObdDetailsContent(s: PilotState) {
    if ("obd" !in (s.info?.fields ?: emptySet()) && s.obd.isEmpty()) return
    HorizontalDivider()
    Text("HSCAN / OBD", fontWeight = FontWeight.SemiBold)
    ObdFields.all.groupBy { it.group }.forEach { (group, fields) ->
        Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        fields.forEach { field ->
            val reading = s.obd[field.key]
            val text = reading?.value?.let { value ->
                if (field.key == "chiller_request") { if (value == 1.0) "On" else "Off" }
                else String.format(Locale.UK,"%.${field.decimals}f",value) + " " + field.unit
            } ?: "-"
            Row(Modifier.fillMaxWidth().testTag("obd-value-${field.key}")
                .alpha(if (s.connected && reading?.freshForDisplay(s.nowMs) == true) 1f else .5f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
