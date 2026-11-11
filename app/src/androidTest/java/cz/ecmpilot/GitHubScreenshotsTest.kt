package cz.ecmpilot

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cz.ecmpilot.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Reproducible documentation gallery. All actions use the in-process demo. */
class GitHubScreenshotsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repository get() = (ui.activity.application as PilotApplication).repository

    private fun scene(scene: DemoScene = DemoScene.CHARGING, theme: ThemeMode = ThemeMode.LIGHT) {
        ui.runOnUiThread { repository.saveSettings(Settings(source = Source.DEMO, scene = scene, theme = theme)) }
        if (scene == DemoScene.OFFLINE) {
            ui.waitUntil(15000) { repository.state.value.error != null }
        } else {
            ui.waitUntil(15000) {
                repository.state.value.connected && repository.state.value.details != null &&
                    repository.state.value.programs?.complete == true && repository.state.value.autoRefresh != null &&
                    repository.state.value.lowPower != null && repository.state.value.chargeSchedule != null
            }
        }
    }

    private fun screen(name: String, source: Source = Source.DEMO) {
        ui.waitForIdle()
        assertEquals(source, repository.state.value.settings.source)
        // Capture only the rendered app: no notifications, status bar or device identifiers.
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        File(ui.activity.getExternalFilesDir(null), "github-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun close() = ui.onNodeWithText("Close").performScrollTo().performClick()

    @Test fun captureDocumentationGallery() {
        val original = repository.state.value.settings
        try {
            ui.waitUntil(35000) { repository.state.value.command?.pending != true && !repository.state.value.autoRefreshSaving }
            ui.runOnUiThread { ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            // Fill the visible diagnostic tail with demo sessions, keeping real
            // gateway identifiers out of the Vehicle details documentation image.
            repeat(12) { scene() }
            screen("home-light")
            ui.runOnUiThread {
                repository.saveAutoRefresh(repository.state.value.autoRefresh!!.settings.copy(enabled = true, chargeLimitEnabled = true))
            }
            ui.waitUntil(15000) { repository.state.value.autoRefresh?.settings?.chargeLimitEnabled == true && !repository.state.value.autoRefreshSaving }
            ui.onNodeWithTag("charging-status").performClick()
            ui.onNodeWithTag("charge-limit-slider").assertExists()
            screen("charging")
            ui.onNodeWithTag("deferred-charging").performClick()
            ui.waitUntil(15000) { repository.state.value.command?.phase == "succeeded" }
            screen("delayed-charging")
            ui.onNodeWithTag("charge-limit-status").performScrollTo()
            screen("charging-limit")
            ui.onNodeWithTag("charge-limit-enabled").performScrollTo().performClick()
            ui.waitUntil(15000) { repository.state.value.autoRefresh?.settings?.chargeLimitEnabled == false && !repository.state.value.autoRefreshSaving }
            screen("charging-limit-off")
            ui.onNodeWithTag("charge-limit-enabled").performClick()
            ui.waitUntil(15000) { repository.state.value.autoRefresh?.settings?.chargeLimitEnabled == true && !repository.state.value.autoRefreshSaving }
            ui.onNodeWithTag("charge-schedule-enabled").performScrollTo().performClick()
            ui.onNodeWithTag("charge-schedule-add").performScrollTo().performClick()
            ui.onNodeWithTag("charge-period-2-from").performScrollTo().performTextReplacement("17:00")
            ui.onNodeWithTag("charge-period-2-to").performScrollTo().performTextReplacement("20:00")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            ui.onNodeWithTag("charge-schedule-apply").performScrollTo().performClick()
            ui.waitUntil(15000) { repository.state.value.chargeSchedule?.settings?.intervals?.size == 2 && !repository.state.value.chargeScheduleSaving }
            ui.onNodeWithTag("charge-schedule-enabled").performScrollTo()
            screen("charging-schedules")
            ui.onNodeWithTag("charge-schedule-apply").performScrollTo()
            screen("charging-schedules-lower")
            close()
            ui.onNodeWithTag("home-climate").performClick()
            ui.onNodeWithTag("preconditioning-outside").assertExists()
            screen("preconditioning")
            ui.onNodeWithTag("plan-1-edit").performScrollTo().performClick()
            ui.onNodeWithTag("plan-1-Mon").performScrollTo().assertIsSelected()
            screen("schedules")
            close()
            ui.onNodeWithTag("menu-gateway").performScrollTo().performClick()
            ui.onNodeWithTag("auto-refresh-enabled").performScrollTo().assertIsOn()
            screen("gateway")
            ui.onNodeWithTag("low-power-apply").performScrollTo()
            screen("gateway-low-power")
            ui.onNodeWithTag("charge-limit-help").performScrollTo()
            screen("gateway-charge-limit")
            close()
            ui.onNodeWithTag("menu-trips").performScrollTo().performClick()
            ui.onNodeWithText("Trip 1").assertExists()
            screen("trips")
            close()
            ui.onNodeWithTag("menu-details").performScrollTo().performClick()
            ui.onNodeWithText("12 V battery voltage").assertExists()
            screen("details")
            close()
            ui.onNodeWithTag("settings").performScrollTo().performClick()
            screen("settings")
            ui.onNodeWithTag("local-source").performScrollTo().performClick()
            ui.onNodeWithTag("gateway-auto-discovery").performScrollTo()
            screen("connection-discovery")
            close()
            scene(DemoScene.DOORS)
            ui.onNodeWithTag("vehicle-status").performClick()
            ui.onNodeWithTag("vehicle-overhead").assertExists()
            screen("doors")
            close()
            scene(DemoScene.RUNNING, ThemeMode.DARK)
            screen("home-dark")
            ui.onNodeWithTag("home-climate").performClick()
            ui.onNodeWithText("Stop preconditioning").assertExists()
            screen("preconditioning-dark")
            close()
            scene(DemoScene.REJECT)
            ui.onNodeWithTag("refresh-vehicle").performClick()
            ui.waitUntil(15000) { repository.state.value.command?.phase == "rejected" }
            ui.onNodeWithTag("vehicle-action-error").assertExists()
            screen("request-error")
            ui.onNodeWithText("Close").performClick()
            scene(DemoScene.OFFLINE, ThemeMode.DARK)
            screen("offline")
        } finally {
            ui.waitUntil(35000) { repository.state.value.command?.pending != true && !repository.state.value.autoRefreshSaving && !repository.state.value.chargeScheduleSaving }
            ui.runOnUiThread {
                repository.saveSettings(original)
                ui.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /** Explicit opt-in; only passive reads and navigation, with no settings writes. */
    @Test fun captureLiveVehicleGallery() {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(arguments.getString("captureLive") == "true")
        assertEquals(Source.UDP, repository.state.value.settings.source)
        try {
            ui.runOnUiThread { ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            ui.waitUntil(30000) { repository.state.value.connected && repository.state.value.vehicle != null && repository.state.value.details != null }
            screen("live-home", Source.UDP)
            ui.onNodeWithTag("home-climate").performClick()
            ui.onNodeWithTag("preconditioning-outside").assertExists()
            screen("live-preconditioning", Source.UDP)
            close()
            ui.onNodeWithTag("charging-status").performClick()
            screen("live-charging", Source.UDP)
            close()
            ui.onNodeWithTag("vehicle-status").performClick()
            screen("live-doors", Source.UDP)
            close()
            ui.onNodeWithTag("menu-trips").performScrollTo().performClick()
            screen("live-trips", Source.UDP)
            close()
        } finally {
            ui.runOnUiThread { ui.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}
