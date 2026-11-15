package com.airbridge.app

import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetAddress
import java.net.Socket

private val Ink = Color(0xFFF4F4F2)
private val Paper = Color(0xFF101010)
private val Card = Color(0xFF1B1B1B)
private val Muted = Color(0xFF9A9A9A)

private fun formatTransferBytes(bytes: Long): String {
    if (bytes < 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) { value /= 1000.0; unit++ }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}

class MainActivity : ComponentActivity() {
    @Volatile private var activeSendSocket: Socket? = null
    @Volatile private var cancelSendRequested = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        val service = Intent(this, NetworkTransferService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        setContent { AirBridgeScreen(onSend = { peer -> sendPending(peer) }, onAddText = { text -> addTextItem(text) }, onCancelSend = { cancelPendingSend() }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = mutableListOf<android.net.Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            val stream = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            }
            stream?.let { uris.add(it) }
        } else {
            val streams = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            }
            streams?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { uris.add(it) }
            }
        }
        if (uris.isNotEmpty()) {
            AirBridgeState.pendingUris = uris.distinct()
            AirBridgeState.directShareRequested = true
        }
    }

    private fun addTextItem(text: String) {
        if (text.isBlank()) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "AirBridge-Text-${System.currentTimeMillis()}.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirBridge")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
            AirBridgeState.status = "Could not create the text item."
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Could not write text.")
            val ready = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, ready, null, null)
            AirBridgeState.pendingUris = AirBridgeState.pendingUris + uri
        } catch (failure: Throwable) {
            contentResolver.delete(uri, null, null)
            AirBridgeState.status = failure.localizedMessage ?: "Could not add text."
        }
    }

    private fun sendPending(peer: AirBridgePeer) {
        if (AirBridgeState.transferActive) return
        val uris = AirBridgeState.pendingUris
        if (uris.isEmpty()) {
            AirBridgeState.status = "Share a file to AirBridge first."
            return
        }
        cancelSendRequested = false
        AirBridgeState.transferActive = true
        AirBridgeState.transferLabel = "Preparing transfer"
        Thread(Runnable {
            try {
                uris.forEachIndexed { index, uri ->
                    if (cancelSendRequested) throw java.io.InterruptedIOException("Transfer cancelled.")
                    var previousBytes = 0L
                    var previousTime = System.nanoTime()
                    var smoothedSpeed = 0.0
                    AirBridgeProtocol.send(this, uri, InetAddress.getByName(peer.host), peer.port,
                        isCancelled = { cancelSendRequested }, onSocket = { activeSendSocket = it }) { done, total ->
                        val now = System.nanoTime()
                        val elapsed = (now - previousTime) / 1_000_000_000.0
                        if (elapsed >= 0.15) {
                            val instant = (done - previousBytes).toDouble() / elapsed
                            smoothedSpeed = if (smoothedSpeed == 0.0) instant else smoothedSpeed * 0.7 + instant * 0.3
                            previousBytes = done
                            previousTime = now
                        }
                        AirBridgeState.update {
                            AirBridgeState.transferActive = true
                            AirBridgeState.transferLabel = "Sending ${index + 1}/${uris.size}"
                            AirBridgeState.transferProgress = if (total == 0L) 1f else done.toFloat() / total.toFloat()
                            AirBridgeState.transferredBytes = done
                            AirBridgeState.totalTransferBytes = total
                            AirBridgeState.transferSpeedBytesPerSecond = smoothedSpeed.toLong()
                        }
                    }
                }
                AirBridgeState.update {
                    AirBridgeState.transferActive = false
                    AirBridgeState.transferProgress = 1f
                    AirBridgeState.status = "Sent ${uris.size} file(s) to ${peer.name}"
                    AirBridgeState.pendingUris = emptyList()
                }
            } catch (failure: Throwable) {
                AirBridgeState.update { AirBridgeState.transferActive = false; AirBridgeState.transferSpeedBytesPerSecond = 0L; AirBridgeState.status = if (cancelSendRequested) "Transfer cancelled" else (failure.localizedMessage ?: "Send failed") }
            }
        }).apply { isDaemon = true }.start()
    }

    private fun cancelPendingSend() {
        cancelSendRequested = true
        activeSendSocket?.close()
        AirBridgeTransferControl.cancelReceive()
        AirBridgeState.status = "Cancelling transfer…"
    }
}

