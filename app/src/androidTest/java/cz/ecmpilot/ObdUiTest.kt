package cz.ecmpilot

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cz.ecmpilot.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Demo only; adapter selection never touches Bluetooth or the vehicle. */
class ObdUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repository get() = (ui.activity.application as PilotApplication).repository
    private fun screen(name: String) {
        ui.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(ui.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun discoverSaveAndReadVehicleDetails() {
        val original = repository.state.value.settings
        try {
            ui.runOnUiThread {
                ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                repository.saveSettings(Settings(scene = DemoScene.ASLEEP))
            }
            ui.waitUntil(25000) { repository.state.value.elm != null && repository.state.value.obd.size == 27 }
            ui.onNodeWithTag("home-battery-temperature").performScrollTo().assertTextEquals("Battery temperature · 9.0 °C")
            screen("obd-home-demo")
            ui.onNodeWithTag("menu-gateway").performScrollTo().performClick()
            ui.onNodeWithTag("obd-scan").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(15000) { repository.state.value.elm?.let { it.operation == 1 && !it.busy && it.result == 2 } == true }
            ui.onNodeWithTag("obd-adapter-00:10:CC:4F:36:03").performScrollTo().performClick()
            ui.onNodeWithTag("obd-save").performScrollTo().assertIsEnabled().performClick()
            ui.waitUntil(15000) { !repository.state.value.elmPending && repository.state.value.elm?.adapter != null }
            ui.onNodeWithTag("obd-saved-adapter").performScrollTo().assertTextEquals("Saved adapter: 00:10:CC:4F:36:03")
            assertEquals(2L,repository.state.value.elm!!.revision)
            ui.onNodeWithTag("obd-save").performScrollTo()
            screen("obd-adapter-demo")
            assertNull(repository.state.value.command)
            assertEquals(false,repository.state.value.vehicle!!.awake)
            ui.onNodeWithText("Close").performScrollTo().performClick()
            ui.onNodeWithTag("menu-details").performScrollTo().performClick()
            ui.onNodeWithText("HSCAN / OBD").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Candidate readings").assertDoesNotExist()
            ui.onNode(hasText("8.0 °C") and hasAnyAncestor(hasTestTag("obd-value-battery_min_c"))).performScrollTo().assertIsDisplayed()
            ui.onNode(hasText("9.0 °C") and hasAnyAncestor(hasTestTag("obd-value-battery_average_c"))).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("3.801 V").performScrollTo().assertIsDisplayed()
            screen("obd-battery-demo")
            ui.onNodeWithText("Heater setpoint").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Chiller request").performScrollTo().assertIsDisplayed()
            screen("obd-thermal-demo")
            assertNull(repository.state.value.command)
        } finally {
            ui.waitUntil(15000) { !repository.state.value.elmPending }
            ui.runOnUiThread { repository.saveSettings(original) }
        }
    }
}
