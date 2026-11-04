package cz.ecmpilot.protocol

import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoRefreshTest {
    private fun packet(data: String, seq: Int = 1, boot: String = "0".repeat(32)) =
        """{"v":0,"type":"status","gw":"test","boot":"$boot","reply_to":"${"a".repeat(32)}","seq":$seq,"now_ms":1000,$data}"""
    private val settings = """"auto_refresh":{"rev":1,"enabled":false,"standby_min":60,"charging_min":5,"min_aux_soc_pct":50,"capable":true,"config_valid":true,"state":"disabled","remaining_ms":0,"request_count":0}"""
    @Test fun readsDefaultsAndResetsCacheOnBootWithoutAcceptingOldSnapshots() {
        val tracker = StatusTracker()
        val parsed = Protocol.parse(packet(settings, 3).toByteArray(), 1000, 0)
        assertEquals(AutoRefreshSettings(), parsed.autoRefresh!!.settings)
        assertTrue(tracker.accept(parsed))
        assertFalse(tracker.accept(Protocol.parse(packet(settings.replace("\"rev\":1", "\"rev\":2"), 2).toByteArray(), 1000, 0)))
        assertEquals(1L, tracker.autoRefresh!!.settings.revision)
        tracker.accept(Protocol.parse(packet("\"error\":null", 1, "1".repeat(32)).toByteArray(), 1000, 0))
        assertNull(tracker.autoRefresh)
    }
    @Test fun rejectsInvalidPeriodRevisionSocAndNonBooleanEnable() {
        for (text in listOf(settings.replace("\"rev\":1", "\"rev\":0"),
            settings.replace("\"standby_min\":60", "\"standby_min\":0"),
            settings.replace("\"charging_min\":5", "\"charging_min\":1441"),
            settings.replace("\"min_aux_soc_pct\":50", "\"min_aux_soc_pct\":101"),
            settings.replace("\"enabled\":false", "\"enabled\":0"))) {
            try { Protocol.parse(packet(text).toByteArray(), 1000, 0); fail(text) } catch (_: ProtocolException) { }
        }
    }
    @Test fun demoSettingsSaveDoesNotCreateVehicleCommandAndConflictingEditorsCannotOverwrite() = runBlocking {
        val demo = DemoConnection(DemoScene.ASLEEP) { 1000L }
        suspend fun read() = demo.exchange(Protocol.request("a".repeat(32), "auto_refresh.get"))
        val initial = read()
        val desired = initial.autoRefresh!!.settings.copy(enabled = true, standbyMin = 12, chargingMin = 3, minAuxSocPct = 60)
        val request = Protocol.request("a".repeat(32), "auto_refresh.set", initial.gw, initial.boot, desired.args())
        val saved = demo.exchange(request)
        assertNull(saved.command); assertEquals(2L, saved.autoRefresh!!.settings.revision)
        assertEquals(2L, demo.exchange(request).autoRefresh!!.settings.revision)
        val conflicting = Protocol.request("a".repeat(32), "auto_refresh.set", initial.gw, initial.boot, desired.copy(standbyMin = 15).args())
        assertEquals("settings_conflict", demo.exchange(conflicting).error)
        assertEquals(12, read().autoRefresh!!.settings.standbyMin)
        val state = demo.exchange(Protocol.request("a".repeat(32), "state.get")).state!!
        assertEquals(false, state.awake); assertEquals("off", state.climate.state)
        assertFalse(AutoRefreshSettings(standbyMin = 0).valid())
    }

    @Test fun chargeLimitConfigurationSharesGatewayRevisionAndRequiresRefresh() = runBlocking {
        assertFalse(AutoRefreshSettings(chargeLimitEnabled = true).valid())
        assertFalse(AutoRefreshSettings(chargeLimitSocPct = 0).valid())
        assertFalse(AutoRefreshSettings(chargeLimitSocPct = 101).valid())
        val demo = DemoConnection(DemoScene.ASLEEP) { 1000L }
        val initial = demo.exchange(Protocol.request("a".repeat(32), "auto_refresh.get"))
        suspend fun save(value: AutoRefreshSettings) = demo.exchange(Protocol.request("a".repeat(32), "auto_refresh.set", initial.gw, initial.boot, value.args()))
        val enabled = save(initial.autoRefresh!!.settings.copy(enabled = true, chargeLimitEnabled = true))
        assertEquals(80, enabled.autoRefresh!!.settings.chargeLimitSocPct)
        val changed = save(enabled.autoRefresh!!.settings.copy(chargeLimitSocPct = 85))
        assertEquals(3L, changed.autoRefresh!!.settings.revision)
        assertTrue(changed.autoRefresh!!.settings.chargeLimitEnabled)
        assertEquals(85, changed.autoRefresh!!.settings.chargeLimitSocPct)
        assertEquals("monitoring", changed.autoRefresh!!.chargeLimitState)
        assertNull(changed.command)
        val disabled = save(changed.autoRefresh!!.settings.copy(enabled = false, chargeLimitEnabled = false))
        assertFalse(disabled.autoRefresh!!.settings.chargeLimitEnabled)
        assertEquals(85, disabled.autoRefresh!!.settings.chargeLimitSocPct)
    }
}
