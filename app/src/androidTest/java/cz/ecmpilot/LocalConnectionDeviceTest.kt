package cz.ecmpilot

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.ecmpilot.data.openLocalConnection
import cz.ecmpilot.model.Settings
import cz.ecmpilot.model.Source
import cz.ecmpilot.protocol.Protocol
import cz.ecmpilot.protocol.newId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only device check. Does not reset settings or send vehicle commands. */
@RunWith(AndroidJUnit4::class)
class LocalConnectionDeviceTest {
    @Test fun gatewayQueriesFollowCurrentNetworkPolicy() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val hosts = arguments.getString("gatewayHosts")
        assumeTrue("Set gatewayHosts explicitly to enable real network queries", !hosts.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val vpn = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        Log.i("PilotNetworkTest", "Android active VPN=$vpn")
        val clock = { SystemClock.elapsedRealtime() }
        for (host in requireNotNull(hosts).split(',')) {
            val settings = Settings(source = Source.UDP, host = host)
            val messages = mutableListOf<String>()
            openLocalConnection(context, settings, clock) {
                messages.add(it); Log.i("PilotNetworkTest", it)
            }.use { connection ->
                val client = newId()
                val info = connection.exchange(Protocol.request(client, "info.get"))
                assertNull(info.error)
                assertNotNull(info.info)
                val state = connection.exchange(Protocol.request(client, "state.get", info.gw, info.boot))
                assertNull(state.error)
                assertNotNull(state.state)
                assertEquals(info.gw, state.gw)
                assertTrue(messages.isNotEmpty())
                Log.i("PilotNetworkTest", "$host: info/state OK, gateway=${info.gw}")
            }
        }
    }
}
