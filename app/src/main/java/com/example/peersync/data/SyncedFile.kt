package com.example.peersync.data

data class SyncedFile(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val path: String
) 