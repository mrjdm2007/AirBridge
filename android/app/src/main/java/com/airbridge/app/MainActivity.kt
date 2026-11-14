package com.airbridge.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetAddress

private val Ink = Color(0xFFF4F4F2)
private val Paper = Color(0xFF101010)
private val Card = Color(0xFF1B1B1B)
private val Muted = Color(0xFF9A9A9A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        val service = Intent(this, NetworkTransferService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        setContent { AirBridgeScreen(onSend = { peer -> sendPending(peer) }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (uri != null) AirBridgeState.pendingUris = listOf(uri)
        } else if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val uris = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            if (uris != null) AirBridgeState.pendingUris = uris
        }
    }

    private fun sendPending(peer: AirBridgePeer) {
        val uris = AirBridgeState.pendingUris
        if (uris.isEmpty()) {
            AirBridgeState.status = "Share a file to AirBridge first."
            return
        }
        Thread(Runnable {
            try {
                uris.forEach { uri ->
                    AirBridgeProtocol.send(this, uri, InetAddress.getByName(peer.host), peer.port) { done, total ->
                        AirBridgeState.update { AirBridgeState.transferActive = true; AirBridgeState.transferLabel = "Sending file"; AirBridgeState.transferProgress = if (total == 0L) 1f else done.toFloat() / total.toFloat() }
                    }
                }
                AirBridgeState.update {
                    AirBridgeState.transferActive = false
                    AirBridgeState.transferProgress = 1f
                    AirBridgeState.status = "Sent ${uris.size} file(s) to ${peer.name}"
                    AirBridgeState.pendingUris = emptyList()
                }
            } catch (failure: Throwable) {
                AirBridgeState.update { AirBridgeState.transferActive = false; AirBridgeState.status = failure.localizedMessage ?: "Send failed" }
            }
        }).start()
    }
}

@Composable
private fun AirBridgeScreen(onSend: (AirBridgePeer) -> Unit) {
    var clipboardSync by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf("Wireless") }
    MaterialTheme(colorScheme = darkColorScheme(background = Paper, surface = Card, primary = Color(0xFFF4F4F2), onPrimary = Color(0xFF101010), onSurface = Ink, onBackground = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AirBridge", fontSize = 29.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("Moto Edge 50 Neo ↔ MacBook Air M1", fontSize = 12.sp, color = Muted)
                    }
                    Icon(Icons.Rounded.Computer, contentDescription = null, tint = Ink, modifier = Modifier.size(27.dp))
                }
                Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Wireless", "Wired").forEach { mode ->
                        val selected = selectedMode == mode
                        Button(
                            onClick = { selectedMode = mode },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Ink else Card,
                                contentColor = if (selected) Paper else Muted
                            )
                        ) { Text(if (mode == "Wireless") "Wireless · Wi-Fi" else "Wired · USB") }
                    }
                }
                if (selectedMode == "Wireless") {
                Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CONNECTION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.4.sp)
                    Text(AirBridgeState.status, color = Ink, fontSize = 13.sp)
                    if (AirBridgeState.transferActive) {
                        Text(AirBridgeState.transferLabel, color = Muted, fontSize = 11.sp)
                        LinearProgressIndicator(progress = { AirBridgeState.transferProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Ink, trackColor = Paper)
                        Text("${(AirBridgeState.transferProgress * 100).toInt()}%", color = Muted, fontSize = 10.sp)
                    }
                    if (AirBridgeState.pendingUris.isNotEmpty()) {
                        Text("${AirBridgeState.pendingUris.size} shared file(s) ready to send", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Text("YOUR MACBOOK AIR M1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.4.sp)
                if (AirBridgeState.peers.isEmpty()) {
                    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(18.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Computer, contentDescription = null, tint = Muted, modifier = Modifier.size(28.dp))
                        Text("Looking for your MacBook Air M1…", color = Ink, fontWeight = FontWeight.Medium)
                        Text("Connect your MacBook Air M1 to this phone’s hotspot.", color = Muted, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(AirBridgeState.peers) { peer ->
                            Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Computer, contentDescription = null, tint = Ink)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(peer.name, color = Ink, fontWeight = FontWeight.SemiBold)
                                    Text(peer.host, color = Muted, fontSize = 11.sp)
                                }
                                Button(onClick = { onSend(peer) }, enabled = AirBridgeState.pendingUris.isNotEmpty()) {
                                    Icon(Icons.Rounded.Send, contentDescription = null)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Send")
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = Ink)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Clipboard sync", color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Not implemented yet", color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = clipboardSync, onCheckedChange = { clipboardSync = it }, enabled = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, tint = Muted, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Received files are saved to Downloads/AirBridge", color = Muted, fontSize = 10.sp)
                }
                Spacer(Modifier.weight(1f, fill = false))
                } else {
                    Column(
                        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.Computer, contentDescription = null, tint = Ink, modifier = Modifier.size(34.dp))
                        Text("USB file transfer", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Connect your phone to your Mac with a data-capable USB cable. Unlock the phone and choose File transfer / Android Auto in the USB notification.",
                            color = Muted, fontSize = 12.sp
                        )
                        Text(
                            "Open AirBridge on your Mac and choose Wired USB to browse phone storage and transfer files. The Mac uses MTP; ADB is not required.",
                            color = Muted, fontSize = 12.sp
                        )
                        Text("Keep the phone unlocked while browsing.", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Text(
                    if (selectedMode == "Wireless") "LOCAL NETWORK · SHA-256 INTEGRITY · NOT ENCRYPTED" else "USB MTP · NO ADB",
                    color = Muted, fontSize = 9.sp, letterSpacing = 1.sp
                )
            }
        }
    }
}
