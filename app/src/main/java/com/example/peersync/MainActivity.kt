package com.example.peersync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.peersync.data.SyncedFile
import com.example.peersync.data.WifiPeerDevice
import com.example.peersync.receiver.WifiP2pReceiver
import com.example.peersync.ui.theme.PeerSyncTheme
import com.example.peersync.viewmodel.DiscoveryStatus
import com.example.peersync.viewmodel.WifiDirectViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private val viewModel: WifiDirectViewModel by viewModels()
    private lateinit var receiver: WifiP2pReceiver
    private lateinit var intentFilter: IntentFilter

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Take persistable URI permission
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setSelectedFolder(uri.toString())
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Copy the file to a temporary location
                val inputStream = contentResolver.openInputStream(uri)
                val fileName = getFileName(uri) ?: "unknown_file"
                val tempFile = File(cacheDir, fileName)
                
                inputStream?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Add the file to sync
                viewModel.addFile(tempFile)
                
                // Clean up temp file after a delay
                Handler(Looper.getMainLooper()).postDelayed({
                    tempFile.delete()
                }, 5000)
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }

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
                    WifiDirectScreen(
                        viewModel = viewModel,
                        onPickFile = { filePickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }) }
                    )
                }
            }
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        folderPickerLauncher.launch(intent)
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
fun WifiDirectScreen(
    viewModel: WifiDirectViewModel,
    onPickFile: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val localFiles by viewModel.localFiles.collectAsState()
    val syncedFiles by viewModel.syncedFiles.collectAsState()
    val context = LocalContext.current
    var showCreateFileDialog by remember { mutableStateOf(false) }
    
    // Basic permissions needed for WiFi Direct
    val basicPermissionsState = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
    )

    // Background location permission state (only for Android 11+)
    val backgroundLocationPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rememberMultiplePermissionsState(
            listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        )
    } else {
        null
    }

    // Request basic permissions first
    LaunchedEffect(Unit) {
        basicPermissionsState.launchMultiplePermissionRequest()
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

        // Permission Status Cards
        PermissionStatusCard(
            title = "Location Permissions",
            isGranted = basicPermissionsState.allPermissionsGranted,
            onRequest = { basicPermissionsState.launchMultiplePermissionRequest() }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && backgroundLocationPermissionState != null) {
            PermissionStatusCard(
                title = "Background Location",
                isGranted = backgroundLocationPermissionState.allPermissionsGranted,
                onRequest = {
                    if (basicPermissionsState.allPermissionsGranted) {
                        // For Android 11+, we need to send users to Settings
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    } else {
                        // Show message that basic permissions are needed first
                        // You might want to show a snackbar or dialog here
                    }
                }
            )
        }

        // WiFi Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.isWifiEnabled) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = if (uiState.isWifiEnabled) "WiFi is Enabled" else "WiFi is Disabled",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (uiState.isWifiEnabled) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer
                )

                if (!uiState.isWifiEnabled) {
                    Button(
                        onClick = { viewModel.enableWifi() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(if (uiState.isEnablingWifi) "Enabling WiFi..." else "Enable WiFi")
                    }
                }
            }
        }

        if (uiState.isConnected) {
            Text(
                text = "Connected to: ${uiState.selectedDevice?.deviceName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Add file button
            Button(
                onClick = {
                    try {
                        onPickFile()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error launching file picker", e)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text("Add File")
            }

            // Local files section
            if (localFiles.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = "Local Files",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(localFiles) { file ->
                            FileItem(file = file)
                        }
                    }

                    // Sync status
                    uiState.syncStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                status.contains("failed") -> MaterialTheme.colorScheme.error
                                status.contains("completed with errors") -> MaterialTheme.colorScheme.error
                                status.contains("completed successfully") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onBackground
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Sync button
                    Button(
                        onClick = { viewModel.syncFiles() },
                        enabled = uiState.syncStatus == null || !uiState.syncStatus!!.contains("Syncing"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(if (uiState.syncStatus?.contains("Syncing") == true) "Syncing..." else "Sync Files")
                    }
                }
            }

            // Synced files section
            if (syncedFiles.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = "Synced Files",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(syncedFiles) { file ->
                            FileItem(file = file)
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.disconnectFromPeer() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Text("Disconnect")
            }
        } else if (uiState.isWifiEnabled && basicPermissionsState.allPermissionsGranted &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || backgroundLocationPermissionState?.allPermissionsGranted == true)) {
            Button(
                onClick = { viewModel.startDiscovery() },
                enabled = !uiState.isDiscovering,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(if (uiState.isDiscovering) "Discovering..." else "Start Peer Discovery")
            }
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
            DiscoveryStatus.CONNECTED -> "Connected successfully!"
            DiscoveryStatus.CONNECTION_FAILED -> "Failed to connect. Please try again."
            DiscoveryStatus.WIFI_DISABLED -> "Please enable WiFi to use PeerSync"
            else -> null
        }

        statusMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = when (uiState.discoveryStatus) {
                    DiscoveryStatus.STARTED, DiscoveryStatus.CONNECTING, DiscoveryStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                    DiscoveryStatus.NO_PEERS -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.availablePeers.isNotEmpty() && !uiState.isConnected && uiState.isWifiEnabled) {
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
                        isConnected = uiState.isConnected && uiState.selectedDevice?.deviceAddress == peer.deviceAddress,
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
    isConnected: Boolean,
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
            
            if (isConnected) {
                Text(
                    text = "Connected",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
        } else {
                Button(
                    onClick = { onConnect(peer) }
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    title: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isGranted) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (isGranted) "Granted" else "Required",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isGranted) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            if (!isGranted) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Grant")
                }
            }
        }
    }
}

@Composable
fun FileItem(
    file: SyncedFile
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
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = formatFileSize(file.size),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun CreateTextFileDialog(
    onDismiss: () -> Unit,
    onCreateFile: (String, String) -> Unit
) {
    var fileName by remember { mutableStateOf(TextFieldValue()) }
    var fileContent by remember { mutableStateOf(TextFieldValue()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Create Text File")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = fileContent,
                    onValueChange = { fileContent = it },
                    label = { Text("Content") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = fileName.text.trim()
                    if (name.isNotEmpty()) {
                        onCreateFile(
                            if (!name.endsWith(".txt")) "$name.txt" else name,
                            fileContent.text
                        )
                    }
                },
                enabled = fileName.text.trim().isNotEmpty()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatFileSize(size: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var unit = 0
    while (value > 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}


