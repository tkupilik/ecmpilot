package cz.ecmpilot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cz.ecmpilot.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Demo transport only; never changes power settings on a real gateway. */
class LowPowerUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repository get()=(ui.activity.application as PilotApplication).repository
    @Test fun scrolledGatewaySettingsShowCountdownAndSaveWithoutVehicleAction() {
        val original=repository.state.value.settings
        try {
            ui.runOnUiThread {repository.saveSettings(Settings(scene=DemoScene.CHARGING))}
            ui.waitUntil(15000){repository.state.value.connected && repository.state.value.lowPower!=null}
            ui.onNodeWithTag("menu-gateway").performScrollTo().performClick()
            ui.onNodeWithTag("gateway-low-power-tab").assertDoesNotExist()
            ui.onNodeWithTag("charge-limit-enabled").performScrollTo().assertExists()
            ui.onNodeWithTag("low-power-enabled").performScrollTo().assertIsOn()
            ui.onNodeWithTag("low-power-hours").performScrollTo().assertTextContains("48")
            ui.onNodeWithTag("low-power-status").performScrollTo().assertTextContains("Sleeping in")
            ui.onNodeWithTag("low-power-hours").performScrollTo().performTextReplacement("72")
            ui.onNodeWithTag("low-power-apply").performScrollTo().performClick()
            ui.waitUntil(15000){repository.state.value.lowPower?.settings?.idleHours==72 && !repository.state.value.lowPowerSaving}
            assertNull(repository.state.value.command)
            ui.onNodeWithTag("low-power-enabled").performScrollTo().performClick()
            ui.onNodeWithTag("low-power-apply").performScrollTo().performClick()
            ui.waitUntil(15000){repository.state.value.lowPower?.settings?.enabled==false && !repository.state.value.lowPowerSaving}
            ui.onNodeWithTag("low-power-status").performScrollTo().assertTextContains("off")
            ui.onNodeWithText("Close").performScrollTo().performClick()
        } finally { ui.runOnUiThread {repository.saveSettings(original)} }
    }
}
