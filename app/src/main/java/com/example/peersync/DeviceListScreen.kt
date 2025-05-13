package com.example.peersync

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeviceListScreen(peers: List<WifiP2pDevice>) {
    var selectedDevice by remember { mutableStateOf<WifiP2pDevice?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Available Devices", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(peers) { device ->
                Text(
                    text = device.deviceName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedDevice = device }
                        .padding(12.dp)
                )
            }
        }

        selectedDevice?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Selected: ${it.deviceName}")
        }
    }
}
