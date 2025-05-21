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

    private val fileTransferService = FileTransferService()
    private val syncScope = CoroutineScope(Dispatchers.IO)
    private var syncFolder: File? = null

    companion object {
        private const val TAG = "SyncManager"
        private const val SYNC_FOLDER_NAME = "synced_files"
    }

    init {
        initializeSyncFolder()
    }

    private fun initializeSyncFolder() {
        syncFolder = File(context.filesDir, SYNC_FOLDER_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        updateFilesList()
    }

    fun startSyncServer() {
        fileTransferService.startServer { operation ->
            when (operation) {
                is FileOperation.Add -> handleFileAdd(operation)
                is FileOperation.Delete -> handleFileDelete(operation)
            }
        }
    }

    fun stopSyncServer() {
        fileTransferService.stopServer()
    }

    private fun handleFileAdd(operation: FileOperation.Add) {
        syncScope.launch {
            try {
                val targetFile = File(syncFolder, operation.fileName)
                operation.file.copyTo(targetFile, overwrite = true)
                operation.file.delete() // Delete temp file
                updateFilesList()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling file add", e)
            }
        }
    }

    private fun handleFileDelete(operation: FileOperation.Delete) {
        syncScope.launch {
            try {
                val file = File(syncFolder, operation.fileName)
                if (file.exists()) {
                    file.delete()
                    updateFilesList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling file delete", e)
            }
        }
    }

    suspend fun syncFile(sourceFile: File, hostAddress: String) {
        try {
            // Copy file to sync folder
            val targetFile = File(syncFolder, sourceFile.name)
            
            // Check if file exists and compare timestamps
            if (targetFile.exists() && targetFile.lastModified() >= sourceFile.lastModified()) {
                // Skip if local file is newer or same age
                Log.d(TAG, "Skipping file ${sourceFile.name} as local copy is newer or same age")
                return
            }
            
            // Copy file to sync folder
            sourceFile.copyTo(targetFile, overwrite = true)
            
            // Retry logic for sending file
            var attempts = 0
            var success = false
            var lastError: Exception? = null
            
            while (attempts < 3 && !success) {
                try {
                    // Send to peer
                    fileTransferService.sendFile(targetFile, hostAddress)
                    success = true
                } catch (e: Exception) {
                    lastError = e
                    attempts++
                    if (attempts < 3) {
                        Log.w(TAG, "Retry $attempts: Error syncing file ${sourceFile.name}", e)
                        kotlinx.coroutines.delay(1000L * attempts) // Exponential backoff
                    }
                }
            }
            
            if (!success) {
                throw lastError ?: Exception("Failed to sync file after 3 attempts")
            }
            
            updateFilesList()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing file ${sourceFile.name}", e)
            throw e
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