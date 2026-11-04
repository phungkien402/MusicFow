package com.example.data.downloader

import android.content.Context
import android.util.Log
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import com.example.data.model.Song
import com.example.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ParsedLinkMetadata(
    val originalUrl: String,
    val source: SourceType,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val estimatedDurationMs: Long,
    val directStreamUrl: String? = null
)

class AudioLinkExtractor(
    private val context: Context,
    private val youTubeResolver: YouTubeAudioResolver = NewPipeYouTubeResolver()
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Identifies platform and extracts track metadata
     */
    suspend fun analyzeUrl(rawUrl: String): Result<ParsedLinkMetadata> = withContext(Dispatchers.IO) {
        try {
            val url = rawUrl.trim()
            if (url.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Vui lòng nhập đường dẫn URL"))
            }

            val source = detectSource(url)
            when (source) {
                SourceType.YOUTUBE -> extractYouTubeMetadata(url)
                SourceType.TIKTOK -> extractTikTokMetadata(url)
                else -> extractDirectUrlMetadata(url)
            }
        } catch (e: Exception) {
            Log.e("AudioLinkExtractor", "Error analyzing URL", e)
            Result.failure(e)
        }
    }

    private fun detectSource(url: String): SourceType {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> SourceType.YOUTUBE
            lower.contains("tiktok.com") || lower.contains("vt.tiktok") || lower.contains("vm.tiktok") -> SourceType.TIKTOK
            else -> SourceType.DIRECT_URL
        }
    }

    private suspend fun extractYouTubeMetadata(url: String): Result<ParsedLinkMetadata> {
        val videoId = parseYouTubeId(url)
            ?: return Result.failure(IllegalArgumentException("Không tìm thấy video ID từ URL YouTube"))

        val infoRes = youTubeResolver.resolve(videoId)
        if (infoRes.isFailure) {
            return Result.failure(infoRes.exceptionOrNull() ?: Exception("Lỗi khi phân tích YouTube"))
        }

        val info = infoRes.getOrThrow()
        return Result.success(
            ParsedLinkMetadata(
                originalUrl = url,
                source = SourceType.YOUTUBE,
                title = info.title,
                artist = info.uploader,
                thumbnailUrl = info.thumbnailUrl,
                estimatedDurationMs = info.durationMs,
                directStreamUrl = null
            )
        )
    }

    private fun extractTikTokMetadata(url: String): Result<ParsedLinkMetadata> {
        var title = "TikTok Trending Sound"
        var author = "TikTok Music"
        var thumb: String? = null
        var directStream: String? = null

        try {
            // 1. Try TikWM API for real MP3 audio track
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val tikwmUrl = "https://www.tikwm.com/api/?url=$encoded"
            val tikwmReq = Request.Builder()
                .url(tikwmUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(tikwmReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        if (json.optInt("code", -1) == 0) {
                            val data = json.optJSONObject("data")
                            if (data != null) {
                                title = data.optString("title", title).take(60)
                                author = data.optJSONObject("author")?.optString("nickname", author) ?: author
                                thumb = data.optString("cover", thumb)
                                val musicUrl = data.optString("music", null)
                                val playUrl = data.optString("play", null)
                                directStream = if (!musicUrl.isNullOrBlank()) musicUrl else playUrl
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AudioLinkExtractor", "TikWM stream lookup note: ${e.message}")
        }

        if (title == "TikTok Trending Sound") {
            try {
                val oembedUrl = "https://www.tiktok.com/oembed?url=${url}"
                val request = Request.Builder().url(oembedUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            title = json.optString("title", title).take(60)
                            author = json.optString("author_name", author)
                            if (thumb == null) thumb = json.optString("thumbnail_url", null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AudioLinkExtractor", "TikTok oEmbed failed", e)
            }
        }

        return Result.success(
            ParsedLinkMetadata(
                originalUrl = url,
                source = SourceType.TIKTOK,
                title = if (title.isBlank()) "TikTok Viral Beat" else title,
                artist = if (author.isBlank()) "TikTok Artist" else author,
                thumbnailUrl = thumb,
                estimatedDurationMs = 60000L,
                directStreamUrl = directStream
            )
        )
    }

    private fun extractDirectUrlMetadata(url: String): Result<ParsedLinkMetadata> {
        var filename = url.substringAfterLast("/").substringBefore("?")
        if (filename.isBlank()) filename = "Web Media Track"
        else {
            filename = URLDecoder.decode(filename, "UTF-8")
        }

        val title = filename.substringBeforeLast(".")
        return Result.success(
            ParsedLinkMetadata(
                originalUrl = url,
                source = SourceType.DIRECT_URL,
                title = if (title.isBlank()) "Online Media Stream" else title,
                artist = "Web Source",
                thumbnailUrl = null,
                estimatedDurationMs = 180000L,
                directStreamUrl = url
            )
        )
    }

    private fun parseYouTubeId(url: String): String? {
        val pattern = Pattern.compile("(?:youtu\\.be\\/|youtube\\.com\\/(?:embed\\/|v\\/|watch\\?v=|watch\\?.+&v=|shorts\\/))([\\w-]{11})")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * Performs download with progress reporting and creates a playable audio file
     */
    suspend fun downloadAudio(
        metadata: ParsedLinkMetadata,
        quality: AudioQuality,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speed: String) -> Unit
    ): Result<Song> = withContext(Dispatchers.IO) {
        val downloadDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }
        val normalizedTitle = java.text.Normalizer.normalize(metadata.title, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
        val sanitized = normalizedTitle
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(40)
        val sanitizedTitle = if (sanitized.isNotEmpty()) sanitized else "track"
        val extension = when (quality.format) {
            AudioFormat.WAV -> "wav"
            AudioFormat.M4A -> "m4a"
            AudioFormat.FLAC -> "flac"
            else -> "mp3"
        }
        val targetFile = File(downloadDir, "${sanitizedTitle}_${System.currentTimeMillis()}.$extension")

        try {
            var streamCandidate = metadata.directStreamUrl

            if (metadata.source == SourceType.YOUTUBE) {
                val videoId = parseYouTubeId(metadata.originalUrl)
                    ?: run {
                        targetFile.delete()
                        return@withContext Result.failure(IllegalArgumentException("Không tìm thấy ID video YouTube"))
                    }

                onProgress(5, 0, (4.5 * 1024 * 1024 * quality.estimatedSizeFactor).toLong(), "Đang phân tích luồng âm thanh YouTube...")
                val res = youTubeResolver.resolve(videoId)
                if (res.isFailure) {
                    targetFile.delete()
                    val msg = res.exceptionOrNull()?.message ?: "Lỗi giải mã luồng YouTube"
                    return@withContext Result.failure(java.io.IOException("Lỗi giải mã YouTube: $msg"))
                }
                val info = res.getOrThrow()
                val best = info.audio
                if (best == null || best.url.isBlank()) {
                    targetFile.delete()
                    return@withContext Result.failure(java.io.IOException("Không tìm thấy luồng âm thanh phù hợp cho video này"))
                }
                streamCandidate = best.url
            }

            if (streamCandidate.isNullOrBlank()) {
                streamCandidate = if (metadata.originalUrl.startsWith("http") &&
                    !metadata.originalUrl.contains("youtube.com") &&
                    !metadata.originalUrl.contains("youtu.be") &&
                    !metadata.originalUrl.contains("tiktok.com")) {
                    metadata.originalUrl
                } else null
            }

            if (streamCandidate.isNullOrBlank()) {
                targetFile.delete()
                return@withContext Result.failure(java.io.IOException("Không có liên kết âm thanh khả dụng"))
            }

            val isYouTubeStream = (metadata.source == SourceType.YOUTUBE)
            val downloadedSuccess = downloadStreamWithProgress(streamCandidate, targetFile, onProgress, isYouTube = isYouTubeStream)

            if (!downloadedSuccess || !targetFile.exists() || targetFile.length() < 5000) {
                targetFile.delete()
                return@withContext Result.failure(
                    java.io.IOException(
                        "Không thể trích xuất âm thanh từ liên kết. Máy chủ tạm thời bận hoặc video bị giới hạn khu vực/bản quyền. Vui lòng thử lại sau ít phút."
                    )
                )
            }

            // Format detection from first 16 bytes
            val header = ByteArray(16)
            val bytesRead = try {
                FileInputStream(targetFile).use { it.read(header) }
            } catch (e: Exception) { 0 }
            val detectedFormat = if (bytesRead > 0) {
                AudioFormatDetector.detect(header.copyOf(bytesRead))
            } else null

            val realFormat = detectedFormat ?: if (quality == AudioQuality.AUTO) AudioFormat.MP3 else quality.format

            val finalFile = if (!targetFile.name.endsWith(".${realFormat.extension}", ignoreCase = true)) {
                val newFileName = targetFile.nameWithoutExtension + ".${realFormat.extension}"
                val renamed = File(targetFile.parentFile, newFileName)
                if (targetFile.renameTo(renamed)) renamed else targetFile
            } else {
                targetFile
            }

            var actualDuration = metadata.estimatedDurationMs
            var detectedBitrate: String? = null
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(finalFile.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    if (it > 0) actualDuration = it
                }
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { bps ->
                    if (bps > 0) {
                        val kbps = (bps + 500) / 1000
                        detectedBitrate = "$kbps kbps"
                    }
                }
                retriever.release()
            } catch (ignored: Exception) {}

            val realBitrate = detectedBitrate ?: "Không rõ"
            val downloadedBytes = finalFile.length()
            val totalBytes = downloadedBytes

            // Cache thumbnail locally so it is 100% available offline
            var localThumbPath = metadata.thumbnailUrl
            if (!metadata.thumbnailUrl.isNullOrBlank() && metadata.thumbnailUrl.startsWith("http")) {
                try {
                    val thumbFile = File(downloadDir, "${sanitizedTitle}_thumb_${System.currentTimeMillis()}.jpg")
                    val thumbRequest = Request.Builder().url(metadata.thumbnailUrl).build()
                    httpClient.newCall(thumbRequest).execute().use { thumbResponse ->
                        if (thumbResponse.isSuccessful) {
                            thumbResponse.body?.byteStream()?.use { input ->
                                FileOutputStream(thumbFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (thumbFile.exists() && thumbFile.length() > 0) {
                                localThumbPath = thumbFile.absolutePath
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioLinkExtractor", "Failed to cache thumbnail locally", e)
                }
            }

            onProgress(100, totalBytes, totalBytes, "Hoàn tất")

            val song = Song(
                title = metadata.title,
                artist = metadata.artist,
                album = when (metadata.source) {
                    SourceType.YOUTUBE -> "YouTube Audio"
                    SourceType.TIKTOK -> "TikTok Sound"
                    else -> "SoundFlow Downloads"
                },
                durationMs = actualDuration,
                filePath = finalFile.absolutePath,
                sourceUrl = metadata.originalUrl,
                format = realFormat,
                bitrate = realBitrate,
                fileSizeBytes = finalFile.length(),
                isDownloaded = true,
                isFavorite = false,
                sourceType = metadata.source,
                thumbnailUrl = localThumbPath,
                addedTimestamp = System.currentTimeMillis()
            )

            Result.success(song)
        } catch (e: Exception) {
            targetFile.delete()
            Log.e("AudioLinkExtractor", "Download failed", e)
            Result.failure(e)
        }
    }

    private fun downloadStreamWithProgress(
        streamUrl: String,
        targetFile: File,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speed: String) -> Unit,
        isYouTube: Boolean = false
    ): Boolean {
        return try {
            val chunkSize = 8 * 1024 * 1024L
            var downloaded = 0L
            var totalBytes = -1L
            var lastTime = System.currentTimeMillis()
            var bytesSinceLast = 0L

            while (totalBytes == -1L || downloaded < totalBytes) {
                val reqBuilder = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                if (isYouTube && (totalBytes == -1L || totalBytes > chunkSize)) {
                    val endByte = if (totalBytes > 0) minOf(downloaded + chunkSize - 1, totalBytes - 1) else (downloaded + chunkSize - 1)
                    reqBuilder.header("Range", "bytes=$downloaded-$endByte")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        targetFile.delete()
                        return false
                    }
                    val body = resp.body ?: run {
                        targetFile.delete()
                        return false
                    }

                    val contentType = resp.header("Content-Type")?.lowercase()
                    if (contentType != null && (contentType.startsWith("text/") || contentType.contains("html") || contentType.contains("json"))) {
                        targetFile.delete()
                        return false
                    }

                    val code = resp.code
                    if (code == 200) {
                        val len = body.contentLength()
                        totalBytes = if (len > 0) len else (5 * 1024 * 1024L)

                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            val stream = body.byteStream()
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                bytesSinceLast += bytesRead
                                val now = System.currentTimeMillis()
                                if (now - lastTime >= 250) {
                                    val speedKbps = (bytesSinceLast * 1000.0) / (now - lastTime) / 1024.0
                                    val speedText = if (speedKbps > 1024) "%.1f MB/s".format(speedKbps / 1024) else "%.0f KB/s".format(speedKbps)
                                    val percent = ((downloaded * 100) / totalBytes).toInt().coerceIn(1, 99)
                                    onProgress(percent, downloaded, totalBytes, speedText)
                                    lastTime = now
                                    bytesSinceLast = 0L
                                }
                            }
                            output.flush()
                        }
                        return targetFile.exists() && targetFile.length() > 5000
                    } else if (code == 206) {
                        if (totalBytes == -1L) {
                            val contentRange = resp.header("Content-Range")
                            val parsedTotal = contentRange?.substringAfterLast("/")?.trim()?.toLongOrNull()
                            totalBytes = parsedTotal ?: body.contentLength().takeIf { it > 0 } ?: (10 * 1024 * 1024L)
                        }

                        FileOutputStream(targetFile, true).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            val stream = body.byteStream()
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                bytesSinceLast += bytesRead
                                val now = System.currentTimeMillis()
                                if (now - lastTime >= 250) {
                                    val speedKbps = (bytesSinceLast * 1000.0) / (now - lastTime) / 1024.0
                                    val speedText = if (speedKbps > 1024) "%.1f MB/s".format(speedKbps / 1024) else "%.0f KB/s".format(speedKbps)
                                    val percent = ((downloaded * 100) / totalBytes).toInt().coerceIn(1, 99)
                                    onProgress(percent, downloaded, totalBytes, speedText)
                                    lastTime = now
                                    bytesSinceLast = 0L
                                }
                            }
                            output.flush()
                        }
                    } else {
                        targetFile.delete()
                        return false
                    }
                }
            }

            val ok = targetFile.exists() && targetFile.length() > 5000
            if (!ok) targetFile.delete()
            ok
        } catch (e: Exception) {
            targetFile.delete()
            false
        }
    }
}
