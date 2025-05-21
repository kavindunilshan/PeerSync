package com.example.peersync.viewmodel

import android.content.Context
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.FileObserver
import android.util.Log
import androidx.lifecycle.ViewModel
import com.example.peersync.data.WifiPeerDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class WifiDirectViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WifiDirectUiState())
    val uiState: StateFlow<WifiDirectUiState> = _uiState.asStateFlow()

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    private var folderObserver: FileObserver? = null
    private lateinit var syncFolder: File
    private var appContext: Context? = null
    fun setAppContext(context: Context) {
        appContext = context.applicationContext
    }

    fun addFilesToSyncFolder(uris: List<Uri>) {
        val context = appContext ?: return
        for (uri in uris) {
            val inputStream = context.contentResolver.openInputStream(uri) ?: continue
            val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "unknown_file"
            val destFile = File(syncFolder, fileName)

            copyStreamToFile(inputStream, destFile)
        }
    }

    private fun copyStreamToFile(inputStream: InputStream, destFile: File) {
        inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

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

    fun initializeSyncFolder(context: Context) {
        syncFolder = context.getExternalFilesDir("PeerSync") ?: context.filesDir
        if (!syncFolder.exists()) {
            syncFolder.mkdirs()
        }
        _uiState.update { it.copy(syncFolderPath = syncFolder.absolutePath) }
        startObservingFolder()
    }

    private fun startObservingFolder() {
        folderObserver?.stopWatching() // stop previous if any

        folderObserver = object : FileObserver(syncFolder.absolutePath, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null) {
                    Log.d("PeerSync", "FileObserver detected new file: $path")
                    // Update UI state to include this new file
                    val newFileUri = File(syncFolder, path).toURI()
                    // We want to update UI to show this new file:
                    _uiState.update { currentState ->
                        val existingFiles = currentState.syncedFiles.toMutableList()
                        if (existingFiles.none { it.path == path }) {
                            existingFiles.add(File(syncFolder, path))
                        }
                        currentState.copy(syncedFiles = existingFiles)
                    }

                    // TODO: Trigger sending the file over WiFi Direct here
                }
            }
        }
        folderObserver?.startWatching()
    }

    override fun onCleared() {
        super.onCleared()
        folderObserver?.stopWatching()
    }


    fun addFilesToSync(uris: List<Uri>) {
        _uiState.update {
            it.copy(syncFiles = it.syncFiles + uris)
        }
    }

}

data class WifiDirectUiState(
    val isDiscovering: Boolean = false,
    val isConnecting: Boolean = false,
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.IDLE,
    val availablePeers: List<WifiPeerDevice> = emptyList(),
    val selectedDevice: WifiPeerDevice? = null,
    val syncFiles: List<Uri> = emptyList(),
    val syncFolderPath: String? = null,
    val syncedFiles: List<File> = emptyList()
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