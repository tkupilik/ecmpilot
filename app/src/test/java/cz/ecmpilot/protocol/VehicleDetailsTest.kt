package cz.ecmpilot.protocol

import org.junit.Assert.*
import org.junit.Test

class VehicleDetailsTest {
    private fun packet(body: String, seq: Int = 1, boot: String = "0".repeat(32)) =
        """{"v":0,"type":"status","gw":"test","boot":"$boot","reply_to":"${"a".repeat(32)}","seq":$seq,"now_ms":1000,$body}"""
    private val details = """"details":{"body":{"open_mask":21,"known_mask":31,"age_ms":60000,"locking_state":3,"lock_age_ms":50},"trips":{"avg1":{"value":21.5,"age_ms":60000},"history":[24,null,22,21,20,19],"history_age_ms":60000}}"""

    @Test fun masksLockAndTripAgesAreIndependent() {
        val d = Protocol.parse(packet(details).toByteArray(), 1000, 10).details!!
        assertEquals(listOf(true,false,true,false,true), (0..4).map { d.body!!.open(it) })
        assertEquals("Unlocked", d.body!!.lockLabel)
        assertFalse(d.body.stamp.fresh(1000)); assertTrue(d.body.lockStamp.fresh(1000))
        assertEquals(21.5, d.trips["avg1"]!!.value!!, 0.001)
        assertFalse(d.trips["avg1"]!!.fresh(1000)); assertNull(d.history[1].value)
        assertEquals(61010L, d.history[0].stamp.age(2000))
    }

    @Test fun missingKnownBitsNeverMeanClosed() {
        val d = Protocol.parse(packet(details.replace("\"known_mask\":31", "\"known_mask\":1")).toByteArray(), 1000, 0).details!!
        assertEquals(true, d.body!!.open(0)); assertNull(d.body.open(1)); assertNull(d.body.open(8))
        val invalid = Protocol.parse(packet(details.replace("\"locking_state\":3", "\"locking_state\":7")).toByteArray(), 1000, 0).details!!
        assertEquals("Lock state unavailable", invalid.body!!.lockLabel)
    }

    @Test fun bootChangeClearsDetailsAndOldPacketsCannotOverwriteThem() {
        val t = StatusTracker()
        assertTrue(t.accept(Protocol.parse(packet(details, 2).toByteArray(), 1000, 0)))
        assertNotNull(t.details)
        assertFalse(t.accept(Protocol.parse(packet(details, 1).toByteArray(), 1000, 0)))
        assertTrue(t.accept(Protocol.parse(packet("\"error\":null", 1, "1".repeat(32)).toByteArray(), 1000, 0)))
        assertNull(t.details)
    }

    @Test fun oldGatewayMayOmitAllDetails() {
        assertNull(Protocol.parse(packet("\"error\":null").toByteArray(), 1000, 0).details)
    }

    @Test fun invalidOpenMaskCannotReportEveryDoorClosed() {
        for (value in listOf("null", "32")) {
            val d = Protocol.parse(packet(details.replace("\"open_mask\":21", "\"open_mask\":$value")).toByteArray(), 1000, 0).details!!
            assertEquals(0, d.body!!.knownMask)
            assertTrue((0..4).all { d.body.open(it) == null })
        }
    }
}
