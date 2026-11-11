package cz.ecmpilot.data

import com.google.gson.JsonParser
import cz.ecmpilot.model.CommandRef
import cz.ecmpilot.model.CommandUi
import cz.ecmpilot.model.Correlation
import org.junit.Assert.*
import org.junit.Test

class CommandReceiptTest {
    private val ref = CommandRef("1".repeat(32), "gateway", "2".repeat(32), "Preconditioning",
        "ecmpilot-gw.local", 42840, Correlation("3".repeat(32), 17))

    @Test fun outstandingRequestsKeepTheirOriginalIdentityAcrossRestart() {
        for (phase in listOf("sending", "accepted", "executing", "unknown")) {
            val command = CommandUi(ref, phase, "Waiting for result", 7)
            assertEquals(command, CommandReceipt.decode(requireNotNull(CommandReceipt.encode(command))))
        }
        val legacy = CommandUi(ref.copy(correlation = null), "accepted", "Waiting", 1)
        assertEquals(legacy, CommandReceipt.decode(requireNotNull(CommandReceipt.encode(legacy))))
    }

    @Test fun completedRequestsAndDismissedResultsDoNotSurviveRestart() {
        for (phase in listOf("succeeded", "rejected", "failed")) {
            assertNull(CommandReceipt.encode(CommandUi(ref, phase, "Vehicle locked required", 8)))
        }
        assertNull(CommandReceipt.encode(null))
        assertNull(CommandReceipt.encode(CommandUi(null, "preparing", "Reading state")))
    }

    @Test fun oldStoredFailuresAreIgnoredAfterUpgrade() {
        val receipt = JsonParser.parseString(CommandReceipt.encode(CommandUi(ref, "accepted", "Waiting", 1))).asJsonObject
        for (phase in listOf("rejected", "failed", "succeeded", "unexpected")) {
            receipt.addProperty("phase", phase)
            assertNull(CommandReceipt.decode(receipt.toString()))
        }
    }

    @Test fun invalidStoredReceiptCannotRestoreAPopup() {
        for (text in listOf("", "null", "{}", "[]", "invalid")) assertNull(CommandReceipt.decode(text))
    }
}
