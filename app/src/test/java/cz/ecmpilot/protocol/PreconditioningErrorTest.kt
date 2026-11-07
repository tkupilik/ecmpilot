package cz.ecmpilot.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import cz.ecmpilot.model.CommandResult
import cz.ecmpilot.model.messageFor
import org.junit.Assert.*
import org.junit.Test

class PreconditioningErrorTest {
    private fun envelope(code: JsonElement? = null): ByteArray {
        val command = JsonObject().apply {
            addProperty("id", "a".repeat(32)); addProperty("revision", 2)
            addProperty("phase", "failed"); addProperty("error", "preconditioning_rejected")
            if (code != null) add("preconditioning_feedback", code)
        }
        return JsonObject().apply {
            addProperty("v", 0); addProperty("type", "status"); addProperty("gw", "test-gateway")
            addProperty("boot", "b".repeat(32)); addProperty("reply_to", "c".repeat(32))
            addProperty("seq", 1); addProperty("now_ms", 1000); add("command", command)
        }.toString().toByteArray()
    }
    @Test fun translatesActualDoorsUnlockedRefusal() {
        val result = Protocol.parse(envelope(JsonPrimitive(4)), 1000, 0).command!!
        assertEquals(4, result.preconditioningFeedback)
        assertEquals("Close all doors and lock the vehicle, then try again. (Code 4)", messageFor(result))
    }
    @Test fun explainsOtherSourceDefinedRefusalsAndPreservesCode() {
        for ((code, text) in mapOf(5 to "Battery charge is too low", 6 to "insufficient fuel", 7 to "Too many preconditioning sessions")) {
            val result = Protocol.parse(envelope(JsonPrimitive(code)), 1000, 0).command!!
            assertTrue(messageFor(result).contains(text))
            assertTrue(messageFor(result).contains("Code $code"))
        }
    }
    @Test fun olderGatewayAndUnknownCodeStillProduceUsefulMessage() {
        for (code in listOf(null, JsonNull.INSTANCE)) {
            val result = Protocol.parse(envelope(code), 1000, 0).command!!
            assertNull(result.preconditioningFeedback)
            assertFalse(messageFor(result).contains("gateway feedback"))
        }
        assertTrue(messageFor(Protocol.parse(envelope(JsonPrimitive(15)), 1000, 0).command!!).contains("code 15"))
    }
    @Test fun rejectsMalformedFeedback() {
        for (code in listOf(JsonPrimitive(-1), JsonPrimitive(16), JsonPrimitive(4.5), JsonPrimitive("4"), JsonPrimitive(true))) {
            try { Protocol.parse(envelope(code), 1000, 0); fail("Accepted $code") }
            catch (_: ProtocolException) { }
        }
    }
    @Test fun timeoutExplainsMissingWakeOrReplyInsteadOfVehicleRefusal() {
        val result = CommandResult("a".repeat(32), 2, "unknown", "bsi_wake_timeout")
        assertTrue(messageFor(result).contains("did not wake up"))
        assertTrue(messageFor(result.copy(error = "vehicle_reply_timeout")).contains("did not confirm"))
        assertEquals("Confirmed by vehicle status.", messageFor(result.copy(phase = "succeeded", preconditioningFeedback = 4)))
    }
}
