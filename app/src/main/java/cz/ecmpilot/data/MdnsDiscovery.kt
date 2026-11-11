package cz.ecmpilot.data

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Bounded foreground lookup. Discovery/resolve traffic never sends a vehicle action. */
@Suppress("DEPRECATION")
internal suspend fun discoverGateways(context: Context, network: Network, savedId: String, diagnostic: (String) -> Unit): List<DiscoveredGateway> {
    val manager = context.getSystemService(NsdManager::class.java) ?: return emptyList()
    val services = Channel<NsdServiceInfo>(32)
    val active = AtomicBoolean(true)
    val found = mutableListOf<DiscoveredGateway>()
    val lock = if (Build.VERSION.SDK_INT < 34)
        context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("ecmpilot-discovery")?.apply {
            setReferenceCounted(false); acquire()
        } else null
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {
            if (!active.get()) runCatching { manager.stopServiceDiscovery(this) }
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            if (active.get() && service.serviceType.trimEnd('.') == GATEWAY_SERVICE_TYPE.trimEnd('.') &&
                (Build.VERSION.SDK_INT < 33 || service.network == network)) services.trySend(service)
        }
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onDiscoveryStopped(type: String) { services.close() }
        override fun onStartDiscoveryFailed(type: String, code: Int) { services.close() }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }
    try {
        diagnostic("Looking for gateway via mDNS on local network $network")
        if (Build.VERSION.SDK_INT >= 33)
            manager.discoverServices(GATEWAY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, Executor { it.run() }, listener)
        else manager.discoverServices(GATEWAY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            for (service in services) {
                val resolved = withTimeoutOrNull(1200) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                        val resolver = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (continuation.isActive) continuation.resume(info)
                            }
                        }
                        continuation.invokeOnCancellation {
                            if (Build.VERSION.SDK_INT >= 34) runCatching { manager.stopServiceResolution(resolver) }
                        }
                        try { manager.resolveService(service, resolver) }
                        catch (_: RuntimeException) { if (continuation.isActive) continuation.resume(null) }
                    }
                } ?: continue
                if (Build.VERSION.SDK_INT >= 33 && resolved.network != network) continue
                val addresses = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses else listOfNotNull(resolved.host)
                val candidate = gatewayAdvertisement(resolved.attributes["id"]?.toString(Charsets.UTF_8),
                    resolved.attributes["proto"]?.toString(Charsets.UTF_8), resolved.port, addresses) ?: continue
                if (savedId.isNotBlank() && candidate.id != savedId) continue
                found.add(candidate)
                if (savedId.isNotBlank()) break
            }
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (error: RuntimeException) { diagnostic("mDNS unavailable: ${error.message}; trying configured address") }
    finally {
        active.set(false)
        runCatching { manager.stopServiceDiscovery(listener) }
        services.close()
        if (lock?.isHeld == true) lock.release()
    }
    return selectGateways(found, savedId)
}
