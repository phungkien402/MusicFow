package com.example.data.model

data class AudioQuality(
    val id: String,
    val format: AudioFormat,
    val bitrate: String,
    val label: String,
    val description: String,
    val badge: String,
    val estimatedSizeFactor: Double // relative size estimation
) {
    companion object {
        val QUALITIES = listOf(
            AudioQuality(
                id = "mp3_320",
                format = AudioFormat.MP3,
                bitrate = "320 kbps",
                label = "MP3 - 320 kbps (Cực cao)",
                description = "Chất lượng âm thanh tối ưu, dải âm đầy đủ, độ nét phòng thu",
                badge = "HQ 320K",
                estimatedSizeFactor = 1.0
            ),
            AudioQuality(
                id = "m4a_256",
                format = AudioFormat.M4A,
                bitrate = "256 kbps",
                label = "M4A / AAC - 256 kbps (Chuẩn)",
                description = "Định dạng Apple Music/AAC sắc nét, tiết kiệm bộ nhớ",
                badge = "AAC 256K",
                estimatedSizeFactor = 0.8
            ),
            AudioQuality(
                id = "mp3_128",
                format = AudioFormat.MP3,
                bitrate = "128 kbps",
                label = "MP3 - 128 kbps (Tiết kiệm)",
                description = "Tải cực nhanh, phù hợp kết nối mạng yếu hoặc bộ nhớ ít",
                badge = "MP3 128K",
                estimatedSizeFactor = 0.4
            ),
            AudioQuality(
                id = "flac_lossless",
                format = AudioFormat.FLAC,
                bitrate = "Lossless",
                label = "FLAC - Lossless (Không nén)",
                description = "Âm thanh nguyên bản không nén chất lượng cao nhất cho audiophile",
                badge = "FLAC HI-RES",
                estimatedSizeFactor = 2.5
            ),
            AudioQuality(
                id = "wav_hires",
                format = AudioFormat.WAV,
                bitrate = "1411 kbps",
                label = "WAV - 1411 kbps Studio",
                description = "Dạng sóng PCM nguyên gốc, phát mượt trên mọi thiết bị",
                badge = "WAV PCM",
                estimatedSizeFactor = 3.0
            )
        )

        val DEFAULT = QUALITIES[0]
    }
}
