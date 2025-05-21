package com.example.peersync.viewmodel

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import androidx.lifecycle.ViewModel
import com.example.peersync.data.WifiPeerDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WifiDirectViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WifiDirectUiState())
    val uiState: StateFlow<WifiDirectUiState> = _uiState.asStateFlow()

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    fun initializeWifiDirect(context: Context) {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager?.initialize(context, context.mainLooper, null)
    }

    fun getWifiP2pManager(): WifiP2pManager? = wifiP2pManager
    fun getChannel(): WifiP2pManager.Channel? = channel

    fun startDiscovery() {
        _uiState.update { it.copy(isDiscovering = true) }
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.STARTED) }
            }

            override fun onFailure(reason: Int) {
                val status = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> DiscoveryStatus.UNSUPPORTED
                    WifiP2pManager.BUSY -> DiscoveryStatus.BUSY
                    WifiP2pManager.ERROR -> DiscoveryStatus.ERROR
                    else -> DiscoveryStatus.FAILED
                }
                _uiState.update { 
                    it.copy(
                        discoveryStatus = status,
                        isDiscovering = false
                    )
                }
            }
        })
    }

    fun updatePeersList(devices: List<WifiP2pDevice>) {
        val peerDevices = devices.map { device ->
            WifiPeerDevice(
                deviceName = device.deviceName,
                deviceAddress = device.deviceAddress,
                isGroupOwner = device.isGroupOwner,
                status = device.status
            )
        }
        _uiState.update { it.copy(
            availablePeers = peerDevices,
            discoveryStatus = if (peerDevices.isEmpty()) DiscoveryStatus.NO_PEERS else DiscoveryStatus.STARTED
        ) }
    }

    fun connectToDevice(device: WifiPeerDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _uiState.update { it.copy(
                    isConnecting = true,
                    selectedDevice = device,
                    discoveryStatus = DiscoveryStatus.CONNECTING
                ) }
            }

            override fun onFailure(reason: Int) {
                _uiState.update { it.copy(
                    isConnecting = false,
                    discoveryStatus = DiscoveryStatus.CONNECTION_FAILED
                ) }
            }
        })
    }
}

data class WifiDirectUiState(
    val isDiscovering: Boolean = false,
    val isConnecting: Boolean = false,
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.IDLE,
    val availablePeers: List<WifiPeerDevice> = emptyList(),
    val selectedDevice: WifiPeerDevice? = null
)

enum class DiscoveryStatus {
    IDLE,
    STARTED,
    FAILED,
    UNSUPPORTED,
    BUSY,
    ERROR,
    NO_PEERS,
    CONNECTING,
    CONNECTION_FAILED
} 