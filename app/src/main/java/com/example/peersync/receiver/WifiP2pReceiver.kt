package com.example.peersync.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.peersync.viewmodel.DiscoveryStatus
import com.example.peersync.viewmodel.WifiDirectViewModel

class WifiP2pReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val viewModel: WifiDirectViewModel
) : BroadcastReceiver() {

    private fun hasRequiredPermissions(context: Context): Boolean {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasNearbyDevicesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasBackgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasLocationPermission && hasNearbyDevicesPermission && hasBackgroundLocationPermission
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        when (intent?.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // WiFi Direct is enabled, we can proceed with discovery
                    if (hasRequiredPermissions(context)) {
                        try {
                            viewModel.startDiscovery()
                        } catch (e: SecurityException) {
                            viewModel.updateConnectionInfo(WifiP2pInfo())
                        }
                    }
                } else {
                    // WiFi Direct is disabled
                    viewModel.updatePeersList(emptyList()) // Clear the peers list
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (!hasRequiredPermissions(context)) {
                    viewModel.updatePeersList(emptyList())
                    return
                }

                try {
                    manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
                        viewModel.updatePeersList(peers.deviceList.toList())
                    }
                } catch (e: SecurityException) {
                    viewModel.updatePeersList(emptyList())
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                if (!hasRequiredPermissions(context)) {
                    viewModel.updateConnectionInfo(WifiP2pInfo())
                    return
                }

                try {
                    val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    
                    if (networkInfo?.isConnected == true) {
                        // We are connected with the other device, request connection info
                        manager.requestConnectionInfo(channel) { info ->
                            viewModel.updateConnectionInfo(info)
                        }
                    } else {
                        // We are disconnected
                        viewModel.updateConnectionInfo(WifiP2pInfo())
                    }
                } catch (e: SecurityException) {
                    viewModel.updateConnectionInfo(WifiP2pInfo())
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // Handle device state changes if needed
            }
        }
    }
} 