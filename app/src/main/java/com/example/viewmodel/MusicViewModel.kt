package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // Filtered song list for Library Screen
    val filteredSongs: StateFlow<List<Song>> = combine(
        allSongs,
        _libraryFilter,
        _searchQuery
    ) { songs, filter, query ->
        val list = when (filter) {
            LibraryFilter.ALL -> songs
            LibraryFilter.DOWNLOADED -> songs.filter { it.isDownloaded }
            LibraryFilter.FAVORITES -> songs.filter { it.isFavorite }
            LibraryFilter.DEVICE -> songs.filter { it.sourceType == SourceType.LOCAL_DEVICE }
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

    private val _songToAddToPlaylist = MutableStateFlow<Song?>(null)
    val songToAddToPlaylist: StateFlow<Song?> = _songToAddToPlaylist.asStateFlow()

    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog: StateFlow<Boolean> = _showCreatePlaylistDialog.asStateFlow()

    private val _selectedPlaylistForDetail = MutableStateFlow<Playlist?>(null)
    val selectedPlaylistForDetail: StateFlow<Playlist?> = _selectedPlaylistForDetail.asStateFlow()

    private val _playlistDetailSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistDetailSongs: StateFlow<List<Song>> = _playlistDetailSongs.asStateFlow()

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

            val result = linkExtractor.analyzeUrl(url)
            _isAnalyzing.value = false

            result.onSuccess { metadata ->
                _analyzedMetadata.value = metadata
                _showQualityDialog.value = true
            }.onFailure { e ->
                _errorMessage.value = "Không thể phân tích liên kết: ${e.localizedMessage ?: "Vui lòng kiểm tra lại URL"}"
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
            repository.getSongsForPlaylist(playlist.id).collect { songs ->
                if (songs.isNotEmpty()) {
                    if (shuffle) {
                        playerManager.toggleShuffle()
                        val randomIdx = songs.indices.random()
                        playerManager.setQueue(songs, randomIdx)
                    } else {
                        playerManager.setQueue(songs, 0)
                    }
                } else {
                    _errorMessage.value = "Playlist '${playlist.name}' hiện chưa có bài hát"
                }
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
    fun setSleepTimer(minutes: Int?) = playerManager.setSleepTimer(minutes)

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            repository.toggleFavorite(song.id)
        }
    }

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            repository.deleteSong(song)
            _successMessage.value = "Đã xoá '${song.title}'"
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

    private fun loadPlaylistDetails(playlistId: Long) {
        viewModelScope.launch {
            repository.getSongsForPlaylist(playlistId).collect { songs ->
                _playlistDetailSongs.value = songs
            }
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
