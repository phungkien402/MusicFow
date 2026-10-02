package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.AudioFormat
import com.example.data.model.Song
import com.example.data.model.SourceType

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val filePath: String,
    val sourceUrl: String?,
    val format: String,
    val bitrate: String,
    val fileSizeBytes: Long,
    val isDownloaded: Boolean,
    val isFavorite: Boolean,
    val sourceType: String,
    val thumbnailUrl: String?,
    val addedTimestamp: Long,
    val playCount: Int = 0
) {
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        filePath = filePath,
        sourceUrl = sourceUrl,
        format = AudioFormat.fromExtension(format),
        bitrate = bitrate,
        fileSizeBytes = fileSizeBytes,
        isDownloaded = isDownloaded,
        isFavorite = isFavorite,
        sourceType = try { SourceType.valueOf(sourceType) } catch (e: Exception) { SourceType.OFFLINE },
        thumbnailUrl = thumbnailUrl,
        addedTimestamp = addedTimestamp,
        playCount = playCount
    )

    companion object {
        fun fromSong(song: Song): SongEntity = SongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            filePath = song.filePath,
            sourceUrl = song.sourceUrl,
            format = song.format.extension,
            bitrate = song.bitrate,
            fileSizeBytes = song.fileSizeBytes,
            isDownloaded = song.isDownloaded,
            isFavorite = song.isFavorite,
            sourceType = song.sourceType.name,
            thumbnailUrl = song.thumbnailUrl,
            addedTimestamp = song.addedTimestamp,
            playCount = song.playCount
        )
    }
}
