package com.example.peersync.sync

import android.content.Context
import android.util.Log
import com.example.peersync.data.SyncedFile
import com.example.peersync.service.FileOperation
import com.example.peersync.service.FileTransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class SyncManager(private val context: Context) {
    private val _syncedFiles = MutableStateFlow<List<SyncedFile>>(emptyList())
    val syncedFiles: StateFlow<List<SyncedFile>> = _syncedFiles.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val fileTransferService = FileTransferService()
    private val syncScope = CoroutineScope(Dispatchers.IO)
    private var syncFolder: File? = null
    private var isConnected = false
    private var peerAddress: String? = null
    private var onRequestFilesCallback: (() -> Unit)? = null

    companion object {
        private const val TAG = "SyncManager"
        private const val SYNC_FOLDER_NAME = "sync_folder"
    }

    fun onConnectionEstablished(peerAddress: String) {
        this.peerAddress = peerAddress
        isConnected = true
        createSyncFolder()
    }

    fun onConnectionTerminated() {
        isConnected = false
        peerAddress = null
        removeSyncFolder()
        _syncState.value = SyncState.Idle
    }

    private fun createSyncFolder() {
        syncFolder = File(context.filesDir, SYNC_FOLDER_NAME).apply {
            if (exists()) {
                deleteRecursively() // Clear any existing content
            }
            mkdirs()
        }
        updateFilesList()
    }

    private fun removeSyncFolder() {
        syncFolder?.let { folder ->
            if (folder.exists()) {
                folder.deleteRecursively()
            }
        }
        syncFolder = null
        _syncedFiles.value = emptyList()
    }

    fun startSyncServer() {
        if (!isConnected) return

        fileTransferService.startServer { operation ->
            when (operation) {
                is FileOperation.Add -> handleFileAdd(operation)
                is FileOperation.Delete -> handleFileDelete(operation)
                is FileOperation.RequestFiles -> handleRequestFiles()
            }
        }
    }

    fun stopSyncServer() {
        fileTransferService.stopServer()
    }

    private fun handleFileAdd(operation: FileOperation.Add) {
        if (!isConnected) return

        syncScope.launch {
            try {
                val targetFile = File(syncFolder, operation.fileName)
                operation.file.copyTo(targetFile, overwrite = true)
                operation.file.delete() // Delete temp file
                updateFilesList()
                _syncState.value = SyncState.FileReceived(operation.fileName)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling file add", e)
                _syncState.value = SyncState.Error("Failed to receive file: ${e.message}")
            }
        }
    }

    private fun handleFileDelete(operation: FileOperation.Delete) {
        if (!isConnected) return

        syncScope.launch {
            try {
                val file = File(syncFolder, operation.fileName)
                if (file.exists()) {
                    file.delete()
                    updateFilesList()
                    _syncState.value = SyncState.FileDeleted(operation.fileName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling file delete", e)
                _syncState.value = SyncState.Error("Failed to delete file: ${e.message}")
            }
        }
    }

    private fun handleRequestFiles() {
        if (!isConnected) return
        
        _syncState.value = SyncState.Syncing("Peer requested files, sending local files...")
        onRequestFilesCallback?.invoke()
    }

    suspend fun syncFile(sourceFile: File, hostAddress: String) {
        if (!isConnected) return

        try {
            val targetFile = File(syncFolder, sourceFile.name)

            // Always sync in connected mode
            sourceFile.copyTo(targetFile, overwrite = true)

            // Retry logic for sending file
            var attempts = 0
            var success = false
            var lastError: Exception? = null

            while (attempts < 3 && !success) {
                try {
                    fileTransferService.sendFile(targetFile, hostAddress)
                    success = true
                    _syncState.value = SyncState.FileSent(sourceFile.name)
                } catch (e: Exception) {
                    lastError = e
                    attempts++
                    if (attempts < 3) {
                        Log.w(TAG, "Retry $attempts: Error syncing file ${sourceFile.name}", e)
                        kotlinx.coroutines.delay(1000L * attempts)
                    }
                }
            }

            if (!success) {
                throw lastError ?: Exception("Failed to sync file after 3 attempts")
            }

            updateFilesList()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing file ${sourceFile.name}", e)
            _syncState.value = SyncState.Error("Failed to sync file: ${e.message}")
            throw e
        }
    }

    suspend fun performBidirectionalSync(localFiles: List<File>) {
        if (!isConnected || peerAddress == null) {
            _syncState.value = SyncState.Error("Not connected to peer")
            return
        }

        try {
            _syncState.value = SyncState.Syncing("Starting bidirectional sync...")
            
            // Set up callback for when peer requests files
            onRequestFilesCallback = {
                syncScope.launch {
                    try {
                        localFiles.forEach { file ->
                            try {
                                syncFile(file, peerAddress!!)
                                _syncState.value = SyncState.Syncing("Sent ${file.name} to peer")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending file ${file.name} to peer", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in request files callback", e)
                    }
                }
            }
            
            // Step 1: Send all local files to peer
            localFiles.forEach { file ->
                try {
                    syncFile(file, peerAddress!!)
                    _syncState.value = SyncState.Syncing("Sent ${file.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending file ${file.name}", e)
                }
            }

            // Step 2: Request peer to send their files back
            _syncState.value = SyncState.Syncing("Requesting files from peer...")
            try {
                fileTransferService.requestFilesFromPeer(peerAddress!!)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting files from peer", e)
            }
            
            // Add a small delay to ensure all files are processed
            kotlinx.coroutines.delay(2000)
            
            _syncState.value = SyncState.SyncCompleted("Bidirectional sync completed")
            
            // Clear sync state after a delay
            kotlinx.coroutines.delay(3000)
            _syncState.value = SyncState.Idle
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during bidirectional sync", e)
            _syncState.value = SyncState.Error("Bidirectional sync failed: ${e.message}")
        }
    }

    private fun updateFilesList() {
        syncFolder?.let { folder ->
            val files = folder.listFiles()?.map { file ->
                SyncedFile(
                    name = file.name,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    path = file.absolutePath
                )
            } ?: emptyList()
            _syncedFiles.value = files
        }
    }

    fun getSyncFolder(): File? = syncFolder
}

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val message: String) : SyncState()
    data class FileSent(val fileName: String) : SyncState()
    data class FileReceived(val fileName: String) : SyncState()
    data class FileDeleted(val fileName: String) : SyncState()
    data class SyncCompleted(val message: String) : SyncState()
    data class Error(val message: String) : SyncState()
} 