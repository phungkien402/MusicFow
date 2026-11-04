package com.example.data.downloader

import android.content.Context
import android.util.Log
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import com.example.data.model.Song
import com.example.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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

class AudioLinkExtractor(private val context: Context) {

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

    private fun extractYouTubeMetadata(url: String): Result<ParsedLinkMetadata> {
        val videoId = parseYouTubeId(url)
        var title = "YouTube Audio Track"
        var artist = "YouTube Creator"
        var thumb = if (videoId != null) "https://img.youtube.com/vi/$videoId/hqdefault.jpg" else null
        var directStream: String? = null

        if (videoId != null) {
            // 1. Primary: High-speed YouTube to MP3 converter engine
            try {
                val loaderReq = Request.Builder()
                    .url("https://loader.to/ajax/download.php?format=mp3&url=https://www.youtube.com/watch?v=$videoId")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()
                httpClient.newCall(loaderReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success", false)) {
                                val t = json.optString("title", "")
                                if (t.isNotBlank()) title = t
                                val img = json.optString("thumbnail_url", "")
                                if (img.isNotBlank()) thumb = img

                                val immediate = json.optString("download_url", "")
                                if (immediate.isNotBlank() && (immediate.startsWith("http://") || immediate.startsWith("https://"))) {
                                    directStream = immediate
                                } else {
                                    val progressUrl = json.optString("progress_url", "")
                                    val streamId = json.optString("id", "")
                                    val pollTarget = if (progressUrl.isNotBlank()) progressUrl else "https://loader.to/ajax/progress.php?id=$streamId"
                                    directStream = "LOADER_PROGRESS:$pollTarget"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AudioLinkExtractor", "loader.to metadata lookup: ${e.message}")
            }

            // 2. Secondary fallback: YouTube oEmbed API for real title and author
            if (title == "YouTube Audio Track") {
                try {
                    val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
                    val request = Request.Builder().url(oembedUrl).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                val json = JSONObject(body)
                                title = json.optString("title", title)
                                artist = json.optString("author_name", artist)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (videoId != null) {
                        title = "YouTube Video #$videoId"
                    }
                }
            }
        }

        return Result.success(
            ParsedLinkMetadata(
                originalUrl = url,
                source = SourceType.YOUTUBE,
                title = title,
                artist = artist,
                thumbnailUrl = thumb,
                estimatedDurationMs = 215000L,
                directStreamUrl = directStream
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
        try {
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

            var downloadedBytes = 0L
            var totalBytes = (4.5 * 1024 * 1024 * quality.estimatedSizeFactor).toLong() // estimated 4.5MB * factor

            var streamCandidate = metadata.directStreamUrl

            // 1. YouTube Multi-Source Resilient Extraction Engine
            if (metadata.source == SourceType.YOUTUBE) {
                val videoId = parseYouTubeId(metadata.originalUrl)

                // Tier 1A: Check if initial extraction provided a polling progress URL
                if (streamCandidate != null && streamCandidate.startsWith("LOADER_PROGRESS:")) {
                    val pollUrl = streamCandidate.removePrefix("LOADER_PROGRESS:")
                    onProgress(10, 0, totalBytes, "Đang kết nối máy chủ xử lý YouTube (Tầng 1)...")
                    streamCandidate = pollLoaderProgress(pollUrl, totalBytes, onProgress)
                }

                // Tier 1B: Primary high-speed converter endpoint
                if (streamCandidate.isNullOrBlank() && videoId != null) {
                    onProgress(12, 0, totalBytes, "Đang trích xuất audio chất lượng cao...")
                    streamCandidate = tryLoaderConversion(
                        endpoint = "https://loader.to/ajax/download.php",
                        videoId = videoId,
                        totalEstimatedBytes = totalBytes,
                        onProgress = onProgress
                    )
                }

                // Tier 2: Redundant mirror converters
                if (streamCandidate.isNullOrBlank() && videoId != null) {
                    onProgress(15, 0, totalBytes, "Đang chuyển sang máy chủ dự phòng (Tầng 2)...")
                    streamCandidate = tryLoaderConversion(
                        endpoint = "https://en.loader.to/ajax/download.php",
                        videoId = videoId,
                        totalEstimatedBytes = totalBytes,
                        onProgress = onProgress
                    ) ?: tryLoaderConversion(
                        endpoint = "https://api.video-download-api.com/api/download.php",
                        videoId = videoId,
                        totalEstimatedBytes = totalBytes,
                        onProgress = onProgress
                    )
                }

                // Tier 3: Invidious Open Audio Streams
                if (streamCandidate.isNullOrBlank() && videoId != null) {
                    onProgress(20, 0, totalBytes, "Đang kết nối cổng Invidious audio (Tầng 3)...")
                    streamCandidate = tryInvidiousStream(videoId, onProgress)
                }

                // Tier 4: Piped Open Audio Streams
                if (streamCandidate.isNullOrBlank() && videoId != null) {
                    onProgress(25, 0, totalBytes, "Đang kết nối cổng Piped audio (Tầng 4)...")
                    streamCandidate = tryPipedStream(videoId, onProgress)
                }
            }

            // 2. Direct web link fallback for direct media files
            if (streamCandidate.isNullOrBlank()) {
                streamCandidate = if (metadata.originalUrl.startsWith("http") &&
                    !metadata.originalUrl.contains("youtube.com") &&
                    !metadata.originalUrl.contains("youtu.be") &&
                    !metadata.originalUrl.contains("tiktok.com")) {
                    metadata.originalUrl
                } else null
            }

            var downloadedRealMedia = false
            val finalCandidate = streamCandidate

            if (!finalCandidate.isNullOrBlank()) {
                try {
                    downloadedRealMedia = downloadStreamWithProgress(finalCandidate, targetFile, onProgress)
                } catch (e: Exception) {
                    Log.w("AudioLinkExtractor", "Real stream download failed: ${e.message}")
                    downloadedRealMedia = false
                }
            }

            // Verification: Ensure authentic audio was written without fake sample generation
            if (!downloadedRealMedia || !targetFile.exists() || targetFile.length() < 5000) {
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

            downloadedBytes = finalFile.length()
            totalBytes = downloadedBytes

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
            Log.e("AudioLinkExtractor", "Download failed", e)
            Result.failure(e)
        }
    }

    private suspend fun pollLoaderProgress(
        pollUrl: String,
        totalEstimatedBytes: Long,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speed: String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        var resolvedUrl: String? = null
        for (pollAttempt in 1..28) {
            if (pollAttempt > 1) delay(1500)
            try {
                val pollReq = Request.Builder()
                    .url(pollUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                httpClient.newCall(pollReq).execute().use { pResp ->
                    if (pResp.isSuccessful) {
                        val pBody = pResp.body?.string()
                        if (!pBody.isNullOrBlank()) {
                            val pJson = JSONObject(pBody)
                            val rawProgress = pJson.optInt("progress", 0)
                            val percent = if (rawProgress in 1..1000) (rawProgress / 10).coerceIn(10, 95) else (10 + pollAttempt * 3).coerceIn(10, 95)
                            val statusText = pJson.optString("text", "Đang xử lý âm thanh YouTube")
                            onProgress(percent, 0, totalEstimatedBytes, "$statusText (${percent}%)...")

                            val dUrl = pJson.optString("download_url", "")
                            if (dUrl.isNotBlank() && (dUrl.startsWith("http://") || dUrl.startsWith("https://"))) {
                                resolvedUrl = dUrl
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AudioLinkExtractor", "Poll loader.to attempt $pollAttempt error: ${e.message}")
            }
            if (!resolvedUrl.isNullOrBlank()) break
        }
        resolvedUrl
    }

    private suspend fun tryLoaderConversion(
        endpoint: String,
        videoId: String,
        totalEstimatedBytes: Long,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speed: String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$endpoint?format=mp3&url=https://www.youtube.com/watch?v=$videoId")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val immediate = json.optString("download_url", "")
                        if (immediate.isNotBlank() && (immediate.startsWith("http://") || immediate.startsWith("https://"))) {
                            return@withContext immediate
                        }
                        val progressUrl = json.optString("progress_url", "")
                        val streamId = json.optString("id", "")
                        val targetPoll = if (progressUrl.isNotBlank()) progressUrl else "https://loader.to/ajax/progress.php?id=$streamId"
                        return@withContext pollLoaderProgress(targetPoll, totalEstimatedBytes, onProgress)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AudioLinkExtractor", "Loader attempt on $endpoint failed: ${e.message}")
        }
        null
    }

    private suspend fun tryInvidiousStream(
        videoId: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speed: String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val instances = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be",
            "https://invidious.tiekoetter.com"
        )
        for (instance in instances) {
            try {
                onProgress(20, 0, 5000000L, "Đang kết nối cổng âm thanh dự phòng...")
                val req = Request.Builder()
                    .url("$instance/api/v1/videos/$videoId")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                            if (adaptiveFormats != null) {
                                for (i in 0 until adaptiveFormats.length()) {
                                    val fmt = adaptiveFormats.optJSONObject(i) ?: continue
                                    val type = fmt.optString("type", "")
                                    val url = fmt.optString("url", "")
                                    if (type.contains("audio") && url.isNotBlank()) {
                                        return@withContext if (url.startsWith("/")) "$instance$url" else url
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AudioLinkExtractor", "Invidious instance $instance failed: ${e.message}")
            }
        }
        null
    }

    private suspend fun tryPipedStream(
        videoId: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speed: String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val pipedHosts = listOf("https://pipedapi.kavin.rocks", "https://api.piped.privacydev.net")
        for (host in pipedHosts) {
            try {
                onProgress(25, 0, 5000000L, "Đang kết nối luồng Piped audio...")
                val req = Request.Builder()
                    .url("$host/streams/$videoId")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val audioStreams = json.optJSONArray("audioStreams")
                            if (audioStreams != null && audioStreams.length() > 0) {
                                var bestUrl: String? = null
                                var maxBitrate = 0
                                for (i in 0 until audioStreams.length()) {
                                    val stream = audioStreams.optJSONObject(i) ?: continue
                                    val sUrl = stream.optString("url", "")
                                    val bitrate = stream.optInt("bitrate", 0)
                                    if (sUrl.isNotBlank() && bitrate >= maxBitrate) {
                                        maxBitrate = bitrate
                                        bestUrl = sUrl
                                    }
                                }
                                if (bestUrl != null) return@withContext bestUrl
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AudioLinkExtractor", "Piped host $host failed: ${e.message}")
            }
        }
        null
    }

    private fun downloadStreamWithProgress(
        streamUrl: String,
        targetFile: File,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speed: String) -> Unit
    ): Boolean {
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    targetFile.delete()
                    return false
                }
                val body = response.body ?: run {
                    targetFile.delete()
                    return false
                }

                val contentType = response.header("Content-Type")?.lowercase()
                if (contentType != null && (contentType.startsWith("text/") || contentType.contains("html") || contentType.contains("json"))) {
                    targetFile.delete()
                    return false
                }

                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0) contentLength else (5 * 1024 * 1024L)

                val inputStream = body.byteStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloadedBytes = 0L
                var lastTime = System.currentTimeMillis()
                var bytesSinceLast = 0L
                var checkedFirstBytes = false
                var isContentInvalid = false

                FileOutputStream(targetFile).use { outputStream ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (!checkedFirstBytes) {
                            for (i in 0 until bytesRead) {
                                val ch = buffer[i].toInt().toChar()
                                if (!ch.isWhitespace()) {
                                    checkedFirstBytes = true
                                    if (ch == '<' || ch == '{') {
                                        isContentInvalid = true
                                    }
                                    break
                                }
                            }
                            if (isContentInvalid) {
                                return@use
                            }
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        bytesSinceLast += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 250) {
                            val speedKbps = (bytesSinceLast * 1000.0) / (now - lastTime) / 1024.0
                            val speedText = if (speedKbps > 1024) "%.1f MB/s".format(speedKbps / 1024) else "%.0f KB/s".format(speedKbps)
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(1, 99)
                            onProgress(percent, downloadedBytes, totalBytes, speedText)
                            lastTime = now
                            bytesSinceLast = 0L
                        }
                    }
                    outputStream.flush()
                }

                if (isContentInvalid) {
                    targetFile.delete()
                    return false
                }
            }
        } catch (e: Exception) {
            targetFile.delete()
            return false
        }

        val isValid = targetFile.exists() && targetFile.length() > 5000
        if (!isValid) {
            targetFile.delete()
        }
        return isValid
    }
}
