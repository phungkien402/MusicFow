package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ui.theme.AppTheme
import com.example.data.downloader.AudioLinkExtractor
import com.example.data.downloader.ParsedLinkMetadata
import com.example.data.local.MusicDatabase
import com.example.data.model.AudioQuality
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.data.model.SourceType
import com.example.data.player.AudioPlayerManager
import com.example.data.player.EqualizerPreset
import com.example.data.player.PlaybackStateStore
import com.example.data.player.RepeatMode
import com.example.data.player.ReverbMode
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.downloader.DownloadWorker
import com.example.data.downloader.SourceKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.UUID

enum class AppTab(val title: String) {
    LIBRARY("Thư viện"),
    DOWNLOADER("Tải nhạc Link"),
    PLAYLISTS("Playlists")
}

enum class LibraryFilter(val label: String) {
    ALL("Tất cả"),
    DOWNLOADED("Đã tải"),
    FAVORITES("Yêu thích"),
    DEVICE("Bộ nhớ máy")
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val database = MusicDatabase.getInstance(application)
    private val repository = MusicRepository(application, database.musicDao())
    val playerManager = AudioPlayerManager(application)
    private val linkExtractor = AudioLinkExtractor(application)

    // Current Tab
    private val _currentTab = MutableStateFlow(AppTab.LIBRARY)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    // Library filter & search
    private val _libraryFilter = MutableStateFlow(LibraryFilter.ALL)
    val libraryFilter: StateFlow<LibraryFilter> = _libraryFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Songs data flows
    val allSongs: StateFlow<List<Song>> = repository.allSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedSongs: StateFlow<List<Song>> = repository.downloadedSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteSongs: StateFlow<List<Song>> = repository.favoriteSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryFilterCounts: StateFlow<Map<LibraryFilter, Int>> = allSongs.combine(_libraryFilter) { songs, _ ->
        mapOf(
            LibraryFilter.ALL to songs.size,
            LibraryFilter.DOWNLOADED to songs.count { it.sourceType in listOf(SourceType.YOUTUBE, SourceType.TIKTOK, SourceType.DIRECT_URL) },
            LibraryFilter.FAVORITES to songs.count { it.isFavorite },
            LibraryFilter.DEVICE to songs.count { it.sourceType == SourceType.LOCAL_DEVICE || it.sourceType == SourceType.OFFLINE }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Filtered song list for Library Screen
    val filteredSongs: StateFlow<List<Song>> = combine(
        allSongs,
        _libraryFilter,
        _searchQuery
    ) { songs, filter, query ->
        val list = when (filter) {
            LibraryFilter.ALL -> songs
            LibraryFilter.DOWNLOADED -> songs.filter { it.sourceType in listOf(SourceType.YOUTUBE, SourceType.TIKTOK, SourceType.DIRECT_URL) }
            LibraryFilter.FAVORITES -> songs.filter { it.isFavorite }
            LibraryFilter.DEVICE -> songs.filter { it.sourceType == SourceType.LOCAL_DEVICE || it.sourceType == SourceType.OFFLINE }
        }

        if (query.isBlank()) {
            list
        } else {
            val q = query.trim().lowercase()
            list.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.format.displayName.lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Downloader states
    private val _inputUrl = MutableStateFlow("")
    val inputUrl: StateFlow<String> = _inputUrl.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analyzedMetadata = MutableStateFlow<ParsedLinkMetadata?>(null)
    val analyzedMetadata: StateFlow<ParsedLinkMetadata?> = _analyzedMetadata.asStateFlow()

    private val _selectedQuality = MutableStateFlow(AudioQuality.DEFAULT)
    val selectedQuality: StateFlow<AudioQuality> = _selectedQuality.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<Long?>(null)
    val selectedPlaylistId: StateFlow<Long?> = _selectedPlaylistId.asStateFlow()

    private data class LocalTaskInfo(
        val metadata: ParsedLinkMetadata,
        val quality: AudioQuality,
        val targetPlaylistId: Long?,
        val targetPlaylistName: String?
    )

    private val workManager by lazy { WorkManager.getInstance(getApplication()) }
    private val playbackStateStore = PlaybackStateStore(application)
    private val hiddenTaskIds = MutableStateFlow<Set<String>>(emptySet())
    private val localTaskMap = ConcurrentHashMap<String, LocalTaskInfo>()
    private val localTaskMapByUrl = ConcurrentHashMap<String, LocalTaskInfo>()
    private val notifiedSuccessIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val sessionObservedActiveWorkIds = Collections.synchronizedSet(mutableSetOf<String>())
    private var _forceNextDownload = false

    val activeDownloads: StateFlow<List<DownloadTask>> = combine(
        workManager.getWorkInfosByTagFlow(DownloadWorker.TAG),
        hiddenTaskIds
    ) { workInfos, hidden ->
        workInfos
            .filter { !hidden.contains(it.id.toString()) }
            .map { workInfo ->
                val taskId = workInfo.id.toString()
                val urlFromTag = workInfo.tags.firstOrNull { it.startsWith("url:") }?.removePrefix("url:")
                val uniqueKey = urlFromTag?.let { SourceKey.of(it) } ?: ""
                val info = localTaskMap[taskId] ?: localTaskMapByUrl[uniqueKey]

                val title = info?.metadata?.title
                    ?: workInfo.tags.firstOrNull { it.startsWith("title:") }?.removePrefix("title:")
                    ?: "Bài hát đang tải"
                val artist = info?.metadata?.artist
                    ?: workInfo.tags.firstOrNull { it.startsWith("artist:") }?.removePrefix("artist:")
                    ?: ""
                val url = info?.metadata?.originalUrl
                    ?: urlFromTag
                    ?: ""
                val source = info?.metadata?.source
                    ?: workInfo.tags.firstOrNull { it.startsWith("source:") }?.removePrefix("source:")?.let {
                        try { SourceType.valueOf(it) } catch (_: Exception) { null }
                    } ?: SourceType.DIRECT_URL
                val quality = info?.quality
                    ?: workInfo.tags.firstOrNull { it.startsWith("quality:") }?.removePrefix("quality:")?.let {
                        AudioQuality.byId(it)
                    } ?: AudioQuality.DEFAULT
                val targetPlaylistId = info?.targetPlaylistId
                    ?: workInfo.tags.firstOrNull { it.startsWith("playlistId:") }?.removePrefix("playlistId:")?.toLongOrNull()
                val targetPlaylistName = info?.targetPlaylistName
                    ?: workInfo.tags.firstOrNull { it.startsWith("playlistName:") }?.removePrefix("playlistName:")

                val progress = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                val speed = workInfo.progress.getString(DownloadWorker.KEY_SPEED) ?: ""

                val status: DownloadStatus
                val progressPercent: Int
                val downloadSpeedText: String
                var completedSongId: Long? = null
                var errorMessage: String? = null

                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED -> {
                        status = DownloadStatus.DOWNLOADING
                        progressPercent = 0
                        downloadSpeedText = "Đang chờ tải..."
                    }
                    WorkInfo.State.RUNNING -> {
                        status = DownloadStatus.DOWNLOADING
                        progressPercent = progress
                        downloadSpeedText = speed.ifBlank { "Đang tải..." }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        status = DownloadStatus.COMPLETED
                        progressPercent = 100
                        downloadSpeedText = "Đã lưu vào bộ nhớ"
                        completedSongId = workInfo.outputData.getLong(DownloadWorker.KEY_SONG_ID, -1L).takeIf { it > 0 }
                    }
                    WorkInfo.State.FAILED -> {
                        status = DownloadStatus.FAILED
                        progressPercent = progress
                        downloadSpeedText = ""
                        errorMessage = workInfo.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Tải bài hát thất bại"
                    }
                    WorkInfo.State.CANCELLED -> {
                        status = DownloadStatus.FAILED
                        progressPercent = progress
                        downloadSpeedText = ""
                        errorMessage = "Đã huỷ tải xuống"
                    }
                    WorkInfo.State.BLOCKED -> {
                        status = DownloadStatus.DOWNLOADING
                        progressPercent = 0
                        downloadSpeedText = "Đang chờ điều kiện..."
                    }
                }

                DownloadTask(
                    id = taskId,
                    title = title,
                    artist = artist,
                    url = url,
                    source = source,
                    quality = quality,
                    targetPlaylistId = targetPlaylistId,
                    targetPlaylistName = targetPlaylistName,
                    progressPercent = progressPercent,
                    downloadSpeedText = downloadSpeedText,
                    status = status,
                    errorMessage = errorMessage,
                    completedSongId = completedSongId
                )
            }
            .sortedWith(
                compareBy<DownloadTask> {
                    when (it.status) {
                        DownloadStatus.DOWNLOADING -> 0
                        DownloadStatus.FAILED -> 1
                        DownloadStatus.COMPLETED -> 2
                        else -> 3
                    }
                }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    // Dialog & Sheet states
    private val _showNowPlayingSheet = MutableStateFlow(false)
    val showNowPlayingSheet: StateFlow<Boolean> = _showNowPlayingSheet.asStateFlow()

    private val _showQualityDialog = MutableStateFlow(false)
    val showQualityDialog: StateFlow<Boolean> = _showQualityDialog.asStateFlow()

    private var _pendingDuplicateMetadata: ParsedLinkMetadata? = null
    private val _duplicateSong = MutableStateFlow<Song?>(null)
    val duplicateSong: StateFlow<Song?> = _duplicateSong.asStateFlow()

    private val _songToAddToPlaylist = MutableStateFlow<Song?>(null)
    val songToAddToPlaylist: StateFlow<Song?> = _songToAddToPlaylist.asStateFlow()

    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog: StateFlow<Boolean> = _showCreatePlaylistDialog.asStateFlow()

    private val _selectedPlaylistForDetail = MutableStateFlow<Playlist?>(null)
    val selectedPlaylistForDetail: StateFlow<Playlist?> = _selectedPlaylistForDetail.asStateFlow()

    private val _playlistDetailSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistDetailSongs: StateFlow<List<Song>> = _playlistDetailSongs.asStateFlow()

    private val _songToEdit = MutableStateFlow<Song?>(null)
    val songToEdit: StateFlow<Song?> = _songToEdit.asStateFlow()

    private val _showAddMultipleSongsDialog = MutableStateFlow(false)
    val showAddMultipleSongsDialog: StateFlow<Boolean> = _showAddMultipleSongsDialog.asStateFlow()

    init {
        // Initialize preset songs on first run and restore saved playback state
        viewModelScope.launch {
            repository.initializePresetTracksIfEmpty()
            val library = repository.allSongs.first()
            val saved = playbackStateStore.load()
            if (saved != null) {
                val resolved = saved.resolve(library)
                if (resolved != null) {
                    val (queue, idx) = resolved
                    playerManager.restoreQueue(queue, idx, saved.positionMs)
                    playerManager.setShuffleEnabled(saved.shuffle)
                    playerManager.setRepeatMode(saved.repeatMode)
                }
            }
        }

        playerManager.onSongFinishedListener = { finishedSong ->
            viewModelScope.launch {
                repository.incrementPlayCount(finishedSong.id)
            }
        }

        viewModelScope.launch {
            playerManager.playbackError.collect { error ->
                if (error != null) {
                    _errorMessage.value = error
                }
            }
        }

        // Save playback snapshot on pause, song change, every 10s while playing
        viewModelScope.launch {
            playerManager.currentSong.collect {
                savePlaybackSnapshot()
            }
        }

        viewModelScope.launch {
            playerManager.isPlaying.collect { playing ->
                if (!playing) {
                    savePlaybackSnapshot()
                }
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(10_000L)
                if (playerManager.isPlaying.value) {
                    savePlaybackSnapshot()
                }
            }
        }

        // Collect download work updates without leaking side-effects in map,
        // notifying success only for works observed in ENQUEUED/RUNNING during this session.
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(DownloadWorker.TAG).collect { workInfos ->
                for (workInfo in workInfos) {
                    val taskId = workInfo.id.toString()
                    if (workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING) {
                        sessionObservedActiveWorkIds.add(taskId)
                    } else if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                        if (sessionObservedActiveWorkIds.contains(taskId) && notifiedSuccessIds.add(taskId)) {
                            val urlFromTag = workInfo.tags.firstOrNull { it.startsWith("url:") }?.removePrefix("url:")
                            val uniqueKey = urlFromTag?.let { SourceKey.of(it) } ?: ""
                            val info = localTaskMap[taskId] ?: localTaskMapByUrl[uniqueKey]
                            val title = info?.metadata?.title
                                ?: workInfo.tags.firstOrNull { it.startsWith("title:") }?.removePrefix("title:")
                                ?: "Bài hát"
                            _successMessage.value = "Tải thành công: $title"
                        }
                    }
                }
            }
        }
    }

    fun setTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        _libraryFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setInputUrl(url: String) {
        _inputUrl.value = url
    }

    fun openNowPlayingSheet() {
        _showNowPlayingSheet.value = true
    }

    fun closeNowPlayingSheet() {
        _showNowPlayingSheet.value = false
    }

    fun openAddToPlaylist(song: Song) {
        _songToAddToPlaylist.value = song
    }

    fun closeAddToPlaylist() {
        _songToAddToPlaylist.value = null
    }

    fun openCreatePlaylistDialog() {
        _showCreatePlaylistDialog.value = true
    }

    fun closeCreatePlaylistDialog() {
        _showCreatePlaylistDialog.value = false
    }

    fun dismissQualityDialog() {
        _showQualityDialog.value = false
        _analyzedMetadata.value = null
        _forceNextDownload = false
    }

    fun dismissDuplicateDialog() {
        _duplicateSong.value = null
        _pendingDuplicateMetadata = null
        _forceNextDownload = false
    }

    fun continueDownloadAfterDuplicate() {
        val meta = _pendingDuplicateMetadata
        _duplicateSong.value = null
        _pendingDuplicateMetadata = null
        if (meta != null) {
            _forceNextDownload = true
            _analyzedMetadata.value = meta
            _showQualityDialog.value = true
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _successMessage.value = null
    }

    // Analyzing URL
    fun analyzeUrl() {
        val url = _inputUrl.value.trim()
        if (url.isEmpty()) {
            _errorMessage.value = "Vui lòng nhập hoặc dán link YouTube, TikTok hoặc link nhạc"
            return
        }

        viewModelScope.launch {
            _isAnalyzing.value = true
            _errorMessage.value = null

            val existing = repository.findExistingDownload(url)
            val result = linkExtractor.analyzeUrl(url)
            _isAnalyzing.value = false

            result.onSuccess { metadata ->
                if (existing != null) {
                    _pendingDuplicateMetadata = metadata
                    _duplicateSong.value = existing
                } else {
                    _analyzedMetadata.value = metadata
                    _showQualityDialog.value = true
                }
            }.onFailure { e ->
                if (existing != null) {
                    _pendingDuplicateMetadata = ParsedLinkMetadata(
                        originalUrl = url,
                        source = SourceType.DIRECT_URL,
                        title = existing.title,
                        artist = existing.artist,
                        thumbnailUrl = existing.thumbnailUrl,
                        estimatedDurationMs = existing.durationMs,
                        directStreamUrl = url
                    )
                    _duplicateSong.value = existing
                } else {
                    _errorMessage.value = "Không thể phân tích liên kết: ${e.localizedMessage ?: "Vui lòng kiểm tra lại URL"}"
                }
            }
        }
    }

    fun selectQuality(quality: AudioQuality) {
        _selectedQuality.value = quality
    }

    fun selectTargetPlaylist(playlistId: Long?) {
        _selectedPlaylistId.value = playlistId
    }

    // Start download from analyzed metadata
    fun confirmDownload(
        customTitle: String? = null,
        customArtist: String? = null
    ) {
        val meta = _analyzedMetadata.value ?: return
        val quality = _selectedQuality.value
        val targetPlaylistId = _selectedPlaylistId.value
        val targetPlaylist = playlists.value.find { it.id == targetPlaylistId }

        val finalMetadata = meta.copy(
            title = customTitle?.takeIf { it.isNotBlank() } ?: meta.title,
            artist = customArtist?.takeIf { it.isNotBlank() } ?: meta.artist
        )

        val isForce = _forceNextDownload
        _forceNextDownload = false

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = DownloadWorker.buildInput(finalMetadata, quality, targetPlaylistId, force = isForce)

        val workRequestBuilder = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .addTag("title:${finalMetadata.title}")
            .addTag("artist:${finalMetadata.artist}")
            .addTag("url:${finalMetadata.originalUrl}")
            .addTag("source:${finalMetadata.source.name}")
            .addTag("quality:${quality.id}")

        if (targetPlaylistId != null) {
            workRequestBuilder.addTag("playlistId:$targetPlaylistId")
        }
        if (!targetPlaylist?.name.isNullOrBlank()) {
            workRequestBuilder.addTag("playlistName:${targetPlaylist?.name}")
        }

        val workRequest = workRequestBuilder.setInputData(inputData).build()
        val taskId = workRequest.id.toString()

        val info = LocalTaskInfo(finalMetadata, quality, targetPlaylistId, targetPlaylist?.name)
        localTaskMap[taskId] = info
        val uniqueName = SourceKey.of(finalMetadata.originalUrl).ifBlank { UUID.randomUUID().toString() }
        localTaskMapByUrl[uniqueName] = info

        hiddenTaskIds.value = hiddenTaskIds.value - taskId
        sessionObservedActiveWorkIds.add(taskId)

        val policy = if (isForce) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(
            uniqueName,
            policy,
            workRequest
        )

        _showQualityDialog.value = false
        _analyzedMetadata.value = null
        _inputUrl.value = ""
    }

    fun retryDownload(taskId: String) {
        val task = activeDownloads.value.find { it.id == taskId }
        val localInfo = localTaskMap[taskId]
        val metadata = localInfo?.metadata ?: task?.let {
            ParsedLinkMetadata(
                originalUrl = it.url,
                source = it.source,
                title = it.title,
                artist = it.artist,
                thumbnailUrl = null,
                estimatedDurationMs = 0L,
                directStreamUrl = null
            )
        } ?: return
        val quality = localInfo?.quality ?: task?.quality ?: AudioQuality.DEFAULT
        val targetPlaylistId = localInfo?.targetPlaylistId ?: task?.targetPlaylistId
        val targetPlaylistName = localInfo?.targetPlaylistName ?: task?.targetPlaylistName

        try {
            workManager.cancelWorkById(UUID.fromString(taskId))
            workManager.pruneWork()
        } catch (_: Exception) {}

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = DownloadWorker.buildInput(metadata, quality, targetPlaylistId)

        val workRequestBuilder = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .addTag("title:${metadata.title}")
            .addTag("artist:${metadata.artist}")
            .addTag("url:${metadata.originalUrl}")
            .addTag("source:${metadata.source.name}")
            .addTag("quality:${quality.id}")

        if (targetPlaylistId != null) {
            workRequestBuilder.addTag("playlistId:$targetPlaylistId")
        }
        if (!targetPlaylistName.isNullOrBlank()) {
            workRequestBuilder.addTag("playlistName:$targetPlaylistName")
        }

        val workRequest = workRequestBuilder.setInputData(inputData).build()
        val newTaskId = workRequest.id.toString()

        val newLocalInfo = LocalTaskInfo(metadata, quality, targetPlaylistId, targetPlaylistName)
        localTaskMap[newTaskId] = newLocalInfo
        val uniqueName = SourceKey.of(metadata.originalUrl).ifBlank { UUID.randomUUID().toString() }
        localTaskMapByUrl[uniqueName] = newLocalInfo

        hiddenTaskIds.value = (hiddenTaskIds.value - newTaskId) + taskId

        workManager.enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun removeDownloadTask(taskId: String) {
        try {
            workManager.cancelWorkById(UUID.fromString(taskId))
            workManager.pruneWork()
        } catch (_: Exception) {}
        hiddenTaskIds.value = hiddenTaskIds.value + taskId
        localTaskMap.remove(taskId)
    }

    // Player controls
    fun playSong(song: Song, queue: List<Song> = filteredSongs.value) {
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playerManager.setQueue(queue, index)
    }

    fun playPlaylist(playlist: Playlist, shuffle: Boolean = false) {
        viewModelScope.launch {
            try {
                // Must use first() so changes to songs table (e.g. playCount) do not re-trigger playQueue
                val songs = repository.getSongsForPlaylist(playlist.id).first()
                if (songs.isNotEmpty()) {
                    if (shuffle) {
                        playerManager.setShuffleEnabled(true)
                        val randomIdx = songs.indices.random()
                        playerManager.setQueue(songs, randomIdx)
                    } else {
                        playerManager.setShuffleEnabled(false)
                        playerManager.setQueue(songs, 0)
                    }
                } else {
                    _errorMessage.value = "Playlist '${playlist.name}' hiện chưa có bài hát"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Không thể phát playlist: ${e.message}"
            }
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun playNext() = playerManager.playNext()
    fun playPrevious() = playerManager.playPrevious()
    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)
    fun skipForward() = playerManager.skipForward10()
    fun skipBackward() = playerManager.skipBackward10()
    fun toggleShuffle() = playerManager.toggleShuffle()
    fun cycleRepeatMode() = playerManager.cycleRepeatMode()
    fun setPlaybackSpeed(speed: Float) = playerManager.setPlaybackSpeed(speed)
    fun setEqualizerPreset(preset: EqualizerPreset) = playerManager.setEqualizerPreset(preset)
    val antiClippingEnabled: StateFlow<Boolean> = playerManager.antiClippingEnabled
    fun setAntiClippingEnabled(enabled: Boolean) = playerManager.setAntiClippingEnabled(enabled)

    val customVirtualizer: StateFlow<Float> = playerManager.customVirtualizer
    val customTrebleClarity: StateFlow<Float> = playerManager.customTrebleClarity
    val loudnessBoosterEnabled: StateFlow<Boolean> = playerManager.loudnessBoosterEnabled
    val reverbMode: StateFlow<ReverbMode> = playerManager.reverbMode

    fun setCustomVirtualizer(strength: Float) = playerManager.setCustomVirtualizer(strength)
    fun setCustomTrebleClarity(strength: Float) = playerManager.setCustomTrebleClarity(strength)
    fun setLoudnessBoosterEnabled(enabled: Boolean) = playerManager.setLoudnessBoosterEnabled(enabled)
    fun setReverbMode(mode: ReverbMode) = playerManager.setReverbMode(mode)
    fun resetCustomEqualizer() = playerManager.resetCustomEqualizer()
    fun setSleepTimer(minutes: Int?) {
        if (minutes == -1) {
            playerManager.setStopAfterCurrentSong(true)
            _successMessage.value = "Nhạc sẽ tự tắt khi bài hát hiện tại kết thúc"
        } else if (minutes == null || minutes <= 0) {
            playerManager.setSleepTimer(null)
            _successMessage.value = "Đã tắt hẹn giờ"
        } else {
            playerManager.setSleepTimer(minutes)
            _successMessage.value = "Đã hẹn giờ tắt nhạc sau $minutes phút"
        }
    }

    // Theme Management
    private val prefs = getApplication<Application>().getSharedPreferences("soundflow_prefs", Context.MODE_PRIVATE)
    private val _currentTheme = MutableStateFlow(
        try {
            AppTheme.valueOf(prefs.getString("app_theme", AppTheme.CYBERPUNK.name) ?: AppTheme.CYBERPUNK.name)
        } catch (e: Exception) {
            AppTheme.CYBERPUNK
        }
    )
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    fun setAppTheme(theme: AppTheme) {
        _currentTheme.value = theme
        prefs.edit().putString("app_theme", theme.name).apply()
        _successMessage.value = "Đã áp dụng chủ đề ${theme.displayName}"
    }

    // Lyrics
    private val _isGeneratingLyrics = MutableStateFlow(false)
    val isGeneratingLyrics: StateFlow<Boolean> = _isGeneratingLyrics.asStateFlow()

    fun autoSyncLyrics(song: Song) {
        viewModelScope.launch {
            _isGeneratingLyrics.value = true
            try {
                val durMs = if (song.durationMs > 0) song.durationMs else (playerManager.durationMs.value.takeIf { it > 0 } ?: 210000L)
                val syncedLrc = com.example.data.lyrics.LyricsSynchronizerService.getOrGenerateSyncedLyrics(
                    title = song.title,
                    artist = song.artist,
                    durationMs = durMs,
                    existingLyrics = song.lyrics
                )
                if (syncedLrc.isNotBlank()) {
                    repository.updateSongLyrics(song.id, syncedLrc)
                    if (playerManager.currentSong.value?.id == song.id) {
                        playerManager.updateCurrentSongLyrics(syncedLrc)
                    }
                    _successMessage.value = "✨ Đã tạo lời khớp timeline nhạc thành công!"
                } else {
                    _errorMessage.value = "Chưa tìm thấy lời tự động cho bản phối này. Bạn hãy bấm 'Sửa' để dán lời vào, SoundFlow sẽ tự động khớp timeline cho bạn!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Lỗi khi tạo lời đồng bộ: ${e.message}"
            } finally {
                _isGeneratingLyrics.value = false
            }
        }
    }

    fun updateSongLyrics(songId: Long, lyrics: String) {
        viewModelScope.launch {
            repository.updateSongLyrics(songId, lyrics.trim().ifBlank { null })
            playerManager.currentSong.value?.let { curr ->
                if (curr.id == songId) {
                    playerManager.updateCurrentSongLyrics(lyrics.trim().ifBlank { null })
                }
            }
            _successMessage.value = "Đã cập nhật lời bài hát"
        }
    }

    // Queue Management & Crossfade
    val playQueue = playerManager.playQueue
    val queueIndex = playerManager.currentIndex
    val crossfadeSeconds = playerManager.crossfadeSeconds

    fun playQueueSong(index: Int) = playerManager.playQueueSong(index)
    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)
    fun addToQueueNext(song: Song) {
        playerManager.addToQueueNext(song)
        _successMessage.value = "Đã thêm '${song.title}' vào phát tiếp theo"
    }
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playerManager.moveQueueItem(fromIndex, toIndex)
    fun clearQueueExceptCurrent() = playerManager.clearQueueExceptCurrent()
    fun setCrossfadeSeconds(seconds: Int) {
        playerManager.setCrossfadeSeconds(seconds)
        _successMessage.value = if (seconds > 0) "Crossfade: $seconds giây" else "Đã tắt Crossfade"
    }

    // Backup & Restore
    fun exportBackup(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val json = repository.exportBackupJson()
                onResult(json)
                _successMessage.value = "Đã xuất dữ liệu sao lưu thành công!"
            } catch (e: Exception) {
                _errorMessage.value = "Lỗi xuất sao lưu: ${e.message}"
                onResult(null)
            }
        }
    }

    fun importBackup(jsonString: String) {
        viewModelScope.launch {
            try {
                val res = repository.importBackupJson(jsonString)
                if (res.success) {
                    _successMessage.value = res.message
                } else {
                    _errorMessage.value = res.message
                }
            } catch (e: Exception) {
                _errorMessage.value = "Lỗi khôi phục sao lưu: ${e.message}"
            }
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            repository.toggleFavorite(song.id)
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            playerManager.onSongDeleted(song)
            repository.deleteSong(song)
            _successMessage.value = "Đã xoá '${song.title}'"
            if (playerManager.currentSong.value == null) {
                _showNowPlayingSheet.value = false
            }
        }
    }

    // Playlist actions
    fun createPlaylist(name: String, description: String, colorHex: String = "#00F5FF") {
        if (name.isBlank()) {
            _errorMessage.value = "Vui lòng nhập tên Playlist"
            return
        }
        viewModelScope.launch {
            repository.createPlaylist(name.trim(), description.trim(), colorHex)
            _showCreatePlaylistDialog.value = false
            _successMessage.value = "Đã tạo playlist '$name'"
        }
    }

    fun addSongToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId, song.id)
            _songToAddToPlaylist.value = null
            _successMessage.value = "Đã thêm '${song.title}' vào playlist"
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, song.id)
            // Refresh detail list
            loadPlaylistDetails(playlistId)
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist.id)
            if (_selectedPlaylistForDetail.value?.id == playlist.id) {
                _selectedPlaylistForDetail.value = null
            }
            _successMessage.value = "Đã xoá playlist '${playlist.name}'"
        }
    }

