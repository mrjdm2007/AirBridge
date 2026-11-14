package com.airbridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

data class AirBridgePeer(val name: String, val host: String, val port: Int)

object AirBridgeState {
    val peers = androidx.compose.runtime.mutableStateListOf<AirBridgePeer>()
    var status by androidx.compose.runtime.mutableStateOf("Starting local discovery…")
    var lastReceived by androidx.compose.runtime.mutableStateOf("")
    var pendingUris by androidx.compose.runtime.mutableStateOf<List<android.net.Uri>>(emptyList())
    private val main = Handler(Looper.getMainLooper())
    fun update(block: () -> Unit) = main.post(block)
}

class NetworkTransferService : Service() {
    private lateinit var nsd: NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()
        createNotification()
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
            .setContentText("Discovering trusted devices on your local network")
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
                    val client = listener.accept()
                    workers.execute {
                        client.use { socket ->
                            try {
                                val name = AirBridgeProtocol.receive(this, socket)
                                AirBridgeState.update {
                                    AirBridgeState.lastReceived = "Received $name in Downloads/AirBridge"
                                    AirBridgeState.status = AirBridgeState.lastReceived
                                }
                            } catch (failure: Throwable) {
                                AirBridgeState.update { AirBridgeState.status = failure.localizedMessage ?: "Transfer failed" }
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
                if (info.serviceType.contains("_airbridge._tcp")) {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            if (host.contains(":")) return
                            val peer = AirBridgePeer(serviceInfo.serviceName, host, serviceInfo.port)
                            AirBridgeState.update {
                                if (AirBridgeState.peers.none { it.host == peer.host && it.port == peer.port }) AirBridgeState.peers.add(peer)
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                AirBridgeState.update { AirBridgeState.peers.removeAll { it.name == info.serviceName } }
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { AirBridgeState.update { AirBridgeState.status = "Discovery failed ($errorCode)" } }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }
        discovery = listener
        nsd.discoverServices("_airbridge._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun onDestroy() {
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { registration?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        workers.shutdownNow()
        super.onDestroy()
    }
}