@Composable
private fun AirBridgeScreen(onSend: (AirBridgePeer) -> Unit, onAddText: (String) -> Unit, onCancelSend: () -> Unit) {
    var selectedMode by remember { mutableStateOf("Wireless") }
    var textDraft by remember { mutableStateOf(TextFieldValue("")) }
    var category by remember { mutableStateOf("Files") }
    val context = LocalContext.current
    val hapticView = LocalView.current
    val buttonHaptic: () -> Unit = { hapticView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    LaunchedEffect(AirBridgeState.directShareRequested, AirBridgeState.peers.size, AirBridgeState.pendingUris) {
        if (AirBridgeState.directShareRequested && AirBridgeState.pendingUris.isNotEmpty()) {
            val target = AirBridgeState.peers.firstOrNull()
            if (target != null) {
                AirBridgeState.directShareRequested = false
                onSend(target)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
            AirBridgeState.pendingUris = (AirBridgeState.pendingUris + uris).distinct()
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Paper, surface = Card, primary = Color(0xFFF4F4F2), onPrimary = Color(0xFF101010), onSurface = Ink, onBackground = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AirBridge", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(if (AirBridgeState.peers.isEmpty()) "Waiting for connection" else "Connected · MacBook Air M1", fontSize = 11.sp, color = Muted)
                    }
                    Icon(Icons.Rounded.Computer, contentDescription = null, tint = Ink, modifier = Modifier.size(25.dp))
                }
                Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(13.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Wireless", "Wired").forEach { mode ->
                        val active = selectedMode == mode
                        Button(onClick = { selectedMode = mode }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (active) Ink else Card, contentColor = if (active) Paper else Muted),
                            shape = RoundedCornerShape(10.dp)) { Text(if (mode == "Wireless") "Wireless · Wi-Fi" else "Wired · USB", fontSize = 11.sp) }
                    }
                }
                if (selectedMode == "Wireless") {
                    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(17.dp)).padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(if (AirBridgeState.peers.isEmpty()) Muted else Color(0xFF68C98A), RoundedCornerShape(50)))
                            Spacer(Modifier.width(8.dp))
                            Text(if (AirBridgeState.peers.isEmpty()) "Waiting for connection" else "MacBook Air M1 connected", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink)
                        }
                        if (AirBridgeState.transferActive) {
                            Text(AirBridgeState.transferLabel, fontSize = 10.sp, color = Muted)
                            LinearProgressIndicator(progress = { AirBridgeState.transferProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Ink, trackColor = Paper)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${formatTransferBytes(AirBridgeState.transferredBytes)} / ${formatTransferBytes(AirBridgeState.totalTransferBytes)}", fontSize = 10.sp, color = Muted)
                                Text("${formatTransferBytes(AirBridgeState.transferSpeedBytesPerSecond)}/s", fontSize = 10.sp, color = Muted)
                            }
                            val remaining = (AirBridgeState.totalTransferBytes - AirBridgeState.transferredBytes).coerceAtLeast(0L)
                            val speed = AirBridgeState.transferSpeedBytesPerSecond
                            Text(if (speed > 0L) "${formatTransferBytes(remaining)} remaining · about ${((remaining + speed - 1) / speed)} sec" else "Calculating remaining time…", fontSize = 9.sp, color = Muted)
                            OutlinedButton(onClick = { buttonHaptic(); onCancelSend() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text("Cancel transfer", fontSize = 11.sp) }
                        }
                        Text("SEND SOMETHING", fontSize = 9.sp, color = Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            listOf("Files", "Photos", "Audio", "Apps").forEach { item ->
                                TextButton(onClick = { category = item }) {
                                    Text(item, fontSize = 10.sp, color = if (category == item) Ink else Muted, fontWeight = if (category == item) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                        Button(onClick = {
                            buttonHaptic()
                            val mime = when (category) {
                                "Photos" -> arrayOf("image/*")
                                "Audio" -> arrayOf("audio/*")
                                "Apps" -> arrayOf("application/vnd.android.package-archive")
                                else -> arrayOf("*/*")
                            }
                            picker.launch(mime)
                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper), shape = RoundedCornerShape(11.dp)) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp)); Text("Select ${category.lowercase()}", fontSize = 12.sp)
                        }
                        OutlinedTextField(value = textDraft, onValueChange = { textDraft = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Paste or type text to send", color = Muted, fontSize = 11.sp) }, minLines = 2, maxLines = 4,
                            shape = RoundedCornerShape(11.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Muted, unfocusedBorderColor = Color(0xFF383838), cursorColor = Ink))
                        OutlinedButton(onClick = { onAddText(textDraft.text); textDraft = TextFieldValue("") }, enabled = textDraft.text.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp)) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("Add text to transfer", fontSize = 11.sp)
                        }
                        if (AirBridgeState.pendingUris.isNotEmpty()) {
                            Text("${AirBridgeState.pendingUris.size} item(s) selected", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { AirBridgeState.pendingUris = emptyList() }, enabled = !AirBridgeState.transferActive, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("Clear", fontSize = 11.sp) }
                                Button(onClick = { buttonHaptic(); AirBridgeState.peers.firstOrNull()?.let(onSend) }, enabled = AirBridgeState.peers.isNotEmpty() && !AirBridgeState.transferActive, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper), shape = RoundedCornerShape(10.dp)) {
                                    Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Send", fontSize = 11.sp)
                                }
                            }
                        }
                        if (AirBridgeState.receivedTextContent != null) {
                            Row(
                                Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(AirBridgeState.receivedTextName, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text("Text received · ready to copy", color = Muted, fontSize = 9.sp)
                                }
                                TextButton(onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(ClipData.newPlainText("AirBridge text", AirBridgeState.receivedTextContent))
                                }) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy", fontSize = 11.sp)
                                }
                            }
                        }
                        if (AirBridgeState.status.isNotBlank()) Text(AirBridgeState.status, fontSize = 9.sp, color = Muted)
                    }
                    Text("Received files are saved to Downloads/AirBridge", color = Muted, fontSize = 10.sp)
                } else {
                    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(17.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Computer, contentDescription = null, tint = Ink, modifier = Modifier.size(30.dp))
                        Text("USB file transfer", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Connect your phone to your Mac with a data-capable cable. Unlock it and choose File transfer / Android Auto in the USB notification.", color = Muted, fontSize = 11.sp)
                        Text("Open AirBridge on your Mac and choose Wired USB to browse phone storage. MTP is used; ADB is not required.", color = Muted, fontSize = 11.sp)
                    }
                }
                Text(if (selectedMode == "Wireless") "LOCAL NETWORK · SHA-256 INTEGRITY · NOT ENCRYPTED" else "USB MTP · NO ADB", color = Muted, fontSize = 8.sp, letterSpacing = 0.8.sp)
            }
        }
    }
}