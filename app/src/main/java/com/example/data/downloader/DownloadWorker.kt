package com.example.data.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.local.MusicDatabase
import com.example.data.model.AudioQuality
import com.example.data.model.SourceType
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SONG_ID = "song_id"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS = "progress"
        const val KEY_SPEED = "speed"
        const val MAX_ATTEMPTS = 3
        const val TAG = "soundflow_download"

        private const val CHANNEL_ID = "soundflow_download_channel"
        private const val CHANNEL_NAME = "Tải nhạc"
        private const val NOTIFICATION_ID = 2001

        private const val KEY_ORIGINAL_URL = "original_url"
        private const val KEY_SOURCE = "source"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_THUMBNAIL_URL = "thumbnail_url"
        private const val KEY_ESTIMATED_DURATION_MS = "estimated_duration_ms"
        private const val KEY_DIRECT_STREAM_URL = "direct_stream_url"
        private const val KEY_QUALITY_ID = "quality_id"
        private const val KEY_TARGET_PLAYLIST_ID = "target_playlist_id"

        data class Input(
            val metadata: ParsedLinkMetadata,
            val quality: AudioQuality,
            val targetPlaylistId: Long?
        )

        fun buildInput(metadata: ParsedLinkMetadata, quality: AudioQuality, targetPlaylistId: Long?): Data {
            val builder = Data.Builder()
                .putString(KEY_ORIGINAL_URL, metadata.originalUrl)
                .putString(KEY_SOURCE, metadata.source.name)
                .putString(KEY_TITLE, metadata.title)
                .putString(KEY_ARTIST, metadata.artist)
                .putLong(KEY_ESTIMATED_DURATION_MS, metadata.estimatedDurationMs)
                .putString(KEY_QUALITY_ID, quality.id)

            metadata.thumbnailUrl?.let { builder.putString(KEY_THUMBNAIL_URL, it) }
            metadata.directStreamUrl?.let { builder.putString(KEY_DIRECT_STREAM_URL, it) }
            if (targetPlaylistId != null) {
                builder.putLong(KEY_TARGET_PLAYLIST_ID, targetPlaylistId)
            }
            return builder.build()
        }

        fun readInput(data: Data): Input {
            val originalUrl = data.getString(KEY_ORIGINAL_URL) ?: ""
            val sourceName = data.getString(KEY_SOURCE) ?: SourceType.DIRECT_URL.name
            val source = try {
                SourceType.valueOf(sourceName)
            } catch (e: Exception) {
                SourceType.DIRECT_URL
            }
            val title = data.getString(KEY_TITLE) ?: ""
            val artist = data.getString(KEY_ARTIST) ?: ""
            val thumbnailUrl = data.getString(KEY_THUMBNAIL_URL)
            val estimatedDurationMs = data.getLong(KEY_ESTIMATED_DURATION_MS, 0L)
            val directStreamUrl = data.getString(KEY_DIRECT_STREAM_URL)
            val qualityId = data.getString(KEY_QUALITY_ID) ?: AudioQuality.DEFAULT.id
            val quality = AudioQuality.byId(qualityId)

            val targetPlaylistId = if (data.keyValueMap.containsKey(KEY_TARGET_PLAYLIST_ID)) {
                data.getLong(KEY_TARGET_PLAYLIST_ID, -1L).takeIf { it != -1L }
            } else {
                null
            }

            val metadata = ParsedLinkMetadata(
                originalUrl = originalUrl,
                source = source,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                estimatedDurationMs = estimatedDurationMs,
                directStreamUrl = directStreamUrl
            )
            return Input(metadata, quality, targetPlaylistId)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val input = readInput(inputData)
        return createForegroundNotification(input.metadata.title, 0)
    }

    private fun createForegroundNotification(title: String, progress: Int): ForegroundInfo {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val displayTitle = title.ifBlank { "Tải nhạc" }
        val progressText = if (progress in 0..100) "$progress%" else ""
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(displayTitle)
            .setContentText(if (progressText.isNotEmpty()) "Đang tải $progressText" else "Đang kết nối...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val input = readInput(inputData)
        val metadata = input.metadata
        val quality = input.quality
        val targetPlaylistId = input.targetPlaylistId

        val repo = MusicRepository(appContext, MusicDatabase.getInstance(appContext).musicDao())

        // 1. Link đã tải rồi -> trả id bài cũ, không gọi mạng
        val existingSong = repo.findExistingDownload(metadata.originalUrl)
        if (existingSong != null) {
            return@withContext Result.success(workDataOf(KEY_SONG_ID to existingSong.id))
        }

        // 2. Chạy foreground khi đang tải (trong try/catch)
        try {
            setForeground(getForegroundInfo())
        } catch (_: Exception) {
            // Không làm test fail khi test không có foreground
        }

        // 3. Tải bài hát với AudioLinkExtractor, throttle progress (<= 4 lần / giây)
        var lastProgressTime = 0L
        val minIntervalMs = 250L

        val extractor = AudioLinkExtractor(appContext)
        var downloadError: Throwable? = null
        val song = try {
            val res = extractor.downloadAudio(metadata, quality) { percent, _, _, speed ->
                val now = System.currentTimeMillis()
                if (percent == 100 || now - lastProgressTime >= minIntervalMs) {
                    lastProgressTime = now
                    try {
                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS to percent,
                                KEY_SPEED to speed
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            if (res.isSuccess) {
                res.getOrNull()
            } else {
                downloadError = res.exceptionOrNull()
                null
            }
        } catch (e: Exception) {
            downloadError = e
            null
        }

        // 4. Kết quả tải
        if (song != null) {
            val savedId = repo.saveDownloadedSong(song, targetPlaylistId)
            Result.success(workDataOf(KEY_SONG_ID to savedId))
        } else {
            if (runAttemptCount + 1 < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                val errorMsg = downloadError?.localizedMessage
                    ?: downloadError?.message
                    ?: "Không thể trích xuất âm thanh từ liên kết. Vui lòng thử lại sau."
                Result.failure(workDataOf(KEY_ERROR to errorMsg))
            }
        }
    }
}
