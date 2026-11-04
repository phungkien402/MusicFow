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

    private val _activeDownloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadTask>> = _activeDownloads.asStateFlow()

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
        // Initialize preset songs on first run
        viewModelScope.launch {
            repository.initializePresetTracksIfEmpty()
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
    }

    fun dismissDuplicateDialog() {
        _duplicateSong.value = null
        _pendingDuplicateMetadata = null
    }

    fun continueDownloadAfterDuplicate() {
        val meta = _pendingDuplicateMetadata
        _duplicateSong.value = null
        _pendingDuplicateMetadata = null
        if (meta != null) {
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

        val taskId = UUID.randomUUID().toString()
        val newTask = DownloadTask(
            id = taskId,
            title = finalMetadata.title,
            artist = finalMetadata.artist,
            url = finalMetadata.originalUrl,
            source = finalMetadata.source,
            quality = quality,
            targetPlaylistId = targetPlaylistId,
            targetPlaylistName = targetPlaylist?.name,
            progressPercent = 0,
            status = DownloadStatus.DOWNLOADING
        )

        _activeDownloads.value = listOf(newTask) + _activeDownloads.value
        _showQualityDialog.value = false
        _analyzedMetadata.value = null
        _inputUrl.value = ""

        viewModelScope.launch {
            val downloadResult = linkExtractor.downloadAudio(
                metadata = finalMetadata,
                quality = quality
            ) { percent, downloaded, total, speed ->
                updateDownloadTask(taskId) { current ->
                    current.copy(
                        progressPercent = percent,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        downloadSpeedText = speed,
                        status = DownloadStatus.DOWNLOADING
                    )
                }
            }

            downloadResult.onSuccess { downloadedSong ->
                val savedId = repository.saveDownloadedSong(downloadedSong, targetPlaylistId)
                updateDownloadTask(taskId) { current ->
                    current.copy(
                        progressPercent = 100,
                        status = DownloadStatus.COMPLETED,
                        downloadSpeedText = "Đã lưu vào bộ nhớ",
                        completedSongId = savedId
                    )
                }
                _successMessage.value = "Tải thành công: ${downloadedSong.title}"
            }.onFailure { err ->
                updateDownloadTask(taskId) { current ->
                    current.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = err.localizedMessage ?: "Tải bài hát thất bại"
                    )
                }
                _errorMessage.value = "Lỗi khi tải bài hát: ${err.message}"
            }
        }
    }

    private fun updateDownloadTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _activeDownloads.value = _activeDownloads.value.map {
            if (it.id == taskId) transform(it) else it
        }
    }

    fun removeDownloadTask(taskId: String) {
        _activeDownloads.value = _activeDownloads.value.filter { it.id != taskId }
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

    override fun onCleared() {
        super.onCleared()
        playerManager.cleanup()
    }
}
