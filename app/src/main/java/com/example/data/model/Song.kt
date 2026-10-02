package com.example.data.model

enum class AudioFormat(val displayName: String, val extension: String, val isLossless: Boolean) {
    MP3("MP3", "mp3", false),
    M4A("M4A", "m4a", false),
    FLAC("FLAC", "flac", true),
    WAV("WAV", "wav", true),
    AAC("AAC", "aac", false),
    OGG("OGG", "ogg", false);

    companion object {
        fun fromExtension(ext: String): AudioFormat {
            return entries.find { it.extension.equals(ext, ignoreCase = true) } ?: MP3
        }
    }
}

enum class SourceType(val displayName: String) {
    OFFLINE("Ngoại tuyến"),
    YOUTUBE("YouTube"),
    TIKTOK("TikTok"),
    LOCAL_DEVICE("Thiết bị"),
    DIRECT_URL("Web URL")
}

data class Song(
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "SoundFlow Collection",
    val durationMs: Long,
    val filePath: String,
    val sourceUrl: String? = null,
    val format: AudioFormat = AudioFormat.MP3,
    val bitrate: String = "320 kbps",
    val fileSizeBytes: Long = 0,
    val isDownloaded: Boolean = true,
    val isFavorite: Boolean = false,
    val sourceType: SourceType = SourceType.OFFLINE,
    val thumbnailUrl: String? = null,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val playCount: Int = 0
) {
    val durationFormatted: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val fileSizeFormatted: String
        get() {
            if (fileSizeBytes <= 0) return "2.8 MB"
            val mb = fileSizeBytes.toDouble() / (1024 * 1024)
            return "%.1f MB".format(mb)
        }
}
