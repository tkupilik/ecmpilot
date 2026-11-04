package cz.ecmpilot.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import com.google.gson.JsonParser
import kotlin.concurrent.thread

class UdpConnectionTest {
    private fun response(id: String, gateway: String = "test-gateway", correlation: Long) = """{"v":0,"type":"status","gw":"$gateway","boot":"8a14f3041ff548b7ae74634061270b42","reply_to":"$id","seq":1,"now_ms":100,"correlation_id":$correlation}""".toByteArray()
    private fun clock() = System.nanoTime() / 1_000_000
    @Test fun lostReplyRetriesIdenticalDatagram() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3500
            val received = Collections.synchronizedList(mutableListOf<ByteArray>())
            val request = Protocol.request(newId(), "climate.set", "test-gateway", "8a14f3041ff548b7ae74634061270b42")
            val worker = thread(isDaemon = true) {
                repeat(2) { index ->
                    val p = DatagramPacket(ByteArray(1025), 1025); server.receive(p); received += p.data.copyOf(p.length)
                    if (index == 1) { val bytes = response(request.id, correlation = request.correlation!!.id); server.send(DatagramPacket(bytes, bytes.size, p.socketAddress)) }
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
                for (bytes in listOf(response(newId(), correlation = request.correlation!!.id), ByteArray(1500) { 32 }, response(request.id, correlation = request.correlation!!.id))) server.send(DatagramPacket(bytes, bytes.size, p.socketAddress))
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
                val bytes = response(request.id, "other-gateway", request.correlation!!.id); server.send(DatagramPacket(bytes, bytes.size, p.socketAddress))
            }
            UdpConnection(DatagramSocket().apply { connect(InetAddress.getLoopbackAddress(), server.localPort) }, ::clock).use {
                try { it.exchange(request); fail("Gateway mismatch must fail") } catch (_: ProtocolException) { }
            }
            worker.join(1000)
        }
    }

    @Test fun delayedReadSurvivesThreeSecondRttAndReadRetriesKeepBothIds() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val request = Protocol.request(newId(), "state.get", "test-gateway")
            val packets = Collections.synchronizedList(mutableListOf<ByteArray>())
            val worker = thread(isDaemon = true) {
                val first = DatagramPacket(ByteArray(1025), 1025); server.receive(first); packets += first.data.copyOf(first.length)
                Thread.sleep(3100)
                val bytes = response(request.id, correlation = request.correlation!!.id)
                server.send(DatagramPacket(bytes, bytes.size, first.socketAddress))
                server.soTimeout = 50
                try { while (true) { val p=DatagramPacket(ByteArray(1025),1025);server.receive(p);packets+=p.data.copyOf(p.length) } }
                catch (_: java.net.SocketTimeoutException) { }
            }
            UdpConnection(DatagramSocket().apply { connect(InetAddress.getLoopbackAddress(), server.localPort) }, ::clock).use {
                val status = it.exchange(request)
                assertTrue(status.roundTripMs >= 3000);assertEquals(request.correlation!!.id,status.correlationId)
            }
            worker.join(1000)
            assertTrue(packets.size >= 2); packets.forEach { assertArrayEquals(request.bytes(),it) }
        }
    }

    @Test fun matchingMessageWithWrongCorrelationCannotSatisfyAnExchange() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val request=Protocol.request(newId(),"state.get","test-gateway")
            val worker=thread(isDaemon=true) {
                val p=DatagramPacket(ByteArray(1025),1025);server.receive(p)
                val wrong=response(request.id,correlation=request.correlation!!.id+1)
                server.send(DatagramPacket(wrong,wrong.size,p.socketAddress))
                Thread.sleep(150)
                val correct=response(request.id,correlation=request.correlation!!.id)
                server.send(DatagramPacket(correct,correct.size,p.socketAddress))
            }
            UdpConnection(DatagramSocket().apply{connect(InetAddress.getLoopbackAddress(),server.localPort)},::clock).use {
                val result=it.exchange(request);assertTrue(result.roundTripMs>=100);assertEquals(request.correlation!!.id,result.correlationId)
            };worker.join(1000)
        }
    }

    @Test fun aLostReadLeavesTheSocketUsableAndLateRepliesCannotAnswerANewerRead() = runBlocking {
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout=8000
            val first=Protocol.request(newId(),"state.get","test-gateway")
            val second=Protocol.request(newId(),"state.get","test-gateway")
            val peers=Collections.synchronizedSet(mutableSetOf<Int>())
            val worker=thread(isDaemon=true) {
                while(true) {
                    val packet=DatagramPacket(ByteArray(1025),1025);server.receive(packet);peers+=packet.port
                    val id=JsonParser.parseString(String(packet.data,0,packet.length,Charsets.UTF_8)).asJsonObject.get("id").asString
                    if(id==first.id)continue // Lose all replies to the first read.
                    val old=response(first.id,correlation=first.correlation!!.id)
                    server.send(DatagramPacket(old,old.size,packet.socketAddress))
                    Thread.sleep(100)
                    val fresh=response(second.id,correlation=second.correlation!!.id)
                    server.send(DatagramPacket(fresh,fresh.size,packet.socketAddress));break
                }
            }
            val socket=DatagramSocket().apply{connect(InetAddress.getLoopbackAddress(),server.localPort)}
            UdpConnection(socket,::clock).use { connection ->
                try { connection.exchange(first);fail("The first read must time out") } catch(_:java.net.SocketTimeoutException) { }
                assertFalse(socket.isClosed)
                assertEquals(second.id,connection.exchange(second).replyTo)
            }
            worker.join(1000);assertEquals(1,peers.size)
        }
    }
}
