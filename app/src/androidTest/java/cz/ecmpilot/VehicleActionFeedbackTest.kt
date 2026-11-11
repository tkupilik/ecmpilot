package cz.ecmpilot

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cz.ecmpilot.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*

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
        if (repository.state.value.settings.scene != DemoScene.REJECT)
            ui.onNodeWithText("Close").performScrollTo().performClick()
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
            ui.onNodeWithTag("apply-charge").assertDoesNotExist()
            ui.onNodeWithTag("deferred-charging").assertIsOff()
            assertNull(repository.state.value.command)
            for (invalid in listOf("03:", "27:00")) {
                ui.onNodeWithTag("charge-start-time").performScrollTo().performTextReplacement(invalid)
                ui.mainClock.advanceTimeBy(1200)
                ui.waitForIdle()
                assertNull(repository.state.value.command)
            }
            ui.onNodeWithTag("charge-start-time").performScrollTo().performTextReplacement("03:17")
            ui.waitUntil(15000) { repository.state.value.command?.phase == "succeeded" }
            ui.onNodeWithTag("deferred-charging").assertIsOff()
            ui.onNodeWithTag("charge-start-time").performScrollTo().assertTextContains("03:17", substring = true)
            assertEquals("03:17", repository.state.value.vehicle?.charge?.startLocal)
            ui.onNodeWithTag("charge-mode-progress").assertDoesNotExist()
            // A passive update must not submit the same command again.
            val rev = repository.state.value.vehicle!!.charge.rev
            ui.mainClock.advanceTimeBy(1200)
            ui.waitForIdle()
            assertEquals(rev, repository.state.value.vehicle!!.charge.rev)
            ui.onNodeWithTag("deferred-charging").performScrollTo().performClick()
            ui.waitUntil(15000) { repository.state.value.command?.phase == "succeeded" && repository.state.value.vehicle?.charge?.mode == "deferred" }
            ui.onNodeWithTag("deferred-charging").assertIsOn()
            assertEquals("03:17", repository.state.value.vehicle?.charge?.startLocal)
            ui.onNodeWithTag("apply-charge").assertDoesNotExist()
            ui.onNodeWithText("Close").performScrollTo().performClick()
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
                ui.onNodeWithTag("vehicle-action-error-close").performClick()
                ui.onNodeWithTag("vehicle-action-error").assertDoesNotExist()
                if (ui.onAllNodesWithTag("dialog-sheet").fetchSemanticsNodes().isNotEmpty())
                    ui.onNodeWithText("Close").performScrollTo().performClick()
            }
        } finally {
            ui.waitUntil(15000) { repository.state.value.command?.pending!=true }
            ui.runOnUiThread { repository.saveSettings(original) }
        }
    }
}
