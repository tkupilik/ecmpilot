package cz.ecmpilot.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.OsConstants
import cz.ecmpilot.model.Settings
import cz.ecmpilot.protocol.GatewayConnection
import cz.ecmpilot.protocol.UdpConnection
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

fun openLocalConnection(
    context: Context, settings: Settings, clock: () -> Long,
    diagnostic: (String) -> Unit = {},
): GatewayConnection {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    // A VPN can advertise WIFI as its underlying transport too.
    val networks = manager.allNetworks.filter {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
    }
    val activeVpn = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    val network = networks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        ?: networks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
        ?: throw IOException("Connect the phone to the gateway's Wi-Fi or local Ethernet network")
    val address = network.getAllByName(settings.host).firstOrNull(::isLocalAddress)
        ?: throw IOException("Gateway must resolve to a local unicast address")
    var socket = DatagramSocket()
    try {
        socket.broadcast = false
        try {
            network.bindSocket(socket)
            diagnostic("UDP ${settings.host}:${settings.port} via local network $network")
        } catch (error: IOException) {
            val denied = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<ErrnoException>().any { it.errno == OsConstants.EPERM }
            if (!activeVpn || !denied) throw error
            // Non-bypassable VPNs reject binding to their underlying Wi-Fi.
            // A fresh unbound socket follows Android's VPN/routing policy.
            // Keep the physical LAN requirement and local-address validation.
            socket.close()
            socket = DatagramSocket()
            socket.broadcast = false
            diagnostic("VPN denied direct Wi-Fi/Ethernet binding (EPERM); using Android system routing to ${settings.host}:${settings.port}")
        }
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
