package cz.ecmpilot

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cz.ecmpilot.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Demo transport only; no CAN commands or UDP requests to the vehicle. */
class VehicleActionFeedbackTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repository get() = (ui.activity.application as PilotApplication).repository
    @Before fun keepScreenOnDuringActions() {
        ui.runOnUiThread { ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private fun refresh() = ui.onNodeWithTag("refresh-vehicle").performScrollTo().performClick()
    private fun precondition() {
        ui.onNodeWithTag("home-climate").performScrollTo().performClick()
        ui.onNodeWithTag("confirm-climate").performScrollTo().performClick()
    }
    private fun charge() {
        ui.onNodeWithTag("charging-status").performScrollTo().performClick()
        ui.onNodeWithTag("deferred-charging").performScrollTo().performClick()
        ui.onNodeWithTag("apply-charge").performScrollTo().performClick()
    }
    private fun programs() {
        ui.onNodeWithTag("home-climate").performScrollTo().performClick()
        ui.onNodeWithTag("preconditioning-schedules").performScrollTo().performClick()
        ui.onNodeWithTag("apply-preconditioning-schedules").performScrollTo().performClick()
    }
    private fun ready() = ui.waitUntil(15000) {
        repository.state.value.connected && repository.state.value.controlReady("refresh") && repository.state.value.programs?.complete==true
    }

    @Test fun changingStartTimeWithDelayedChargingOffSavesTheTime() {
        val original = repository.state.value.settings
        try {
            ui.runOnUiThread { repository.saveSettings(Settings(scene = DemoScene.CHARGING)) }
            ready()
            ui.onNodeWithTag("charging-status").performScrollTo().performClick()
            ui.onNodeWithTag("deferred-charging").assertIsOff()
            ui.onNodeWithTag("charge-start-time").performScrollTo().performTextReplacement("03:17")
            ui.onNodeWithTag("apply-charge").performScrollTo().performClick()
            ui.waitUntil(15000) { repository.state.value.command?.phase == "succeeded" }
            ui.onNodeWithTag("charging-status").performScrollTo().performClick()
            ui.onNodeWithTag("deferred-charging").assertIsOff()
            ui.onNodeWithTag("charge-start-time").performScrollTo().assertTextContains("03:17")
            ui.onNodeWithText("Close").performClick()
        } finally {
            ui.waitUntil(15000) { repository.state.value.command?.pending != true }
            ui.runOnUiThread { repository.saveSettings(original) }
        }
    }
    private fun assertSilentSuccess(action: () -> Unit) {
        action()
        ui.onNodeWithTag("refresh-vehicle").performScrollTo()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("vehicle-action-progress").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("refresh-vehicle").assertIsNotEnabled()
        ui.onNodeWithTag("command-result").assertDoesNotExist()
        ui.waitUntil(15000) { repository.state.value.command?.phase=="succeeded" }
        ui.onNodeWithTag("vehicle-action-progress").assertDoesNotExist()
        ui.onNodeWithTag("vehicle-action-error").assertDoesNotExist()
        ui.onNodeWithTag("command-result").assertDoesNotExist()
        ui.onNodeWithTag("refresh-vehicle").assertIsEnabled()
    }

    @Test fun allVehicleActionsUseSpinnerSilentSuccessAndDescriptiveErrorPopup() {
        val original=repository.state.value.settings
        try {
            ui.runOnUiThread { repository.saveSettings(Settings(scene=DemoScene.CHARGING)) }
            ready()
            for (action in listOf(::refresh,::charge,::precondition,::programs)) assertSilentSuccess { action() }
            ui.onNodeWithTag("menu-details").performScrollTo().performClick()
            ui.onNodeWithText("SOC", substring=false).assertExists()
            ui.onNodeWithText("Driver SOC").assertDoesNotExist()
            ui.onNodeWithText("Close").performScrollTo().performClick()

            for ((scene,action) in listOf(DemoScene.REJECT to ::refresh,DemoScene.REJECT to ::charge,
                DemoScene.REJECT to ::precondition,DemoScene.UNCONFIRMED to ::refresh)) {
                ui.runOnUiThread { repository.saveSettings(Settings(scene=scene)) }
                ready()
                action()
                ui.waitUntil(15000) { repository.state.value.command?.hasError==true }
                ui.onNodeWithTag("vehicle-action-error").assertExists()
                ui.onNodeWithText(repository.state.value.command!!.message).assertExists()
                ui.onNodeWithTag("vehicle-action-progress").assertDoesNotExist()
                ui.onNodeWithTag("command-result").assertDoesNotExist()
                ui.onNodeWithText("Close").performClick()
                ui.onNodeWithTag("vehicle-action-error").assertDoesNotExist()
            }
        } finally {
            ui.waitUntil(15000) { repository.state.value.command?.pending!=true }
            ui.runOnUiThread { repository.saveSettings(original) }
        }
    }
}
