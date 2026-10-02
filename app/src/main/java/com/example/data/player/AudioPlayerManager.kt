package com.example.data.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.util.Log
import com.example.data.downloader.SampleAudioGenerator
import com.example.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RepeatMode(val label: String) {
    OFF("Tắt lặp"),
    ALL("Lặp tất cả"),
    ONE("Lặp 1 bài")
}

enum class EqualizerPreset(val label: String, val description: String) {
    BALANCED("Cân bằng", "Tự nhiên, phù hợp mọi dòng nhạc"),
    BASS_BOOST("Tăng Bass", "Âm trầm mạnh mẽ, EDM, HipHop"),
    VOCAL("Giọng hát", "Tôn vocal trong trẻo, Podcast, Acoustic"),
    ELECTRONIC("Điện tử", "Đẩy dải treble & bass sắc nét"),
    ROCK("Rock & Metal", "Midrange sôi động, tiếng guitar rõ rệt"),
    ACOUSTIC("Acoustic", "Âm trường rộng, thanh thoát"),
    CUSTOM("Tùy chỉnh", "Tự do điều chỉnh 5 dải tần & Bass")
}

class AudioPlayerManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var sleepTimer: CountDownTimer? = null

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playQueue = MutableStateFlow<List<Song>>(emptyList())
    val playQueue: StateFlow<List<Song>> = _playQueue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.ALL)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _equalizerPreset = MutableStateFlow(EqualizerPreset.BALANCED)
    val equalizerPreset: StateFlow<EqualizerPreset> = _equalizerPreset.asStateFlow()

    // 5 custom equalizer bands: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz (-1.0f to +1.0f)
    private val _customBandLevels = MutableStateFlow(listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f))
    val customBandLevels: StateFlow<List<Float>> = _customBandLevels.asStateFlow()

    private val _customBassBoost = MutableStateFlow(0.0f)
    val customBassBoost: StateFlow<Float> = _customBassBoost.asStateFlow()

    private val _sleepTimerMinutesLeft = MutableStateFlow<Int?>(null)
    val sleepTimerMinutesLeft: StateFlow<Int?> = _sleepTimerMinutesLeft.asStateFlow()

    var onSongFinishedListener: ((Song) -> Unit)? = null

    init {
        MusicPlaybackService.activePlayerManager = this
    }

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        _playQueue.value = songs
        val validIndex = startIndex.coerceIn(0, songs.size - 1)
        _currentIndex.value = validIndex
        playSong(songs[validIndex])
    }

    fun playSong(song: Song) {
        _currentSong.value = song
        _currentPositionMs.value = 0L
        _isPlaying.value = true

        scope.launch {
            try {
                releasePlayer()

                withContext(Dispatchers.IO) {
                    val file = File(song.filePath)
                    val altOfflineFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline_tracks/${file.name}")
                    val altDownloadsFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${file.name}")

                    val validPath = when {
                        file.exists() && file.length() > 0 -> file.absolutePath
                        altOfflineFile.exists() && altOfflineFile.length() > 0 -> altOfflineFile.absolutePath
                        altDownloadsFile.exists() && altDownloadsFile.length() > 0 -> altDownloadsFile.absolutePath
                        else -> null
                    }

                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )

                        if (validPath != null) {
                            setDataSource(validPath)
                            // Synchronous prepare for local files is instant (<10ms)
                            prepare()
                            _durationMs.value = duration.toLong().coerceAtLeast(song.durationMs)
                            setupAudioEffects(audioSessionId)
                            applyPlaybackSpeed(_playbackSpeed.value)
                            start()
                            _isPlaying.value = true
                            startProgressTracker()
                            MusicPlaybackService.updateService(context, song.copy(filePath = validPath), true)
                        } else if (!song.sourceUrl.isNullOrBlank() && song.sourceUrl.startsWith("http")) {
                            setDataSource(context, Uri.parse(song.sourceUrl))
                            setOnPreparedListener { mp ->
                                _durationMs.value = mp.duration.toLong().coerceAtLeast(song.durationMs)
                                setupAudioEffects(mp.audioSessionId)
                                applyPlaybackSpeed(_playbackSpeed.value)
                                mp.start()
                                _isPlaying.value = true
                                startProgressTracker()
                                MusicPlaybackService.updateService(context, song, true)
                            }
                            prepareAsync()
                        } else {
                            // If file was somehow empty, regenerate on the fly so it never fails!
                            val ensuredFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${file.name}")
                            SampleAudioGenerator.createSampleWav(ensuredFile, 20)
                            setDataSource(ensuredFile.absolutePath)
                            prepare()
                            _durationMs.value = duration.toLong().coerceAtLeast(song.durationMs)
                            setupAudioEffects(audioSessionId)
                            start()
                            _isPlaying.value = true
                            startProgressTracker()
                            MusicPlaybackService.updateService(context, song.copy(filePath = ensuredFile.absolutePath), true)
                        }

                        setOnCompletionListener {
                            handleSongCompletion()
                        }

                        setOnErrorListener { _, what, extra ->
                            Log.e("AudioPlayerManager", "MediaPlayer error: what=$what, extra=$extra")
                            _isPlaying.value = false
                            true
                        }
                    }
                }

                _currentSong.value = song

            } catch (e: Exception) {
                Log.e("AudioPlayerManager", "Failed to play song: ${song.title}", e)
                _isPlaying.value = false
            }
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
            scope.launch(Dispatchers.IO) {
                _currentSong.value?.let {
                    MusicPlaybackService.updateService(context, it, false)
                }
            }
        }
    }

    fun resume() {
        val player = mediaPlayer ?: return
        if (!player.isPlaying) {
            player.start()
            _isPlaying.value = true
            startProgressTracker()
            scope.launch(Dispatchers.IO) {
                _currentSong.value?.let {
                    MusicPlaybackService.updateService(context, it, true)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        val clamped = positionMs.coerceIn(0, _durationMs.value.coerceAtLeast(1))
        _currentPositionMs.value = clamped
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
        } else {
            player.seekTo(clamped.toInt())
        }
    }

    fun skipForward10() {
        seekTo(_currentPositionMs.value + 10000)
    }

    fun skipBackward10() {
        seekTo(_currentPositionMs.value - 10000)
    }

    fun playNext() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return

        if (_isShuffle.value) {
            val randomIndex = queue.indices.random()
            _currentIndex.value = randomIndex
            playSong(queue[randomIndex])
            return
        }

        val nextIndex = _currentIndex.value + 1
        if (nextIndex < queue.size) {
            _currentIndex.value = nextIndex
            playSong(queue[nextIndex])
        } else if (_repeatMode.value == RepeatMode.ALL) {
            _currentIndex.value = 0
            playSong(queue[0])
        }
    }

    fun playPrevious() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return

        // If played more than 3 seconds, replay current song from beginning
        if (_currentPositionMs.value > 3000) {
            seekTo(0)
            return
        }

        val prevIndex = _currentIndex.value - 1
        if (prevIndex >= 0) {
            _currentIndex.value = prevIndex
            playSong(queue[prevIndex])
        } else {
            val lastIndex = queue.size - 1
            _currentIndex.value = lastIndex
            playSong(queue[lastIndex])
        }
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        applyPlaybackSpeed(speed)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.playbackParams = player.playbackParams.setSpeed(speed)
                    }
                }
            } catch (e: Exception) {
                Log.w("AudioPlayerManager", "Could not set playback speed", e)
            }
        }
    }

    private fun setupAudioEffects(audioSessionId: Int) {
        try {
            equalizer?.release()
            bassBoost?.release()

            if (audioSessionId != 0) {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = true
                }
                bassBoost = BassBoost(0, audioSessionId).apply {
                    enabled = true
                }
                applyEqualizerPreset(_equalizerPreset.value)
            }
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "AudioFx initialization note: ${e.message}")
        }
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        _equalizerPreset.value = preset
        applyEqualizerPreset(preset)
    }

    fun setCustomBand(bandIndex: Int, level: Float) {
        val current = _customBandLevels.value.toMutableList()
        if (bandIndex in 0 until current.size) {
            current[bandIndex] = level.coerceIn(-1.0f, 1.0f)
            _customBandLevels.value = current
            _equalizerPreset.value = EqualizerPreset.CUSTOM
            applyEqualizerPreset(EqualizerPreset.CUSTOM)
        }
    }

    fun setCustomBassBoost(strength: Float) {
        _customBassBoost.value = strength.coerceIn(0.0f, 1.0f)
        _equalizerPreset.value = EqualizerPreset.CUSTOM
        applyEqualizerPreset(EqualizerPreset.CUSTOM)
    }

    fun resetCustomEqualizer() {
        _customBandLevels.value = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
        _customBassBoost.value = 0.0f
        if (_equalizerPreset.value == EqualizerPreset.CUSTOM) {
            applyEqualizerPreset(EqualizerPreset.CUSTOM)
        }
    }

    private fun applyEqualizerPreset(preset: EqualizerPreset) {
        val eq = equalizer ?: return
        try {
            val numBands = eq.numberOfBands.toInt()
            val minLevel = eq.bandLevelRange[0]
            val maxLevel = eq.bandLevelRange[1]
            val maxDelta = (maxLevel - minLevel) / 2

            // Weights for each band from low frequency to high frequency [-1.0 to 1.0]
            val weights = when (preset) {
                EqualizerPreset.BALANCED -> listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
                EqualizerPreset.BASS_BOOST -> listOf(0.95f, 0.70f, 0.10f, -0.20f, 0.10f)
                EqualizerPreset.VOCAL -> listOf(-0.40f, -0.10f, 0.85f, 0.75f, 0.15f)
                EqualizerPreset.ELECTRONIC -> listOf(0.85f, 0.45f, -0.25f, 0.50f, 0.90f)
                EqualizerPreset.ROCK -> listOf(0.65f, 0.35f, 0.60f, 0.70f, 0.55f)
                EqualizerPreset.ACOUSTIC -> listOf(0.30f, 0.20f, 0.45f, 0.65f, 0.75f)
                EqualizerPreset.CUSTOM -> _customBandLevels.value
            }

            for (i in 0 until numBands) {
                val weight = if (i < weights.size) weights[i] else 0.0f
                val level = (weight * maxDelta).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
                eq.setBandLevel(i.toShort(), level)
            }

            // BassBoost hardware effect
            bassBoost?.let { bb ->
                val strength = when (preset) {
                    EqualizerPreset.BASS_BOOST -> 1000.toShort()
                    EqualizerPreset.ELECTRONIC -> 600.toShort()
                    EqualizerPreset.ROCK -> 400.toShort()
                    EqualizerPreset.ACOUSTIC -> 100.toShort()
                    EqualizerPreset.CUSTOM -> (_customBassBoost.value * 1000).toInt().toShort()
                    else -> 0.toShort()
                }
                bb.setStrength(strength)
            }
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Failed to apply equalizer preset: ${e.message}")
        }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimer?.cancel()
        _sleepTimerMinutesLeft.value = minutes

        if (minutes != null && minutes > 0) {
            val totalMillis = minutes * 60 * 1000L
            sleepTimer = object : CountDownTimer(totalMillis, 60000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val remainingMins = (millisUntilFinished / 60000L).toInt() + 1
                    _sleepTimerMinutesLeft.value = remainingMins
                }

                override fun onFinish() {
                    _sleepTimerMinutesLeft.value = null
                    mediaPlayer?.pause()
                    _isPlaying.value = false
                }
            }.start()
        }
    }

    private fun handleSongCompletion() {
        _currentSong.value?.let { onSongFinishedListener?.invoke(it) }

        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                seekTo(0)
                mediaPlayer?.start()
                _isPlaying.value = true
                startProgressTracker()
            }
            RepeatMode.ALL -> playNext()
            RepeatMode.OFF -> {
                if (_currentIndex.value < _playQueue.value.size - 1) {
                    playNext()
                } else {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                }
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                try {
                    mediaPlayer?.let { player ->
                        if (player.isPlaying) {
                            _currentPositionMs.value = player.currentPosition.toLong()
                        }
                    }
                } catch (e: Exception) {
                    // ignored
                }
                delay(250)
            }
        }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        try {
            equalizer?.release()
            bassBoost?.release()
        } catch (e: Exception) {}
        equalizer = null
        bassBoost = null

        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                // ignored
            }
        }
        mediaPlayer = null
    }

    fun cleanup() {
        sleepTimer?.cancel()
        releasePlayer()
        MusicPlaybackService.stop(context)
    }
}
