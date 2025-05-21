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
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.peersync.data.WifiPeerDevice
import com.example.peersync.data.SyncedFile
import com.example.peersync.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class WifiDirectViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WifiDirectUiState())
    val uiState: StateFlow<WifiDirectUiState> = _uiState.asStateFlow()

    private val _localFiles = MutableStateFlow<List<SyncedFile>>(emptyList())
    val localFiles: StateFlow<List<SyncedFile>> = _localFiles.asStateFlow()

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var wifiManager: WifiManager? = null
    private var context: Context? = null
    private var syncManager: SyncManager? = null
    private var localFolder: File? = null

    val syncedFiles: StateFlow<List<SyncedFile>> get() = syncManager?.syncedFiles ?: MutableStateFlow(emptyList())

    fun initializeWifiDirect(context: Context) {
        this.context = context
        localFolder = File(context.filesDir, "local_files").apply { mkdirs() }
        syncManager = SyncManager(context)
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
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.os.Handler(context?.mainLooper!!).postDelayed({
                    initiateDiscovery()
                }, 1000)
            } else {
                initiateDiscovery()
            }
        } catch (e: SecurityException) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED,
                isDiscovering = false
            ) }
        }
    }

    private fun initiateDiscovery() {
        if (!hasRequiredPermissions()) {
            _uiState.update { it.copy(
                discoveryStatus = DiscoveryStatus.PERMISSION_DENIED,
                isDiscovering = false
            ) }
            return
        }

        try {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.STARTED) }
                    startContinuousDiscovery()
                }

                override fun onFailure(reason: Int) {
                    val status = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> DiscoveryStatus.UNSUPPORTED
                        WifiP2pManager.BUSY -> {
                            // For Android 14+, retry after a short delay if busy
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                android.os.Handler(context?.mainLooper!!).postDelayed({
                                    startDiscovery()
                                }, 2000)
                                DiscoveryStatus.STARTED
                            } else {
                                DiscoveryStatus.BUSY
                            }
                        }
                        WifiP2pManager.ERROR -> {
                            // For Android 14+, check if WiFi is actually enabled
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && wifiManager?.isWifiEnabled == true) {
                                android.os.Handler(context?.mainLooper!!).postDelayed({
                                    startDiscovery()
                                }, 2000)
                                DiscoveryStatus.STARTED
                            } else {
                                DiscoveryStatus.ERROR
                            }
                        }
                        else -> DiscoveryStatus.FAILED
                    }
                    _uiState.update {
                        it.copy(
                            discoveryStatus = status,
                            isDiscovering = status == DiscoveryStatus.STARTED
                        )
                    }
                }
            }) ?: run {
                _uiState.update {
                    it.copy(
                        discoveryStatus = DiscoveryStatus.ERROR,
                        isDiscovering = false
                    )
                }
            }
        } catch (e: SecurityException) {
            _uiState.update {
                it.copy(
                    discoveryStatus = DiscoveryStatus.PERMISSION_DENIED,
                    isDiscovering = false
                )
            }
        } catch (e: IllegalArgumentException) {
            _uiState.update {
                it.copy(
                    discoveryStatus = DiscoveryStatus.ERROR,
                    isDiscovering = false
                )
            }
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

        if (info.groupFormed) {
            syncManager?.startSyncServer()
        } else {
            syncManager?.stopSyncServer()
        }
    }

    fun disconnectFromPeer() {
        if (!hasRequiredPermissions()) {
            _uiState.update { it.copy(discoveryStatus = DiscoveryStatus.PERMISSION_DENIED) }
            return
        }

        try {
            // Stop sync server before disconnecting
            syncManager?.stopSyncServer()
            
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

    fun addFile(sourceFile: File) {
        viewModelScope.launch {
            try {
                // Copy file to local folder
                val targetFile = File(localFolder, sourceFile.name)
                sourceFile.copyTo(targetFile, overwrite = true)
                updateLocalFilesList()
            } catch (e: Exception) {
                Log.e("WifiDirectViewModel", "Error adding file", e)
            }
        }
    }

    private fun updateLocalFilesList() {
        localFolder?.let { folder ->
            val files = folder.listFiles()?.map { file ->
                SyncedFile(
                    name = file.name,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    path = file.absolutePath
                )
            } ?: emptyList()
            _localFiles.value = files
        }
    }

    fun syncFiles() {
        viewModelScope.launch {
            try {
                val hostAddress = uiState.value.groupOwnerAddress ?: return@launch
                
                // Update transfer status
                _uiState.update { it.copy(syncStatus = "Syncing files...") }
                
                var hasErrors = false
                
                // Sync all local files to peer
                localFolder?.listFiles()?.forEach { file ->
                    try {
                        syncManager?.syncFile(file, hostAddress)
                    } catch (e: Exception) {
                        Log.e("WifiDirectViewModel", "Error syncing file ${file.name}", e)
                        hasErrors = true
                    }
                }
                
                // Update UI based on sync result
                _uiState.update { it.copy(
                    syncStatus = if (hasErrors) "Sync completed with errors" else "Sync completed successfully"
                ) }
                
                // Clear status after delay
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3000)
                    _uiState.update { it.copy(syncStatus = null) }
                }
            } catch (e: Exception) {
                Log.e("WifiDirectViewModel", "Error during sync", e)
                _uiState.update { it.copy(syncStatus = "Sync failed: ${e.message}") }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        context?.let { ctx ->
            // For Android 14 and above, we primarily need NEARBY_WIFI_DEVICES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            }
            // For Android 13
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNearbyDevices = ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED

                val hasLocation = ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                return hasNearbyDevices && hasLocation
            }
            // For Android 11-12
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasLocation = ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                return hasLocation && hasBackgroundLocation
            }
            // For Android 10 and below
            else {
                return ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        syncManager?.stopSyncServer()
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
    val selectedFolderPath: String? = null,
    val syncStatus: String? = null
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