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
    }

    fun updatePlayer(exo: Player) {
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
        return mediaSession
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

        fun updateActivePlayer(player: Player) {
            instance?.updatePlayer(player)
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
