package com.example.peersync.ui.theme

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSyncAppUI(
    peers: List<WifiP2pDevice>,
    onDiscoverClicked: () -> Unit,
    onConnectClicked: (WifiP2pDevice) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PeerSync - WiFi Direct") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Discover button
            Button(
                onClick = onDiscoverClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Discover"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Discover Devices")
            }

            // Peers list
            Text(
                text = "Available Devices",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (peers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No devices found",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(peers) { device ->
                        DeviceItem(
                            device = device,
                            onConnectClicked = { onConnectClicked(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: WifiP2pDevice,
    onConnectClicked: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
//            Icon(
//                imageVector = Icons.Default.PhoneAndroid,
//                contentDescription = "Device",
//                tint = MaterialTheme.colorScheme.primary
//            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = device.deviceName.ifEmpty { "Unknown Device" },
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = device.deviceAddress,
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                val status = when (device.status) {
                    WifiP2pDevice.AVAILABLE -> "Available"
                    WifiP2pDevice.INVITED -> "Invited"
                    WifiP2pDevice.CONNECTED -> "Connected"
                    WifiP2pDevice.FAILED -> "Failed"
                    WifiP2pDevice.UNAVAILABLE -> "Unavailable"
                    else -> "Unknown"
                }

                Text(
                    text = "Status: $status",
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = onConnectClicked,
                enabled = device.status != WifiP2pDevice.CONNECTED
            ) {
                Text(
                    text = if (device.status == WifiP2pDevice.CONNECTED) "Connected" else "Connect"
                )
            }
        }
    }
}