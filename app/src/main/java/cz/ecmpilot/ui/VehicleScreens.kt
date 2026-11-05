package cz.ecmpilot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.ecmpilot.model.*
import cz.ecmpilot.R
import java.util.Locale

private val chargingGreen = Color(0xFF37885B)
private val delayedBlue = Color(0xFF468DDA)
private val openAmber = Color(0xFFD8842B)
private fun fmt(value: Double, decimals: Int = 0) = String.format(Locale.UK, "%.${decimals}f", value)

@Composable
internal fun VehicleOverview(s: PilotState, onClimate: () -> Unit, onCharging: () -> Unit, onBody: () -> Unit) {
    val v = s.vehicle
    val soc = v?.reading("soc_pct"); val range = v?.reading("range_km"); val outside = v?.reading("outside_c")
    val auxSoc = v?.reading("aux_battery_soc_pct")
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).alpha(if (s.connected && soc?.freshForDisplay(s.nowMs) == true) 1f else .5f), verticalAlignment = Alignment.Bottom) {
                    Text(soc?.value?.let { fmt(it) } ?: "—", fontSize = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp)
                    Text(" %", fontSize = 23.sp, modifier = Modifier.padding(bottom = 9.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.alpha(if (s.connected && range?.freshForDisplay(s.nowMs) == true) 1f else .5f)) {
                    Text(range?.value?.let { "${fmt(it)} km" } ?: "—", fontSize = 25.sp, fontWeight = FontWeight.Medium)
                    Text("Estimated range", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SocBar(value = soc?.value,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).alpha(if (s.connected && soc?.freshForDisplay(s.nowMs) == true) 1f else .4f),
                color = when {
                    !s.connected || soc?.freshForDisplay(s.nowMs) != true -> MaterialTheme.colorScheme.onSurfaceVariant
                    v?.charge?.mode == "deferred" && v.charge.modeStamp.freshForDisplay(s.nowMs) -> delayedBlue
                    else -> chargingGreen
                })
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(outside?.value?.let { "Outside · ${fmt(it, 1)} °C" } ?: "Outside · —",
                    modifier = Modifier.weight(1f).padding(end = 12.dp).alpha(if (s.connected && outside?.freshForDisplay(s.nowMs) == true) 1f else .5f),
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("12V SOC " + (auxSoc?.value?.let { "${fmt(it, if (it % 1.0 == 0.0) 0 else 1)}%" } ?: "—"),
                    modifier = Modifier.testTag("aux-battery-soc").alpha(if (s.connected && auxSoc?.freshForDisplay(s.nowMs) == true) 1f else .5f),
                    maxLines = 1, softWrap = false,
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val climateFresh = s.connected && v?.climate?.stamp?.freshForDisplay(s.nowMs) == true
    val running = climateFresh && v?.climate?.state == "on"
    val charge = v?.charge
    val chargeFresh = s.connected && charge?.stamp?.freshForDisplay(s.nowMs) == true
    val scheduleFresh = s.connected && charge?.modeStamp?.freshForDisplay(s.nowMs) == true
    val charging = chargeFresh && charge?.state == "charging"
    val delayed = scheduleFresh && charge?.mode == "deferred" && !(chargeFresh && charge.connected == false)
    val body = s.details?.body
    val bodyFresh = s.connected && body?.lockStamp?.freshForDisplay(s.nowMs) == true
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusTile("Preconditioning", when (v?.climate?.state) { "on" -> "Running"; "off" -> "Off"; else -> "Unavailable" },
            Glyph.FAN, if (running) chargingGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            running, onClimate, Modifier.weight(1f).testTag("home-climate"), !climateFresh)
        StatusTile("Charging", when { charging -> "Charging"; delayed -> "Delayed"; charge?.state == "charging" -> "Charging";
                charge?.connected == false -> "Unplugged"; charge?.state == "complete" -> "Complete";
                charge?.state == "waiting" -> "Waiting"; charge?.state == "fault" -> "Fault"; else -> "Unavailable" },
            Glyph.BOLT, when { charging -> chargingGreen; delayed -> delayedBlue; else -> MaterialTheme.colorScheme.onSurfaceVariant },
            false, onCharging, Modifier.weight(1f).testTag("charging-status"), !chargeFresh && !scheduleFresh)
        StatusTile("Doors & locks", body?.takeIf { it.lockingState in 1..6 }?.lockLabel ?: "Unavailable", if (body?.locked == false && body.lockingState != null) Glyph.UNLOCK else Glyph.LOCK,
            if (bodyFresh && body?.locked == true) chargingGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            false, onBody, Modifier.weight(1f).testTag("vehicle-status"), !bodyFresh)
    }
}

@Composable
internal fun SocBar(value: Double?, color: Color, modifier: Modifier = Modifier) {
    val fraction = ((value ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
    Box(modifier.height(7.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = .16f))
        .progressSemantics(fraction).semantics { contentDescription = value?.let { "Battery ${fmt(it)} percent" } ?: "Battery state unavailable" }) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
    }
}

private enum class Glyph { FAN, BOLT, LOCK, UNLOCK }

@Composable
private fun StatusTile(title: String, value: String, glyph: Glyph, color: Color, running: Boolean,
                       onClick: () -> Unit, modifier: Modifier, stale: Boolean) {
    val angle = if (running) rememberInfiniteTransition(label = "fan").animateFloat(0f, 360f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "fan rotation").value else 0f
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 108.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 14.dp).alpha(if (stale) .5f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphImage(glyph, color, Modifier.size(29.dp).rotate(angle))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 9.dp))
            Text((if (stale && value != "Unavailable") "Last: " else "") + value, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GlyphImage(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24
        when (glyph) {
            Glyph.FAN -> {
                drawCircle(color, 2 * u)
                repeat(3) { i -> rotate(i * 120f) {
                    val blade = Path().apply { moveTo(12*u, 10*u); cubicTo(2*u, 9*u, 6*u, -2*u, 12*u, 3*u); cubicTo(16*u, 5*u, 15*u, 8*u, 14*u, 10*u); close() }
                    drawPath(blade, color)
                } }
            }
            Glyph.BOLT -> drawPath(Path().apply {
                moveTo(14*u, u); lineTo(4*u, 14*u); lineTo(10*u, 14*u); lineTo(9*u, 23*u)
                lineTo(21*u, 9*u); lineTo(14*u, 9*u); close()
            }, color)
            Glyph.LOCK, Glyph.UNLOCK -> {
                drawRoundRect(color, Offset(4*u, 10*u), Size(16*u, 12*u), CornerRadius(2*u))
                drawArc(color, 180f, if (glyph == Glyph.LOCK) 180f else 140f, false,
                    Offset(if (glyph == Glyph.LOCK) 7*u else 12*u, u), Size(10*u, 16*u), style = Stroke(2*u))
                drawCircle(Color.White.copy(alpha = .8f), 1.5f*u, Offset(12*u, 16*u))
            }
        }
    }
}

@Composable
internal fun HomeMenuRow(title: String, subtitle: String, onClick: () -> Unit, tag: String) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BodyContent(s: PilotState) {
    val body = s.details?.body
    val fresh = s.connected && body?.stamp?.freshForDisplay(s.nowMs) == true
    val lockFresh = s.connected && body?.lockStamp?.freshForDisplay(s.nowMs) == true
    Text(body?.lockLabel ?: "Lock state unavailable", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.alpha(if (lockFresh) 1f else .5f).testTag("locking-label"))
    if (!lockFresh && body?.lockingState != null) Text("Last reported lock state", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val car = painterResource(R.drawable.vehicle_top)
    Box(Modifier.fillMaxWidth().height(340.dp).testTag("vehicle-overhead")
        .semantics { contentDescription = "Vehicle viewed from above; front at the top" }) {
        Image(car, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            // Coordinates follow the illustration canvas, so indicators stay aligned on all screen sizes.
            val u = minOf(size.width / car.intrinsicSize.width, size.height / car.intrinsicSize.height)
            val w = car.intrinsicSize.width * u
            val h = car.intrinsicSize.height * u
            val dx = (size.width - w) / 2
            val dy = (size.height - h) / 2
            fun p(x: Float, y: Float) = Offset(dx + x*w, dy + y*h)
            for (i in 0..3) {
                val left = i == 0 || i == 2
                val front = i < 2
                val x = if (left) .292f else .708f
                val hingeY = if (front) .345f else .505f
                val endY = if (front) .49f else .67f
                val open = body?.open(i)
                if (open == true) {
                    val tipX = x + if (left) -.115f else .115f
                    val edge = Path().apply {
                        val hinge = p(x, hingeY); val tip = p(tipX, endY - .025f); val latch = p(x, endY)
                        moveTo(hinge.x, hinge.y); lineTo(tip.x, tip.y); lineTo(latch.x, latch.y); close()
                    }
                    val color = if (fresh) openAmber else outline.copy(alpha = .5f)
                    drawPath(edge, color.copy(alpha = if (fresh) .2f else .1f))
                    drawLine(color, p(x, hingeY), p(tipX, endY - .025f), 3.dp.toPx(), cap = StrokeCap.Round)
                } else if (open == null) {
                    drawCircle(outline.copy(alpha = .45f), 3.dp.toPx(), p(if (left) .24f else .76f, (hingeY + endY) / 2))
                }
            }
            val trunk = body?.open(4)
            if (trunk == true) drawLine(if (fresh) openAmber else outline.copy(alpha = .5f),
                p(.365f, .955f), p(.635f, .955f), 3.dp.toPx(), cap = StrokeCap.Round)
            else if (trunk == null) drawCircle(outline.copy(alpha = .45f), 3.dp.toPx(), p(.5f, .955f))
        }
    }
    if (!fresh && body?.knownMask != null && body.knownMask != 0) Text("Last known doors · ${ageLabel(body.stamp.age(s.nowMs))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf("Front left", "Front right", "Rear left", "Rear right", "Boot").forEachIndexed { i, label ->
        StatusValueRow(label, when (body?.open(i)) { true -> "Open"; false -> "Closed"; else -> "Unavailable" }, fresh && body?.open(i) != null)
    }
}

@Composable
internal fun TripsContent(s: PilotState) {
    val trips = s.details?.trips.orEmpty()
    for (i in 1..2) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Trip $i", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                TripValue("Average consumption", trips["avg$i"], "kWh/100 km", s, 1)
                TripValue("Distance", trips["distance$i"], "km", s)
                TripValue("Average speed", trips["speed$i"], "km/h", s)
            }
        }
    }
    TripValue("Instant consumption", trips["instant"], "kWh/100 km", s, 1)
    Text("Consumption history", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    val history = s.details?.history.orEmpty()
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        repeat(6) { i ->
            val reading = history.getOrNull(i)
            val value = reading?.value
            Column(Modifier.weight(1f).alpha(if (s.connected && reading?.freshForDisplay(s.nowMs) == true) 1f else .5f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value?.let { fmt(it) } ?: "—", fontSize = 12.sp)
                Box(Modifier.padding(top = 6.dp).width(24.dp).height(((value ?: 0.0).coerceIn(0.0, 50.0)*2+2).dp)
                    .background(if (value == null) MaterialTheme.colorScheme.surfaceVariant else chargingGreen, RoundedCornerShape(4.dp)))
                Text("${i+1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text("kWh/100 km", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun TripValue(label: String, reading: Reading?, unit: String, s: PilotState, decimals: Int = 0) {
    Column {
        StatusValueRow(label, reading?.value?.let { "${fmt(it, decimals)} $unit" } ?: "—", s.connected && reading?.freshForDisplay(s.nowMs) == true)
        if (reading?.value != null && (!s.connected || !reading.freshForDisplay(s.nowMs)))
            Text("Last known · ${ageLabel(reading.stamp.age(s.nowMs))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun StatusValueRow(label: String, value: String, fresh: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, modifier = Modifier.alpha(if (fresh) 1f else .5f))
    }
}
private fun ageLabel(age: Long?) = when { age == null -> "age unavailable"; age < 60_000 -> "${age/1000} s ago"; age < 86_400_000 -> "${age/60_000} min ago"; else -> "over a day ago" }
