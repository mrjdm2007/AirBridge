package com.airbridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

data class AirBridgePeer(val name: String, val host: String, val port: Int)

object AirBridgeTransferControl {
    @Volatile var activeReceiveSocket: Socket? = null
    fun cancelReceive() { activeReceiveSocket?.close() }
}

object AirBridgeState {
    val peers = androidx.compose.runtime.mutableStateListOf<AirBridgePeer>()
    var status by androidx.compose.runtime.mutableStateOf("Starting local discovery…")
    var lastReceived by androidx.compose.runtime.mutableStateOf("")
    var receivedTextName by androidx.compose.runtime.mutableStateOf("")
    var receivedTextContent by androidx.compose.runtime.mutableStateOf<String?>(null)
    var transferProgress by androidx.compose.runtime.mutableFloatStateOf(0f)
    var transferActive by androidx.compose.runtime.mutableStateOf(false)
    var transferLabel by androidx.compose.runtime.mutableStateOf("")
    var transferredBytes by androidx.compose.runtime.mutableLongStateOf(0L)
    var totalTransferBytes by androidx.compose.runtime.mutableLongStateOf(0L)
    var transferSpeedBytesPerSecond by androidx.compose.runtime.mutableLongStateOf(0L)
    var pendingUris by androidx.compose.runtime.mutableStateOf<List<android.net.Uri>>(emptyList())
    var directShareRequested by androidx.compose.runtime.mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())
    fun update(block: () -> Unit) { main.post { block() } }
}

