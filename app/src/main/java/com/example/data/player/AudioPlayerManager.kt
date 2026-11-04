package com.example.data.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    HIFI_MASTER("Hi-Fi Master", "Chuẩn phòng thu, chi tiết âm thanh cao, âm trường 3D"),
    BALANCED("Bit-Perfect", "Nguyên bản 100%, sạch hoàn toàn, không can thiệp DSP"),
    TIKTOK_BOOST("TikTok Bass Đầm", "Tái tạo độ nảy, dày âm & trầm sâu chuẩn TikTok"),
    BASS_BOOST("Siêu trầm Bass", "Âm trầm mạnh mẽ, uy lực cho EDM & HipHop"),
    VOCAL("Vocal HD", "Tôn giọng hát trong trẻo, chi tiết lời ca sĩ"),
    ELECTRONIC("Điện tử / EDM", "Đẩy dải treble & bass sắc nét, sôi động"),
    ROCK("Rock & Metal", "Midrange sôi động, tiếng guitar rõ rệt"),
    ACOUSTIC("Acoustic", "Âm trường thoáng đãng, mộc mạc và chân thực"),
    CUSTOM("Tùy chỉnh Studio", "Tự do chỉnh 5 dải EQ, Bass, Âm vòm 3D & Độ trong")
}

enum class ReverbMode(val label: String, val presetValue: Short) {
    OFF("Tắt", PresetReverb.PRESET_NONE),
    STUDIO("Phòng thu Studio", PresetReverb.PRESET_SMALLROOM),
    ROOM("Phòng nghe nhạc", PresetReverb.PRESET_MEDIUMROOM),
    CONCERT("Khán phòng hòa nhạc", PresetReverb.PRESET_MEDIUMHALL),
    STAGE("Sân khấu lớn", PresetReverb.PRESET_LARGEHALL)
}

class AudioPlayerManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var crossfadeJob: Job? = null
    private var stopAfterCurrentSong = false
    private var isManuallyPaused = false
    private var isPausedDueToTransientLoss = false
    private var pendingSeekPosition: Long? = null
    private var playRequestId = 0L

    // 2 ExoPlayers: playerA and playerB
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var playersInitialized = false
    private var activePlayerIndex = 0 // 0 -> playerA active, 1 -> playerB active

    private var activeSong: Song? = null
    private var standbySong: Song? = null

    // Crossfade ramp tracking
    private var isCrossfading = false
    private var crossfadeElapsedMs = 0L
    private var crossfadeDurationMs = 0L
    private var crossfadeProgress = 0f
    private var outgoingPlayer: ExoPlayer? = null
    private var incomingPlayer: ExoPlayer? = null
    private var outgoingSong: Song? = null
    private var incomingSong: Song? = null

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private val sharedAudioSessionId: Int by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioManager?.generateAudioSessionId() ?: C.AUDIO_SESSION_ID_UNSET
            } else {
                C.AUDIO_SESSION_ID_UNSET
            }
        } catch (_: Throwable) {
            C.AUDIO_SESSION_ID_UNSET
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_isPlaying.value && !isManuallyPaused) {
                    isPausedDueToTransientLoss = true
                    try { activePlayer?.pause() } catch (_: Throwable) {}
                    try { standbyPlayer?.pause() } catch (_: Throwable) {}
                    _isPlaying.value = false
                    progressJob?.cancel()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val duckVol = getBaseVolume() * 0.2f
                try { activePlayer?.volume = duckVol } catch (_: Throwable) {}
                try { standbyPlayer?.volume = duckVol } catch (_: Throwable) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isPausedDueToTransientLoss) {
                    isPausedDueToTransientLoss = false
                    resume()
                } else if (_isPlaying.value) {
                    restoreVolumesAfterDuck()
                }
            }
        }
    }

    private fun restoreVolumesAfterDuck() {
        val base = getBaseVolume()
        if (isCrossfading) {
            val (outGain, inGain) = Crossfade.equalPowerGains(crossfadeProgress)
            try { outgoingPlayer?.volume = outGain * base } catch (_: Throwable) {}
            try { incomingPlayer?.volume = inGain * base } catch (_: Throwable) {}
        } else {
            try { activePlayer?.volume = base } catch (_: Throwable) {}
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build()
                }
                val res = am.requestAudioFocus(audioFocusRequest!!)
                res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val res = am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (_: Throwable) {}
    }

    private fun createPlayerInstance(tag: String): ExoPlayer? {
        return try {
            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()

            val p = ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(audioAttributes, false) // handleAudioFocus = false!
                .setHandleAudioBecomingNoisy(true)
                .setLooper(android.os.Looper.getMainLooper())
                .build()

            if (sharedAudioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                try {
                    p.setAudioSessionId(sharedAudioSessionId)
                } catch (e: Throwable) {
                    Log.w("AudioPlayerManager", "Failed to set audio session id on $tag: ${e.message}")
                }
            }
            p.addListener(createPlayerListener(p))
            p
        } catch (e: Throwable) {
            Log.w("AudioPlayerManager", "Failed to initialize ExoPlayer $tag: ${e.message}")
            null
        }
    }

    private fun ensurePlayersInitialized() {
        if (!playersInitialized) {
            playerA = createPlayerInstance("PlayerA")
            playerB = createPlayerInstance("PlayerB")
            playersInitialized = true
            val sessionId = if (sharedAudioSessionId != C.AUDIO_SESSION_ID_UNSET) sharedAudioSessionId
            else (playerA?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET)
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                setupAudioEffects(sessionId)
            }
        }
    }

    val activePlayer: ExoPlayer?
        get() {
            ensurePlayersInitialized()
            return if (activePlayerIndex == 0) playerA else playerB
        }

    val standbyPlayer: ExoPlayer?
        get() {
            ensurePlayersInitialized()
            return if (activePlayerIndex == 0) playerB else playerA
        }

    val exoPlayer: ExoPlayer?
        get() = activePlayer

    private fun createPlayerListener(player: ExoPlayer) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    handleSongCompletion(player)
                }
                Player.STATE_READY -> {
                    if (player == activePlayer) {
                        _durationMs.value = player.duration.coerceAtLeast(0L)
                        applyPlaybackSpeedTo(player, _playbackSpeed.value)
                    }
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("AudioPlayerManager", "ExoPlayer error: ${error.message}", error)
            if (player == activePlayer) {
                _isPlaying.value = false
                _playbackError.value = error.localizedMessage ?: "Lỗi phát nhạc"
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (sharedAudioSessionId == C.AUDIO_SESSION_ID_UNSET) {
                setupAudioEffects(audioSessionId)
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            if (player == activePlayer && !isCrossfading) {
                _isPlaying.value = playing
                if (playing) {
                    startProgressTracker()
                } else {
                    progressJob?.cancel()
                }
            }
        }
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var presetReverb: PresetReverb? = null

    private val _antiClippingEnabled = MutableStateFlow(true)
    val antiClippingEnabled: StateFlow<Boolean> = _antiClippingEnabled.asStateFlow()

    fun setAntiClippingEnabled(enabled: Boolean) {
        _antiClippingEnabled.value = enabled
        applyEqualizerPreset(_equalizerPreset.value)
    }

    fun getBaseVolume(): Float {
        return if (_antiClippingEnabled.value && _equalizerPreset.value != EqualizerPreset.BALANCED) {
            0.88f // Headroom to prevent DAC/hardware clipping on boosted frequencies
        } else {
            1.0f
        }
    }

    private fun updateVolumeWithHeadroom(hasBoost: Boolean) {
        try {
            val baseVol = if (_antiClippingEnabled.value && hasBoost) 0.88f else 1.0f
            if (!isCrossfading) {
                activePlayer?.volume = baseVol
            }
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Could not update volume with headroom", e)
        }
    }

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

    private val shuffleOrder = mutableListOf<Int>()
    private var shufflePosition = 0

    private val _repeatMode = MutableStateFlow(RepeatMode.ALL)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _equalizerPreset = MutableStateFlow(EqualizerPreset.BALANCED)
    val equalizerPreset: StateFlow<EqualizerPreset> = _equalizerPreset.asStateFlow()

    private val _customBandLevels = MutableStateFlow(listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f))
    val customBandLevels: StateFlow<List<Float>> = _customBandLevels.asStateFlow()

    private val _customBassBoost = MutableStateFlow(0.0f)
    val customBassBoost: StateFlow<Float> = _customBassBoost.asStateFlow()

    private val _customVirtualizer = MutableStateFlow(0.0f)
    val customVirtualizer: StateFlow<Float> = _customVirtualizer.asStateFlow()

    private val _customTrebleClarity = MutableStateFlow(0.0f)
    val customTrebleClarity: StateFlow<Float> = _customTrebleClarity.asStateFlow()

    private val _loudnessBoosterEnabled = MutableStateFlow(false)
    val loudnessBoosterEnabled: StateFlow<Boolean> = _loudnessBoosterEnabled.asStateFlow()

    private val _reverbMode = MutableStateFlow(ReverbMode.OFF)
    val reverbMode: StateFlow<ReverbMode> = _reverbMode.asStateFlow()

    private val _sleepTimerMinutesLeft = MutableStateFlow<Int?>(null)
    val sleepTimerMinutesLeft: StateFlow<Int?> = _sleepTimerMinutesLeft.asStateFlow()

    private val _isStopAfterSong = MutableStateFlow(false)
    val isStopAfterSong: StateFlow<Boolean> = _isStopAfterSong.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val playerPrefs by lazy {
        context.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
    }
    private val _crossfadeSeconds = MutableStateFlow(
        try {
            context.getSharedPreferences("player_settings", Context.MODE_PRIVATE).getInt("crossfade_seconds", 0)
        } catch (_: Throwable) { 0 }
    )
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()

    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 15)
        _crossfadeSeconds.value = clamped
        try {
            playerPrefs.edit().putInt("crossfade_seconds", clamped).apply()
        } catch (_: Throwable) {}
    }

    var onSongFinishedListener: ((Song) -> Unit)? = null
    private var playJob: kotlinx.coroutines.Job? = null

    companion object {
        @Volatile
        var activeInstance: AudioPlayerManager? = null
    }

    init {
        activeInstance = this
        MusicPlaybackService.activePlayerManager = this
    }

    @VisibleForTesting
    fun activeVolumes(): Map<Long, Float> {
        val result = mutableMapOf<Long, Float>()
        fun record(player: ExoPlayer?, song: Song?) {
            if (player != null && song != null && player.playbackState != Player.STATE_IDLE) {
                val vol = if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED || !_isPlaying.value) {
                    0f
                } else {
                    player.volume
                }
                result[song.id] = vol
            }
        }
        if (isCrossfading) {
            record(outgoingPlayer, outgoingSong)
            record(incomingPlayer, incomingSong)
        } else {
            record(activePlayer, activeSong)
            record(standbyPlayer, standbySong)
        }
        return result
    }

    fun playQueueSong(index: Int) {
        val queue = _playQueue.value
        if (index in queue.indices) {
            _currentIndex.value = index
            playSong(queue[index])
        }
    }

    fun stopPlaybackCompletely() {
        isManuallyPaused = true
        isPausedDueToTransientLoss = false
        finishCrossfadeNormally()
        abandonAudioFocus()
        progressJob?.cancel()
        playJob?.cancel()
        playJob = null
        try { activePlayer?.stop(); activePlayer?.clearMediaItems() } catch (_: Throwable) {}
        try { standbyPlayer?.stop(); standbyPlayer?.clearMediaItems() } catch (_: Throwable) {}
        activeSong = null
        standbySong = null
        _isPlaying.value = false
        _currentSong.value = null
        _playQueue.value = emptyList()
        _currentIndex.value = 0
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        MusicPlaybackService.stop(context)
    }

    fun isSameSong(s1: Song?, s2: Song?): Boolean {
        if (s1 == null || s2 == null) return false
        if (s1.id > 0 && s2.id > 0) return s1.id == s2.id
        if (s1.filePath.isNotBlank() && s2.filePath.isNotBlank()) return s1.filePath == s2.filePath
        if (!s1.sourceUrl.isNullOrBlank() && !s2.sourceUrl.isNullOrBlank()) return s1.sourceUrl == s2.sourceUrl
        return s1.title.isNotBlank() && s1.title.trim().equals(s2.title.trim(), ignoreCase = true) &&
                s1.artist.trim().equals(s2.artist.trim(), ignoreCase = true)
    }

    private fun remapIndex(idx: Int, from: Int, to: Int): Int {
        return when {
            idx == from -> to
            from < to && idx in (from + 1)..to -> idx - 1
            from > to && idx in to until from -> idx + 1
            else -> idx
        }
    }

    fun onSongDeleted(deletedSong: Song) {
        val current = _currentSong.value
        val isCurrentPlaying = isSameSong(current, deletedSong)

        val oldQueue = _playQueue.value
        val oldIdx = _currentIndex.value
        val newQueue = oldQueue.filter { !isSameSong(it, deletedSong) }

        if (_isShuffle.value) {
            val toRemove = oldQueue.indices.filter { isSameSong(oldQueue[it], deletedSong) }.sortedDescending()
            for (delIdx in toRemove) {
                val orderIdx = shuffleOrder.indexOf(delIdx)
                if (orderIdx != -1) {
                    shuffleOrder.removeAt(orderIdx)
                    for (i in shuffleOrder.indices) {
                        if (shuffleOrder[i] > delIdx) {
                            shuffleOrder[i]--
                        }
                    }
                    if (orderIdx < shufflePosition) {
                        shufflePosition--
                    }
                    if (shuffleOrder.isNotEmpty()) {
                        shufflePosition = shufflePosition.coerceIn(0, shuffleOrder.size - 1)
                    } else {
                        shufflePosition = 0
                    }
                }
            }
            if (isCurrentPlaying) {
                stopPlaybackCompletely()
                _playQueue.value = newQueue
                if (newQueue.isNotEmpty() && shuffleOrder.isNotEmpty()) {
                    val nextIdx = shuffleOrder[shufflePosition]
                    _currentIndex.value = nextIdx
                    playSong(newQueue[nextIdx])
                }
            } else {
                _playQueue.value = newQueue
                if (newQueue.isNotEmpty() && shuffleOrder.isNotEmpty()) {
                    _currentIndex.value = shuffleOrder[shufflePosition]
                } else if (newQueue.isEmpty()) {
                    _currentIndex.value = 0
                }
            }
        } else {
            if (isCurrentPlaying) {
                stopPlaybackCompletely()
                _playQueue.value = newQueue
                if (newQueue.isNotEmpty()) {
                    val nextIdx = oldIdx.coerceIn(0, newQueue.size - 1)
                    _currentIndex.value = nextIdx
                    playSong(newQueue[nextIdx])
                }
            } else {
                _playQueue.value = newQueue
                if (newQueue.isNotEmpty()) {
                    val newIdx = if (current != null) {
                        newQueue.indexOfFirst { isSameSong(it, current) }.coerceAtLeast(0)
                    } else {
                        oldIdx.coerceIn(0, newQueue.size - 1)
                    }
                    _currentIndex.value = newIdx
                } else {
                    _currentIndex.value = 0
                }
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val currentList = _playQueue.value.toMutableList()
        if (index in currentList.indices) {
            val currIdx = _currentIndex.value
            val isCurrentPlaying = (index == currIdx)
            currentList.removeAt(index)
            _playQueue.value = currentList
            if (_isShuffle.value) {
                val orderIdx = shuffleOrder.indexOf(index)
                if (orderIdx != -1) {
                    shuffleOrder.removeAt(orderIdx)
                    for (i in shuffleOrder.indices) {
                        if (shuffleOrder[i] > index) {
                            shuffleOrder[i]--
                        }
                    }
                    if (orderIdx < shufflePosition) {
                        shufflePosition--
                    }
                    if (shuffleOrder.isNotEmpty()) {
                        shufflePosition = shufflePosition.coerceIn(0, shuffleOrder.size - 1)
                    } else {
                        shufflePosition = 0
                    }
                }
                if (isCurrentPlaying) {
                    if (currentList.isNotEmpty() && shuffleOrder.isNotEmpty()) {
                        val nextIdx = shuffleOrder[shufflePosition]
                        _currentIndex.value = nextIdx
                        playSong(currentList[nextIdx])
                    } else {
                        stopPlaybackCompletely()
                    }
                } else {
                    if (shuffleOrder.isNotEmpty()) {
                        _currentIndex.value = shuffleOrder[shufflePosition]
                    }
                }
            } else {
                if (index < currIdx) {
                    _currentIndex.value = currIdx - 1
                } else if (isCurrentPlaying) {
                    if (currentList.isNotEmpty()) {
                        val nextIdx = currIdx.coerceIn(0, currentList.size - 1)
                        _currentIndex.value = nextIdx
                        playSong(currentList[nextIdx])
                    } else {
                        stopPlaybackCompletely()
                    }
                }
            }
        }
    }

    fun addToQueueNext(song: Song) {
        val currentList = _playQueue.value.toMutableList()
        if (currentList.isEmpty()) {
            _playQueue.value = listOf(song)
            _currentIndex.value = 0
            playSong(song)
            return
        }
        val currIdx = _currentIndex.value
        val insertIdx = (currIdx + 1).coerceIn(0, currentList.size)
        currentList.add(insertIdx, song)
        _playQueue.value = currentList
        if (_isShuffle.value) {
            for (i in shuffleOrder.indices) {
                if (shuffleOrder[i] >= insertIdx) {
                    shuffleOrder[i]++
                }
            }
            val insertPos = (shufflePosition + 1).coerceIn(0, shuffleOrder.size)
            shuffleOrder.add(insertPos, insertIdx)
        }
    }

    fun addToQueue(song: Song) {
        val currentList = _playQueue.value.toMutableList()
        if (currentList.isEmpty()) {
            _playQueue.value = listOf(song)
            _currentIndex.value = 0
            playSong(song)
            return
        }
        val insertIdx = currentList.size
        currentList.add(song)
        _playQueue.value = currentList
        if (_isShuffle.value) {
            shuffleOrder.add(insertIdx)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentList = _playQueue.value.toMutableList()
        if (fromIndex in currentList.indices && toIndex in currentList.indices && fromIndex != toIndex) {
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)
            _playQueue.value = currentList
            val currIdx = _currentIndex.value
            _currentIndex.value = remapIndex(currIdx, fromIndex, toIndex)
            if (_isShuffle.value) {
                for (i in shuffleOrder.indices) {
                    shuffleOrder[i] = remapIndex(shuffleOrder[i], fromIndex, toIndex)
                }
            }
        }
    }

    fun clearQueueExceptCurrent() {
        val current = _currentSong.value ?: return
        _playQueue.value = listOf(current)
        _currentIndex.value = 0
        if (_isShuffle.value) {
            generateShuffleOrder(0)
        }
    }

    private fun generateShuffleOrder(currentSongIndex: Int) {
        val queue = _playQueue.value
        if (queue.isEmpty()) {
            shuffleOrder.clear()
            shufflePosition = 0
            return
        }
        val remaining = queue.indices.filter { it != currentSongIndex }.toMutableList()
        remaining.shuffle()
        shuffleOrder.clear()
        if (currentSongIndex in queue.indices) {
            shuffleOrder.add(currentSongIndex)
        }
        shuffleOrder.addAll(remaining)
        shufflePosition = 0
    }

    fun setShuffleEnabled(enabled: Boolean) {
        _isShuffle.value = enabled
        if (enabled) {
            generateShuffleOrder(_currentIndex.value)
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) {
            stopPlaybackCompletely()
            return
        }
        _playQueue.value = songs
        val validIndex = startIndex.coerceIn(0, songs.size - 1)
        _currentIndex.value = validIndex
        if (_isShuffle.value) {
            generateShuffleOrder(validIndex)
        }
        val song = songs[validIndex]
        _currentSong.value = song
        _durationMs.value = song.durationMs
        playSong(song)
    }

    fun playSong(song: Song) {
        playSong(song, 0L)
    }

    fun playSong(song: Song, initialPositionMs: Long = 0L) {
        val crossfadeMs = _crossfadeSeconds.value * 1000L
        val manualFadeMs = minOf(crossfadeMs, 1000L)

        // If currently playing a different song and manual crossfade is applicable
        if (manualFadeMs > 0 && _isPlaying.value && activePlayer != null && activeSong != null && song.id != activeSong?.id && initialPositionMs == 0L) {
            val idx = _playQueue.value.indexOfFirst { it.id == song.id }
            if (idx >= 0) _currentIndex.value = idx
            startAutoCrossfade(song, crossfadeMs = manualFadeMs)
            return
        }

        if (isCrossfading) {
            finishCrossfadeNormally()
        }

        _currentSong.value = song
        _currentPositionMs.value = initialPositionMs
        _isPlaying.value = true
        isManuallyPaused = false
        isPausedDueToTransientLoss = false
        _playbackError.value = null

        val requestId = ++playRequestId
        playJob?.cancel()

        playJob = scope.launch {
            try {
                ensurePlayersInitialized()
                val player = activePlayer ?: return@launch

                val validPath = withContext(Dispatchers.IO) {
                    if (!isActive) return@withContext null
                    val file = File(song.filePath)
                    val altOfflineFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline_tracks/${file.name}")
                    val altDownloadsFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${file.name}")

                    when {
                        file.exists() && file.length() > 0 -> file.absolutePath
                        altOfflineFile.exists() && altOfflineFile.length() > 0 -> altOfflineFile.absolutePath
                        altDownloadsFile.exists() && altDownloadsFile.length() > 0 -> altDownloadsFile.absolutePath
                        else -> null
                    }
                }

                if (!isActive || requestId != playRequestId || isManuallyPaused) {
                    return@launch
                }

                val canStreamSource = song.sourceType == com.example.data.model.SourceType.DIRECT_URL &&
                        !song.sourceUrl.isNullOrBlank() && song.sourceUrl.startsWith("http")
                if (validPath == null && !canStreamSource) {
                    _isPlaying.value = false
                    _playbackError.value = "Không tìm thấy file bài hát: ${song.title}. Hãy tải lại."
                    return@launch
                }

                requestAudioFocus()

                val uri = if (validPath != null) {
                    Uri.fromFile(File(validPath))
                } else {
                    Uri.parse(song.sourceUrl)
                }

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(mediaMetadata)
                    .build()

                activeSong = song
                _currentSong.value = song

                player.setMediaItem(mediaItem, initialPositionMs)
                player.prepare()
                applyPlaybackSpeedTo(player, _playbackSpeed.value)
                player.volume = getBaseVolume()
                player.play()

                _isPlaying.value = true
                MusicPlaybackService.start(context)
                MusicPlaybackService.updateActivePlayer(player)
                startProgressTracker()

            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("AudioPlayerManager", "Failed to play song: ${song.title}", e)
                    _isPlaying.value = false
                }
            }
        }
    }

    private fun resolveValidFilePath(song: Song): String? {
        val file = File(song.filePath)
        val altOfflineFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline_tracks/${file.name}")
        val altDownloadsFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${file.name}")
        return when {
            file.exists() && file.length() > 0 -> file.absolutePath
            altOfflineFile.exists() && altOfflineFile.length() > 0 -> altOfflineFile.absolutePath
            altDownloadsFile.exists() && altDownloadsFile.length() > 0 -> altDownloadsFile.absolutePath
            else -> null
        }
    }

    private fun preloadStandby(song: Song) {
        val player = standbyPlayer ?: return
        try {
            val validPath = resolveValidFilePath(song)
            val uri = if (validPath != null) Uri.fromFile(File(validPath)) else Uri.parse(song.sourceUrl)
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(mediaMetadata)
                .build()

            player.setMediaItem(mediaItem, 0L)
            player.volume = 0f
            player.prepare()
            standbySong = song
        } catch (e: Throwable) {
            Log.w("AudioPlayerManager", "preloadStandby note: ${e.message}")
        }
    }

    private fun startAutoCrossfade(nextSong: Song, crossfadeMs: Long) {
        if (isCrossfading) {
            finishCrossfadeNormally()
        }
        isCrossfading = true
        crossfadeElapsedMs = 0L
        crossfadeProgress = 0f
        crossfadeDurationMs = crossfadeMs

        val oldPlayer = activePlayer
        val newPlayer = standbyPlayer

        outgoingPlayer = oldPlayer
        outgoingSong = activeSong
        incomingPlayer = newPlayer
        incomingSong = nextSong

        // Advance queue
        advanceQueueIndicesTo(nextSong)
        _currentSong.value = nextSong
        activeSong = nextSong
        activePlayerIndex = 1 - activePlayerIndex

        // Ensure standby player is prepared
        if (newPlayer?.playbackState == Player.STATE_IDLE || standbySong?.id != nextSong.id) {
            preloadStandby(nextSong)
        }
        standbySong = null

        newPlayer?.volume = 0f
        applyPlaybackSpeedTo(newPlayer, _playbackSpeed.value)
        newPlayer?.play()
        newPlayer?.let { MusicPlaybackService.updateActivePlayer(it) }

        startCrossfadeRampJob()
    }

    private fun startCrossfadeRampJob() {
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            val stepMs = 40L
            while (isActive && isCrossfading && crossfadeProgress < 1f) {
                if (_isPlaying.value && !isManuallyPaused) {
                    delay(stepMs)
                    if (!_isPlaying.value || isManuallyPaused || !isCrossfading) continue
                    crossfadeElapsedMs += stepMs
                    crossfadeProgress = (crossfadeElapsedMs.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
                    val (outGain, inGain) = Crossfade.equalPowerGains(crossfadeProgress)
                    val base = getBaseVolume()
                    try { outgoingPlayer?.volume = outGain * base } catch (_: Throwable) {}
                    try { incomingPlayer?.volume = inGain * base } catch (_: Throwable) {}
                } else {
                    delay(50L)
                }
            }
            if (isActive && isCrossfading) {
                finishCrossfadeNormally()
            }
        }
    }

    private fun finishCrossfadeNormally() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        try {
            outgoingPlayer?.volume = 0f
            outgoingPlayer?.stop()
            outgoingPlayer?.clearMediaItems()
        } catch (_: Throwable) {}
        outgoingPlayer = null
        outgoingSong = null

        try {
            incomingPlayer?.volume = getBaseVolume()
        } catch (_: Throwable) {}
        incomingPlayer = null
        incomingSong = null
        isCrossfading = false
    }

    private fun advanceQueueIndicesTo(targetSong: Song) {
        val queue = _playQueue.value
        if (queue.isEmpty()) return
        val idx = queue.indexOfFirst { it.id == targetSong.id }
        if (idx >= 0) {
            _currentIndex.value = idx
            if (_isShuffle.value) {
                val sIdx = shuffleOrder.indexOf(idx)
                if (sIdx >= 0) shufflePosition = sIdx
            }
        }
    }

    private fun peekNextSong(): Song? {
        val queue = _playQueue.value
        if (queue.isEmpty()) return null
        if (_isShuffle.value) {
            val nextShufflePos = shufflePosition + 1
            return if (nextShufflePos < shuffleOrder.size) {
                queue.getOrNull(shuffleOrder[nextShufflePos])
            } else if (_repeatMode.value == RepeatMode.ALL) {
                queue.getOrNull(shuffleOrder.firstOrNull() ?: 0)
            } else null
        } else {
            val nextIndex = _currentIndex.value + 1
            return if (nextIndex < queue.size) {
                queue.getOrNull(nextIndex)
            } else if (_repeatMode.value == RepeatMode.ALL) {
                queue.firstOrNull()
            } else null
        }
    }

    private fun peekPreviousSong(): Song? {
        val queue = _playQueue.value
        if (queue.isEmpty()) return null
        if (_isShuffle.value) {
            val prevShufflePos = shufflePosition - 1
            return if (prevShufflePos >= 0 && prevShufflePos < shuffleOrder.size) {
                queue.getOrNull(shuffleOrder[prevShufflePos])
            } else if (_repeatMode.value == RepeatMode.ALL) {
                queue.getOrNull(shuffleOrder.lastOrNull() ?: 0)
            } else null
        } else {
            val prevIndex = _currentIndex.value - 1
            return if (prevIndex >= 0) {
                queue.getOrNull(prevIndex)
            } else if (_repeatMode.value == RepeatMode.ALL) {
                queue.lastOrNull()
            } else null
        }
    }

    private fun advanceQueueIndicesToNext() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return
        if (_isShuffle.value) {
            if (shufflePosition + 1 < shuffleOrder.size) {
                shufflePosition++
                _currentIndex.value = shuffleOrder[shufflePosition]
            } else if (_repeatMode.value == RepeatMode.ALL) {
                shufflePosition = 0
                _currentIndex.value = shuffleOrder[0]
            }
        } else {
            if (_currentIndex.value + 1 < queue.size) {
                _currentIndex.value++
            } else if (_repeatMode.value == RepeatMode.ALL) {
                _currentIndex.value = 0
            }
        }
    }

    private fun retreatQueueIndicesToPrevious() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return
        if (_isShuffle.value) {
            if (shufflePosition > 0 && shufflePosition < shuffleOrder.size) {
                shufflePosition--
                _currentIndex.value = shuffleOrder[shufflePosition]
            } else if (_repeatMode.value == RepeatMode.ALL) {
                shufflePosition = shuffleOrder.size - 1
                _currentIndex.value = shuffleOrder[shufflePosition]
            }
        } else {
            if (_currentIndex.value > 0) {
                _currentIndex.value--
            } else if (_repeatMode.value == RepeatMode.ALL) {
                _currentIndex.value = queue.size - 1
            }
        }
    }

    private fun switchToStandbyImmediately() {
        val nextSong = standbySong ?: peekNextSong() ?: return
        advanceQueueIndicesTo(nextSong)
        _currentSong.value = nextSong
        activeSong = nextSong

        val oldPlayer = activePlayer
        val newPlayer = standbyPlayer

        activePlayerIndex = 1 - activePlayerIndex
        standbySong = null

        try {
            oldPlayer?.stop()
            oldPlayer?.clearMediaItems()
        } catch (_: Throwable) {}

        try {
            newPlayer?.volume = getBaseVolume()
            applyPlaybackSpeedTo(newPlayer, _playbackSpeed.value)
            newPlayer?.play()
            newPlayer?.let { MusicPlaybackService.updateActivePlayer(it) }
        } catch (_: Throwable) {}

        _isPlaying.value = true
        startProgressTracker()
    }

    fun updateCurrentSongMetadata(newTitle: String, newArtist: String) {
        _currentSong.value = _currentSong.value?.copy(title = newTitle, artist = newArtist)
    }

    fun updateCurrentSongLyrics(newLyrics: String?) {
        _currentSong.value = _currentSong.value?.copy(lyrics = newLyrics)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        isManuallyPaused = true
        isPausedDueToTransientLoss = false
        progressJob?.cancel()
        playJob?.cancel()
        playJob = null
        try { activePlayer?.pause() } catch (_: Throwable) {}
        try { standbyPlayer?.pause() } catch (_: Throwable) {}
        try { outgoingPlayer?.pause() } catch (_: Throwable) {}
        try { incomingPlayer?.pause() } catch (_: Throwable) {}
        _isPlaying.value = false
    }

    fun resume() {
        isManuallyPaused = false
        isPausedDueToTransientLoss = false
        val song = _currentSong.value ?: return

        val pendingSeek = pendingSeekPosition
        if (pendingSeek != null) {
            pendingSeekPosition = null
            playSong(song, initialPositionMs = pendingSeek)
            return
        }

        requestAudioFocus()
        val player = activePlayer
        if (player != null && player.playbackState != Player.STATE_IDLE) {
            val base = getBaseVolume()
            if (isCrossfading) {
                val (outGain, inGain) = Crossfade.equalPowerGains(crossfadeProgress)
                try { outgoingPlayer?.volume = outGain * base } catch (_: Throwable) {}
                try { incomingPlayer?.volume = inGain * base } catch (_: Throwable) {}
                try { outgoingPlayer?.play() } catch (_: Throwable) {}
                try { incomingPlayer?.play() } catch (_: Throwable) {}
            } else {
                try {
                    player.volume = base
                    player.play()
                } catch (_: Throwable) {}
            }
            _isPlaying.value = true
            startProgressTracker()
            MusicPlaybackService.start(context)
        } else {
            playSong(song, initialPositionMs = _currentPositionMs.value)
        }
    }

    fun seekTo(positionMs: Long) {
        if (isCrossfading) {
            finishCrossfadeNormally()
        }
        val maxDuration = _durationMs.value.coerceAtLeast(1L)
        val safeMargin = if (maxDuration > 5000L) 1500L else 500L
        val maxSeekable = (maxDuration - safeMargin).coerceAtLeast(0L)
        val clamped = positionMs.coerceIn(0L, maxSeekable)
        _currentPositionMs.value = clamped
        try {
            activePlayer?.volume = getBaseVolume()
            activePlayer?.seekTo(clamped)
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "seekTo error", e)
        }
    }

    fun skipForward10() {
        val maxDuration = _durationMs.value.coerceAtLeast(1L)
        val safeMargin = if (maxDuration > 5000L) 1500L else 500L
        val maxSeekable = (maxDuration - safeMargin).coerceAtLeast(0L)
        val target = (_currentPositionMs.value + 10000L).coerceAtMost(maxSeekable)
        seekTo(target)
    }

    fun skipBackward10() {
        seekTo((_currentPositionMs.value - 10000L).coerceAtLeast(0L))
    }

    fun playNext() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return
        val nextSong = peekNextSong()
        if (nextSong == null) {
            _isPlaying.value = false
            _currentPositionMs.value = 0L
            try { activePlayer?.pause() } catch (_: Throwable) {}
            return
        }

        val crossfadeMs = _crossfadeSeconds.value * 1000L
        val manualFadeMs = minOf(crossfadeMs, 1000L)
        if (manualFadeMs > 0 && _isPlaying.value && activePlayer != null) {
            if (isCrossfading) {
                finishCrossfadeNormally()
            }
            startAutoCrossfade(nextSong, crossfadeMs = manualFadeMs)
        } else {
            if (isCrossfading) {
                finishCrossfadeNormally()
            }
            advanceQueueIndicesToNext()
            playSong(nextSong, 0L)
        }
    }

    fun playPrevious() {
        val queue = _playQueue.value
        if (queue.isEmpty()) return

        if (_currentPositionMs.value > 3000L) {
            seekTo(0)
            return
        }

        val prevSong = peekPreviousSong()
        if (prevSong == null) {
            seekTo(0)
            return
        }

        val crossfadeMs = _crossfadeSeconds.value * 1000L
        val manualFadeMs = minOf(crossfadeMs, 1000L)
        if (manualFadeMs > 0 && _isPlaying.value && activePlayer != null) {
            if (isCrossfading) {
                finishCrossfadeNormally()
            }
            startAutoCrossfade(prevSong, crossfadeMs = manualFadeMs)
        } else {
            if (isCrossfading) {
                finishCrossfadeNormally()
            }
            retreatQueueIndicesToPrevious()
            playSong(prevSong, 0L)
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        if (newShuffle) {
            generateShuffleOrder(_currentIndex.value)
        }
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
        try {
            activePlayer?.let { applyPlaybackSpeedTo(it, speed) }
            standbyPlayer?.let { applyPlaybackSpeedTo(it, speed) }
        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Could not set playback speed", e)
        }
    }

    private fun applyPlaybackSpeedTo(player: ExoPlayer?, speed: Float) {
        try {
            player?.let { p ->
                p.playbackParameters = p.playbackParameters.withSpeed(speed)
            }
        } catch (_: Throwable) {}
    }

    private fun setupAudioEffects(audioSessionId: Int) {
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            loudnessEnhancer?.release()
            presetReverb?.release()

            equalizer = null
            bassBoost = null
            virtualizer = null
            loudnessEnhancer = null
            presetReverb = null

            if (audioSessionId != 0 && audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                try {
                    equalizer = Equalizer(0, audioSessionId)
                } catch (e: Exception) {
                    Log.w("AudioPlayerManager", "Equalizer init note: ${e.message}")
                }
                try {
                    bassBoost = BassBoost(0, audioSessionId)
                } catch (e: Exception) {
                    Log.w("AudioPlayerManager", "BassBoost init note: ${e.message}")
                }
                try {
                    virtualizer = Virtualizer(0, audioSessionId)
                } catch (e: Exception) {
                    Log.w("AudioPlayerManager", "Virtualizer init note: ${e.message}")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    try {
                        loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                    } catch (e: Exception) {
                        Log.w("AudioPlayerManager", "LoudnessEnhancer init note: ${e.message}")
                    }
                }
                try {
                    presetReverb = PresetReverb(0, audioSessionId)
                } catch (e: Exception) {
                    Log.w("AudioPlayerManager", "PresetReverb init note: ${e.message}")
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

    fun setCustomVirtualizer(strength: Float) {
        _customVirtualizer.value = strength.coerceIn(0.0f, 1.0f)
        _equalizerPreset.value = EqualizerPreset.CUSTOM
        applyEqualizerPreset(EqualizerPreset.CUSTOM)
    }

    fun setCustomTrebleClarity(strength: Float) {
        _customTrebleClarity.value = strength.coerceIn(0.0f, 1.0f)
        _equalizerPreset.value = EqualizerPreset.CUSTOM
        applyEqualizerPreset(EqualizerPreset.CUSTOM)
    }

    fun setLoudnessBoosterEnabled(enabled: Boolean) {
        _loudnessBoosterEnabled.value = enabled
        _equalizerPreset.value = EqualizerPreset.CUSTOM
        applyEqualizerPreset(EqualizerPreset.CUSTOM)
    }

    fun setReverbMode(mode: ReverbMode) {
        _reverbMode.value = mode
        _equalizerPreset.value = EqualizerPreset.CUSTOM
        applyEqualizerPreset(EqualizerPreset.CUSTOM)
    }

    fun resetCustomEqualizer() {
        _customBandLevels.value = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
        _customBassBoost.value = 0.0f
        _customVirtualizer.value = 0.0f
        _customTrebleClarity.value = 0.0f
        _loudnessBoosterEnabled.value = false
        _reverbMode.value = ReverbMode.OFF
        if (_equalizerPreset.value == EqualizerPreset.CUSTOM) {
            applyEqualizerPreset(EqualizerPreset.CUSTOM)
        }
    }

    private fun applyEqualizerPreset(preset: EqualizerPreset) {
        val eq = equalizer
        val bb = bassBoost
        val virt = virtualizer
        val le = loudnessEnhancer
        val rev = presetReverb

        try {
            if (preset == EqualizerPreset.BALANCED) {
                try {
                    eq?.enabled = false
                    bb?.enabled = false
                    virt?.enabled = false
                    le?.enabled = false
                    rev?.enabled = false
                    updateVolumeWithHeadroom(false)
                } catch (e: Exception) {}
                return
            }

            eq?.enabled = true

            val numBands = eq?.numberOfBands?.toInt() ?: 0
            val minLevel = eq?.bandLevelRange?.get(0) ?: -1500
            val maxLevel = eq?.bandLevelRange?.get(1) ?: 1500
            val maxDelta = (maxLevel - minLevel) / 2

            val weights = when (preset) {
                EqualizerPreset.HIFI_MASTER -> listOf(0.40f, 0.25f, 0.05f, 0.35f, 0.50f)
                EqualizerPreset.BALANCED -> listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
                EqualizerPreset.TIKTOK_BOOST -> listOf(0.80f, 0.55f, 0.10f, 0.20f, 0.35f)
                EqualizerPreset.BASS_BOOST -> listOf(0.95f, 0.70f, 0.10f, -0.20f, 0.10f)
                EqualizerPreset.VOCAL -> listOf(-0.35f, -0.05f, 0.80f, 0.75f, 0.30f)
                EqualizerPreset.ELECTRONIC -> listOf(0.85f, 0.45f, -0.20f, 0.50f, 0.90f)
                EqualizerPreset.ROCK -> listOf(0.65f, 0.35f, 0.60f, 0.70f, 0.55f)
                EqualizerPreset.ACOUSTIC -> listOf(0.25f, 0.15f, 0.40f, 0.60f, 0.70f)
                EqualizerPreset.CUSTOM -> {
                    val base = _customBandLevels.value.toMutableList()
                    val clarity = _customTrebleClarity.value
                    if (base.size >= 5 && clarity > 0f) {
                        base[3] = (base[3] + clarity * 0.4f).coerceIn(-1.0f, 1.0f)
                        base[4] = (base[4] + clarity * 0.6f).coerceIn(-1.0f, 1.0f)
                    }
                    base
                }
            }

            for (i in 0 until numBands) {
                val weight = if (i < weights.size) weights[i] else 0.0f
                val level = (weight * maxDelta).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
                eq?.setBandLevel(i.toShort(), level)
            }

            bb?.let { bass ->
                val strength = when (preset) {
                    EqualizerPreset.HIFI_MASTER -> 300.toShort()
                    EqualizerPreset.TIKTOK_BOOST -> 480.toShort()
                    EqualizerPreset.BASS_BOOST -> 1000.toShort()
                    EqualizerPreset.ELECTRONIC -> 600.toShort()
                    EqualizerPreset.ROCK -> 400.toShort()
                    EqualizerPreset.ACOUSTIC -> 100.toShort()
                    EqualizerPreset.CUSTOM -> (_customBassBoost.value * 1000).toInt().toShort()
                    else -> 0.toShort()
                }
                bass.enabled = strength > 0
                if (strength > 0) {
                    bass.setStrength(strength)
                }
            }

            virt?.let { v ->
                val vStrength = when (preset) {
                    EqualizerPreset.HIFI_MASTER -> 450.toShort()
                    EqualizerPreset.TIKTOK_BOOST -> 250.toShort()
                    EqualizerPreset.BASS_BOOST -> 200.toShort()
                    EqualizerPreset.VOCAL -> 350.toShort()
                    EqualizerPreset.ELECTRONIC -> 500.toShort()
                    EqualizerPreset.ROCK -> 300.toShort()
                    EqualizerPreset.ACOUSTIC -> 400.toShort()
                    EqualizerPreset.CUSTOM -> (_customVirtualizer.value * 1000).toInt().toShort()
                    else -> 0.toShort()
                }
                v.enabled = vStrength > 0
                if (vStrength > 0) {
                    v.setStrength(vStrength)
                }
            }

            le?.let { enhancer ->
                val shouldEnable = when (preset) {
                    EqualizerPreset.HIFI_MASTER -> true
                    EqualizerPreset.TIKTOK_BOOST -> true
                    EqualizerPreset.BASS_BOOST -> true
                    EqualizerPreset.ELECTRONIC -> true
                    EqualizerPreset.CUSTOM -> _loudnessBoosterEnabled.value
                    else -> false
                }
                val gain = when (preset) {
                    EqualizerPreset.HIFI_MASTER -> 180
                    EqualizerPreset.TIKTOK_BOOST -> 300
                    EqualizerPreset.BASS_BOOST -> 200
                    EqualizerPreset.ELECTRONIC -> 250
                    EqualizerPreset.CUSTOM -> 250
                    else -> 0
                }
                enhancer.enabled = shouldEnable
                if (shouldEnable) {
                    enhancer.setTargetGain(gain)
                }
            }

            rev?.let { r ->
                val targetMode = if (preset == EqualizerPreset.CUSTOM) _reverbMode.value else ReverbMode.OFF
                if (targetMode != ReverbMode.OFF) {
                    r.preset = targetMode.presetValue
                    r.enabled = true
                } else {
                    r.enabled = false
                }
            }

            val hasBoost = weights.any { it > 0.05f } || preset == EqualizerPreset.TIKTOK_BOOST || preset == EqualizerPreset.BASS_BOOST || preset == EqualizerPreset.HIFI_MASTER
            updateVolumeWithHeadroom(hasBoost)

        } catch (e: Exception) {
            Log.w("AudioPlayerManager", "Failed to apply equalizer preset: ${e.message}")
        }
    }

    fun setStopAfterCurrentSong(enabled: Boolean) {
        sleepTimerJob?.cancel()
        _sleepTimerMinutesLeft.value = null
        stopAfterCurrentSong = enabled
        _isStopAfterSong.value = enabled
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        stopAfterCurrentSong = false
        _isStopAfterSong.value = false

        if (minutes == null || minutes <= 0) {
            _sleepTimerMinutesLeft.value = null
            return
        }

        _sleepTimerMinutesLeft.value = minutes

        sleepTimerJob = scope.launch(Dispatchers.Main) {
            val totalMs = minutes * 60 * 1000L
            val targetTime = System.currentTimeMillis() + totalMs
            while (isActive) {
                val remainingMs = targetTime - System.currentTimeMillis()
                if (remainingMs <= 0) {
                    break
                }
                val remainingMins = Math.ceil(remainingMs / 60000.0).toInt().coerceAtLeast(1)
                _sleepTimerMinutesLeft.value = remainingMins
                delay(1000L)
            }
            _sleepTimerMinutesLeft.value = null
            pause()
        }
    }

    private fun handleSongCompletion(player: ExoPlayer) {
        if (player != activePlayer && player != outgoingPlayer) return
        if (isCrossfading && player == outgoingPlayer) {
            finishCrossfadeNormally()
            return
        }
        if (isCrossfading) return

        if (!_isPlaying.value || isManuallyPaused) {
            Log.d("AudioPlayerManager", "handleSongCompletion ignored because player is paused")
            return
        }

        _currentSong.value?.let { onSongFinishedListener?.invoke(it) }

        if (stopAfterCurrentSong) {
            stopAfterCurrentSong = false
            _isStopAfterSong.value = false
            _sleepTimerMinutesLeft.value = null
            pause()
            return
        }

        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                activePlayer?.seekTo(0)
                activePlayer?.volume = getBaseVolume()
                activePlayer?.play()
                _currentPositionMs.value = 0L
                _isPlaying.value = true
                startProgressTracker()
            }
            RepeatMode.ALL -> {
                if (_crossfadeSeconds.value == 0 && standbySong != null && standbyPlayer?.playbackState != Player.STATE_IDLE) {
                    switchToStandbyImmediately()
                } else {
                    playNext()
                }
            }
            RepeatMode.OFF -> {
                val hasNext = if (_isShuffle.value) {
                    shufflePosition + 1 < shuffleOrder.size
                } else {
                    _currentIndex.value < _playQueue.value.size - 1
                }
                if (hasNext) {
                    if (_crossfadeSeconds.value == 0 && standbySong != null && standbyPlayer?.playbackState != Player.STATE_IDLE) {
                        switchToStandbyImmediately()
                    } else {
                        playNext()
                    }
                } else {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    try { activePlayer?.pause() } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                try {
                    val player = activePlayer
                    if (player != null) {
                        val cur = player.currentPosition
                        if (cur >= 0) {
                            _currentPositionMs.value = cur
                        }
                        val dur = player.duration
                        if (dur > 0) {
                            _durationMs.value = dur
                        }

                        if (dur > 0 && cur >= 0) {
                            val remaining = dur - cur
                            val crossfadeMs = _crossfadeSeconds.value * 1000L
                            val nextSong = peekNextSong()
                            val repeatOne = (_repeatMode.value == RepeatMode.ONE)

                            // Nap san bai ke vao standby khi con <= crossfadeMs + 3000
                            if (!repeatOne && nextSong != null && remaining <= (crossfadeMs + 3000L)) {
                                if (standbySong?.id != nextSong.id || standbyPlayer?.playbackState == Player.STATE_IDLE) {
                                    preloadStandby(nextSong)
                                }
                            }

                            // Kiem tra shouldStart
                            if (!isCrossfading && Crossfade.shouldStart(
                                    positionMs = cur,
                                    durationMs = dur,
                                    crossfadeMs = crossfadeMs,
                                    hasNext = (nextSong != null),
                                    repeatOne = repeatOne
                                )
                            ) {
                                startAutoCrossfade(nextSong!!, crossfadeMs = crossfadeMs.coerceAtLeast(1000L))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignored
                }
                delay(50L)
            }
        }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            loudnessEnhancer?.release()
            presetReverb?.release()
        } catch (e: Exception) {}
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        presetReverb = null

        try {
            activePlayer?.stop()
            activePlayer?.clearMediaItems()
        } catch (e: Exception) {}
        try {
            standbyPlayer?.stop()
            standbyPlayer?.clearMediaItems()
        } catch (e: Exception) {}
    }

    fun snapshot(): SavedPlayback? {
        val queue = _playQueue.value
        if (queue.isEmpty()) return null
        return SavedPlayback(
            songIds = queue.map { it.id },
            currentIndex = _currentIndex.value,
            positionMs = _currentPositionMs.value,
            shuffle = _isShuffle.value,
            repeatMode = _repeatMode.value
        )
    }

    fun restoreQueue(songs: List<Song>, index: Int, positionMs: Long) {
        if (songs.isEmpty()) return
        playJob?.cancel()
        finishCrossfadeNormally()
        releasePlayer()
        val validIndex = index.coerceIn(0, songs.size - 1)
        _playQueue.value = songs
        _currentIndex.value = validIndex
        val song = songs[validIndex]
        _currentSong.value = song
        _currentPositionMs.value = positionMs
        _durationMs.value = song.durationMs
        _isPlaying.value = false
        isManuallyPaused = true
        pendingSeekPosition = positionMs
        if (_isShuffle.value) {
            generateShuffleOrder(validIndex)
        }
    }

    fun cleanup() {
        abandonAudioFocus()
        sleepTimerJob?.cancel()
        stopAfterCurrentSong = false
        _isStopAfterSong.value = false
        _sleepTimerMinutesLeft.value = null
        finishCrossfadeNormally()
        releasePlayer()
        try {
            playerA?.release()
        } catch (_: Exception) {}
        try {
            playerB?.release()
        } catch (_: Exception) {}
        playerA = null
        playerB = null
        playersInitialized = false
        MusicPlaybackService.stop(context)
    }
}
