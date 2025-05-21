package com.example.peersync.filesync

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class FileSyncManager(private val context: Context) {

    companion object {
        private const val SYNC_FOLDER_NAME = "PeerSync"
    }

    fun getSyncFolder(): File {
        val internalStorage = context.filesDir
        val syncFolder = File(internalStorage, SYNC_FOLDER_NAME)
        if (!syncFolder.exists()) {
            syncFolder.mkdirs()
        }
        return syncFolder
    }

    fun listFilesInSyncFolder(): List<File> {
        val folder = getSyncFolder()
        return folder.listFiles()?.toList() ?: emptyList()
    }

    fun readFileBytes(file: File): ByteArray? {
        return try {
            FileInputStream(file).use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveFileToSyncFolder(fileName: String, fileBytes: ByteArray): Boolean {
        return try {
            val newFile = File(getSyncFolder(), fileName)
            FileOutputStream(newFile).use { it.write(fileBytes) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isFileExists(fileName: String): Boolean {
        val file = File(getSyncFolder(), fileName)
        return file.exists()
    }
}
