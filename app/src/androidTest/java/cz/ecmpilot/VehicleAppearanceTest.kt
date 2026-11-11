package cz.ecmpilot

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cz.ecmpilot.model.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** In-process demo only. Restores the phone's original connection settings. */
class VehicleAppearanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repository get() = (ui.activity.application as PilotApplication).repository
    private fun screen(name: String) {
        ui.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(ui.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun carAndNeutralThemeAndStoredPlans() {
        val original = repository.state.value.settings
        try {
            ui.runOnUiThread { repository.saveSettings(Settings(theme = ThemeMode.DARK, scene = DemoScene.DOORS)) }
            ui.waitUntil(15000) { repository.state.value.connected && repository.state.value.programs?.complete == true }
            screen("appearance-home-dark")
            ui.onNodeWithTag("vehicle-status").performClick()
            ui.onNodeWithTag("vehicle-overhead").assertExists()
            screen("appearance-car-dark")
            ui.onNodeWithText("Close").performClick()
            ui.onNodeWithTag("charging-status").performClick()
            ui.onNodeWithText("Start time (HH:mm)").assertExists()
            screen("appearance-charging-off-dark")
            ui.onNodeWithTag("deferred-charging").performClick()
            ui.waitUntil(15000) { repository.state.value.command?.phase == "succeeded" }
            ui.onNodeWithText("Start time (HH:mm)").assertExists()
            screen("appearance-charging-on-dark")
            ui.onNodeWithText("Close").performClick()
            ui.onNodeWithTag("home-climate").performClick()
            ui.onNodeWithTag("preconditioning-outside").assertExists()
            ui.onNodeWithText("Front left setpoint").assertDoesNotExist()
            ui.onNodeWithText("Front right setpoint").assertDoesNotExist()
            screen("appearance-preconditioning-dark")
            ui.onNodeWithTag("preconditioning-schedules").performScrollTo().assertIsOff()
            ui.onNodeWithTag("plan-1-time").assertDoesNotExist()
            ui.onNodeWithTag("preconditioning-schedules").performClick()
            ui.onNodeWithTag("plan-1-time").assertExists()
            ui.onNodeWithTag("preconditioning-schedules").performClick()
            ui.onNodeWithTag("plan-1-time").assertDoesNotExist()
            ui.onNodeWithTag("plan-2-edit").performScrollTo().performClick()
            ui.onNodeWithTag("plan-2-time").assertExists()
            ui.onNodeWithTag("plan-2-edit").performScrollTo().performClick()
            ui.onNodeWithTag("plan-2-time").assertDoesNotExist()
            ui.onNodeWithTag("plan-1-edit").performScrollTo().performClick()
            ui.onNodeWithTag("plan-1-time").performScrollTo().assertTextContains("07:30")
            ui.onNodeWithTag("plan-1-Mon").assertIsSelected()
            ui.onNodeWithTag("plan-1-Wed").assertIsSelected()
            ui.onNodeWithTag("plan-1-Thu").assertIsSelected()
            screen("appearance-programs-dark")
            ui.onNodeWithTag("preconditioning-schedules").performScrollTo().performClick()
            ui.onNodeWithTag("apply-preconditioning-schedules").performScrollTo().performClick()
            ui.waitUntil(15000) { repository.state.value.command?.phase == "succeeded" }
            ui.waitUntil(15000) { repository.state.value.programs?.enabled == true }
            ui.runOnUiThread { repository.dismissResult(); repository.saveSettings(Settings(theme = ThemeMode.LIGHT, scene = DemoScene.DOORS)) }
            ui.waitUntil(15000) { repository.state.value.connected && repository.state.value.details != null }
            ui.onNodeWithTag("vehicle-status").performClick()
            screen("appearance-car-light")
            ui.onNodeWithText("Close").performClick()
        } finally {
            ui.waitUntil(35000) { repository.state.value.command?.pending != true }
            ui.runOnUiThread { repository.saveSettings(original) }
        }
    }
}
