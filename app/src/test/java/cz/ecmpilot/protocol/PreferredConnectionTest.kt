package cz.ecmpilot.protocol

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class PreferredConnectionTest {
    private fun request(op: String = "state.get") = Protocol.request("a".repeat(32), op, "gw")

    private class Link(val label: String) : GatewayConnection {
        val seen = CopyOnWriteArrayList<Request>()
        val retries = CopyOnWriteArrayList<Boolean>()
        @Volatile var closed = false
        @Volatile var fail = false
        var respond: suspend (Request) -> Unit = {}
        override val addressLabel get() = label
        override suspend fun exchange(request: Request, retry: Boolean): Status {
            seen += request; retries += retry
            if (closed || fail) throw IOException("Link unavailable")
            respond(request)
            return Status("gw", "boot", request.id, 1, 0, null, null, null, null, 0, 0)
        }
        override fun close() { closed = true }
    }

    private class Setup(
        open: suspend () -> GatewayConnection,
        probeIntervalMs: Long = 60000,
        probe: suspend (GatewayConnection) -> Unit = { it.exchange(Protocol.request("a".repeat(32), "info.get", "gw")); Unit },
        permitted: Boolean = true,
    ) {
        val ble = Link("Bluetooth")
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        @Volatile var enabled = false
        @Volatile var available = true
        val mux = PreferredConnection(
            openUdp = open,
            currentBle = { ble.takeIf { enabled && available && !it.closed } },
            startBle = { enabled = true; starts.incrementAndGet(); Unit },
            stopBle = { enabled = false; stops.incrementAndGet(); ble.close() },
            discardBle = { it.close() },
            probeUdp = probe,
            canUseBle = { permitted },
            onClose = { enabled = false; ble.close() },
            probeIntervalMs = probeIntervalMs,
            bleWaitMs = 1500,
        )
    }

    private suspend fun eventually(test: () -> Boolean) = withTimeout(3000) {
        while (!test()) delay(5)
    }

    @Test fun healthyUdpDoesNotStartBluetoothOrRunRecovery() = runBlocking {
        val udp = Link("UDP")
        val opens = AtomicInteger()
        val probes = AtomicInteger()
        val s = Setup({ opens.incrementAndGet(); udp }, probeIntervalMs = 10, probe = { probes.incrementAndGet(); Unit })
        s.mux.use { mux ->
            repeat(3) { mux.exchange(request()) }
            delay(40)
            assertEquals("UDP", mux.addressLabel)
            assertEquals(1, opens.get())
            assertEquals(0, probes.get())
            assertEquals(0, s.starts.get())
            assertTrue(s.ble.seen.isEmpty())
        }
        assertTrue(udp.closed)
    }

    @Test fun failedUdpActionUsesExactlyTheSameRequestAndRetryOnBle() = runBlocking {
        val udp = Link("UDP").apply { fail = true }
        val s = Setup({ udp })
        val action = request("vehicle.refresh")
        val bytes = action.bytes()
        s.mux.use { mux ->
            mux.exchange(action, true)
            assertEquals("Bluetooth", mux.addressLabel)
            assertEquals(1, s.starts.get())
            assertTrue(udp.closed)
            assertSame(action, udp.seen.single())
            assertSame(action, s.ble.seen.single())
            assertArrayEquals(bytes, s.ble.seen.single().bytes())
            assertTrue(s.ble.retries.single())
        }
    }

    @Test fun noIpNetworkFallsBackAndDoesNotRetryUdpBeforeEveryBlePoll() = runBlocking {
        val opens = AtomicInteger()
        val s = Setup({ opens.incrementAndGet(); throw IOException("No Wi-Fi") })
        s.mux.use { mux ->
            repeat(4) { mux.exchange(request()) }
            assertEquals(1, opens.get())
            assertEquals(1, s.starts.get())
            assertEquals(4, s.ble.seen.size)
        }
    }

    @Test fun unavailableBluetoothDoesNotBlockWorkingUdp() = runBlocking {
        val s = Setup({ Link("UDP") }, permitted = false)
        s.mux.use { mux -> mux.exchange(request()); assertEquals("UDP", mux.addressLabel) }
        assertEquals(0, s.starts.get())
    }

    @Test fun failedRecoveryDoesNotPromoteUdpAndKeepsTrying() = runBlocking {
        val attempts = AtomicInteger()
        val candidates = CopyOnWriteArrayList<Link>()
        val s = Setup({
            if (attempts.incrementAndGet() == 1) throw IOException("No UDP")
            Link("UDP").also { candidates += it }
        }, probeIntervalMs = 10, probe = { throw IOException("No matching gateway reply") })
        s.mux.use { mux ->
            mux.exchange(request())
            eventually { candidates.count { it.closed } >= 2 }
            mux.exchange(request())
            assertEquals("Bluetooth", mux.addressLabel)
            assertEquals(0, s.stops.get())
        }
    }

    @Test fun backgroundInfoProbeReturnsToUdpAndReleasesBle() = runBlocking {
        val udp = Link("UDP")
        val opens = AtomicInteger()
        val s = Setup({ if (opens.incrementAndGet() == 1) throw IOException("No UDP"); udp }, probeIntervalMs = 10)
        s.mux.use { mux ->
            mux.exchange(request())
            eventually { udp.seen.isNotEmpty() }
            assertEquals("info.get", udp.seen.first().json.get("op").asString)
            assertFalse(s.ble.closed)
            withTimeout(3000) {
                while (mux.addressLabel != "UDP") { mux.exchange(request()); delay(5) }
            }
            assertTrue(s.ble.closed)
            assertEquals(1, s.stops.get())
            assertEquals(2, opens.get())
        }
    }

    @Test fun slowUdpProbeDoesNotDelayBleQueriesAndCloseCancelsProbe() = runBlocking {
        val udp = Link("UDP")
        val opens = AtomicInteger()
        val probeStarted = CompletableDeferred<Unit>()
        val probeCancelled = CompletableDeferred<Unit>()
        val s = Setup({ if (opens.incrementAndGet() == 1) throw IOException("No UDP"); udp }, probeIntervalMs = 10, probe = {
            probeStarted.complete(Unit)
            try { awaitCancellation() } finally { probeCancelled.complete(Unit) }
        })
        try {
            s.mux.exchange(request())
            withTimeout(3000) { probeStarted.await() }
            withTimeout(1000) { s.mux.exchange(request()) }
            assertEquals("Bluetooth", s.mux.addressLabel)
        } finally { s.mux.close() }
        withTimeout(3000) { probeCancelled.await() }
        assertTrue(udp.closed)
        assertTrue(s.ble.closed)
        delay(30)
        assertEquals(2, opens.get())
    }

    @Test fun recoveryDoesNotInterruptAnInflightBleAction() = runBlocking {
        val udp = Link("UDP")
        val opens = AtomicInteger()
        val allowProbe = CompletableDeferred<Unit>()
        val bleStarted = CompletableDeferred<Unit>()
        val finishBle = CompletableDeferred<Unit>()
        val s = Setup({ if (opens.incrementAndGet() == 1) throw IOException("No UDP"); udp }, probeIntervalMs = 10, probe = {
            allowProbe.await()
            it.exchange(request("info.get"))
        })
        s.mux.use { mux ->
            mux.exchange(request())
            s.ble.respond = { if (it.json.get("op").asString == "vehicle.refresh") { bleStarted.complete(Unit); finishBle.await() } }
            val action = request("vehicle.refresh")
            val pending = async(Dispatchers.Default) { mux.exchange(action, true) }
            withTimeout(3000) { bleStarted.await() }
            allowProbe.complete(Unit)
            eventually { udp.seen.isNotEmpty() }
            assertFalse(s.ble.closed)
            assertEquals(0, s.stops.get())
            finishBle.complete(Unit)
            withTimeout(3000) { pending.await() }
            assertEquals("Bluetooth", mux.addressLabel)
            withTimeout(3000) { while (mux.addressLabel != "UDP") { mux.exchange(request()); delay(5) } }
            assertTrue(s.ble.closed)
            assertFalse(udp.seen.any { it.id == action.id })
        }
    }

    @Test fun udpRecoveryCanWinWhileWaitingForBluetooth() = runBlocking {
        val udp = Link("UDP")
        val opens = AtomicInteger()
        val s = Setup({ if (opens.incrementAndGet() == 1) throw IOException("No UDP"); udp }, probeIntervalMs = 10)
        s.available = false
        s.mux.use { mux ->
            withTimeout(1000) { mux.exchange(request()) }
            assertEquals("UDP", mux.addressLabel)
            assertTrue(s.ble.closed)
        }
    }

    @Test fun udpLossAfterRecoveryCanRestartBluetooth() = runBlocking {
        val opens = AtomicInteger()
        val udp = Link("UDP")
        val secondBle = Link("Bluetooth again")
        var current: Link? = null
        val mux = PreferredConnection(
            openUdp = { if (opens.incrementAndGet() == 1) throw IOException("No UDP"); udp },
            currentBle = { current },
            startBle = { current = secondBle },
            stopBle = { current = null },
            discardBle = { current = null },
            probeUdp = { it.exchange(request("info.get")); Unit },
            probeIntervalMs = 10,
        )
        mux.use {
            mux.exchange(request())
            withTimeout(3000) { while (mux.addressLabel != "UDP") { mux.exchange(request()); delay(5) } }
            udp.fail = true
            mux.exchange(request())
            assertEquals("Bluetooth again", mux.addressLabel)
        }
    }
}
