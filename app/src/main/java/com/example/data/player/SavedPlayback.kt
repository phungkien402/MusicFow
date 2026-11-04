package com.example.data.player

import com.example.data.model.Song

data class SavedPlayback(
    val songIds: List<Long>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: RepeatMode
) {
    fun resolve(library: List<Song>): Pair<List<Song>, Int>? {
        val songMap = library.associateBy { it.id }
        val validSongs = songIds.mapNotNull { songMap[it] }
        if (validSongs.isEmpty()) return null

        val targetSongId: Long? = if (currentIndex in songIds.indices && songMap.containsKey(songIds[currentIndex])) {
            songIds[currentIndex]
        } else {
            val nextSongId = (currentIndex + 1 until songIds.size).map { songIds[it] }.firstOrNull { songMap.containsKey(it) }
            if (nextSongId != null) {
                nextSongId
            } else {
                (currentIndex - 1 downTo 0).map { songIds[it] }.firstOrNull { songMap.containsKey(it) }
            }
        }

        if (targetSongId == null) {
            return null
        }

        val resolvedIndex = validSongs.indexOfFirst { it.id == targetSongId }
        if (resolvedIndex == -1) return null

        return Pair(validSongs, resolvedIndex)
    }
}
