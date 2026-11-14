package cz.ecmpilot.protocol

import org.junit.Assert.*
import org.junit.Test

class BleTransportTest {
    @Test fun roundTripEveryMtuIncludingMaximumMessage() {
        for (capacity in listOf(20, 64, 180, 514)) for (size in listOf(1, 12, 172, 173, 1024)) {
            val message = ByteArray(size) { (it % 127).toByte() }
            val receiver = BleFraming.Receiver()
            var offset = 0
            while (offset < size) {
                val packet = BleFraming.fragment(message, 65535, offset, capacity)
                assertTrue(receiver.feed(packet, offset.toLong()))
                offset += packet.size - BleFraming.HEADER
            }
            assertEquals(65535, receiver.token)
            assertArrayEquals(message, receiver.message())
        }
    }
    @Test fun rejectMissingReorderedTimedOutAndConflictingFragments() {
        val message = ByteArray(100)
        val first = BleFraming.fragment(message, 1, 0, 20)
        val second = BleFraming.fragment(message, 1, 12, 20)
        val receiver = BleFraming.Receiver()
        assertFalse(receiver.feed(second, 0))
        assertTrue(receiver.feed(first, 0))
        assertFalse(receiver.feed(second, 3001))
        assertTrue(receiver.feed(first, 0))
        assertFalse(receiver.feed(BleFraming.fragment(message, 1, 24, 20), 1))
        assertTrue(receiver.feed(first, 0))
        assertFalse(receiver.feed(BleFraming.fragment(message, 2, 12, 20), 1))
        assertTrue(receiver.feed(first, 0))
        assertFalse(receiver.feed(second.copyOf().apply { this[1] = 2 }, 1))
        assertFalse(receiver.feed(first.copyOf().apply { this[4] = 1; this[5] = 5 }, 1))
    }
}
