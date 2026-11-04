package com.example.data.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.util.Log
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
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var stopAfterCurrentSong = false
    private var isManuallyPaused = false
    private var isPausedDueToTransientLoss = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another audio app took permanent focus (Spotify, YouTube, etc.)
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Short interruption (phone call, GPS voice prompt)
                if (_isPlaying.value && !isManuallyPaused) {
                    isPausedDueToTransientLoss = true
                    mediaPlayer?.let { player ->
                        try { if (player.isPlaying) player.pause() } catch (e: Exception) {}
                    }
                    _isPlaying.value = false
                    progressJob?.cancel()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Short notification: duck volume
                mediaPlayer?.let { player ->
                    try {
                        val base = getBaseVolume() * 0.35f
                        player.setVolume(base, base)
                    } catch (e: Exception) {}
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Audio focus restored: ONLY resume if it was paused automatically by a transient loss!
                // If the user manually paused the music, NEVER resume automatically!
                if (isPausedDueToTransientLoss && !isManuallyPaused) {
                    isPausedDueToTransientLoss = false
                    val base = getBaseVolume()
                    mediaPlayer?.let { player ->
                        try { player.setVolume(base, base) } catch (e: Exception) {}
                    }
                    resume()
                } else {
                    // Restore ducked volume
                    val base = getBaseVolume()
                    mediaPlayer?.let { player ->
                        try { player.setVolume(base, base) } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setAcceptsDelayedFocusGain(false)
                    .build().also { audioFocusRequest = it }
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
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
        } catch (e: Exception) {}
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
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.setVolume(baseVol, baseVol)
                }
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

    // Shuffled deck tracking: ensures every song in queue is played once before reshuffling
    private val shuffleOrder = mutableListOf<Int>()
    private var shufflePosition = 0

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

    private val _crossfadeSeconds = MutableStateFlow(0)
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()

    fun setCrossfadeSeconds(seconds: Int) {
        _crossfadeSeconds.value = seconds.coerceIn(0, 15)
    }

    var onSongFinishedListener: ((Song) -> Unit)? = null
    private var playJob: kotlinx.coroutines.Job? = null

    init {
        MusicPlaybackService.activePlayerManager = this
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
        _isPlaying.value = false
        progressJob?.cancel()
        playJob?.cancel()
        playJob = null
        releasePlayer()
        _currentSong.value = null
        _playQueue.value = emptyList()
        _currentIndex.value = 0
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        abandonAudioFocus()
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
                releasePlayer()
                _playQueue.value = newQueue
                if (newQueue.isNotEmpty() && shuffleOrder.isNotEmpty()) {
                    val nextIdx = shuffleOrder[shufflePosition]
                    _currentIndex.value = nextIdx
                    playSong(newQueue[nextIdx])
                } else {
                    stopPlaybackCompletely()
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
                // First release player so the deleted track's audio stops immediately
                releasePlayer()
                _playQueue.value = newQueue
                if (newQueue.isNotEmpty()) {
                    val nextIdx = oldIdx.coerceIn(0, newQueue.size - 1)
                    _currentIndex.value = nextIdx
                    playSong(newQueue[nextIdx])
                } else {
                    stopPlaybackCompletely()
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

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        _playQueue.value = songs
        val validIndex = startIndex.coerceIn(0, songs.size - 1)
        _currentIndex.value = validIndex
        if (_isShuffle.value) {
            generateShuffleOrder(validIndex)
        }
        playSong(songs[validIndex])
    }

    fun playSong(song: Song) {
        _currentSong.value = song
        _currentPositionMs.value = 0L
        _isPlaying.value = true
        isManuallyPaused = false
        isPausedDueToTransientLoss = false
        _playbackError.value = null
        requestAudioFocus()

        playJob?.cancel()
        releasePlayer()

        playJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!isActive) return@withContext
                    val file = File(song.filePath)
                    val altOfflineFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "offline_tracks/${file.name}")
                    val altDownloadsFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${file.name}")

                    val validPath = when {
                        file.exists() && file.length() > 0 -> file.absolutePath
                        altOfflineFile.exists() && altOfflineFile.length() > 0 -> altOfflineFile.absolutePath
                        altDownloadsFile.exists() && altDownloadsFile.length() > 0 -> altDownloadsFile.absolutePath
                        else -> null
                    }

                    if (!isActive || !_isPlaying.value || isManuallyPaused) return@withContext

                    if (validPath == null && (song.sourceUrl.isNullOrBlank() || !song.sourceUrl.startsWith("http"))) {
                        releasePlayer()
                        _isPlaying.value = false
                        _playbackError.value = "Không tìm thấy file bài hát: ${song.title}. Hãy tải lại."
                        return@withContext
                    }

                    val newPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )

                        if (validPath != null) {
                            setDataSource(validPath)
                            prepare()
                            if (!isActive || !_isPlaying.value || isManuallyPaused) {
                                release()
                                return@withContext
                            }
                            _durationMs.value = duration.toLong().coerceAtLeast(song.durationMs)
                            setupAudioEffects(audioSessionId)
                            applyPlaybackSpeed(_playbackSpeed.value)
                            startWithCrossfade(this)
                            _isPlaying.value = true
                            startProgressTracker()
                            MusicPlaybackService.updateService(context, song.copy(filePath = validPath), true)
                        } else if (!song.sourceUrl.isNullOrBlank() && song.sourceUrl.startsWith("http")) {
                            setDataSource(context, Uri.parse(song.sourceUrl))
                            setOnPreparedListener { mp ->
                                if (!isActive || !_isPlaying.value || isManuallyPaused) {
                                    try { mp.release() } catch (e: Exception) {}
                                    return@setOnPreparedListener
                                }
                                _durationMs.value = mp.duration.toLong().coerceAtLeast(song.durationMs)
                                setupAudioEffects(mp.audioSessionId)
                                applyPlaybackSpeed(_playbackSpeed.value)
                                startWithCrossfade(mp)
                                _isPlaying.value = true
                                startProgressTracker()
                                MusicPlaybackService.updateService(context, song, true)
                            }
                            prepareAsync()
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

                    if (!isActive) {
                        try {
                            newPlayer.setOnCompletionListener(null)
                            newPlayer.release()
                        } catch (e: Exception) {}
                        return@withContext
                    }

                    mediaPlayer = newPlayer
                }

                _currentSong.value = song

            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("AudioPlayerManager", "Failed to play song: ${song.title}", e)
                    _isPlaying.value = false
                }
            }
        }
    }

    fun updateCurrentSongMetadata(newTitle: String, newArtist: String) {
        _currentSong.value = _currentSong.value?.copy(title = newTitle, artist = newArtist)
    }

    fun updateCurrentSongLyrics(newLyrics: String?) {
        _currentSong.value = _currentSong.value?.copy(lyrics = newLyrics)
    }

    fun togglePlayPause() {
        if (mediaPlayer == null) {
            _currentSong.value?.let { playSong(it) }
            return
        }
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
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.pause()
                }
            } catch (e: Exception) {}
        }
        _isPlaying.value = false
        abandonAudioFocus()
        scope.launch(Dispatchers.IO) {
            _currentSong.value?.let {
                MusicPlaybackService.updateService(context, it, false)
            }
        }
    }

    fun resume() {
        isManuallyPaused = false
        isPausedDueToTransientLoss = false
        val player = mediaPlayer ?: run {
            _currentSong.value?.let { playSong(it) }
            return
        }
        requestAudioFocus()
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
        val maxDuration = _durationMs.value.coerceAtLeast(1L)
        // Leave a safety margin near the end so Android's MediaPlayer decoder doesn't
        // overshoot into EOF and trigger premature song completion / skip to next
        val safeMargin = if (maxDuration > 5000L) 1500L else 500L
        val maxSeekable = (maxDuration - safeMargin).coerceAtLeast(0L)
        val clamped = positionMs.coerceIn(0L, maxSeekable)
        _currentPositionMs.value = clamped
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(clamped.toInt())
            }
            if (_isPlaying.value && !isManuallyPaused && !player.isPlaying) {
                player.start()
            }
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

        if (_isShuffle.value) {
            if (shuffleOrder.size != queue.size || shuffleOrder.isEmpty()) {
                generateShuffleOrder(_currentIndex.value)
            }

            if (shufflePosition + 1 < shuffleOrder.size) {
                // Next unplayed song in current randomized cycle
                shufflePosition++
                val nextIndex = shuffleOrder[shufflePosition]
                _currentIndex.value = nextIndex
                playSong(queue[nextIndex])
            } else {
                // All songs in queue have been played once!
                if (_repeatMode.value == RepeatMode.ALL) {
                    val lastPlayed = shuffleOrder.lastOrNull() ?: _currentIndex.value
                    val newDeck = queue.indices.toMutableList()
                    newDeck.shuffle()
                    // Avoid playing the exact same song back-to-back if queue has more than 1 song
                    if (newDeck.size > 1 && newDeck[0] == lastPlayed) {
                        val swapIdx = (1 until newDeck.size).random()
                        val tmp = newDeck[0]
                        newDeck[0] = newDeck[swapIdx]
                        newDeck[swapIdx] = tmp
                    }
                    shuffleOrder.clear()
                    shuffleOrder.addAll(newDeck)
                    shufflePosition = 0
                    val nextIndex = shuffleOrder[0]
                    _currentIndex.value = nextIndex
                    playSong(queue[nextIndex])
                } else {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                }
            }
            return
        }

        val nextIndex = _currentIndex.value + 1
        if (nextIndex < queue.size) {
            _currentIndex.value = nextIndex
            playSong(queue[nextIndex])
        } else if (_repeatMode.value == RepeatMode.ALL) {
            _currentIndex.value = 0
            playSong(queue[0])
        } else {
            _isPlaying.value = false
            _currentPositionMs.value = 0L
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

        if (_isShuffle.value) {
            if (shufflePosition > 0 && shufflePosition < shuffleOrder.size) {
                shufflePosition--
                val prevIndex = shuffleOrder[shufflePosition]
                _currentIndex.value = prevIndex
                playSong(queue[prevIndex])
            } else {
                seekTo(0)
            }
            return
        }

        val prevIndex = _currentIndex.value - 1
        if (prevIndex >= 0) {
            _currentIndex.value = prevIndex
            playSong(queue[prevIndex])
        } else if (_repeatMode.value == RepeatMode.ALL) {
            val lastIndex = queue.size - 1
            _currentIndex.value = lastIndex
            playSong(queue[lastIndex])
        } else {
            seekTo(0)
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
            virtualizer?.release()
            loudnessEnhancer?.release()
            presetReverb?.release()

            equalizer = null
            bassBoost = null
            virtualizer = null
            loudnessEnhancer = null
            presetReverb = null

            if (audioSessionId != 0) {
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
            // 1. Bit-Perfect Pure Clean Bypass when BALANCED:
            // Completely disables AudioFx filters, avoiding inter-sample clipping and phase smearing that causes "rè"
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

            // Weights for each band from low frequency to high frequency [-1.0 to 1.0]
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

            // BassBoost hardware effect
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

            // 3D Spatial Virtualizer hardware effect
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

            // Dynamic Loudness Enhancer (limiter-protected punch)
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
                    EqualizerPreset.HIFI_MASTER -> 180 // +1.8dB
                    EqualizerPreset.TIKTOK_BOOST -> 300 // +3.0dB
                    EqualizerPreset.BASS_BOOST -> 200 // +2.0dB
                    EqualizerPreset.ELECTRONIC -> 250 // +2.5dB
                    EqualizerPreset.CUSTOM -> 250
                    else -> 0
                }
                enhancer.enabled = shouldEnable
                if (shouldEnable) {
                    enhancer.setTargetGain(gain)
                }
            }

            // Reverb hardware effect
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

    private fun handleSongCompletion() {
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
                seekTo(0)
                mediaPlayer?.start()
                _isPlaying.value = true
                startProgressTracker()
            }
            RepeatMode.ALL -> playNext()
            RepeatMode.OFF -> {
                if (_isShuffle.value) {
                    if (shufflePosition + 1 < shuffleOrder.size) {
                        playNext()
                    } else {
                        _isPlaying.value = false
                        _currentPositionMs.value = 0L
                    }
                } else {
                    if (_currentIndex.value < _playQueue.value.size - 1) {
                        playNext()
                    } else {
                        _isPlaying.value = false
                        _currentPositionMs.value = 0L
                    }
                }
            }
        }
    }

    private fun startWithCrossfade(player: MediaPlayer) {
        if (!_isPlaying.value || isManuallyPaused) {
            return
        }
        val crossfade = _crossfadeSeconds.value
        val targetVol = getBaseVolume()
        if (crossfade <= 0) {
            try { player.setVolume(targetVol, targetVol) } catch (e: Exception) {}
            player.start()
        } else {
            try { player.setVolume(0.0f, 0.0f) } catch (e: Exception) {}
            player.start()
            scope.launch {
                val steps = 15
                val delayMs = (crossfade * 1000L) / steps
                for (i in 1..steps) {
                    if (!isActive || mediaPlayer != player || isManuallyPaused || !_isPlaying.value) break
                    val vol = (i / steps.toFloat()) * targetVol
                    try { player.setVolume(vol, vol) } catch (e: Exception) { break }
                    delay(delayMs)
                }
                if (isActive && mediaPlayer == player && !isManuallyPaused && _isPlaying.value) {
                    try { player.setVolume(targetVol, targetVol) } catch (e: Exception) {}
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
                            val cur = player.currentPosition.toLong()
                            _currentPositionMs.value = cur
                            val dur = _durationMs.value
                            val crossfade = _crossfadeSeconds.value
                            if (crossfade > 0 && dur > 0) {
                                val remainingMs = dur - cur
                                val fadeMs = crossfade * 1000L
                                if (remainingMs in 0..fadeMs) {
                                    val targetVol = getBaseVolume()
                                    val fadeVol = ((remainingMs.toFloat() / fadeMs) * targetVol).coerceIn(0.05f, targetVol)
                                    try { player.setVolume(fadeVol, fadeVol) } catch (e: Exception) {}
                                }
                            }
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
            virtualizer?.release()
            loudnessEnhancer?.release()
            presetReverb?.release()
        } catch (e: Exception) {}
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        presetReverb = null

        val player = mediaPlayer
        mediaPlayer = null
        player?.let {
            try {
                it.setOnCompletionListener(null)
                it.setOnErrorListener(null)
                it.setOnPreparedListener(null)
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            } catch (e: Exception) {
                // ignored
            }
        }
    }

    fun cleanup() {
        abandonAudioFocus()
        sleepTimerJob?.cancel()
        stopAfterCurrentSong = false
        _isStopAfterSong.value = false
        _sleepTimerMinutesLeft.value = null
        releasePlayer()
        MusicPlaybackService.stop(context)
    }
}
