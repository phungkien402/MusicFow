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
        val MP3_320 = AudioQuality(
            id = "mp3_320",
            format = AudioFormat.MP3,
            bitrate = "320 kbps",
            label = "MP3 - 320 kbps (Cực cao)",
            description = "Chất lượng âm thanh tối ưu, dải âm đầy đủ, hỗ trợ tốt mọi thiết bị",
            badge = "HQ 320K",
            estimatedSizeFactor = 1.0
        )

        val M4A_256 = AudioQuality(
            id = "m4a_256",
            format = AudioFormat.M4A,
            bitrate = "256 kbps",
            label = "M4A / AAC - 256 kbps (Chuẩn)",
            description = "Định dạng Apple Music/AAC sắc nét, trong trẻo, tối ưu dung lượng",
            badge = "AAC 256K",
            estimatedSizeFactor = 0.8
        )

        val WAV_HIRES = AudioQuality(
            id = "wav_hires",
            format = AudioFormat.WAV,
            bitrate = "1411 kbps",
            label = "WAV - 1411 kbps Studio PCM",
            description = "Dạng sóng PCM nguyên bản phòng thu, không nén, độ động tối đa",
            badge = "WAV PCM",
            estimatedSizeFactor = 3.0
        )

        val MP3_128 = AudioQuality(
            id = "mp3_128",
            format = AudioFormat.MP3,
            bitrate = "128 kbps",
            label = "MP3 - 128 kbps (Tiết kiệm)",
            description = "Tải cực nhanh, phù hợp kết nối mạng yếu hoặc tiết kiệm bộ nhớ",
            badge = "MP3 128K",
            estimatedSizeFactor = 0.4
        )

        val FLAC_LOSSLESS = AudioQuality(
            id = "flac_lossless",
            format = AudioFormat.FLAC,
            bitrate = "Lossless",
            label = "FLAC - Lossless (Không nén)",
            description = "Âm thanh nguyên bản chất lượng audiophile 24-bit/96kHz (chỉ link Lossless)",
            badge = "FLAC HI-RES",
            estimatedSizeFactor = 2.5
        )

        val QUALITIES = listOf(MP3_320, M4A_256, WAV_HIRES, MP3_128, FLAC_LOSSLESS)

        val DEFAULT = MP3_320

        /**
         * Returns ONLY the formats that can actually be extracted / downloaded
         * based on the source platform and URL.
         * YouTube and TikTok do not provide raw 24-bit FLAC streams,
         * so FLAC is excluded for them to avoid fallback to default sound.
         */
        fun getAvailableQualities(source: SourceType, url: String): List<AudioQuality> {
            return when (source) {
                SourceType.YOUTUBE -> listOf(
                    MP3_320,
                    M4A_256,
                    WAV_HIRES,
                    MP3_128
                )
                SourceType.TIKTOK -> listOf(
                    MP3_320,
                    M4A_256,
                    WAV_HIRES,
                    MP3_128
                )
                SourceType.DIRECT_URL -> {
                    val lower = url.lowercase()
                    when {
                        lower.contains(".flac") -> listOf(FLAC_LOSSLESS, WAV_HIRES, MP3_320)
                        lower.contains(".wav") -> listOf(WAV_HIRES, MP3_320)
                        lower.contains(".m4a") || lower.contains(".aac") -> listOf(M4A_256, MP3_320)
                        lower.contains(".mp3") -> listOf(MP3_320, MP3_128)
                        else -> listOf(
                            MP3_320,
                            M4A_256,
                            WAV_HIRES,
                            MP3_128
                        )
                    }
                }
                else -> listOf(
                    MP3_320,
                    M4A_256,
                    WAV_HIRES,
                    MP3_128
                )
            }
        }
    }
}
