package com.example.data.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.data.model.Song

class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val playerManager = activePlayerManager ?: AudioPlayerManager.activeInstance
        val exo = playerManager?.exoPlayer

        if (exo != null) {
            setupMediaSession(exo)
        }
    }

    private fun setupMediaSession(player: Player) {
        val forwardingPlayer = createForwardingPlayer(player)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(openAppPendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    when (playerCommand) {
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                            activePlayerManager?.playNext()
                            return androidx.media3.session.SessionResult.RESULT_SUCCESS
                        }
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                            activePlayerManager?.playPrevious()
                            return androidx.media3.session.SessionResult.RESULT_SUCCESS
                        }
                    }
                    return super.onPlayerCommandRequest(session, controller, playerCommand)
                }
            })
            .build()
        // Dang ky phien voi service: Media3 chi tu dang thong bao media (va Now Bar/man hinh khoa doc tu thong bao do)
        // cho cac phien da duoc add, ma giao dien app khong ket noi MediaController nen phai add tay.
        mediaSession?.let { addSession(it) }
    }

    fun updatePlayer(exo: Player) {
        val session = mediaSession
        if (session != null && !isSessionAdded(session)) addSession(session)
        if (mediaSession == null) {
            setupMediaSession(exo)
        } else {
            val forwardingPlayer = createForwardingPlayer(exo)
            mediaSession?.setPlayer(forwardingPlayer)
        }
    }

    private fun createForwardingPlayer(exo: Player): ForwardingPlayer {
        return object : ForwardingPlayer(exo) {
            override fun seekToNext() {
                activePlayerManager?.playNext() ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                activePlayerManager?.playNext() ?: super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                activePlayerManager?.playPrevious() ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                activePlayerManager?.playPrevious() ?: super.seekToPreviousMediaItem()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (mediaSession == null) {
            (activePlayerManager ?: AudioPlayerManager.activeInstance)?.exoPlayer?.let { setupMediaSession(it) }
        }
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service co the duoc tao truoc khi player san sang: tao phien muon o day.
        if (mediaSession == null) {
            (activePlayerManager ?: AudioPlayerManager.activeInstance)?.exoPlayer?.let { setupMediaSession(it) }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        var instance: MusicPlaybackService? = null
        var activePlayerManager: AudioPlayerManager? = null

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun updateActivePlayer(player: Player) {
            // Hoan sang luot sau cua main thread: lenh Next/Prev tu man hinh khoa/tai nghe di vao QUA chinh MediaSession,
            // goi session.setPlayer() long ben trong lenh do bi Media3 tu choi -> phien bam player cu (trang thai NONE).
            mainHandler.post {
                try {
                    instance?.updatePlayer(player)
                } catch (e: Exception) {
                    android.util.Log.e("MusicPlaybackService", "Khong doi duoc player cho MediaSession", e)
                }
            }
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, MusicPlaybackService::class.java)
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun updateService(context: Context, song: Song, isPlaying: Boolean) {
            start(context)
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, MusicPlaybackService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
