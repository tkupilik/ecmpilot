package cz.ecmpilot.ui

import cz.ecmpilot.model.Reading
import cz.ecmpilot.model.Stamp

// Presentation policy only; vehicle-control prerequisites use their own limits.
private const val DISPLAY_MAX_AGE_MS = 5 * 60 * 1000L

internal fun Stamp.freshForDisplay(nowMs: Long) = fresh(nowMs, DISPLAY_MAX_AGE_MS)
internal fun Reading.freshForDisplay(nowMs: Long) = value != null && stamp.freshForDisplay(nowMs)
