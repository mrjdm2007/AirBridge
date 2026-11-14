package com.airbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101010)
private val Paper = Color(0xFFF5F5F3)
private val Muted = Color(0xFF858585)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AirBridgeScreen() }
    }
}

@Composable
private fun AirBridgeScreen() {
    var clipboardSync by remember { mutableStateOf(true) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Paper,
            surface = Color.White,
            primary = Ink,
            onPrimary = Color.White
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AirBridge", fontSize = 29.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("Your devices, connected.", fontSize = 13.sp, color = Muted)
                    }
                    Icon(Icons.Rounded.Computer, contentDescription = null, tint = Ink, modifier = Modifier.size(27.dp))
                }

                Column(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("YOUR DEVICES", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Muted, letterSpacing = 1.5.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Computer, contentDescription = null, tint = Ink)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("MacBook", fontWeight = FontWeight.SemiBold, color = Ink)
                            Text("Waiting for local connection", fontSize = 12.sp, color = Muted)
                        }
                        Surface(color = Color(0xFFF0F0EE), shape = RoundedCornerShape(50)) {
                            Text("Not connected", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 10.sp, color = Muted)
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(24.dp)).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                    Text("Ready to share", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("Choose files from the Android share menu to send them to your Mac.", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                }

                Row(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = Ink)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Clipboard sync", color = Ink, fontWeight = FontWeight.Medium)
                        Text("Mac → Android · Local connection", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = clipboardSync, onCheckedChange = { clipboardSync = it })
                }

                Spacer(Modifier.weight(1f))
                Text("PRIVATE BY DESIGN  ·  NO CLOUD  ·  DIRECT TRANSFER", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
    }
}
