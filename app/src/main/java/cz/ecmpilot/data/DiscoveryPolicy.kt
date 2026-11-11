package cz.ecmpilot.data

import cz.ecmpilot.protocol.GatewayConnection
import cz.ecmpilot.protocol.Protocol
import cz.ecmpilot.protocol.newId
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

internal const val GATEWAY_SERVICE_TYPE = "_ecmpilot._udp."
internal const val DISCOVERY_TIMEOUT_MS = 3000L

internal data class DiscoveredGateway(val id: String, val port: Int, val addresses: List<InetAddress>)
internal class AmbiguousGatewaysException : IOException("Several gateways found. Disable automatic discovery and enter the address of your gateway.")

internal fun gatewayAdvertisement(id: String?, protocol: String?, port: Int, addresses: List<InetAddress>): DiscoveredGateway? {
    if (protocol != "0" || id == null || !Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}").matches(id) || port !in 1..65535) return null
    val local = addresses.filter(::isLocalAddress).distinct().sortedBy { if (it is Inet4Address) 0 else 1 }
    return if (local.isEmpty()) null else DiscoveredGateway(id, port, local)
}

internal fun selectGateways(found: List<DiscoveredGateway>, savedId: String): List<DiscoveredGateway> {
    val matching = found.filter { savedId.isBlank() || it.id == savedId }
    if (savedId.isBlank() && matching.map { it.id }.distinct().size > 1) throw AmbiguousGatewaysException()
    return matching.distinct()
}

internal suspend fun openDiscoveredConnection(found: List<DiscoveredGateway>, savedId: String,
    diagnostic: (String) -> Unit, open: (InetAddress, Int) -> GatewayConnection): GatewayConnection? {
    for (gateway in selectGateways(found, savedId)) {
        for (address in gateway.addresses.take(2)) {
            var connection: GatewayConnection? = null
            try {
                connection = open(address, gateway.port)
                // A discovered address receives only this read until its ID is checked.
                val reply = connection.exchange(Protocol.request(newId(), "info.get", gateway.id))
                if (reply.error != null || reply.info == null || reply.gw != gateway.id)
                    throw IOException("Discovered gateway identity does not match its reply")
                diagnostic("mDNS found ${gateway.id} at ${address.hostAddress}:${gateway.port}")
                return connection
            } catch (cancel: CancellationException) { connection?.close(); throw cancel }
            catch (error: Exception) {
                connection?.close()
                diagnostic("Discovered endpoint unavailable: ${error.message}")
            }
        }
    }
    return null
}

internal fun isLocalAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isMulticastAddress) return false
    if (address is Inet4Address && address.address.last().toInt() and 255 == 255) return false
    return address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
        (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
}