class NetworkTransferService : Service() {
    private lateinit var nsd: NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var server: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)
    private val receiveSlots = Semaphore(3)

    override fun onCreate() {
        super.onCreate()
        createNotification()
        // Keep multicast DNS packets available to NSD while this foreground service runs.
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("AirBridge-mDNS").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (failure: Exception) {
            AirBridgeState.update { AirBridgeState.status = "Multicast discovery unavailable: ${failure.localizedMessage}" }
        }
        startServer()
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        publishService()
        discoverPeers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("airbridge_transfer", "AirBridge", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(this, "airbridge_transfer")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("AirBridge is ready")
            .setContentText("Looking for your MacBook Air M1 on your phone hotspot")
            .setOngoing(true)
            .build()
        startForeground(77, notification)
    }

    private fun startServer() {
        workers.execute {
            try {
                val listener = ServerSocket(0)
                server = listener
                AirBridgeState.update { AirBridgeState.status = "Ready · waiting for a Mac on Wi-Fi" }
                while (!listener.isClosed) {
                    val client = listener.accept().apply {
                        tcpNoDelay = true
                        receiveBufferSize = 1024 * 1024
                        sendBufferSize = 1024 * 1024
                    }
                    if (!receiveSlots.tryAcquire()) {
                        client.close()
                        continue
                    }
                    workers.execute {
                        AirBridgeTransferControl.activeReceiveSocket = client
                        client.use { socket ->
                            try {
                                socket.soTimeout = 120_000
                                AirBridgeState.update { AirBridgeState.transferActive = true; AirBridgeState.transferProgress = 0f; AirBridgeState.transferLabel = "Receiving file"; AirBridgeState.transferredBytes = 0L; AirBridgeState.totalTransferBytes = 0L; AirBridgeState.transferSpeedBytesPerSecond = 0L }
                                var previousBytes = 0L
                                var previousTime = System.nanoTime()
                                var smoothedSpeed = 0.0
                                val received = AirBridgeProtocol.receive(this, socket) { done, total ->
                                    val now = System.nanoTime()
                                    val elapsed = (now - previousTime) / 1_000_000_000.0
                                    if (elapsed >= 0.15) {
                                        val instant = (done - previousBytes).toDouble() / elapsed
                                        smoothedSpeed = if (smoothedSpeed == 0.0) instant else smoothedSpeed * 0.7 + instant * 0.3
                                        previousBytes = done
                                        previousTime = now
                                    }
                                    AirBridgeState.update { AirBridgeState.transferProgress = if (total == 0L) 1f else done.toFloat() / total.toFloat(); AirBridgeState.transferredBytes = done; AirBridgeState.totalTransferBytes = total; AirBridgeState.transferSpeedBytesPerSecond = smoothedSpeed.toLong() }
                                }
                                socket.getOutputStream().write(0x06)
                                socket.getOutputStream().flush()
                                AirBridgeState.update {
                                    AirBridgeState.transferActive = false
                                    AirBridgeState.transferProgress = 1f
                                    AirBridgeState.transferredBytes = AirBridgeState.totalTransferBytes
                                    AirBridgeState.transferSpeedBytesPerSecond = 0L
                                    AirBridgeState.lastReceived = "Received ${received.name} in Downloads/AirBridge"
                                    AirBridgeState.receivedTextName = if (received.textContent != null) received.name else ""
                                    AirBridgeState.receivedTextContent = received.textContent
                                    AirBridgeState.status = AirBridgeState.lastReceived
                                }
                            } catch (failure: Throwable) {
                                AirBridgeState.update { AirBridgeState.transferActive = false; AirBridgeState.status = failure.localizedMessage ?: "Transfer failed" }
                            } finally {
                                AirBridgeTransferControl.activeReceiveSocket = null
                                receiveSlots.release()
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                AirBridgeState.update { AirBridgeState.status = failure.localizedMessage ?: "Local server stopped" }
            }
        }
    }

    private fun publishService() {
        val port = server?.localPort ?: run {
            // Server binds asynchronously; retry until it is ready.
            workers.execute {
                repeat(50) {
                    val ready = server
                    if (ready != null) {
                        AirBridgeState.update { register(ready.localPort) }
                        return@execute
                    }
                    Thread.sleep(100)
                }
            }
            return
        }
        register(port)
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "AirBridge Android"
            serviceType = "_airbridge._tcp."
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                AirBridgeState.update { AirBridgeState.status = "Discoverable as ${serviceInfo.serviceName}" }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AirBridgeState.update { AirBridgeState.status = "Network advertising failed ($errorCode)" }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discoverPeers() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("_airbridge._tcp") && info.serviceName.startsWith("AirBridge Mac")) {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            if (host.contains(":")) return
                            val peer = AirBridgePeer("MacBook Air M1", host, serviceInfo.port)
                            AirBridgeState.update {
                                if (AirBridgeState.peers.none { it.host == peer.host && it.port == peer.port }) AirBridgeState.peers.add(peer)
                                publishMacShareShortcut()
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                AirBridgeState.update {
                    AirBridgeState.peers.removeAll { it.name == info.serviceName }
                    if (AirBridgeState.peers.isEmpty()) {
                        getSystemService(android.content.pm.ShortcutManager::class.java)?.removeDynamicShortcuts(listOf("airbridge-mac"))
                    }
                }
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { AirBridgeState.update { AirBridgeState.status = "Discovery failed ($errorCode)" } }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }
        discovery = listener
        nsd.discoverServices("_airbridge._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun publishMacShareShortcut() {
        if (Build.VERSION.SDK_INT < 30) return
        val shortcut = android.content.pm.ShortcutInfo.Builder(this, "airbridge-mac")
            .setShortLabel("MacBook M1 Air")
            .setLongLabel("Send to MacBook M1 Air")
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            .setCategories(setOf("com.airbridge.app.SHARE_TARGET"))
            .setLongLived(true)
            .build()
        try {
            getSystemService(android.content.pm.ShortcutManager::class.java)?.pushDynamicShortcut(shortcut)
        } catch (failure: Exception) {
            AirBridgeState.status = "Share target unavailable: ${failure.localizedMessage ?: "system limit"}"
        }
    }

    override fun onDestroy() {
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { registration?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        workers.shutdownNow()
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
        super.onDestroy()
    }
}
