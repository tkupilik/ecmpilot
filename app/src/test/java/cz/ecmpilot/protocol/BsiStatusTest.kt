package cz.ecmpilot.protocol

import org.junit.Assert.*
import org.junit.Test

class BsiStatusTest {
    private fun packet(bsi: String = "") = """{"v":0,"type":"status","gw":"test","boot":"${"0".repeat(32)}","reply_to":"${"a".repeat(32)}","seq":1,"now_ms":1000,"state":{"vehicle":{"awake":true,"parked":null,"age_ms":null},"soc_pct":{"value":57,"age_ms":22680000}$bsi}}"""

    @Test fun phaseAndAgeComeFromBsiInsteadOfCombinedVehicleAge() {
        for ((phase,label) in listOf("standby","normal","entering standby","wake-up","com off").withIndex()) {
            val state=Protocol.parse(packet(""", "bsi":{"power_management":$phase,"age_ms":100}""").toByteArray(),1000,10).state!!
            val bsi=checkNotNull(state.bsi)
            assertEquals(phase,bsi.powerManagement); assertEquals(label,bsi.label)
            assertFalse(state.vehicleStamp.fresh(1000)); assertTrue(bsi.stamp.fresh(1000))
            assertEquals(6110L,bsi.stamp.age(7000)); assertEquals(label,bsi.label)
        }
    }

    @Test fun oldGatewayCannotManufactureBsiNormalFromAwake() {
        val state=Protocol.parse(packet().toByteArray(),1000,0).state!!
        assertEquals(true,state.awake); assertNull(state.bsi)
        val unsupported=Protocol.parse(packet(""", "bsi":{"power_management":7,"age_ms":0}""").toByteArray(),1000,0).state!!
        assertEquals("unknown",unsupported.bsi!!.label)
    }
}
