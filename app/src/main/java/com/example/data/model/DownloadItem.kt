package com.example.data.model

enum class DownloadStatus {
    IDLE,
    ANALYZING,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

data class DownloadTask(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
    val source: SourceType,
    val quality: AudioQuality,
    val targetPlaylistId: Long? = null,
    val targetPlaylistName: String? = null,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val downloadSpeedText: String = "",
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val errorMessage: String? = null,
    val localFilePath: String? = null,
    val completedSongId: Long? = null
)
