package cz.ecmpilot

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import cz.ecmpilot.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** In-process demo only: never enables automatic requests on the real gateway. */
class AutoRefreshUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repository get() = (ui.activity.application as PilotApplication).repository
    @Test fun saveAndReloadGatewayTimerWithoutARefreshOrVehicleActionPopup() {
        val original = repository.state.value.settings
        try {
            ui.runOnUiThread {
                ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                repository.saveSettings(Settings(scene = DemoScene.ASLEEP))
            }
            ui.waitUntil(15000) { repository.state.value.autoRefresh != null && repository.state.value.connected }
            ui.onNodeWithTag("settings").performScrollTo().performClick()
            ui.onNodeWithTag("auto-refresh-enabled").assertDoesNotExist()
            ui.onNodeWithText("Close").performScrollTo().performClick()
            ui.onNodeWithTag("menu-gateway").performScrollTo().performClick()
            ui.onNodeWithTag("charge-limit-enabled").performScrollTo().assertIsNotEnabled()
            ui.onNodeWithTag("auto-refresh-standby").performScrollTo().performTextReplacement("15")
            ui.onNodeWithTag("auto-refresh-charging").performScrollTo().performTextReplacement("2")
            ui.onNodeWithTag("auto-refresh-soc").performScrollTo().performTextReplacement("55")
            ui.onNodeWithTag("auto-refresh-enabled").performScrollTo().performClick()
            ui.onNodeWithTag("charge-limit-enabled").performScrollTo().assertIsEnabled().performClick()
            ui.onNodeWithTag("charge-limit-help").assertExists()
            ui.onNodeWithTag("auto-refresh-apply").performScrollTo().performClick()
            ui.waitUntil(15000) { repository.state.value.autoRefreshSavedRevision == 2L && !repository.state.value.autoRefreshSaving }
            assertEquals(AutoRefreshSettings(2, true, 15, 2, 55, true, 80), repository.state.value.autoRefresh!!.settings)
            assertNull(repository.state.value.command)
            assertEquals(false, repository.state.value.vehicle!!.awake)
            ui.onNodeWithTag("vehicle-action-error").assertDoesNotExist()
            ui.onNodeWithTag("auto-refresh-standby").performScrollTo().performTextReplacement("23")
            ui.onNodeWithTag("auto-refresh-reload").performScrollTo().performClick()
            ui.onNodeWithTag("auto-refresh-standby").performScrollTo().assertTextContains("15")
            ui.onNodeWithText("Close").performScrollTo().performClick()
            ui.onNodeWithTag("charging-status").performScrollTo().performClick()
            ui.onNodeWithTag("charge-limit-help").assertDoesNotExist()
            assertEquals(30f..100f, ui.onNodeWithTag("charge-limit-slider").performScrollTo().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range)
            for (target in listOf(30,100)) {
                ui.onNodeWithTag("charge-limit-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(target.toFloat()) }
                ui.waitUntil(15000) { repository.state.value.autoRefresh?.settings?.chargeLimitSocPct == target && !repository.state.value.autoRefreshSaving }
            }
            ui.onNodeWithTag("charge-limit-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(85f) }
            ui.waitUntil(15000) { repository.state.value.autoRefresh?.settings?.chargeLimitSocPct == 85 && !repository.state.value.autoRefreshSaving }
            assertTrue(repository.state.value.autoRefresh!!.settings.chargeLimitEnabled)
            assertNull(repository.state.value.command)
            ui.onNodeWithText("Close").performScrollTo().performClick()
            ui.onNodeWithTag("menu-gateway").performScrollTo().performClick()
            ui.onNodeWithTag("auto-refresh-enabled").performScrollTo().performClick()
            ui.onNodeWithTag("charge-limit-enabled").performScrollTo().assertIsOff().assertIsNotEnabled()
            ui.onNodeWithTag("auto-refresh-apply").performScrollTo().performClick()
            ui.waitUntil(15000) { repository.state.value.autoRefresh?.settings?.enabled == false && !repository.state.value.autoRefreshSaving }
            ui.onNodeWithText("Close").performScrollTo().performClick()
            ui.onNodeWithTag("home-climate").performScrollTo().performClick()
            ui.onNodeWithTag("preconditioning-outside").assertExists()
            ui.onNodeWithText("Front left setpoint").assertDoesNotExist()
            ui.onNodeWithText("Front right setpoint").assertDoesNotExist()
        } finally {
            ui.waitUntil(15000) { !repository.state.value.autoRefreshSaving }
            ui.runOnUiThread { repository.saveSettings(original) }
        }
    }
}
