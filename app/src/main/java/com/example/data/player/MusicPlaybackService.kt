package com.example.data.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.example.MainActivity
import com.example.R
import com.example.data.model.Song
import java.io.File

class MusicPlaybackService : Service() {

    private var mediaSession: MediaSession? = null
    private var currentSong: Song? = null
    private var isPlaying: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSession(this, "SoundFlowMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    activePlayerManager?.togglePlayPause()
                }

                override fun onPause() {
                    activePlayerManager?.togglePlayPause()
                }

                override fun onSkipToNext() {
                    activePlayerManager?.playNext()
                }

                override fun onSkipToPrevious() {
                    activePlayerManager?.playPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    activePlayerManager?.seekTo(pos)
                }

                override fun onStop() {
                    activePlayerManager?.pause()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> activePlayerManager?.togglePlayPause()
            ACTION_PAUSE -> activePlayerManager?.togglePlayPause()
            ACTION_NEXT -> activePlayerManager?.playNext()
            ACTION_PREV -> activePlayerManager?.playPrevious()
            ACTION_STOP -> {
                activePlayerManager?.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val songTitle = intent?.getStringExtra(EXTRA_SONG_TITLE)
        if (songTitle != null) {
            val artist = intent.getStringExtra(EXTRA_SONG_ARTIST) ?: "SoundFlow"
            val album = intent.getStringExtra(EXTRA_SONG_ALBUM) ?: "SoundFlow"
            val thumb = intent.getStringExtra(EXTRA_SONG_THUMB)
            val duration = intent.getLongExtra(EXTRA_SONG_DURATION, 180000L)
            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)

            currentSong = Song(
                title = songTitle,
                artist = artist,
                album = album,
                durationMs = duration,
                filePath = "",
                thumbnailUrl = thumb
            )

            updateMediaSessionAndNotification()
        }

        return START_STICKY
    }

    private fun updateMediaSessionAndNotification() {
        val song = currentSong ?: return
        val session = mediaSession ?: return

        // 1. Load thumbnail Bitmap if available
        var albumArtBitmap: Bitmap? = null
        if (!song.thumbnailUrl.isNullOrBlank()) {
            val thumbFile = File(song.thumbnailUrl)
            if (thumbFile.exists()) {
                albumArtBitmap = try {
                    BitmapFactory.decodeFile(thumbFile.absolutePath)
                } catch (e: Exception) {
                    null
                }
            }
        }
        if (albumArtBitmap == null) {
            albumArtBitmap = createFallbackAlbumArt(song.title)
        }

        // 2. Update MediaSession state (Samsung Now Bar & Lockscreen)
        val playbackActions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO

        val state = PlaybackState.Builder()
            .setActions(playbackActions)
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                activePlayerManager?.currentPositionMs?.value ?: 0L,
                1.0f
            )
            .build()
        session.setPlaybackState(state)

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, song.durationMs)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArtBitmap)
            .build()
        session.setMetadata(metadata)

        // 3. Build Status Bar Pull-Down Notification
        val notification = buildMediaNotification(song, albumArtBitmap, isPlaying)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildMediaNotification(song: Song, albumArt: Bitmap?, isPlaying: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREV }
        val prevPendingIntent = PendingIntent.getService(this, 1, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val playPauseIntent = Intent(this, MusicPlaybackService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(this, 2, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val nextIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.album)
            .setLargeIcon(albumArt)
            .setContentIntent(openAppPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setStyle(mediaStyle)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "Bài trước",
                    prevPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    playPauseIcon,
                    if (isPlaying) "Tạm dừng" else "Phát",
                    playPausePendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "Bài tiếp",
                    nextPendingIntent
                ).build()
            )
            .build()
    }

    private fun createFallbackAlbumArt(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.parseColor("#1B2232")
            isAntiAlias = true
        }
        canvas.drawCircle(100f, 100f, 100f, paint)

        val textPaint = Paint().apply {
            color = Color.parseColor("#00F5FF")
            textSize = 64f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        val initial = title.firstOrNull()?.uppercase() ?: "S"
        canvas.drawText(initial, 100f, 120f, textPaint)
        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phát nhạc SoundFlow",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị bài hát đang phát và thanh điều khiển nhạc trên thanh trạng thái"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    companion object {
        const val CHANNEL_ID = "soundflow_playback_channel"
        const val NOTIFICATION_ID = 2026

        const val ACTION_PLAY = "com.example.soundflow.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.soundflow.ACTION_PAUSE"
        const val ACTION_NEXT = "com.example.soundflow.ACTION_NEXT"
        const val ACTION_PREV = "com.example.soundflow.ACTION_PREV"
        const val ACTION_STOP = "com.example.soundflow.ACTION_STOP"

        const val EXTRA_SONG_TITLE = "extra_song_title"
        const val EXTRA_SONG_ARTIST = "extra_song_artist"
        const val EXTRA_SONG_ALBUM = "extra_song_album"
        const val EXTRA_SONG_THUMB = "extra_song_thumb"
        const val EXTRA_SONG_DURATION = "extra_song_duration"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        var activePlayerManager: AudioPlayerManager? = null

        fun updateService(context: Context, song: Song, isPlaying: Boolean) {
            try {
                val intent = Intent(context, MusicPlaybackService::class.java).apply {
                    putExtra(EXTRA_SONG_TITLE, song.title)
                    putExtra(EXTRA_SONG_ARTIST, song.artist)
                    putExtra(EXTRA_SONG_ALBUM, song.album)
                    putExtra(EXTRA_SONG_THUMB, song.thumbnailUrl)
                    putExtra(EXTRA_SONG_DURATION, song.durationMs)
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Background start limitations or user dismissed
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, MusicPlaybackService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {}
        }
    }
}
