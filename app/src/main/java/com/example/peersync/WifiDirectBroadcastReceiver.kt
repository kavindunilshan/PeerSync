package com.example.peersync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity,
    private val onPeersAvailable: (List<WifiP2pDevice>) -> Unit
) : BroadcastReceiver() {
    private val TAG = "WiFiDirectBroadcastReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            // WIFI_P2P_STATE_CHANGED_ACTION — Wi-Fi Direct enabled/disabled.
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d(TAG, "Wi-Fi Direct is enabled")
                } else {
                    Log.d(TAG, "Wi-Fi Direct is not enabled")
                }
            }

            // WIFI_P2P_PEERS_CHANGED_ACTION — Peer list changed.
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                try {
                    // Request the updated list of peers
                    manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
                        onPeersAvailable(peers.deviceList.toList())
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception when requesting peers: ${e.message}")
                }
            }

            // WIFI_P2P_CONNECTION_CHANGED_ACTION — You connected/disconnected to/from a peer.
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                if (networkInfo?.isConnected == true) {
                    Log.d(TAG, "Device connected")
                    // Request connection info
                    manager.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed) {
                            Log.d(TAG, "Connection established - Group formed")
                            if (info.isGroupOwner) {
                                Log.d(TAG, "This device is the group owner")
                            } else {
                                Log.d(TAG, "Connected to group owner: ${info.groupOwnerAddress?.hostAddress}")
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "Device disconnected")
                }
            }

            // WIFI_P2P_THIS_DEVICE_CHANGED_ACTION — This device's details changed.
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }

                device?.let {
                    Log.d(TAG, "This device changed: ${it.deviceName}, status: ${it.status}")
                }
            }
        }
    }
}