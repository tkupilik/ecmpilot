package cz.ecmpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.ecmpilot.model.*

@Composable
internal fun LowPowerContent(s: PilotState, onSave: (LowPowerSettings) -> Unit, onReload: () -> Unit) {
    val status = s.lowPower
    var enabled by remember(s.gatewayId, s.boot) { mutableStateOf(true) }
    var hours by remember(s.gatewayId, s.boot) { mutableStateOf("48") }
    var revision by remember(s.gatewayId, s.boot) { mutableLongStateOf(1) }
    var dirty by remember(s.gatewayId, s.boot) { mutableStateOf(false) }
    var submitted by remember(s.gatewayId, s.boot) { mutableStateOf(false) }
    var error by remember(s.gatewayId, s.boot) { mutableStateOf<String?>(null) }
    fun load(value: LowPowerSettings) {
        enabled=value.enabled; hours=value.idleHours.toString(); revision=value.revision; dirty=false; error=null
    }
    LaunchedEffect(status?.settings, s.lowPowerSaving, s.lowPowerSavedRevision, s.lowPowerError) {
        if(status!=null && (!dirty || submitted && !s.lowPowerSaving && s.lowPowerError==null && s.lowPowerSavedRevision==status.settings.revision)) load(status.settings)
        if(submitted && !s.lowPowerSaving) submitted=false
    }
    Text("Automatic deep sleep", style=MaterialTheme.typography.titleMedium)
    if("low_power" !in (s.info?.fields ?: emptySet())) {
        Text("Connect to a gateway with low-power support.", style=MaterialTheme.typography.bodySmall)
        return
    }
    Text(status?.description(s.nowMs) ?: "Reading gateway settings…", modifier=Modifier.testTag("low-power-status"))
    val editable=s.connected && status!=null && !s.lowPowerSaving && !s.autoRefreshSaving && !s.chargeScheduleSaving && s.command?.pending!=true &&
        (s.settings.source!=Source.UDP || s.connectionPermitted && s.info?.security=="lab-plain")
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("Sleep when the vehicle is inactive",Modifier.weight(1f))
        Switch(enabled,{ enabled=it; dirty=true },enabled=editable,modifier=Modifier.testTag("low-power-enabled"))
    }
    OutlinedTextField(hours,{ hours=it.filter(Char::isDigit).take(3); dirty=true; error=null },
        label={Text("Sleep after (hours)")},singleLine=true,enabled=editable,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("low-power-hours"))
    Text("After this long without a message from the car, the gateway turns off Wi-Fi and sleeps. CAN activity wakes it. It stays awake until it has received vehicle data at least once. Vehicle refreshes restart this period; when low 12 V battery pauses refreshes, the countdown can expire.",style=MaterialTheme.typography.bodySmall)
    (error ?: s.lowPowerError)?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("low-power-error"))}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton({status?.let { load(it.settings) };onReload()},enabled=editable,modifier=Modifier.weight(1f).testTag("low-power-reload")){Text("Reload")}
        Button({
            val draft=LowPowerSettings(revision,enabled,hours.toIntOrNull() ?: 0)
            if(!draft.valid())error="Choose 1–720 whole hours."
            else { submitted=true;error=null;onSave(draft) }
        },enabled=editable&&dirty,modifier=Modifier.weight(1f).testTag("low-power-apply")) {
            if(s.lowPowerSaving)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("Apply")
        }
    }
}
