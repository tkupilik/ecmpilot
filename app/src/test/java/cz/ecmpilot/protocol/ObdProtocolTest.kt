package cz.ecmpilot.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdProtocolTest {
    private fun packet(key: String, data: JsonObject, seq: Long = 1, boot: String = "0".repeat(32)): Status {
        val root = JsonObject().apply {
            addProperty("v",0); addProperty("type","status"); addProperty("gw","test"); addProperty("boot",boot)
            addProperty("reply_to","a".repeat(32)); addProperty("seq",seq); addProperty("now_ms",1000); add(key,data)
        }
        return Protocol.parse(root.toString().toByteArray(),2000,10)
    }
    private fun page(n: Int, age: Long = 600000) = JsonObject().apply {
        addProperty("page",n); addProperty("pages",5)
        add("readings",JsonObject().apply {
            ObdFields.all.drop(n*6).take(6).forEach { field ->
                add(field.key,JsonObject().apply { addProperty("value",field.range.start); addProperty("age_ms",age) })
            }
        })
    }
    @Test fun pagesMergeWithoutRenewingAgesAndBootChangeClearsThem() {
        val tracker = StatusTracker()
        for (n in 0..4) assertTrue(tracker.accept(packet("obd",page(n),n+1L)))
        assertEquals(27,tracker.obd.size)
        assertEquals(601010L,tracker.obd.getValue("battery_min_c").stamp.age(3000))
        assertFalse(tracker.accept(packet("obd",page(0,0),1)))
        assertEquals(600010L,tracker.obd.getValue("current_a").stamp.age(2000))
        assertTrue(tracker.accept(packet("obd",page(4),1,"1".repeat(32))))
        assertEquals(3,tracker.obd.size)
        assertNull(tracker.obd["cell_min_v"])
    }
    @Test fun nullReadingsAreUnknownAndCannotBecomeZero() {
        val p=page(0)
        p.getAsJsonObject("readings").getAsJsonObject("current_a").apply { add("value",JsonNull.INSTANCE); add("age_ms",JsonNull.INSTANCE) }
        val reading=packet("obd",p).obd!!.readings.getValue("current_a")
        assertNull(reading.value); assertNull(reading.stamp.age(2000))
    }
    @Test(expected=ProtocolException::class) fun incompletePageIsRejected() {
        val p=page(0); p.getAsJsonObject("readings").remove("current_a"); packet("obd",p)
    }
    @Test(expected=ProtocolException::class) fun unknownPageIsRejected() { packet("obd",page(5)) }
    @Test fun scanSaveAndRetriesInDemoNeverIssueVehicleActions() = runBlocking {
        var now=1000L
        val demo=DemoConnection(DemoScene.ASLEEP) {now}
        val identity=demo.exchange(Protocol.request("a".repeat(32),"info.get"))
        val initial=demo.exchange(Protocol.request("a".repeat(32),"elm327.get",identity.gw,identity.boot)).elm!!
        assertNull(initial.adapter)
        val scan=Protocol.request("a".repeat(32),"elm327.scan",identity.gw,identity.boot,
            JsonObject().apply {addProperty("generation",initial.generation)})
        assertTrue(demo.exchange(scan).elm!!.scanning)
        now+=2000
        val result=demo.exchange(scan).elm!!
        assertFalse(result.busy); assertEquals(1L,result.generation); assertEquals(1,result.adapters.size)
        val adapter=result.adapters.single()
        val save=Protocol.request("a".repeat(32),"elm327.set",identity.gw,identity.boot,JsonObject().apply {
            addProperty("rev",result.revision); addProperty("address",adapter.address); addProperty("address_type",adapter.addressType)
        })
        assertEquals(2L,demo.exchange(save).elm!!.revision)
        assertEquals(2L,demo.exchange(save).elm!!.revision)
        val state=demo.exchange(Protocol.request("a".repeat(32),"state.get",identity.gw,identity.boot))
        assertEquals(false,state.state!!.awake); assertNull(state.command)
        for (n in 0..4) {
            val sample=demo.exchange(Protocol.request("a".repeat(32),"obd.get",identity.gw,identity.boot,JsonObject().apply {addProperty("page",n)}))
            assertEquals(n,sample.obd!!.page)
        }
    }
    @Test(expected=ProtocolException::class) fun invalidScanAddressTypeIsRejected() {
        packet("elm327",JsonObject().apply {
            addProperty("address",""); addProperty("address_type","public"); addProperty("rev",1); addProperty("busy",false)
            addProperty("generation",1); addProperty("completed",1); addProperty("operation",1); addProperty("result",2)
            add("scan",JsonArray().apply {add(JsonArray().apply {add("00:10:CC:4F:36:03"); add(2); add(-60)})})
        })
    }
}
