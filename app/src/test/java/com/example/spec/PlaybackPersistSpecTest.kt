package com.example.spec

/*
 * HOP DONG DOT E (Claude viet). KHONG SUA FILE NAY.
 * Luu va khoi phuc trang thai dang nghe (hang doi, bai hien tai, vi tri, shuffle, repeat):
 *  - data class SavedPlayback(songIds: List<Long>, currentIndex: Int, positionMs: Long, shuffle: Boolean, repeatMode: RepeatMode)
 *      fun resolve(library: List<Song>): Pair<List<Song>, Int>?   (bo bai khong con trong thu vien, giu thu tu)
 *  - class PlaybackStateStore(context): save(SavedPlayback), load(): SavedPlayback?, clear()
 *  - AudioPlayerManager.snapshot(): SavedPlayback?   (null khi queue rong)
 *  - AudioPlayerManager.restoreQueue(songs, index, positionMs): nap lai queue, KHONG tu phat.
 * PlayerQueueSpecTest (hang doi/xoa/shuffle) phai van PASS sau khi chuyen sang Media3.
 */

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Song
import com.example.data.player.AudioPlayerManager
import com.example.data.player.PlaybackStateStore
import com.example.data.player.RepeatMode
import com.example.data.player.SavedPlayback
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackPersistSpecTest {

    private lateinit var context: Context

    private fun song(id: Long) = Song(id = id, title = "Song $id", artist = "A", durationMs = 200_000L, filePath = "/spec/$id.mp3")

    @Before
    fun setUp() { context = ApplicationProvider.getApplicationContext() }

    @Test
    fun `TC-PS-01 luu roi doc lai - giong het`() {
        val store = PlaybackStateStore(context)
        val s = SavedPlayback(listOf(5L, 3L, 9L), 1, 61_500L, true, RepeatMode.ONE)
        store.save(s)
        assertEquals(s, PlaybackStateStore(context).load())
    }

    @Test
    fun `TC-PS-02 chua luu hoac da clear - load tra null`() {
        val store = PlaybackStateStore(context)
        assertNull(store.load())
        store.save(SavedPlayback(listOf(1L), 0, 0L, false, RepeatMode.OFF))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `TC-PS-03 resolve - bai bi xoa thi bo, bai dang nghe van con thi giu dung bai do`() {
        val lib = listOf(song(1), song(2), song(4))
        val (q, idx) = SavedPlayback(listOf(1L, 3L, 4L, 2L), 2, 0L, false, RepeatMode.ALL).resolve(lib)!!
        assertEquals(listOf(1L, 4L, 2L), q.map { it.id })
        assertEquals(4L, q[idx].id)
    }

    @Test
    fun `TC-PS-04 resolve - bai dang nghe bi xoa thi chon bai ke tiep, het bai thi null`() {
        val lib = listOf(song(1), song(2))
        val (q, idx) = SavedPlayback(listOf(1L, 3L, 2L), 1, 0L, false, RepeatMode.ALL).resolve(lib)!!
        assertEquals(listOf(1L, 2L), q.map { it.id })
        assertEquals(2L, q[idx].id)
        assertNull(SavedPlayback(listOf(7L, 8L), 0, 0L, false, RepeatMode.ALL).resolve(lib))
    }

    @Test
    fun `TC-PS-05 snapshot phan anh dung trang thai player`() {
        val player = AudioPlayerManager(context)
        assertNull(player.snapshot())
        player.setQueue(listOf(song(1), song(2), song(3)), 2)
        repeat(3) { if (player.repeatMode.value != RepeatMode.OFF) player.cycleRepeatMode() }
        player.setShuffleEnabled(true)
        val s = player.snapshot()!!
        assertEquals(listOf(1L, 2L, 3L), s.songIds)
        assertEquals(2, s.currentIndex)
        assertTrue(s.shuffle)
        assertEquals(RepeatMode.OFF, s.repeatMode)
    }

    @Test
    fun `TC-PS-06 restoreQueue - nap lai hang doi va vi tri, KHONG tu phat`() {
        val player = AudioPlayerManager(context)
        player.restoreQueue(listOf(song(1), song(2), song(3)), 1, 42_000L)
        assertEquals(listOf(1L, 2L, 3L), player.playQueue.value.map { it.id })
        assertEquals(1, player.currentIndex.value)
        assertEquals(2L, player.currentSong.value?.id)
        assertEquals(42_000L, player.currentPositionMs.value)
        assertFalse(player.isPlaying.value)
    }
}
