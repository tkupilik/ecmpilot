package cz.ecmpilot

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import cz.ecmpilot.model.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Pull each shared sheet above its expanded stop, then watch for anchor feedback. */
class SheetGestureTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repository get() = (ui.activity.application as PilotApplication).repository
    @Before fun keepScreenOnDuringGestures() {
        ui.runOnUiThread { ui.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun distanceControlsDismissalWithoutAFlingShortcutAcrossAllDialogs() {
        val original = repository.state.value.settings
        try {
            ui.runOnUiThread { repository.saveSettings(Settings(theme = ThemeMode.DARK, scene = DemoScene.DOORS)) }
            ui.waitUntil(15000) { repository.state.value.connected && repository.state.value.programs?.complete == true }
            val dp = ui.activity.resources.displayMetrics.density
            for (tag in listOf("vehicle-status", "charging-status", "home-climate", "menu-trips", "menu-details", "menu-gateway", "settings")) {
                ui.onNodeWithTag(tag).performScrollTo().performClick()
                ui.waitForIdle()
                // The outer sheet modifier includes window/inset space;
                // start on the actual visible content viewport instead.
                ui.onNodeWithTag("dialog-scroll").performTouchInput {
                    swipe(Offset(centerX, 8*dp), Offset(centerX, 8*dp+30), durationMillis = 600)
                }
                ui.waitForIdle()
                ui.onNodeWithTag("dialog-sheet").assertExists()
                ui.onNodeWithTag("dialog-scroll").performTouchInput {
                    swipe(Offset(centerX, 8*dp), Offset(centerX, 8*dp+50), durationMillis = 100)
                }
                ui.waitForIdle()
                ui.onNodeWithTag("dialog-sheet").assertExists()
                ui.onNodeWithTag("dialog-scroll").performTouchInput {
                    swipe(Offset(centerX, 8*dp), Offset(centerX, 8*dp+180), durationMillis = 1500)
                }
                try {
                    ui.waitUntil(5000) { ui.onAllNodesWithTag("dialog-sheet").fetchSemanticsNodes().isEmpty() }
                } catch (error: Exception) { throw AssertionError("Deliberate pull did not dismiss $tag",error) }
            }
        } finally { ui.runOnUiThread { repository.saveSettings(original) } }
    }

    @Test fun upwardDragsSettleAcrossAllDialogs() {
        val original = repository.state.value.settings
        try {
            ui.runOnUiThread { repository.saveSettings(Settings(theme = ThemeMode.DARK, scene = DemoScene.DOORS)) }
            ui.waitUntil(15000) { repository.state.value.connected && repository.state.value.programs?.complete == true }
            for (tag in listOf("vehicle-status", "charging-status", "home-climate", "menu-trips", "menu-details", "menu-gateway", "settings")) {
                ui.onNodeWithTag(tag).performScrollTo().performClick()
                val sheet = ui.onNodeWithTag("dialog-sheet")
                sheet.assertExists()
                ui.onNodeWithTag("dialog-scroll").performTouchInput { swipeUp() }
                ui.mainClock.autoAdvance = false
                try {
                    ui.mainClock.advanceTimeBy(1500)
                    val positions = (0 until 30).map {
                        ui.mainClock.advanceTimeBy(100)
                        // Resolve the tag again: v2 interactions retain node
                        // identity, but modal content can be replaced on settle.
                        ui.onNodeWithTag("dialog-scroll").fetchSemanticsNode().boundsInRoot.top
                    }
                    val travel = positions.max() - positions.min()
                    assertTrue("$tag keeps bouncing after release: $travel px; samples=$positions", travel <= 1f)
                } finally { ui.mainClock.autoAdvance = true }
                ui.onNodeWithText("Close").performScrollTo().performClick()
            }
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { repository.saveSettings(original) }
        }
    }
}
