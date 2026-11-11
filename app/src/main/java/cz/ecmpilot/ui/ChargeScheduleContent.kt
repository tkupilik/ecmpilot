package cz.ecmpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.ecmpilot.model.*

@Composable
internal fun ChargeScheduleContent(s: PilotState, onSave: (ChargeScheduleSettings) -> Unit, onReload: () -> Unit) {
    if ("charge_schedule" !in (s.info?.fields ?: emptySet())) return
    val status=s.chargeSchedule
    var baseline by remember(s.gatewayId,s.boot) { mutableStateOf<ChargeScheduleSettings?>(null) }
    var enabled by remember(s.gatewayId,s.boot) { mutableStateOf(false) }
    var slots by remember(s.gatewayId,s.boot) { mutableStateOf(emptyList<ChargePause>()) }
    var dirty by remember(s.gatewayId,s.boot) { mutableStateOf(false) }
    var submitted by remember(s.gatewayId,s.boot) { mutableStateOf(false) }
    var error by remember(s.gatewayId,s.boot) { mutableStateOf<String?>(null) }
    fun load(settings: ChargeScheduleSettings) { baseline=settings;enabled=settings.enabled;slots=settings.intervals;dirty=false;submitted=false;error=null }
    LaunchedEffect(status,s.chargeScheduleSaving,s.chargeScheduleSavedRevision) {
        if (status!=null && (!dirty || submitted && !s.chargeScheduleSaving && s.chargeScheduleError==null && s.chargeScheduleSavedRevision==status.settings.revision)) load(status.settings)
    }
    val editable=baseline!=null && s.connected && !s.chargeScheduleSaving && !s.autoRefreshSaving && !s.lowPowerSaving && s.command?.pending!=true &&
        (s.settings.source!=Source.UDP || s.permissionGranted && s.info?.security=="lab-plain")
    HorizontalDivider(Modifier.padding(top=12.dp))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("Schedules",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
        Switch(enabled,{
            enabled=it
            if(it && slots.isEmpty()) slots=listOf(ChargePause())
            dirty=true;error=null
        },enabled=editable,colors=scheduleSwitchColors(),modifier=Modifier.testTag("charge-schedule-enabled"))
    }
    Text("Pause charging during your high-tariff periods. Times follow the clock in your car.",style=MaterialTheme.typography.bodySmall)
    Text(status?.description ?: "Reading gateway settings…",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("charge-schedule-status"))
    if(enabled) {
        Text("High-tariff periods",fontWeight=FontWeight.Medium)
        slots.forEachIndexed { index,slot ->
            fun change(value: ChargePause) { slots=slots.toMutableList().also {it[index]=value};dirty=true;error=null }
            Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Period ${index+1}",Modifier.weight(1f),fontWeight=FontWeight.Medium)
                        TextButton(onClick={slots=slots.filterIndexed {i,_->i!=index};dirty=true},enabled=editable,modifier=Modifier.testTag("charge-period-${index+1}-remove")) {Text("Remove")}
                        Switch(slot.enabled,{change(slot.copy(enabled=it))},enabled=editable,colors=scheduleSwitchColors(),modifier=Modifier.testTag("charge-period-${index+1}-enabled"))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(slot.from,{change(slot.copy(from=it.take(5)))},label={Text("From")},singleLine=true,
                            isError=!ChargePause.TIME.matches(slot.from),enabled=editable,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),
                            modifier=Modifier.weight(1f).testTag("charge-period-${index+1}-from"))
                        OutlinedTextField(slot.to,{change(slot.copy(to=it.take(5)))},label={Text("To")},singleLine=true,
                            isError=!ChargePause.TIME.matches(slot.to)||slot.from==slot.to,enabled=editable,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),
                            modifier=Modifier.weight(1f).testTag("charge-period-${index+1}-to"))
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun").forEachIndexed {day,name->
                            val bit=1 shl(6-day)
                            FilterChip(selected=slot.days and bit!=0,onClick={change(slot.copy(days=slot.days xor bit))},
                                label={Text(name.take(1))},enabled=editable,
                                modifier=Modifier.weight(1f).semantics {contentDescription=name}.testTag("charge-period-${index+1}-$name"))
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick={slots=slots+ChargePause();dirty=true},enabled=editable && slots.size<8,modifier=Modifier.testTag("charge-schedule-add")) {Text("Add period")}
    }
    (error ?: s.chargeScheduleError)?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("charge-schedule-error"))}
    if(!dirty && s.chargeScheduleSavedRevision!=null && s.chargeScheduleError==null) Text("Saved on gateway.",style=MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick={status?.let {load(it.settings)};onReload()},enabled=editable,modifier=Modifier.weight(1f).testTag("charge-schedule-reload")) {Text("Reload")}
        Button(onClick={
            val draft=ChargeScheduleSettings(baseline?.revision ?: 1,enabled,slots)
            if(!draft.valid()) error="Choose different From and To times and at least one day for each period."
            else {submitted=true;error=null;onSave(draft)}
        },enabled=editable && dirty,modifier=Modifier.weight(1f).testTag("charge-schedule-apply")) {
            if(s.chargeScheduleSaving)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("Apply")
        }
    }
}