    fun selectPlaylistForDetail(playlist: Playlist?) {
        _selectedPlaylistForDetail.value = playlist
        if (playlist != null) {
            loadPlaylistDetails(playlist.id)
        }
    }

    private var playlistDetailsJob: Job? = null
    private fun loadPlaylistDetails(playlistId: Long) {
        playlistDetailsJob?.cancel()
        playlistDetailsJob = viewModelScope.launch {
            repository.getSongsForPlaylist(playlistId).collect { songs ->
                _playlistDetailSongs.value = songs
            }
        }
    }

    fun openEditSongDialog(song: Song) {
        _songToEdit.value = song
    }

    fun closeEditSongDialog() {
        _songToEdit.value = null
    }

    fun updateSongInfo(songId: Long, newTitle: String, newArtist: String) {
        if (newTitle.isBlank()) {
            _errorMessage.value = "Tên bài hát không được để trống"
            return
        }
        viewModelScope.launch {
            repository.updateSongTitleAndArtist(songId, newTitle.trim(), newArtist.trim())
            playerManager.currentSong.value?.let { current ->
                if (current.id == songId) {
                    playerManager.updateCurrentSongMetadata(newTitle.trim(), newArtist.trim())
                }
            }
            _selectedPlaylistForDetail.value?.let { playlist ->
                loadPlaylistDetails(playlist.id)
            }
            _songToEdit.value = null
            _successMessage.value = "Đã đổi tên bài hát thành '$newTitle'"
        }
    }

