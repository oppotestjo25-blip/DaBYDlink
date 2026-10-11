package com.dabydlink.phone

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dabydlink.shared.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Listens for the UDP beacon that the car app broadcasts on the local network. */
class CarScanner(context: Context, private val onChange: (List<Car>) -> Unit) {

    data class Car(val name: String, val host: String, val port: Int, val seenAt: Long)

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val cars = LinkedHashMap<String, Car>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        lock = wifi?.createMulticastLock("DaBYDlink")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        thread = Thread(::loop, "dbl-scanner").also { it.start() }
    }

    fun stop() {
        running = false
        socket?.close()
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    private fun loop() {
        val buf = ByteArray(512)
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            }
            socket = s
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val beacon = Protocol.parseBeacon(packet.data, packet.length) ?: continue
                    val host = packet.address.hostAddress ?: continue
                    synchronized(cars) {
                        cars[host] = Car(beacon.name, host, beacon.port, SystemClock.elapsedRealtime())
                    }
                } catch (_: SocketTimeoutException) {
                }
                publish()
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val list = synchronized(cars) {
            cars.values.removeAll { now - it.seenAt > 5000 }
            cars.values.toList()
        }
        main.post { if (running) onChange(list) }
    }
}
