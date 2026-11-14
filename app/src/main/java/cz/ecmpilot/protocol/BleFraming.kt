package cz.ecmpilot.protocol

/** Same bounded v1 framing as the gateway; independent of GATT MTU and JSON fields. */
object BleFraming {
    const val HEADER = 8
    const val MAX_FRAGMENT = 180
    const val TIMEOUT_MS = 3000L
    private fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
    fun fragment(message: ByteArray, token: Int, offset: Int, capacity: Int): ByteArray {
        require(message.size in 1..Protocol.MAX_BYTES && offset in message.indices && capacity > HEADER)
        val count = minOf(message.size - offset, minOf(capacity, MAX_FRAGMENT) - HEADER)
        return ByteArray(count + HEADER).apply {
            this[0] = 1
            this[1] = ((if (offset == 0) 1 else 0) or (if (offset + count == message.size) 2 else 0)).toByte()
            for ((at, value) in listOf(2 to token, 4 to message.size, 6 to offset)) {
                this[at] = value.toByte(); this[at + 1] = (value ushr 8).toByte()
            }
            message.copyInto(this, HEADER, offset, offset + count)
        }
    }
    class Receiver {
        private val buffer = ByteArray(Protocol.MAX_BYTES)
        var token = -1; private set
        private var total = 0
        private var used = 0
        private var touched = 0L
        private var active = false
        var complete = false; private set
        fun reset() { active = false; complete = false; used = 0; total = 0; token = -1 }
        fun feed(packet: ByteArray, now: Long): Boolean {
            if (complete) return false
            fun bad(): Boolean { reset(); return false }
            if (packet.size !in HEADER + 1..MAX_FRAGMENT || packet[0].toInt() != 1) return bad()
            val flags = packet[1].toInt() and 255
            val incomingToken = u16(packet, 2); val length = u16(packet, 4); val offset = u16(packet, 6)
            val count = packet.size - HEADER
            if (flags and 3 != flags || length !in 1..Protocol.MAX_BYTES || offset > length || count > length - offset ||
                (flags and 1 != 0) != (offset == 0) || (flags and 2 != 0) != (offset + count == length)) return bad()
            if (flags and 1 != 0) { reset(); active = true; token = incomingToken; total = length }
            if (!active || offset != 0 && now - touched > TIMEOUT_MS || token != incomingToken || total != length || offset != used) return bad()
            packet.copyInto(buffer, offset, HEADER)
            used += count; touched = now; complete = flags and 2 != 0
            return true
        }
        fun message(): ByteArray { check(complete); return buffer.copyOf(total) }
    }
}
