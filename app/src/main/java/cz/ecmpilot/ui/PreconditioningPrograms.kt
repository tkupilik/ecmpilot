package cz.ecmpilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.ecmpilot.model.*

private val timePattern = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")

@Composable
internal fun PreconditioningProgramsEditor(s: PilotState, onApply: (DesiredCommand.ClimateProgramsSet) -> Unit) {
    // A poll never replaces an in-progress edit. The original image accompanies
    // Apply so the GW can reject an intervening edit made through MyOpel.
    var baseline by remember(s.gatewayId, s.boot) { mutableStateOf<ClimatePrograms?>(null) }
    var slots by remember(s.gatewayId, s.boot) { mutableStateOf(emptyList<ClimateProgram>()) }
    var times by remember(s.gatewayId, s.boot) { mutableStateOf(emptyList<String>()) }
    var editing by remember(s.gatewayId, s.boot) { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(s.programs) {
        if (baseline == null && s.programs?.complete == true) {
            baseline = s.programs; slots = s.programs.slots; times = slots.map { it.time ?: "" }
        }
    }
    val supported = s.info?.climatePrograms() == true
    val editable = supported && baseline?.complete == true && s.command?.pending != true
    val valid = slots.size == 4 && slots.indices.all { i ->
        timePattern.matches(times[i]) || times[i].isEmpty() && slots[i].days == 0 && !slots[i].enabled
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Schedules", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Vehicle local time", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = slots.any { it.enabled }, onCheckedChange = { enabled ->
            slots = slots.map { it.withEnabled(enabled) }
            editing = emptySet()
        }, enabled = editable && slots.any { it.configured }, colors = scheduleSwitchColors(),
            modifier = Modifier.testTag("preconditioning-schedules"))
    }
    when {
        !supported -> Text("Schedule control needs updated gateway firmware.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        baseline == null -> Text("No vehicle schedule data yet. Refresh the vehicle to obtain its stored plans.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            Text("Switch on to enable all configured plans; off disables them and keeps their times and days. Apply saves to the vehicle.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            slots.forEachIndexed { index, slot ->
                val current = s.programs?.slots?.getOrNull(index)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).alpha(if (s.connected && current?.stamp?.freshForDisplay(s.nowMs) == true) 1f else .5f)) {
                                Text("Plan ${index + 1}", fontWeight = FontWeight.Medium)
                                if (!slot.enabled && index !in editing) Text(
                                    if (slot.configured) "${slot.time} · " + listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                                        .filterIndexed { day, _ -> slot.days and (1 shl (6 - day)) != 0 }.joinToString(" ") else "Not configured",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!slot.enabled) TextButton(onClick = { editing = if (index in editing) editing - index else editing + index },
                                enabled = editable, modifier = Modifier.testTag("plan-${index + 1}-edit")) {
                                Text(if (index in editing) "Done" else if (slot.configured) "Edit" else "Set up")
                            }
                            Switch(slot.enabled, { enabled ->
                                slots = slots.toMutableList().also { it[index] = slot.withEnabled(enabled) }
                                editing = editing - index
                            },
                                enabled = editable && slot.configured, colors = scheduleSwitchColors(), modifier = Modifier.testTag("plan-${index + 1}-enabled"))
                        }
                        if (slot.enabled || index in editing) {
                            OutlinedTextField(times[index], { text ->
                                val value = text.take(5); times = times.toMutableList().also { it[index] = value }
                                if (timePattern.matches(value)) {
                                    val minute = value.take(2).toInt() * 60 + value.takeLast(2).toInt()
                                    slots = slots.toMutableList().also { it[index] = slot.withMinute(minute) }
                                }
                            }, label = { Text("Time (HH:mm)") }, placeholder = { Text("Not configured") },
                                singleLine = true, enabled = editable, isError = times[index].isNotEmpty() && !timePattern.matches(times[index]),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth().testTag("plan-${index + 1}-time"))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { day, name ->
                                    val bit = 1 shl (6 - day)
                                    FilterChip(selected = slot.days and bit != 0, onClick = {
                                        val changed = slot.withDays(slot.days xor bit)
                                        slots = slots.toMutableList().also { it[index] = if (changed.configured) changed else changed.withEnabled(false) }
                                    }, label = { Text(name.take(1), fontSize = 12.sp) }, enabled = editable,
                                        modifier = Modifier.weight(1f).semantics { contentDescription = name }.testTag("plan-${index + 1}-$name"))
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick = {
                val original = baseline?.slots?.mapNotNull { it.raw } ?: return@Button
                onApply(DesiredCommand.ClimateProgramsSet(original, slots.mapNotNull { it.raw }))
            }, enabled = editable && valid && s.controlReady("climate") && slots.map { it.raw } != baseline?.slots?.map { it.raw },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("apply-preconditioning-schedules")) { Text("Apply schedules") }
        }
    }
}

@Composable
internal fun scheduleSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Color(0xFF438AD4), checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = Color.Transparent, uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledCheckedTrackColor = Color(0xFF438AD4).copy(alpha = .38f), disabledCheckedThumbColor = Color.White.copy(alpha = .38f),
    disabledCheckedBorderColor = Color.Transparent, disabledUncheckedTrackColor = Color.Transparent,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = .38f))
