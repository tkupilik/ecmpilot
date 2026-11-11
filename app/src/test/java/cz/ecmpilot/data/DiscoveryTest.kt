package cz.ecmpilot.data

import cz.ecmpilot.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException

class DiscoveryTest {
    private val wifi = InetAddress.getByName("192.168.1.170")
    private val ap = InetAddress.getByName("192.168.4.1")
    private fun candidate(id: String = "ecmpilot-112233445566", address: InetAddress = wifi) =
        gatewayAdvertisement(id, "0", 42840, listOf(address))!!

    @Test fun acceptsLanAndApAndRejectsBadAnnouncements() {
        assertEquals(listOf(wifi), candidate().addresses)
        assertEquals(listOf(ap), candidate(address = ap).addresses)
        assertNull(gatewayAdvertisement("gw", "1", 42840, listOf(wifi)))
        assertNull(gatewayAdvertisement(null, "0", 42840, listOf(wifi)))
        assertNull(gatewayAdvertisement("bad id", "0", 42840, listOf(wifi)))
        assertNull(gatewayAdvertisement("gw", "0", 0, listOf(wifi)))
        for (host in listOf("0.0.0.0", "224.0.0.251", "192.168.1.255", "8.8.8.8"))
            assertNull(gatewayAdvertisement("gw", "0", 42840, listOf(InetAddress.getByName(host))))
    }
    @Test fun findsSavedIdentityAfterAddressChangeAndNeverSelectsAnotherGateway() {
        val home = candidate(); val workplace = candidate(address = ap); val other = candidate("other")
        assertEquals(listOf(home, workplace), selectGateways(listOf(other, home, home, workplace), home.id))
        assertEquals(emptyList<DiscoveredGateway>(), selectGateways(listOf(other), home.id))
    }
    @Test fun firstConnectionRequiresUnambiguousIdentity() {
        assertEquals(listOf(candidate()), selectGateways(listOf(candidate(), candidate()), ""))
        try { selectGateways(listOf(candidate(), candidate("other")), ""); fail() }
        catch (_: AmbiguousGatewaysException) { }
    }

    private class Fake(private val id: String, private val failure: Exception? = null) : GatewayConnection {
        var closed = false
        val requests = mutableListOf<Request>()
        override fun close() { closed = true }
        override suspend fun exchange(request: Request, retry: Boolean): Status {
            requests.add(request)
            failure?.let { throw it }
            return Protocol.parse("""{"v":0,"type":"status","gw":"$id","boot":"${"b".repeat(32)}","reply_to":"${request.id}","seq":1,"now_ms":0,"info":{"name":"GW","security":"lab-plain","fields":[],"operations":{}}}""".toByteArray(), 0, 0)
        }
    }
    @Test fun verifiesReadOnlyInfoAndUsesAdvertisedPort() = runBlocking {
        val gateway = candidate(); val fake = Fake(gateway.id)
        val result = openDiscoveredConnection(listOf(gateway), gateway.id, {}) { address, port ->
            assertEquals(wifi, address); assertEquals(42840, port); fake
        }
        assertSame(fake, result); assertFalse(fake.closed)
        assertEquals(listOf("info.get"), fake.requests.map { it.json["op"].asString })
    }
    @Test fun wrongIdAndTimeoutCloseSocketAndAllowManualFallback() = runBlocking {
        for (fake in listOf(Fake("wrong"), Fake(candidate().id, SocketTimeoutException()))) {
            assertNull(openDiscoveredConnection(listOf(candidate()), candidate().id, {}) { _, _ -> fake })
            assertTrue(fake.closed)
            assertEquals(listOf("info.get"), fake.requests.map { it.json["op"].asString })
        }
        assertNull(openDiscoveredConnection(emptyList(), "", {}) { _, _ -> error("Must not open a socket") })
    }
    @Test fun cancellationClosesSocketAndNeverFallsThroughToAnotherEndpoint() = runBlocking {
        val fake = Fake(candidate().id, CancellationException())
        try { openDiscoveredConnection(listOf(candidate()), candidate().id, {}) { _, _ -> fake }; fail() }
        catch (_: CancellationException) { }
        assertTrue(fake.closed); assertEquals(1, fake.requests.size)
    }
}
