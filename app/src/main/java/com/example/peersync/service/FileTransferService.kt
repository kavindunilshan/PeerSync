package com.example.peersync.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class FileTransferService {
    private val _transferStatus = MutableStateFlow<TransferStatus>(TransferStatus.Idle)
    val transferStatus: StateFlow<TransferStatus> = _transferStatus.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false

    companion object {
        private const val PORT = 8888
        private const val BUFFER_SIZE = 8192
        private const val TAG = "FileTransferService"
    }

    fun startServer(onFileReceived: (FileOperation) -> Unit) {
        if (isServerRunning) return
        isServerRunning = true

        thread {
            try {
                serverSocket = ServerSocket(PORT)
                while (isServerRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        handleClient(client, onFileReceived)
                    } catch (e: IOException) {
                        if (isServerRunning) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting server", e)
                _transferStatus.value = TransferStatus.Error("Failed to start server: ${e.message}")
            } finally {
                stopServer()
            }
        }
    }

    private fun handleClient(client: Socket, onFileReceived: (FileOperation) -> Unit) {
        thread {
            try {
                val input = DataInputStream(client.getInputStream())
                
                // Read operation type
                val operationType = input.readUTF()
                val fileName = input.readUTF()
                
                when (operationType) {
                    "ADD" -> {
                        val fileSize = input.readLong()
                        receiveFile(input, fileName, fileSize, onFileReceived)
                    }
                    "DELETE" -> {
                        onFileReceived(FileOperation.Delete(fileName))
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error handling client", e)
                _transferStatus.value = TransferStatus.Error("Failed to handle client: ${e.message}")
            } finally {
                try {
                    client.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing client socket", e)
                }
            }
        }
    }

    private fun receiveFile(
        input: DataInputStream,
        fileName: String,
        fileSize: Long,
        onFileReceived: (FileOperation) -> Unit
    ) {
        var receivedSize = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        val tempFile = File.createTempFile("receiving_", fileName)
        val output = FileOutputStream(tempFile)

        try {
            while (receivedSize < fileSize) {
                val bytesRead = input.read(buffer, 0, BUFFER_SIZE.coerceAtMost((fileSize - receivedSize).toInt()))
                if (bytesRead == -1) break
                
                output.write(buffer, 0, bytesRead)
                receivedSize += bytesRead
                
                val progress = (receivedSize.toFloat() / fileSize * 100).toInt()
                _transferStatus.value = TransferStatus.Receiving(fileName, progress)
            }
            
            output.close()
            onFileReceived(FileOperation.Add(fileName, tempFile))
            _transferStatus.value = TransferStatus.Success
        } catch (e: IOException) {
            Log.e(TAG, "Error receiving file", e)
            _transferStatus.value = TransferStatus.Error("Failed to receive file: ${e.message}")
            tempFile.delete()
        }
    }

    suspend fun sendFile(file: File, hostAddress: String) = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, PORT), 5000)
                val output = DataOutputStream(socket.getOutputStream())

                output.writeUTF("ADD")
                output.writeUTF(file.name)
                output.writeLong(file.length())
                
                val input = FileInputStream(file)
                val buffer = ByteArray(BUFFER_SIZE)
                var sentSize = 0L
                
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    
                    output.write(buffer, 0, bytesRead)
                    sentSize += bytesRead
                    
                    val progress = (sentSize.toFloat() / file.length() * 100).toInt()
                    _transferStatus.value = TransferStatus.Sending(file.name, progress)
                }
                
                input.close()
                _transferStatus.value = TransferStatus.Success
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error sending file", e)
            _transferStatus.value = TransferStatus.Error("Failed to send file: ${e.message}")
        }
    }

    suspend fun sendDeleteOperation(fileName: String, hostAddress: String) = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, PORT), 5000)
                val output = DataOutputStream(socket.getOutputStream())

                output.writeUTF("DELETE")
                output.writeUTF(fileName)
                
                _transferStatus.value = TransferStatus.Success
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error sending delete operation", e)
            _transferStatus.value = TransferStatus.Error("Failed to send delete operation: ${e.message}")
        }
    }

    fun stopServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
    }
}

sealed class TransferStatus {
    object Idle : TransferStatus()
    data class Sending(val fileName: String, val progress: Int) : TransferStatus()
    data class Receiving(val fileName: String, val progress: Int) : TransferStatus()
    object Success : TransferStatus()
    data class Error(val message: String) : TransferStatus()
}

sealed class FileOperation {
    data class Add(val fileName: String, val file: File) : FileOperation()
    data class Delete(val fileName: String) : FileOperation()
} 