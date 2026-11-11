package cz.ecmpilot.protocol

import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class OffPeakChargingTest {
    @Test fun parsesRealFirmwareReplyWithEightPeriodsAndVehicleClock() {
        val bytes=requireNotNull(javaClass.getResourceAsStream("/gateway/charge-schedule.json")).use {it.readBytes()}
        val status=Protocol.parse(bytes,1000,0).chargeSchedule!!
        assertEquals(8,status.settings.intervals.size)
        assertEquals(Int.MAX_VALUE.toLong(),status.settings.revision)
        assertEquals("07:35",status.clock.display(1000))
        assertEquals(86400000L,status.clock.sourceAgeMs)
    }
    @Test fun validationSupportsOvernightAndRejectsEmptyDaysEqualTimesAndOversize() {
        assertTrue(ChargeScheduleSettings(enabled=true,intervals=listOf(ChargePause(from="23:00",to="01:00",days=16))).valid())
        for(slot in listOf(ChargePause(days=0),ChargePause(from="24:00"),ChargePause(to="07:00")))
            assertFalse(ChargeScheduleSettings(enabled=true,intervals=listOf(slot)).valid())
        assertFalse(ChargeScheduleSettings(enabled=true).valid())
        assertFalse(ChargeScheduleSettings(intervals=List(9){ChargePause()}).valid())
        assertFalse(ChargeScheduleSettings(enabled=true,intervals=listOf(ChargePause(enabled=false))).valid())
    }

    @Test fun clockNeverUsesPhoneTimezoneOrDstRules() {
        val original=TimeZone.getDefault()
        try {
            for(zone in listOf("Europe/Prague","America/New_York","UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val clock=VehicleClock("2026-10-25T02:59",59,120000,1000)
                assertEquals("03:00",clock.display(2000))
                assertEquals("03:01",clock.display(62000))
                assertEquals("25.10.2026 · 03:00",clock.display(2000, withDate=true))
            }
            assertEquals("00:00",VehicleClock("2026-12-31T23:59",59,0,1000).display(2000))
            assertEquals("01.01.2027 · 00:00",VehicleClock("2026-12-31T23:59",59,0,1000).display(2000, withDate=true))
            assertEquals("—",VehicleClock(null,0,null,0).display(1000))
        } finally {TimeZone.setDefault(original)}
    }

    @Test fun configRetriesAreIdempotentAndConflictsPreserveSavedPlans()=runBlocking {
        var now=1000L;val demo=DemoConnection(DemoScene.CHARGING){now};val client=newId()
        val info=demo.exchange(Protocol.request(client,"info.get"))
        assertTrue("charge_schedule" in info.info!!.fields)
        fun request(op:String,args:com.google.gson.JsonObject=com.google.gson.JsonObject())=Protocol.request(client,op,info.gw,info.boot,args)
        val initial=demo.exchange(request("charge.schedule.get")).chargeSchedule!!
        assertFalse(initial.settings.enabled);assertEquals("06:30",initial.clock.display(now))
        val desired=initial.settings.copy(enabled=true,intervals=listOf(ChargePause(),ChargePause(from="17:00",to="20:00")))
        val saved=demo.exchange(request("charge.schedule.set",desired.args())).chargeSchedule!!
        assertEquals(2L,saved.settings.revision);assertEquals(desired.intervals,saved.settings.intervals)
        assertEquals(saved.settings,demo.exchange(request("charge.schedule.set",desired.args())).chargeSchedule!!.settings)
        assertEquals("settings_conflict",demo.exchange(request("charge.schedule.set",desired.copy(enabled=false).args())).error)
        assertEquals(saved.settings,demo.exchange(request("charge.schedule.get")).chargeSchedule!!.settings)
        assertNull(demo.exchange(request("state.get")).command)
        now+=61000
        assertEquals("06:31",demo.exchange(request("charge.schedule.get")).chargeSchedule!!.clock.display(now))
    }

    @Test fun eightPeriodsFitRequestLimitAndTrackerClearsScheduleOnReboot()=runBlocking {
        val settings=ChargeScheduleSettings(Int.MAX_VALUE.toLong(),true,List(8){ChargePause()})
        val request=Protocol.request(newId(),"charge.schedule.set","ecmpilot-288485b41554",newId(),settings.args())
        assertTrue(request.bytes().size<=1024)
        val demo=DemoConnection(DemoScene.CHARGING){1000};val info=demo.exchange(Protocol.request(newId(),"info.get"))
        val status=demo.exchange(Protocol.request(newId(),"charge.schedule.get",info.gw,info.boot))
        val tracker=StatusTracker();tracker.accept(status);assertNotNull(tracker.chargeSchedule)
        tracker.accept(status.copy(boot=newId(),seq=1,chargeSchedule=null));assertNull(tracker.chargeSchedule)
    }
}
