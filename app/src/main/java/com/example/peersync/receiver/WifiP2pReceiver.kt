package com.example.peersync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import com.example.peersync.viewmodel.DiscoveryStatus
import com.example.peersync.viewmodel.WifiDirectViewModel

class WifiP2pReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val viewModel: WifiDirectViewModel
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // WiFi Direct is enabled, we can proceed with discovery
                    viewModel.startDiscovery()
                } else {
                    // WiFi Direct is disabled
                    viewModel.updatePeersList(emptyList()) // Clear the peers list
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
                    viewModel.updatePeersList(peers.deviceList.toList())
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                // We'll handle connection state changes in a future update
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // We'll handle device state changes in a future update
            }
        }
    }
} 