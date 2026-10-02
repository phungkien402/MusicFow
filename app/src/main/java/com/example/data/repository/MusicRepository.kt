package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.downloader.SampleAudioGenerator
import com.example.data.local.MusicDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistSongCrossRef
import com.example.data.local.SongEntity
import com.example.data.model.AudioFormat
import com.example.data.model.Playlist
import com.example.data.model.Song
import com.example.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ImportResult(
    val importedSongs: List<Song>,
    val skippedDuplicates: Int
)

class MusicRepository(
    private val context: Context,
    private val dao: MusicDao
) {

    val allSongs: Flow<List<Song>> = dao.getAllSongs().map { list -> list.map { it.toSong() } }
    val downloadedSongs: Flow<List<Song>> = dao.getDownloadedSongs().map { list -> list.map { it.toSong() } }
    val favoriteSongs: Flow<List<Song>> = dao.getFavoriteSongs().map { list -> list.map { it.toSong() } }

    val playlists: Flow<List<Playlist>> = dao.getAllPlaylists().map { list ->
        list.map { entity ->
            val count = dao.getSongCountForPlaylistSync(entity.id)
            entity.toPlaylist(count)
        }
    }

    suspend fun initializePresetTracksIfEmpty() = withContext(Dispatchers.IO) {
        val existingCount = dao.getSongCount()
        if (existingCount > 0) return@withContext

        // 1. Create Default Playlists
        val playlist1Id = dao.insertPlaylist(
            PlaylistEntity(
                name = "TikTok Trending Beats",
                description = "Giai điệu thịnh hành & xu hướng âm nhạc",
                coverColorHex = "#FE2C55",
                createdTimestamp = System.currentTimeMillis()
            )
        )

        val playlist2Id = dao.insertPlaylist(
            PlaylistEntity(
                name = "Lofi Chill & Thư Giãn",
                description = "Nhạc thư giãn học tập và làm việc",
                coverColorHex = "#00F5FF",
                createdTimestamp = System.currentTimeMillis() + 1
            )
        )

        val playlist3Id = dao.insertPlaylist(
            PlaylistEntity(
                name = "Acoustic & Coffee",
                description = "Âm hưởng nhẹ nhàng, mộc mạc",
                coverColorHex = "#F59E0B",
                createdTimestamp = System.currentTimeMillis() + 2
            )
        )

        // 2. Generate playable offline audio files
        val track1File = SampleAudioGenerator.createSampleWav(
            context = context,
            fileName = "midnight_lofi_vibes.wav",
            durationSeconds = 24,
            chordFrequencies = listOf(261.63, 329.63, 392.00, 493.88),
            tempoBpm = 85
        )

        val track2File = SampleAudioGenerator.createSampleWav(
            context = context,
            fileName = "tiktok_viral_synth.wav",
            durationSeconds = 20,
            chordFrequencies = listOf(440.00, 523.25, 659.25, 587.33),
            tempoBpm = 125
        )

        val track3File = SampleAudioGenerator.createSampleWav(
            context = context,
            fileName = "cyber_bass_groove.wav",
            durationSeconds = 22,
            chordFrequencies = listOf(220.00, 277.18, 329.63, 415.30),
            tempoBpm = 110
        )

        val track4File = SampleAudioGenerator.createSampleWav(
            context = context,
            fileName = "acoustic_sunset_flac.wav",
            durationSeconds = 26,
            chordFrequencies = listOf(349.23, 440.00, 523.25, 698.46),
            tempoBpm = 95
        )

        val songs = listOf(
            SongEntity(
                title = "Midnight Lofi City",
                artist = "SoundFlow Chillhop",
                album = "Lofi Odyssey",
                durationMs = 24000L,
                filePath = track1File.absolutePath,
                sourceUrl = null,
                format = AudioFormat.FLAC.extension,
                bitrate = "Lossless 96kHz",
                fileSizeBytes = track1File.length(),
                isDownloaded = true,
                isFavorite = true,
                sourceType = SourceType.OFFLINE.name,
                thumbnailUrl = null,
                addedTimestamp = System.currentTimeMillis() - 100000,
                playCount = 12
            ),
            SongEntity(
                title = "TikTok Viral Energy",
                artist = "DJ Trendsetter",
                album = "TikTok Bass Drops",
                durationMs = 20000L,
                filePath = track2File.absolutePath,
                sourceUrl = "https://www.tiktok.com/@soundflow/video/sample",
                format = AudioFormat.MP3.extension,
                bitrate = "320 kbps",
                fileSizeBytes = track2File.length(),
                isDownloaded = true,
                isFavorite = true,
                sourceType = SourceType.TIKTOK.name,
                thumbnailUrl = null,
                addedTimestamp = System.currentTimeMillis() - 80000,
                playCount = 28
            ),
            SongEntity(
                title = "Cyber Bass Horizon",
                artist = "Neon Odyssey",
                album = "Future Wave 2026",
                durationMs = 22000L,
                filePath = track3File.absolutePath,
                sourceUrl = "https://youtu.be/sample_cyber_horizon",
                format = AudioFormat.M4A.extension,
                bitrate = "256 kbps",
                fileSizeBytes = track3File.length(),
                isDownloaded = true,
                isFavorite = false,
                sourceType = SourceType.YOUTUBE.name,
                thumbnailUrl = null,
                addedTimestamp = System.currentTimeMillis() - 50000,
                playCount = 7
            ),
            SongEntity(
                title = "Acoustic Golden Hour",
                artist = "Aurora Strings",
                album = "Unplugged Sessions",
                durationMs = 26000L,
                filePath = track4File.absolutePath,
                sourceUrl = null,
                format = AudioFormat.WAV.extension,
                bitrate = "1411 kbps",
                fileSizeBytes = track4File.length(),
                isDownloaded = true,
                isFavorite = false,
                sourceType = SourceType.OFFLINE.name,
                thumbnailUrl = null,
                addedTimestamp = System.currentTimeMillis() - 20000,
                playCount = 15
            )
        )

        dao.insertSongs(songs)

        // Attach songs to playlists
        val savedSongs = dao.getAllSongs().first()
        if (savedSongs.size >= 4) {
            dao.addSongToPlaylist(PlaylistSongCrossRef(playlist2Id, savedSongs[0].id))
            dao.addSongToPlaylist(PlaylistSongCrossRef(playlist1Id, savedSongs[1].id))
            dao.addSongToPlaylist(PlaylistSongCrossRef(playlist1Id, savedSongs[2].id))
            dao.addSongToPlaylist(PlaylistSongCrossRef(playlist3Id, savedSongs[3].id))
        }
    }

    suspend fun saveDownloadedSong(song: Song, targetPlaylistId: Long?): Long = withContext(Dispatchers.IO) {
        val songId = dao.insertSong(SongEntity.fromSong(song))
        if (targetPlaylistId != null && targetPlaylistId > 0) {
            dao.addSongToPlaylist(PlaylistSongCrossRef(targetPlaylistId, songId))
        }
        songId
    }

    suspend fun toggleFavorite(songId: Long) = withContext(Dispatchers.IO) {
        dao.toggleFavorite(songId)
    }

    suspend fun incrementPlayCount(songId: Long) = withContext(Dispatchers.IO) {
        dao.incrementPlayCount(songId)
    }

    suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        dao.deleteSong(song.id)
        val file = File(song.filePath)
        if (file.exists() && file.isFile) {
            file.delete()
        }
    }

    fun getSongsForPlaylist(playlistId: Long): Flow<List<Song>> {
        return dao.getSongsForPlaylist(playlistId).map { list -> list.map { it.toSong() } }
    }

    suspend fun createPlaylist(name: String, description: String, colorHex: String): Long = withContext(Dispatchers.IO) {
        dao.insertPlaylist(
            PlaylistEntity(
                name = name,
                description = description,
                coverColorHex = colorHex,
                createdTimestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        dao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        dao.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        dao.deletePlaylist(playlistId)
    }

    /**
     * Scans MediaStore for local audio files on the device
     */
    suspend fun scanDeviceAudioFiles(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.SIZE
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val title = it.getString(titleCol) ?: "Không tên"
                    val artist = it.getString(artistCol) ?: "Nghệ sĩ chưa rõ"
                    val album = it.getString(albumCol) ?: "Bộ nhớ thiết bị"
                    val duration = it.getLong(durCol)
                    val path = it.getString(dataCol) ?: ""
                    val size = it.getLong(sizeCol)

                    if (path.isNotBlank()) {
                        val existing = dao.getSongByPath(path)
                        val duplicate = dao.findDuplicateSong(title.trim(), artist.trim())
                        if (existing == null && duplicate == null) {
                            val ext = path.substringAfterLast(".", "mp3")
                            val format = AudioFormat.fromExtension(ext)
                            dao.insertSong(
                                SongEntity(
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    durationMs = duration,
                                    filePath = path,
                                    sourceUrl = null,
                                    format = format.extension,
                                    bitrate = if (format.isLossless) "Lossless" else "320 kbps",
                                    fileSizeBytes = size,
                                    isDownloaded = true,
                                    isFavorite = false,
                                    sourceType = SourceType.LOCAL_DEVICE.name,
                                    thumbnailUrl = null,
                                    addedTimestamp = System.currentTimeMillis()
                                )
                            )
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error scanning device audio", e)
        }
        count
    }

    /**
     * Imports audio files selected by user from Document Picker (Google Drive, Files, Downloads)
     * Automatically filters out duplicates by Source URL, Title + Artist, and File Signature.
     */
    suspend fun importAudioFilesFromUris(
        uris: List<Uri>,
        targetPlaylistId: Long? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<Song>()
        var skippedDuplicates = 0
        val importedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "imported_tracks").apply { mkdirs() }
        val allExistingSongs = dao.getAllSongs().first()

        for (uri in uris) {
            try {
                val uriString = uri.toString()

                // Check 1: Exact sourceUrl match
                val existingBySource = dao.getSongBySourceUrl(uriString)
                if (existingBySource != null) {
                    skippedDuplicates++
                    if (targetPlaylistId != null && targetPlaylistId > 0) {
                        dao.addSongToPlaylist(PlaylistSongCrossRef(targetPlaylistId, existingBySource.id))
                    }
                    continue
                }

                // 1. Get filename & size from content resolver
                var displayName = "Audio_${System.currentTimeMillis()}.mp3"
                var sizeBytes = 0L

                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) {
                                cursor.getString(nameIndex)?.let { if (it.isNotBlank()) displayName = it }
                            }
                            if (sizeIndex != -1) {
                                sizeBytes = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MusicRepository", "Could not query content resolver for displayName", e)
                }

                // 2. Extract metadata via MediaMetadataRetriever
                var title = displayName.substringBeforeLast(".")
                var artist = "Nghệ sĩ chưa rõ"
                var album = "Đã tải lên từ máy"
                var durationMs = 0L
                var bitrateStr = "320 kbps"
                var thumbnailPath: String? = null

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                        if (it.isNotBlank()) title = it
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
                        if (it.isNotBlank()) artist = it
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                        if (it.isNotBlank()) album = it
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                        if (it > 0) durationMs = it
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let {
                        val kbps = it.toIntOrNull()?.div(1000)
                        if (kbps != null && kbps > 0) bitrateStr = "$kbps kbps"
                    }
                } catch (e: Exception) {
                    Log.w("MusicRepository", "Retriever metadata extraction note: ${e.message}")
                }

                val safeFileName = displayName.replace(Regex("[^a-zA-Z0-9.-]"), "_")

                // Check 2: Deduplication by Title + Artist + Duration / File Size / Display Name
                val isDuplicate = allExistingSongs.any { existing ->
                    // Same Title and Artist (case-insensitive)
                    (existing.title.trim().equals(title.trim(), ignoreCase = true) &&
                     existing.artist.trim().equals(artist.trim(), ignoreCase = true) &&
                     (durationMs == 0L || existing.durationMs == 0L || Math.abs(existing.durationMs - durationMs) < 3000L)) ||
                    // Or exact same file display name & size
                    (existing.filePath.substringAfterLast("/").contains(safeFileName) && sizeBytes > 0 && existing.fileSizeBytes == sizeBytes) ||
                    // Or same title & exact file size
                    (existing.title.trim().equals(title.trim(), ignoreCase = true) && sizeBytes > 0 && existing.fileSizeBytes == sizeBytes)
                }

                if (isDuplicate) {
                    skippedDuplicates++
                    // If target playlist is specified, ensure the existing song is attached
                    val existingSong = allExistingSongs.find { existing ->
                        existing.title.trim().equals(title.trim(), ignoreCase = true) &&
                        existing.artist.trim().equals(artist.trim(), ignoreCase = true)
                    }
                    if (existingSong != null && targetPlaylistId != null && targetPlaylistId > 0) {
                        dao.addSongToPlaylist(PlaylistSongCrossRef(targetPlaylistId, existingSong.id))
                    }
                    try { retriever.release() } catch (ignored: Exception) {}
                    continue
                }

                // Extract embedded album artwork (ID3 APIC picture)
                try {
                    val art = retriever.embeddedPicture
                    if (art != null && art.isNotEmpty()) {
                        val artFile = File(importedDir, "cover_${System.currentTimeMillis()}_${title.take(15).replace(Regex("[^a-zA-Z0-9]"), "_")}.jpg")
                        FileOutputStream(artFile).use { it.write(art) }
                        if (artFile.exists() && artFile.length() > 0) {
                            thumbnailPath = artFile.absolutePath
                        }
                    }
                } catch (ignored: Exception) {} finally {
                    try { retriever.release() } catch (ignored: Exception) {}
                }

                // 3. Copy file to internal app storage for reliable offline playback
                val extension = displayName.substringAfterLast(".", "mp3")
                val targetFile = File(importedDir, "uploaded_${System.currentTimeMillis()}_$safeFileName")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val format = AudioFormat.fromExtension(extension)
                val finalSize = if (targetFile.exists() && targetFile.length() > 0) targetFile.length() else sizeBytes

                val songEntity = SongEntity(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = if (durationMs > 0) durationMs else 180000L,
                    filePath = targetFile.absolutePath,
                    sourceUrl = uriString,
                    format = format.extension,
                    bitrate = if (format.isLossless) "Lossless" else bitrateStr,
                    fileSizeBytes = finalSize,
                    isDownloaded = true,
                    isFavorite = false,
                    sourceType = SourceType.LOCAL_DEVICE.name,
                    thumbnailUrl = thumbnailPath,
                    addedTimestamp = System.currentTimeMillis()
                )

                val songId = dao.insertSong(songEntity)
                if (targetPlaylistId != null && targetPlaylistId > 0) {
                    dao.addSongToPlaylist(PlaylistSongCrossRef(targetPlaylistId, songId))
                }

                val savedSong = songEntity.copy(id = songId).toSong()
                results.add(savedSong)
            } catch (e: Exception) {
                Log.e("MusicRepository", "Error importing audio file from uri: $uri", e)
            }
        }
        ImportResult(results, skippedDuplicates)
    }
}