    fun openAddMultipleSongsDialog() {
        _showAddMultipleSongsDialog.value = true
    }

    fun closeAddMultipleSongsDialog() {
        _showAddMultipleSongsDialog.value = false
    }

    fun addMultipleSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        viewModelScope.launch {
            repository.addSongsToPlaylist(playlistId, songIds)
            loadPlaylistDetails(playlistId)
            _showAddMultipleSongsDialog.value = false
            _successMessage.value = "Đã thêm ${songIds.size} bài hát vào playlist!"
        }
    }

    fun scanDeviceAudio() {
        viewModelScope.launch {
            val scannedCount = repository.scanDeviceAudioFiles()
            if (scannedCount > 0) {
                _successMessage.value = "Tìm thấy $scannedCount bài hát mới trên thiết bị"
            } else {
                _successMessage.value = "Đã quét xong bộ nhớ. Tất cả file đã sẵn sàng."
            }
        }
    }

    /**
     * Imports custom selected audio files from Document Picker (Google Drive, Files, Downloads, etc.)
     */
    fun importAudioFiles(uris: List<Uri>, targetPlaylistId: Long? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val result = repository.importAudioFilesFromUris(uris, targetPlaylistId)
                val added = result.importedSongs.size
                val duplicates = result.skippedDuplicates

                if (added > 0 && duplicates > 0) {
                    _successMessage.value = "Đã nhập $added bài hát mới (đã chặn $duplicates bài trùng lặp)!"
                } else if (added > 0) {
                    _successMessage.value = "Đã tải lên thành công $added bài hát vào thư viện!"
                } else if (duplicates > 0) {
                    _successMessage.value = "Đã chặn $duplicates bài trùng lặp (tất cả file đã có trong thư viện)!"
                } else {
                    _errorMessage.value = "Không thể đọc các file âm thanh đã chọn."
                }
            } catch (e: Exception) {
                _errorMessage.value = "Lỗi khi nhập file nhạc: ${e.message}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    private fun savePlaybackSnapshot() {
        val snapshot = playerManager.snapshot()
        if (snapshot != null) {
            playbackStateStore.save(snapshot)
        }
    }

    override fun onCleared() {
        super.onCleared()
        savePlaybackSnapshot()
        playerManager.cleanup()
    }
}
