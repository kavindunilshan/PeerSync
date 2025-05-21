package com.example.peersync.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
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
    private var wifiManager: WifiManager? = null
    private var context: Context? = null

    private fun hasRequiredPermissions(): Boolean {
        context?.let { ctx ->
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasNearbyDevicesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            val hasBackgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            return hasLocationPermission && hasNearbyDevicesPermission && hasBackgroundLocationPermission
        }
        return false
    }

    fun initializeWifiDirect(context: Context) {
        this.context = context
        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            channel = wifiP2pManager?.initialize(context, context.mainLooper, null)
            checkWifiState()
        } catch (e: SecurityException) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED
            ) }
        }
    }

    private fun checkWifiState() {
        try {
            val isWifiEnabled = wifiManager?.isWifiEnabled ?: false
            _uiState.update { it.copy(
                isWifiEnabled = isWifiEnabled,
                discoveryStatus = if (!isWifiEnabled) DiscoveryStatus.WIFI_DISABLED else DiscoveryStatus.IDLE
            ) }
            
            if (isWifiEnabled && hasRequiredPermissions()) {
                startDiscovery()
            }
        } catch (e: SecurityException) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED
            ) }
        }
    }

    fun enableWifi() {
        try {
            if (hasRequiredPermissions()) {
                wifiManager?.isWifiEnabled = true
                _uiState.update { it.copy(isEnablingWifi = true) }

                android.os.Handler(context?.mainLooper!!).postDelayed({
                    try {
                        if (wifiManager?.isWifiEnabled == true) {
                            startDiscovery()
                        }
                    } catch (e: SecurityException) {
                        _uiState.update { it.copy(
                            discoveryStatus = DiscoveryStatus.PERMISSION_DENIED
                        ) }
                    }
                }, 2000)
            } else {
                _uiState.update { it.copy(
                    discoveryStatus = DiscoveryStatus.PERMISSION_DENIED
                ) }
            }
        } catch (e: SecurityException) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED
            ) }
        }
    }

    fun getWifiP2pManager(): WifiP2pManager? = wifiP2pManager
    fun getChannel(): WifiP2pManager.Channel? = channel

    fun startDiscovery() {
        if (!hasRequiredPermissions()) {
            _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.PERMISSION_DENIED) }
            return
        }

        try {
            if (wifiManager?.isWifiEnabled != true) {
                _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.WIFI_DISABLED) }
                return
            }

            _uiState.update { it.copy(isDiscovering = true) }
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.STARTED) }
                    startContinuousDiscovery()
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
        } catch (e: SecurityException) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED,
                isDiscovering = false
            ) }
        }
    }

    private fun startContinuousDiscovery() {
        if (!hasRequiredPermissions()) return

        android.os.Handler(context?.mainLooper!!).postDelayed({
            try {
                if (uiState.value.isDiscovering && !uiState.value.isConnected) {
                    startDiscovery()
                }
            } catch (e: SecurityException) {
                _uiState.update { it.copy(
                    discoveryStatus = DiscoveryStatus.PERMISSION_DENIED
                ) }
            }
        }, 30000)
    }

    fun updatePeersList(devices: List<WifiP2pDevice>) {
        if (!hasRequiredPermissions()) return

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
        if (!hasRequiredPermissions()) {
            _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.PERMISSION_DENIED) }
            return
        }

        try {
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
                    startDiscovery()
                }
            })
        } catch (e: SecurityException) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED,
                isConnecting = false
            ) }
        }
    }

    fun updateConnectionInfo(info: WifiP2pInfo) {
        _uiState.update { it.copy(
            isConnected = info.groupFormed,
            isGroupOwner = info.isGroupOwner,
            groupOwnerAddress = info.groupOwnerAddress?.hostAddress,
            discoveryStatus = if (info.groupFormed) DiscoveryStatus.CONNECTED else DiscoveryStatus.IDLE,
            isDiscovering = false
        ) }
    }

    fun disconnectFromPeer() {
        if (!hasRequiredPermissions()) {
            _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.PERMISSION_DENIED) }
            return
        }

        try {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _uiState.update { it.copy(
                        isConnected = false,
                        isGroupOwner = false,
                        groupOwnerAddress = null,
                        selectedDevice = null,
                        discoveryStatus = DiscoveryStatus.IDLE
                    ) }
                    startDiscovery()
                }

                override fun onFailure(reason: Int) {
                    startDiscovery()
                }
            })
        } catch (e: SecurityException) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED
            ) }
        }
    }

    fun setSelectedFolder(path: String?) {
        _uiState.update { it.copy(selectedFolderPath = path) }
    }

    override fun onCleared() {
        super.onCleared()
        context = null
    }
}

data class WifiDirectUiState(
    val isDiscovering: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isGroupOwner: Boolean = false,
    val isWifiEnabled: Boolean = false,
    val isEnablingWifi: Boolean = false,
    val groupOwnerAddress: String? = null,
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.IDLE,
    val availablePeers: List<WifiPeerDevice> = emptyList(),
    val selectedDevice: WifiPeerDevice? = null,
    val selectedFolderPath: String? = null
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
    CONNECTED,
    CONNECTION_FAILED,
    WIFI_DISABLED,
    PERMISSION_DENIED
} 