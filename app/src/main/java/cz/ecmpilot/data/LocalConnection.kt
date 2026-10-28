package cz.ecmpilot.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import cz.ecmpilot.model.Settings
import cz.ecmpilot.protocol.GatewayConnection
import cz.ecmpilot.protocol.UdpConnection
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

fun openLocalConnection(context: Context, settings: Settings, clock: () -> Long): GatewayConnection {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val networks = manager.allNetworks
    val network = networks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        ?: networks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
        ?: throw IOException("Connect the phone to the gateway's Wi-Fi or local Ethernet network")
    val address = network.getAllByName(settings.host).firstOrNull(::isLocalAddress)
        ?: throw IOException("Gateway must resolve to a local unicast address")
    val socket = DatagramSocket()
    try {
        socket.broadcast = false
        network.bindSocket(socket)
        socket.connect(address, settings.port)
        return UdpConnection(socket, clock)
    } catch (error: Exception) { socket.close(); throw error }
}
internal fun isLocalAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isMulticastAddress) return false
    if (address is Inet4Address && address.address.last().toInt() and 255 == 255) return false
    return address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
        (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
}
