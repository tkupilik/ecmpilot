package cz.ecmpilot.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** UDP first; recover it in parallel with BLE without interrupting an in-flight request. */
class PreferredConnection(
    private val openUdp: suspend () -> GatewayConnection,
    private val currentBle: () -> GatewayConnection?,
    private val startBle: () -> Unit,
    private val stopBle: () -> Unit,
    private val discardBle: (GatewayConnection) -> Unit,
    private val probeUdp: suspend (GatewayConnection) -> Unit,
    private val canUseBle: () -> Boolean = { true },
    private val onResponse: (Status) -> Unit = {},
    private val onClose: () -> Unit = {},
    private val probeIntervalMs: Long = 5000,
    private val bleWaitMs: Long = 12000,
) : GatewayConnection {
    private val lifetime = Any()
    private val exchanges = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var udp: GatewayConnection? = null
    private var probing: GatewayConnection? = null
    @Volatile private var active: GatewayConnection? = null
    @Volatile private var bleEnabled = false
    @Volatile private var closed = false

    init {
        require(probeIntervalMs > 0 && bleWaitMs >= 0)
        scope.launch {
            while (isActive) {
                delay(probeIntervalMs)
                if (bleEnabled && udp == null) recoverUdp()
            }
        }
    }

    override val addressLabel get() = active?.addressLabel

    private fun retainUdp(candidate: GatewayConnection) = synchronized(lifetime) {
        if (closed) { candidate.close(); throw IOException("Gateway connection closed") }
        udp = candidate
    }

    private fun dropUdp(connection: GatewayConnection) = synchronized(lifetime) {
        if (udp === connection) udp = null
        connection.close()
    }

    /** A separate socket keeps failed probes from delaying requests carried over BLE. */
    private suspend fun recoverUdp() {
        var candidate: GatewayConnection? = null
        var retained = false
        try {
            val opened = openUdp()
            candidate = opened
            synchronized(lifetime) {
                if (closed) throw IOException("Gateway connection closed")
                probing = opened
            }
            probeUdp(opened)
            currentCoroutineContext().ensureActive()
            synchronized(lifetime) {
                if (!closed && bleEnabled && udp == null) {
                    udp = opened
                    retained = true
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: IOException) { /* Continue using BLE and try again after the interval. */ }
        finally {
            synchronized(lifetime) { if (probing === candidate) probing = null }
            if (!retained) candidate?.close()
        }
    }

    private suspend fun useUdp(connection: GatewayConnection, request: Request, retry: Boolean): Status {
        val response = connection.exchange(request, retry)
        onResponse(response)
        if (closed) throw IOException("Gateway connection closed")
        active = connection
        // Only release BLE at an application request boundary, after a real UDP reply.
        if (bleEnabled) { bleEnabled = false; stopBle() }
        return response
    }

    private suspend fun waitForBle(): GatewayConnection? {
        currentBle()?.let { return it }
        if (!canUseBle()) return null
        return withTimeoutOrNull(bleWaitMs) {
            while (true) {
                if (closed) throw IOException("Gateway connection closed")
                // UDP may recover before Bluetooth discovery/setup finishes.
                if (udp != null) return@withTimeoutOrNull null
                currentBle()?.let { return@withTimeoutOrNull it }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    override suspend fun exchange(request: Request, retry: Boolean): Status = exchanges.withLock {
        if (closed) throw IOException("Gateway connection closed")
        var failure: IOException = IOException("Local gateway unavailable")
        try {
            val primary = udp ?: if (!bleEnabled) openUdp().also { candidate ->
                try { currentCoroutineContext().ensureActive(); retainUdp(candidate) }
                catch (error: Exception) { candidate.close(); throw error }
            } else null
            if (primary != null) return@withLock useUdp(primary, request, retry)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: IOException) {
            failure = error
            udp?.let(::dropUdp)
        }

        if (closed) throw IOException("Gateway connection closed")
        if (!bleEnabled) { bleEnabled = true; startBle() }
        val fallback = waitForBle()
        if (fallback != null) {
            try {
                val response = fallback.exchange(request, retry)
                onResponse(response)
                if (closed) throw IOException("Gateway connection closed")
                active = fallback
                return@withLock response
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: IOException) { discardBle(fallback); failure = error }
        }
        // A recovery probe can finish while BLE is connecting or exchanging.
        udp?.let { recovered ->
            try { return@withLock useUdp(recovered, request, retry) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: IOException) { dropUdp(recovered); failure = error }
        }
        throw failure
    }

    override fun close() {
        synchronized(lifetime) {
            if (closed) return
            closed = true
            scope.cancel()
            udp?.close(); udp = null
            probing?.close(); probing = null
        }
        onClose()
    }
}
