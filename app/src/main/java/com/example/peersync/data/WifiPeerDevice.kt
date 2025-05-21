package com.example.peersync.data

data class WifiPeerDevice(
    val deviceName: String,
    val deviceAddress: String,
    val isGroupOwner: Boolean = false,
    val status: Int = 0
) 