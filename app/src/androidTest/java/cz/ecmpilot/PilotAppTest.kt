package cz.ecmpilot

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cz.ecmpilot.model.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PilotAppTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repository get() = (ui.activity.application as PilotApplication).repository
    @Before fun reset() {
        ui.waitUntil(10_000) { repository.state.value.command?.pending != true }
        ui.runOnUiThread { repository.saveSettings(Settings()) }
        ready()
    }
    private fun ready() = ui.waitUntil(15_000) { repository.state.value.connected && repository.state.value.vehicle != null }
    private fun result(phase: String) = ui.waitUntil(15_000) { repository.state.value.command?.phase == phase }
    private fun screen(name: String) {
        ui.waitForIdle()
        val target = File(ui.activity.getExternalFilesDir(null), "$name.png")
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun a_demoControlAndSheets() {
        ui.onNodeWithText("DEMO").assertExists()
        ui.onNodeWithTag("home-climate").assertIsEnabled()
        screen("home")
        ui.onNodeWithTag("home-climate").performClick()
        ui.onNodeWithText("Prepare the cabin").assertExists()
        screen("climate")
        ui.onNodeWithTag("confirm-climate").performClick()
        result("succeeded")
        ui.waitUntil(10_000) { repository.state.value.vehicle?.climate?.state == "on" }
        ui.onNodeWithText("Stop climate").assertExists()
        ui.onNodeWithTag("charging-options").performScrollTo().performClick()
        ui.onNodeWithText("Later").performClick()
        screen("charging")
        ui.onNodeWithTag("apply-charge").performScrollTo().performClick()
        result("succeeded")
        ui.waitUntil(10_000) { repository.state.value.vehicle?.charge?.mode == "deferred" }
    }
    @Test fun b_asleepAndMissingValues() {
        ui.runOnUiThread { repository.saveSettings(Settings(scene = DemoScene.ASLEEP)) }; ready()
        ui.onNodeWithTag("home-climate").assertIsNotEnabled()
        ui.onNodeWithTag("charging-options").assertIsNotEnabled()
        screen("asleep")
        ui.runOnUiThread { repository.saveSettings(Settings(scene = DemoScene.LIMITED)) }; ready()
        ui.waitUntil { repository.state.value.vehicle?.reading("cabin_c")?.value == null }
        ui.onAllNodesWithText("—").assertCountEquals(3)
    }
    @Test fun c_rejectedAndUnknownNeverBecomeSuccess() {
        ui.runOnUiThread { repository.saveSettings(Settings(scene = DemoScene.REJECT)) }; ready()
        ui.onNodeWithTag("home-climate").performClick(); ui.onNodeWithTag("confirm-climate").performClick(); result("rejected")
        ui.onNodeWithText("Start climate").assertExists()
        ui.runOnUiThread { repository.saveSettings(Settings(scene = DemoScene.UNCONFIRMED)) }; ready()
        ui.onNodeWithTag("home-climate").performClick(); ui.onNodeWithTag("confirm-climate").performClick(); result("unknown")
        ui.onNodeWithText("Result unconfirmed").assertExists()
        screen("unconfirmed")
    }
    @Test fun d_darkTheme() {
        ui.runOnUiThread { repository.saveSettings(Settings(theme = ThemeMode.DARK)) }; ready()
        ui.onNodeWithTag("home-climate").assertIsEnabled(); screen("home-dark")
    }
    @Test fun e_liveUdpWithLostAcknowledgement() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("udpHost") == null) return // opt-in integration test against the explicitly started simulator
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission("cz.ecmpilot", "android.permission.ACCESS_LOCAL_NETWORK")
            ui.runOnUiThread { repository.setPermission(true) }
        }
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithTag("local-source").performScrollTo().performClick()
        ui.onNodeWithTag("gateway-host").performScrollTo().performTextReplacement(arguments.getString("udpHost")!!)
        ui.onNodeWithTag("gateway-port").performScrollTo().performTextReplacement(arguments.getString("udpPort") ?: "42840")
        ui.onNodeWithTag("allow-controls").performScrollTo().performClick()
        ui.onNodeWithTag("save-connection").performScrollTo().performClick()
        ready()
        ui.onNodeWithText("LOCAL").assertExists()
        ui.onNodeWithTag("home-climate").assertIsEnabled().performClick()
        ui.onNodeWithTag("confirm-climate").performClick()
        result("succeeded")
        ui.waitUntil(10_000) { repository.state.value.vehicle?.climate?.state == "on" }
        screen("udp-live")
    }
}
