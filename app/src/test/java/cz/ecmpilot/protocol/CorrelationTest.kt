package cz.ecmpilot.protocol

import com.google.gson.JsonObject
import cz.ecmpilot.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CorrelationTest {
    @Test fun counterIsSequentialAndWrapRotatesItsSession() {
        var session=0
        val counter=CorrelationCounter({ (++session).toString(16).padStart(32,'0') },0xffff_fffeL)
        val last=counter.next();val first=counter.next();val next=counter.next()
        assertEquals(0xffff_ffffL,last.id);assertEquals(1L,first.id);assertEquals(2L,next.id)
        assertNotEquals(last.session,first.session);assertEquals(first.session,next.session)
    }
    @Test fun distinctMessagesOfOneOperationKeepTheirSharedCorrelation() {
        val correlation=Protocol.nextCorrelation()
        val preflight=Protocol.request(newId(),"state.get",correlation=correlation)
        val command=Protocol.request(newId(),"vehicle.refresh",correlation=correlation)
        val query=Protocol.request(newId(),"command.get",args=JsonObject().apply{addProperty("command_id",command.id)},correlation=correlation)
        assertEquals(correlation,preflight.correlation);assertEquals(correlation,command.correlation);assertEquals(correlation,query.correlation)
        assertNotEquals(preflight.id,command.id);assertNotEquals(command.id,query.id)
    }
    @Test fun demoDeduplicatesOperationEvenWithADifferentMessageIdAndReturnsItsOriginalReceipt()=runBlocking {
        var now=1000L;val demo=DemoConnection(DemoScene.ASLEEP){now};val client=newId();val correlation=Protocol.nextCorrelation()
        val first=Protocol.request(client,"vehicle.refresh",correlation=correlation)
        val accepted=demo.exchange(first).command!!
        val duplicate=Protocol.request(client,"vehicle.refresh",correlation=correlation)
        assertEquals(accepted,demo.exchange(duplicate).command)
        now+=1500
        val completed=demo.exchange(duplicate).command!!
        assertEquals(first.id,completed.id);assertEquals(correlation,completed.correlation);assertEquals("succeeded",completed.phase)
        val query=Protocol.request(client,"command.get",correlation=correlation)
        assertEquals(completed,demo.exchange(query).command)
        val altered=Protocol.request(client,"climate.set",args=JsonObject().apply{addProperty("enabled",true)},correlation=correlation)
        assertEquals("id_conflict",demo.exchange(altered).command!!.error)
    }
}
