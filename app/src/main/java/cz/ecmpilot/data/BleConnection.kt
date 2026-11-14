package cz.ecmpilot.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import cz.ecmpilot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object GatewayBle {
    val SERVICE: UUID = UUID.fromString("8b17e6b0-7d63-4e5a-9b6f-30c6ec004b00")
    val RX: UUID = UUID.fromString("8b17e6b0-7d63-4e5a-9b6f-30c6ec004b01")
    val TX: UUID = UUID.fromString("8b17e6b0-7d63-4e5a-9b6f-30c6ec004b02")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    fun permitted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        } else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
internal class BleConnection private constructor(private val clock: () -> Long) : GatewayConnection {
    private data class Event(val kind: Int, val status: Int = 0, val value: ByteArray? = null, val mtu: Int = 23)
    private val events = Channel<Event>(64)
    private val closed = AtomicBoolean()
    @Volatile var healthy = true; private set
    private lateinit var gatt: BluetoothGatt
    private lateinit var rx: BluetoothGattCharacteristic
    private var mtu = 23
    private var token = 0
    private val response = BleFraming.Receiver()
    override val addressLabel get() = "Bluetooth"
    private fun post(event: Event) {
        if (!closed.get() && !events.trySend(event).isSuccess) { healthy = false; events.close(IOException("Bluetooth receive queue full")) }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                healthy = false; post(Event(DISCONNECTED, status))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) post(Event(CONNECTED))
        }
        override fun onMtuChanged(g: BluetoothGatt, size: Int, status: Int) { post(Event(MTU, status, mtu = size)) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { post(Event(SERVICES, status)) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { post(Event(SUBSCRIBED, status)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { post(Event(WRITTEN, status)) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == GatewayBle.TX) post(Event(DATA, value = value.copyOf()))
        }
        @Deprecated("Used on Android before API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == GatewayBle.TX) post(Event(DATA, value = c.value?.copyOf()))
        }
    }
    private fun ingest(event: Event) {
        if (event.kind == DISCONNECTED) throw IOException("Bluetooth disconnected (${event.status})")
        if (event.kind == DATA && event.value != null) {
            val data = event.value
            if (data.size < BleFraming.HEADER) throw IOException("Invalid Bluetooth fragment")
            val incoming = (data[2].toInt() and 255) or ((data[3].toInt() and 255) shl 8)
            if (incoming == token && !response.feed(data, clock())) throw IOException("Invalid Bluetooth response framing")
        }
    }
    private suspend fun waitFor(kind: Int, timeout: Long = 3000): Event = withTimeout(timeout) {
        while (true) {
            val event = events.receive()
            ingest(event)
            if (event.kind == kind) {
                if (event.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth operation failed (${event.status})")
                return@withTimeout event
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    override suspend fun exchange(request: Request, retry: Boolean): Status {
        if (!healthy || closed.get()) throw IOException("Bluetooth disconnected")
        try {
            return withTimeout(if (retry) 8000L else 6500L) {
                response.reset(); token = (token + 1) and 65535
                while (true) { val event = events.tryReceive().getOrNull() ?: break; if (event.kind == DISCONNECTED) ingest(event) }
                val start = clock(); val message = request.bytes()
                var offset = 0
                while (offset < message.size) {
                    val fragment = BleFraming.fragment(message, token, offset, mtu - 3)
                    val accepted = if (Build.VERSION.SDK_INT >= 33)
                        gatt.writeCharacteristic(rx, fragment, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    else { rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; rx.value = fragment; gatt.writeCharacteristic(rx) }
                    if (!accepted) throw IOException("Bluetooth write unavailable")
                    waitFor(WRITTEN)
                    offset += fragment.size - BleFraming.HEADER
                }
                while (!response.complete) { ingest(events.receive()) }
                val received = clock()
                val status = Protocol.parse(response.message(), received, received - start)
                if (status.replyTo != request.id || request.correlation != null && status.correlationId != request.correlation?.id)
                    throw ProtocolException("Bluetooth reply does not match request")
                request.json.get("gw")?.takeUnless { it.isJsonNull }?.asString?.let {
                    if (it != status.gw) throw ProtocolException("Bluetooth gateway identity mismatch")
                }
                status
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("Bluetooth response timed out")
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        healthy = false
        if (::gatt.isInitialized) { runCatching { gatt.disconnect() }; gatt.close() }
        events.close(IOException("Bluetooth connection closed"))
    }
    companion object {
        private const val CONNECTED = 1; private const val DISCONNECTED = 2; private const val MTU = 3
        private const val SERVICES = 4; private const val SUBSCRIBED = 5; private const val WRITTEN = 6; private const val DATA = 7
        suspend fun connect(context: Context, device: BluetoothDevice, clock: () -> Long): BleConnection {
            val link = BleConnection(clock)
            try {
                withTimeout(15000) {
                    link.gatt = device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
                        ?: throw IOException("Cannot open Bluetooth gateway")
                    link.waitFor(CONNECTED, 10000)
                    if (link.gatt.requestMtu(185)) link.mtu = link.waitFor(MTU).mtu
                    if (!link.gatt.discoverServices()) throw IOException("Bluetooth service discovery unavailable")
                    link.waitFor(SERVICES, 5000)
                    val service = link.gatt.getService(GatewayBle.SERVICE) ?: throw IOException("Gateway Bluetooth service missing")
                    link.rx = service.getCharacteristic(GatewayBle.RX) ?: throw IOException("Gateway request characteristic missing")
                    val tx = service.getCharacteristic(GatewayBle.TX) ?: throw IOException("Gateway response characteristic missing")
                    val descriptor = tx.getDescriptor(GatewayBle.CCCD) ?: throw IOException("Gateway notification descriptor missing")
                    if (!link.gatt.setCharacteristicNotification(tx, true)) throw IOException("Cannot enable gateway notifications")
                    val accepted = if (Build.VERSION.SDK_INT >= 33)
                        link.gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; link.gatt.writeDescriptor(descriptor) }
                    if (!accepted) throw IOException("Cannot subscribe to gateway")
                    link.waitFor(SUBSCRIBED)
                }
                return link
            } catch (timeout: TimeoutCancellationException) {
                link.close(); currentCoroutineContext().ensureActive()
                throw SocketTimeoutException("Bluetooth connection setup timed out")
            } catch (error: Exception) { link.close(); throw error }
        }
    }
}

/** One foreground discovery loop; no vehicle wake or OBD action on connection. */
@SuppressLint("MissingPermission")
internal class GatewayBleConnector(
    private val context: Context, private val clientId: String, gatewayId: String,
    private val clock: () -> Long, private val diagnostic: (String) -> Unit,
) {
    @Volatile var expectedId = gatewayId; private set
    @Volatile private var link: BleConnection? = null
    private val lifetime = Any()
    private var closed = false
    private var discovery: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun current(): GatewayConnection? = link?.takeIf { it.healthy }
    fun acceptIdentity(identity: String) = synchronized(lifetime) {
        if (expectedId.isNotBlank() && expectedId != identity) throw IOException("Gateway identity changed")
        expectedId = identity
    }
    fun discard(connection: GatewayConnection) = synchronized(lifetime) {
        if (link === connection) { link = null; connection.close(); diagnostic("Bluetooth gateway connection lost") }
    }
    fun start() = synchronized(lifetime) {
        if (closed || discovery?.isActive == true) return@synchronized
        discovery = scope.launch {
            while (isActive) {
                if (link?.healthy == true) { delay(1000); continue }
                link?.close(); link = null
                if (!GatewayBle.permitted(context)) { delay(5000); continue }
                try {
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    if (adapter?.isEnabled != true) { delay(3000); continue }
                    val device = scan(adapter)
                    if (device == null) { delay(7000); continue }
                    val candidate = BleConnection.connect(context, device, clock)
                    try {
                        val info = candidate.exchange(Protocol.request(clientId, "info.get", expectedId.takeIf { it.isNotBlank() }))
                        if (info.error != null || info.info == null) throw IOException("Bluetooth gateway identification failed")
                        acceptIdentity(info.gw)
                        currentCoroutineContext().ensureActive()
                        val connecting = currentCoroutineContext()
                        synchronized(lifetime) {
                            connecting.ensureActive()
                            if (closed) { candidate.close(); throw CancellationException("Bluetooth transport closed") }
                            link = candidate
                        }
                        diagnostic("Bluetooth gateway ready; local UDP unavailable")
                    } catch (error: Exception) { candidate.close(); throw error }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { diagnostic("Bluetooth discovery: ${error.message ?: "unavailable"}") }
                delay(3000)
            }
        }
    }
    private suspend fun scan(adapter: BluetoothAdapter): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val results = Channel<Pair<String, BluetoothDevice>>(16)
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val mac = result.scanRecord?.getManufacturerSpecificData(0xffff) ?: return
                if (mac.size != 6) return
                val identity = "ecmpilot-" + mac.joinToString("") { "%02x".format(it.toInt() and 255) }
                if (expectedId.isBlank() || expectedId == identity) results.trySend(identity to result.device)
            }
            override fun onScanFailed(code: Int) { results.close(IOException("Bluetooth scan failed ($code)")) }
        }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GatewayBle.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
            if (expectedId.isNotBlank()) return withTimeoutOrNull(8000) { results.receive().second }
            // First setup: require exactly one gateway nearby rather than choosing by signal strength.
            val found = linkedMapOf<String, BluetoothDevice>()
            withTimeoutOrNull(5000) { while (true) { val (id, device) = results.receive(); found[id] = device } }
            return found.values.singleOrNull()
        } finally { runCatching { scanner.stopScan(callback) }; results.close() }
    }
    fun stop() = synchronized(lifetime) {
        discovery?.cancel(); discovery = null
        link?.close(); link = null
    }
    fun close() { synchronized(lifetime) { closed = true; scope.cancel(); stop() } }
}
