package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.Playlist

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String,
    val coverColorHex: String,
    val createdTimestamp: Long
) {
    fun toPlaylist(songCount: Int = 0): Playlist = Playlist(
        id = id,
        name = name,
        description = description,
        coverColorHex = coverColorHex,
        songCount = songCount,
        createdTimestamp = createdTimestamp
    )

    companion object {
        fun fromPlaylist(p: Playlist): PlaylistEntity = PlaylistEntity(
            id = p.id,
            name = p.name,
            description = p.description,
            coverColorHex = p.coverColorHex,
            createdTimestamp = p.createdTimestamp
        )
    }
}

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("songId")
    ]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val addedTimestamp: Long = System.currentTimeMillis()
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val description: String,
    val coverColorHex: String,
    val createdTimestamp: Long,
    val songCount: Int
) {
    fun toPlaylist(): Playlist = Playlist(
        id = id,
        name = name,
        description = description,
        coverColorHex = coverColorHex,
        songCount = songCount,
        createdTimestamp = createdTimestamp
    )
}
