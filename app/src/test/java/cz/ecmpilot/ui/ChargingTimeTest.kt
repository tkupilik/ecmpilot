package cz.ecmpilot.ui

import cz.ecmpilot.model.Charge
import cz.ecmpilot.model.Stamp
import org.junit.Assert.*
import org.junit.Test

class ChargingTimeTest {
    private fun text(minutes: Double?, state: String = "charging") =
        Charge(state = state, remainingMin = minutes, stamp = Stamp(0, 0)).remainingTimeText()

    @Test fun formatsReportedHoursAndMinutes() {
        assertEquals("5 min", text(5.0))
        assertEquals("1 h", text(60.0))
        assertEquals("2 h 15 min", text(135.0))
        assertEquals("10 h 30 min", text(630.0))
    }

    @Test fun missingZeroInvalidAndNonChargingEstimatesAreUnavailable() {
        for (value in listOf(null, 0.0, -1.0, 635.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(text(value))
        for (state in listOf("waiting", "complete", "disconnected", "unknown"))
            assertNull(text(135.0, state))
    }
}
