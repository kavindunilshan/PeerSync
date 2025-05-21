package com.example.peersync

import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.peersync.data.WifiPeerDevice
import com.example.peersync.receiver.WifiP2pReceiver
import com.example.peersync.ui.theme.PeerSyncTheme
import com.example.peersync.viewmodel.DiscoveryStatus
import com.example.peersync.viewmodel.WifiDirectViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {
    private val viewModel: WifiDirectViewModel by viewModels()
    private lateinit var receiver: WifiP2pReceiver
    private lateinit var intentFilter: IntentFilter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initializeWifiDirect(this)

        // Initialize WiFi Direct receiver
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = WifiP2pReceiver(
            viewModel.getWifiP2pManager()!!,
            viewModel.getChannel()!!,
            viewModel
        )

        setContent {
            PeerSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WifiDirectScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WifiDirectScreen(viewModel: WifiDirectViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    val permissionsState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
        )
    )

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "PeerSync",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = { viewModel.startDiscovery() },
            enabled = !uiState.isDiscovering && permissionsState.allPermissionsGranted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(if (uiState.isDiscovering) "Discovering..." else "Start Peer Discovery")
        }

        // Status message
        val statusMessage = when (uiState.discoveryStatus) {
            DiscoveryStatus.STARTED -> "Searching for peers..."
            DiscoveryStatus.FAILED -> "Discovery failed. Please try again."
            DiscoveryStatus.UNSUPPORTED -> "WiFi Direct is not supported on this device."
            DiscoveryStatus.BUSY -> "WiFi Direct is busy. Please wait and try again."
            DiscoveryStatus.ERROR -> "An error occurred with WiFi Direct. Please checks if WiFi is enabled."
            DiscoveryStatus.NO_PEERS -> "No peers found nearby. Make sure other devices have WiFi Direct enabled."
            DiscoveryStatus.CONNECTING -> "Connecting to device..."
            DiscoveryStatus.CONNECTION_FAILED -> "Failed to connect. Please try again."
            else -> null
        }

        statusMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = when (uiState.discoveryStatus) {
                    DiscoveryStatus.STARTED, DiscoveryStatus.CONNECTING -> MaterialTheme.colorScheme.primary
                    DiscoveryStatus.NO_PEERS -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.availablePeers.isNotEmpty()) {
            Text(
                text = "Available Peers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(uiState.availablePeers) { peer ->
                    PeerDeviceItem(
                        peer = peer,
                        onConnect = { viewModel.connectToDevice(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun PeerDeviceItem(
    peer: WifiPeerDevice,
    onConnect: (WifiPeerDevice) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = peer.deviceName.ifEmpty { "Unknown Device" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = peer.deviceAddress,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            Button(
                onClick = { onConnect(peer) }
            ) {
                Text("Connect")
            }
        }
    }
}
