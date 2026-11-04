package cz.ecmpilot.protocol

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

interface GatewayConnection : Closeable {
    suspend fun exchange(request: Request, retry: Boolean = false): Status
}

object UdpPolicy {
    const val READ_TIMEOUT_MS = 5000L
    const val WRITE_TIMEOUT_MS = 6000L
    const val OFFLINE_MS = 15000L
    const val RESULT_TIMEOUT_MS = 35000L
}

/** The socket is connected to one endpoint, so the OS filters unrelated source addresses/ports. */
class UdpConnection(private val socket: DatagramSocket, private val clock: () -> Long) : GatewayConnection {
    override suspend fun exchange(request: Request, retry: Boolean): Status {
        val bytes = request.bytes()
        val start = clock()
        val sends = longArrayOf(0, 700, 1800, 3500)
        val timeout = if (retry) UdpPolicy.WRITE_TIMEOUT_MS else UdpPolicy.READ_TIMEOUT_MS
        var next = 0
        val buffer = ByteArray(Protocol.MAX_BYTES + 1)
        while (clock() - start < timeout) {
            currentCoroutineContext().ensureActive()
            val elapsed = clock() - start
            if (next < sends.size && elapsed >= sends[next]) {
                socket.send(DatagramPacket(bytes, bytes.size)); next++
            }
            val untilSend = if (next < sends.size) sends[next] - (clock() - start) else timeout
            socket.soTimeout = minOf(100L, untilSend.coerceAtLeast(1), (timeout - (clock() - start)).coerceAtLeast(1)).toInt()
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val received = clock()
                val status = try { Protocol.parse(buffer.copyOf(packet.length), received, received - start) } catch (_: ProtocolException) { continue }
                if (status.replyTo != request.id) continue
                if (request.correlation != null && status.correlationId != request.correlation?.id) continue
                val expectedGw = request.json.get("gw")?.takeUnless { it.isJsonNull }?.asString
                if (expectedGw != null && status.gw != expectedGw) throw ProtocolException("Gateway identity does not match saved gateway")
                return status
            } catch (_: SocketTimeoutException) { /* bounded wait; cancellation and retry checked above */ }
        }
        throw SocketTimeoutException("No matching reply from the local gateway")
    }
    override fun close() = socket.close()
}
