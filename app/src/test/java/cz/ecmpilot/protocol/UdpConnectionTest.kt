package cz.ecmpilot.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import kotlin.concurrent.thread

class UdpConnectionTest {
    private fun response(id: String, gateway: String = "test-gateway") = """{"v":0,"type":"status","gw":"$gateway","boot":"8a14f3041ff548b7ae74634061270b42","reply_to":"$id","seq":1,"now_ms":100}""".toByteArray()
    private fun clock() = System.nanoTime() / 1_000_000
    @Test fun lostReplyRetriesIdenticalDatagram() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3500
            val received = Collections.synchronizedList(mutableListOf<ByteArray>())
            val request = Protocol.request(newId(), "climate.set", "test-gateway", "8a14f3041ff548b7ae74634061270b42")
            val worker = thread(isDaemon = true) {
                repeat(2) { index ->
                    val p = DatagramPacket(ByteArray(1025), 1025); server.receive(p); received += p.data.copyOf(p.length)
                    if (index == 1) { val bytes = response(request.id); server.send(DatagramPacket(bytes, bytes.size, p.socketAddress)) }
                }
            }
            val socket = DatagramSocket().apply { connect(InetAddress.getLoopbackAddress(), server.localPort) }
            UdpConnection(socket, ::clock).use { assertEquals(request.id, it.exchange(request, retry = true).replyTo) }
            worker.join(1000)
            assertEquals(2, received.size); assertArrayEquals(received[0], received[1])
        }
    }
    @Test fun staleReplyIdAndOversizedPacketCannotSatisfyRead() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1500
            val request = Protocol.request(newId(), "state.get", "test-gateway")
            val worker = thread(isDaemon = true) {
                val p = DatagramPacket(ByteArray(1025), 1025); server.receive(p)
                for (bytes in listOf(response(newId()), ByteArray(1500) { 32 }, response(request.id))) server.send(DatagramPacket(bytes, bytes.size, p.socketAddress))
            }
            UdpConnection(DatagramSocket().apply { connect(InetAddress.getLoopbackAddress(), server.localPort) }, ::clock).use {
                assertEquals(request.id, it.exchange(request).replyTo)
            }
            worker.join(1000)
        }
    }
    @Test fun identityMismatchIsNotAccepted() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1500
            val request = Protocol.request(newId(), "info.get", "saved-gateway")
            val worker = thread(isDaemon = true) {
                val p = DatagramPacket(ByteArray(1025), 1025); server.receive(p)
                val bytes = response(request.id, "other-gateway"); server.send(DatagramPacket(bytes, bytes.size, p.socketAddress))
            }
            UdpConnection(DatagramSocket().apply { connect(InetAddress.getLoopbackAddress(), server.localPort) }, ::clock).use {
                try { it.exchange(request); fail("Gateway mismatch must fail") } catch (_: ProtocolException) { }
            }
            worker.join(1000)
        }
    }
}
