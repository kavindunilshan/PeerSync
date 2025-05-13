// File: WifiP2pReceiver.kt
package com.example.peersync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.widget.Toast

class WifiP2pReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val peersList: MutableList<WifiP2pDevice>,
    private val onDeviceSelected: (WifiP2pDevice) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Toast.makeText(context, "WiFi Direct ${if (isEnabled) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    manager.requestPeers(channel) { peers ->
                        peersList.clear()
                        peersList.addAll(peers.deviceList)
                    }
                } else {
                    Toast.makeText(context, "Location permission not granted", Toast.LENGTH_SHORT).show()
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo =
                    intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    Toast.makeText(context, "Connected to peer", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Disconnected from peer", Toast.LENGTH_SHORT).show()
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // Optional: show device info
            }
        }
    }
}
