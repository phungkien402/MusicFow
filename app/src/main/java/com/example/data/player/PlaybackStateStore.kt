package com.example.data.player

import android.content.Context
import org.json.JSONArray

class PlaybackStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    fun save(state: SavedPlayback) {
        val jsonSongIds = JSONArray(state.songIds).toString()
        prefs.edit()
            .putString("song_ids", jsonSongIds)
            .putInt("current_index", state.currentIndex)
            .putLong("position_ms", state.positionMs)
            .putBoolean("shuffle", state.shuffle)
            .putString("repeat_mode", state.repeatMode.name)
            .commit()
    }

    fun load(): SavedPlayback? {
        if (!prefs.contains("song_ids")) return null
        return try {
            val jsonStr = prefs.getString("song_ids", null) ?: return null
            val jsonArray = JSONArray(jsonStr)
            val songIds = ArrayList<Long>()
            for (i in 0 until jsonArray.length()) {
                songIds.add(jsonArray.getLong(i))
            }
            if (songIds.isEmpty()) return null
            val currentIndex = prefs.getInt("current_index", 0)
            val positionMs = prefs.getLong("position_ms", 0L)
            val shuffle = prefs.getBoolean("shuffle", false)
            val repeatModeStr = prefs.getString("repeat_mode", RepeatMode.OFF.name)
            val repeatMode = try {
                RepeatMode.valueOf(repeatModeStr ?: RepeatMode.OFF.name)
            } catch (_: Exception) {
                RepeatMode.OFF
            }
            SavedPlayback(songIds, currentIndex, positionMs, shuffle, repeatMode)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}
