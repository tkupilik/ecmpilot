package cz.ecmpilot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cz.ecmpilot.model.*
import org.junit.Rule
import org.junit.Test

/** Demo only: the original connection settings are restored, no CAN action. */
class ChargeScheduleUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repository get()=(ui.activity.application as PilotApplication).repository
    @Test fun editsPeriodsShowsVehicleClockAndCollapsesWhenDisabled() {
        val original=repository.state.value.settings
        try {
            ui.runOnUiThread {repository.saveSettings(Settings(source=Source.DEMO,scene=DemoScene.CHARGING))}
            ui.waitUntil(15000){repository.state.value.chargeSchedule!=null}
            ui.onNodeWithTag("vehicle-time").assertTextContains("Vehicle time · 11.11.2026", substring = true)
            ui.onNodeWithTag("charging-status").performClick()
            ui.onNodeWithTag("charge-schedule-clock").assertDoesNotExist()
            ui.onNodeWithTag("charge-schedule-enabled").performScrollTo().assertIsOff().performClick()
            ui.onNodeWithTag("charge-period-1-from").performScrollTo().performTextReplacement("08:00")
            ui.onNodeWithTag("charge-schedule-add").performScrollTo().performClick()
            ui.onNodeWithTag("charge-period-2-from").performScrollTo().performTextReplacement("17:00")
            ui.onNodeWithTag("charge-period-2-to").performTextReplacement("20:00")
            ui.onNodeWithTag("charge-schedule-apply").performScrollTo().performClick()
            ui.waitUntil(15000){repository.state.value.chargeSchedule?.settings?.intervals?.size==2 && !repository.state.value.chargeScheduleSaving}
            ui.onNodeWithTag("charge-schedule-enabled").performScrollTo().performClick()
            ui.onNodeWithTag("charge-period-1-from").assertDoesNotExist()
            ui.onNodeWithTag("charge-schedule-apply").performScrollTo().performClick()
            ui.waitUntil(15000){repository.state.value.chargeSchedule?.settings?.enabled==false && !repository.state.value.chargeScheduleSaving}
            ui.onNodeWithText("Close").performScrollTo().performClick()
            ui.onNodeWithTag("vehicle-time").assertTextContains("Vehicle time · 11.11.2026", substring = true)
        } finally {ui.runOnUiThread {repository.saveSettings(original)}}
    }
}
