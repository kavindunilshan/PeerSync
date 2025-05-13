package com.example.peersync

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.MaterialTheme
import com.example.peersync.ui.theme.FileSyncAppUI
import com.example.peersync.ui.theme.PeerSyncTheme

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    // Wi-Fi P2P components
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WifiDirectBroadcastReceiver
    private lateinit var intentFilter: IntentFilter

    // List of discovered peers
    private val peers = mutableStateListOf<WifiP2pDevice>()

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            initializeWifiDirect()
        } else {
            Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request permissions first
        checkAndRequestPermissions()

        setContent {
            PeerSyncTheme {
                FileSyncAppUI(
                    peers = peers,
                    onDiscoverClicked = { discoverPeers() },
                    onConnectClicked = { device -> connectToPeer(device) }
                )
            }
        }
    }

    private fun initializeWifiDirect() {
        // Initialize Wi-Fi Direct components
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        // Create broadcast receiver
        receiver = WifiDirectBroadcastReceiver(manager, channel, this) { newPeers ->
            peers.clear()
            peers.addAll(newPeers)
        }

        // Set up intent filters
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>().apply {
            // Basic permissions needed for WiFi Direct
            add(Manifest.permission.ACCESS_FINE_LOCATION) // Required for discovery
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            add(Manifest.permission.INTERNET)

            // For Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        val permissionsToRequest = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            // Permissions already granted, initialize Wi-Fi Direct
            initializeWifiDirect()
        }
    }

    private fun discoverPeers() {
        try {
            Toast.makeText(this, "Starting discovery...", Toast.LENGTH_SHORT).show()

            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Discovery Started", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reason: Int) {
                    val reasonStr = when(reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
                        WifiP2pManager.BUSY -> "System is busy, try again later"
                        WifiP2pManager.ERROR -> "Internal error"
                        else -> "Unknown error: $reason"
                    }
                    Toast.makeText(this@MainActivity, "Discovery Failed: $reasonStr", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Discovery failed: $reasonStr")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denial: ${e.message}")
            Toast.makeText(this, "Missing permissions for discovery", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        try {
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
            }

            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(this@MainActivity, "Connecting to ${device.deviceName}", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reason: Int) {
                    val reasonStr = when(reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                        WifiP2pManager.BUSY -> "System is busy, try again later"
                        WifiP2pManager.ERROR -> "Internal error"
                        else -> "Unknown error: $reason"
                    }
                    Toast.makeText(this@MainActivity, "Connection failed: $reasonStr", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Connection failed: $reasonStr")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denial: ${e.message}")
            Toast.makeText(this, "Missing permissions for connection", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::receiver.isInitialized && ::intentFilter.isInitialized) {
            registerReceiver(receiver, intentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::receiver.isInitialized) {
            unregisterReceiver(receiver)
        }
    }
}