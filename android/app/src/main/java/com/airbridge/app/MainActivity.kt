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
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = mutableListOf<android.net.Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            val stream = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            }
            if (stream != null) uris.add(stream)
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
            val clip = intent.clipData
            if (clip != null) {
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).uri?.let { uris.add(it) }
                }
            }
        }
        if (uris.isNotEmpty()) {
            AirBridgeState.pendingUris = uris.distinct()
            AirBridgeState.directShareRequested = true
        }
    }

