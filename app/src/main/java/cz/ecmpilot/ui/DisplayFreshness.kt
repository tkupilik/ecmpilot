package cz.ecmpilot.ui

import cz.ecmpilot.model.Reading
import cz.ecmpilot.model.Stamp
import cz.ecmpilot.model.Charge

// Presentation policy only; vehicle-control prerequisites use their own limits.
private const val DISPLAY_MAX_AGE_MS = 5 * 60 * 1000L

internal fun Stamp.freshForDisplay(nowMs: Long) = fresh(nowMs, DISPLAY_MAX_AGE_MS)
internal fun Reading.freshForDisplay(nowMs: Long) = value != null && stamp.freshForDisplay(nowMs)

// A reported estimate, not a countdown or evidence that charging has completed.
internal fun Charge.remainingTimeText(): String? {
    val minutes = remainingMin?.takeIf { state == "charging" && it.isFinite() && it >= 1 && it <= 630 }?.toInt() ?: return null
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}
