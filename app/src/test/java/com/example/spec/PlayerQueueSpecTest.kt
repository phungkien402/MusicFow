package com.example.spec

/*
 * HOP DONG (Claude viet tu spec .aistudio/.../implementation_plan.md). KHONG SUA FILE NAY.
 * Pham vi: hang doi phat, xoa bai dang phat, shuffle/repeat, khong phat nhac mau thay bai that.
 * Moi test co ma TC-xxx. Test [THIET KE] = hanh vi ky vong theo spec nhung co the con tranh luan.
 */

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Song
import com.example.data.player.AudioPlayerManager
import com.example.data.player.RepeatMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerQueueSpecTest {

    private lateinit var context: Context
    private lateinit var player: AudioPlayerManager

    private fun song(id: Long, path: String = "/spec/song_$id.mp3", title: String = "Song $id", artist: String = "Artist $id") =
        Song(id = id, title = title, artist = artist, durationMs = 180_000L, filePath = path)

    private fun setRepeat(mode: RepeatMode) {
        repeat(3) { if (player.repeatMode.value != mode) player.cycleRepeatMode() }
        assertEquals(mode, player.repeatMode.value)
    }

    private fun ids() = player.playQueue.value.map { it.id }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        player = AudioPlayerManager(context)
    }

    // ---------- Xoa bai (spec Quyet dinh 2) ----------

    @Test
    fun `TC-DEL-01 xoa bai dang phat duy nhat - dung han, queue rong`() {
        val s = song(1)
        player.setQueue(listOf(s), 0)
        player.onSongDeleted(s)
        assertNull(player.currentSong.value)
        assertFalse(player.isPlaying.value)
        assertTrue(player.playQueue.value.isEmpty())
    }

    @Test
    fun `TC-DEL-02 xoa bai dang phat o giua - chuyen sang bai ke tiep cung vi tri`() {
        val list = listOf(song(1), song(2), song(3))
        player.setQueue(list, 1)
        player.onSongDeleted(list[1])
        assertEquals(listOf(1L, 3L), ids())
        assertEquals(1, player.currentIndex.value)
        assertEquals(3L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-DEL-03 xoa bai dang phat o cuoi - chuyen ve bai cuoi moi`() {
        val list = listOf(song(1), song(2), song(3))
        player.setQueue(list, 2)
        player.onSongDeleted(list[2])
        assertEquals(listOf(1L, 2L), ids())
        assertEquals(1, player.currentIndex.value)
        assertEquals(2L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-DEL-04 xoa bai khac nam TRUOC bai dang phat - bai dang phat giu nguyen, index giam`() {
        val list = listOf(song(1), song(2), song(3))
        player.setQueue(list, 2)
        player.onSongDeleted(list[0])
        assertEquals(listOf(2L, 3L), ids())
        assertEquals(3L, player.currentSong.value?.id)
        assertEquals(1, player.currentIndex.value)
    }

    @Test
    fun `TC-DEL-05 xoa bai khac nam SAU bai dang phat - index giu nguyen`() {
        val list = listOf(song(1), song(2), song(3))
        player.setQueue(list, 0)
        player.onSongDeleted(list[2])
        assertEquals(listOf(1L, 2L), ids())
        assertEquals(0, player.currentIndex.value)
        assertEquals(1L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-DEL-06 khop theo filePath khi id khac nhau (bai chua luu DB id=0)`() {
        val playing = song(0, path = "/spec/a.mp3", title = "A", artist = "X")
        player.setQueue(listOf(playing), 0)
        player.onSongDeleted(song(77, path = "/spec/a.mp3", title = "A khac", artist = "Y"))
        assertNull(player.currentSong.value)
        assertTrue(player.playQueue.value.isEmpty())
    }

    @Test
    fun `TC-DEL-07 xoa bai khong co trong queue - khong doi gi`() {
        val list = listOf(song(1), song(2))
        player.setQueue(list, 1)
        player.onSongDeleted(song(99))
        assertEquals(listOf(1L, 2L), ids())
        assertEquals(2L, player.currentSong.value?.id)
        assertEquals(1, player.currentIndex.value)
    }

    @Test
    fun `TC-DEL-08 THIET KE - hai bai khac nhau (id, file khac) cung ten+ca si - xoa 1 chi mat 1`() {
        val original = song(10, path = "/spec/goc.mp3", title = "Lac Troi", artist = "Son Tung")
        val remix = song(11, path = "/spec/remix.mp3", title = "Lac Troi", artist = "Son Tung")
        player.setQueue(listOf(original, remix), 0)
        player.onSongDeleted(remix)
        assertEquals(listOf(10L), ids())
        assertEquals(10L, player.currentSong.value?.id)
    }

    // ---------- Hang doi ----------

    @Test
    fun `TC-Q-01 removeFromQueue bai truoc bai dang phat - index giam, bai dang phat giu`() {
        player.setQueue(listOf(song(1), song(2), song(3)), 2)
        player.removeFromQueue(0)
        assertEquals(listOf(2L, 3L), ids())
        assertEquals(1, player.currentIndex.value)
        assertEquals(3L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-Q-02 removeFromQueue chinh bai dang phat - phat bai ke tiep`() {
        player.setQueue(listOf(song(1), song(2), song(3)), 0)
        player.removeFromQueue(0)
        assertEquals(listOf(2L, 3L), ids())
        assertEquals(2L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-Q-03 removeFromQueue index sai - khong doi`() {
        player.setQueue(listOf(song(1), song(2)), 0)
        player.removeFromQueue(5)
        player.removeFromQueue(-1)
        assertEquals(listOf(1L, 2L), ids())
    }

    @Test
    fun `TC-Q-04 addToQueueNext chen ngay sau bai dang phat`() {
        player.setQueue(listOf(song(1), song(2), song(3)), 0)
        player.addToQueueNext(song(9))
        assertEquals(listOf(1L, 9L, 2L, 3L), ids())
        assertEquals(1L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-Q-05 addToQueueNext khi queue rong - bai do thanh bai dang phat`() {
        player.addToQueueNext(song(9))
        assertEquals(listOf(9L), ids())
        assertEquals(9L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-Q-06 moveQueueItem keo bai dang phat - currentIndex theo bai`() {
        player.setQueue(listOf(song(1), song(2), song(3)), 0)
        player.moveQueueItem(0, 2)
        assertEquals(listOf(2L, 3L, 1L), ids())
        assertEquals(2, player.currentIndex.value)
    }

    @Test
    fun `TC-Q-07 moveQueueItem voi bai chua co id (id=0, file khac nhau) - van theo dung bai dang phat`() {
        val a = song(0, path = "/spec/a.mp3", title = "A")
        val b = song(0, path = "/spec/b.mp3", title = "B")
        val c = song(0, path = "/spec/c.mp3", title = "C")
        player.setQueue(listOf(a, b, c), 1) // dang phat B
        player.moveQueueItem(0, 2)          // queue: B, C, A
        val q = player.playQueue.value
        assertEquals("B", q[player.currentIndex.value].title)
    }

    @Test
    fun `TC-Q-08 clearQueueExceptCurrent chi con bai dang phat`() {
        player.setQueue(listOf(song(1), song(2), song(3)), 1)
        player.clearQueueExceptCurrent()
        assertEquals(listOf(2L), ids())
        assertEquals(0, player.currentIndex.value)
    }

    @Test
    fun `TC-Q-09 setQueue rong bi bo qua, startIndex ngoai pham vi bi kep`() {
        player.setQueue(emptyList(), 0)
        assertNull(player.currentSong.value)
        player.setQueue(listOf(song(1), song(2)), 10)
        assertEquals(1, player.currentIndex.value)
        assertEquals(2L, player.currentSong.value?.id)
    }

    // ---------- Next / Previous / Repeat ----------

    @Test
    fun `TC-NAV-01 playNext o bai cuoi, repeat OFF - dung, khong quay vong`() {
        setRepeat(RepeatMode.OFF)
        player.setQueue(listOf(song(1), song(2)), 1)
        player.playNext()
        assertEquals(1, player.currentIndex.value)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun `TC-NAV-02 playNext o bai cuoi, repeat ALL - ve bai dau`() {
        setRepeat(RepeatMode.ALL)
        player.setQueue(listOf(song(1), song(2)), 1)
        player.playNext()
        assertEquals(0, player.currentIndex.value)
        assertEquals(1L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-NAV-03 playPrevious o bai dau, repeat ALL - ve bai cuoi`() {
        setRepeat(RepeatMode.ALL)
        player.setQueue(listOf(song(1), song(2), song(3)), 0)
        player.playPrevious()
        assertEquals(2, player.currentIndex.value)
        assertEquals(3L, player.currentSong.value?.id)
    }

    @Test
    fun `TC-NAV-04 cycleRepeatMode OFF - ALL - ONE - OFF`() {
        setRepeat(RepeatMode.OFF)
        player.cycleRepeatMode(); assertEquals(RepeatMode.ALL, player.repeatMode.value)
        player.cycleRepeatMode(); assertEquals(RepeatMode.ONE, player.repeatMode.value)
        player.cycleRepeatMode(); assertEquals(RepeatMode.OFF, player.repeatMode.value)
    }

    @Test
    fun `TC-SHUF-01 shuffle repeat OFF - di qua moi bai dung 1 lan roi dung`() {
        setRepeat(RepeatMode.OFF)
        val list = (1L..6L).map { song(it) }
        player.setQueue(list, 0)
        player.setShuffleEnabled(true)
        val seen = mutableListOf(player.currentSong.value!!.id)
        repeat(5) { player.playNext(); seen.add(player.currentSong.value!!.id) }
        assertEquals("moi bai phat dung 1 lan: $seen", 6, seen.toSet().size)
        player.playNext()
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun `TC-SHUF-02 shuffle - playNext roi playPrevious quay lai dung bai truoc`() {
        player.setQueue((1L..5L).map { song(it) }, 0)
        player.setShuffleEnabled(true)
        player.playNext()
        val second = player.currentSong.value!!.id
        player.playNext()
        player.playPrevious()
        assertEquals(second, player.currentSong.value!!.id)
    }

    @Test
    fun `TC-SHUF-03 shuffle - xoa 1 bai khac khong lam phat lai bai da nghe`() {
        setRepeat(RepeatMode.OFF)
        val list = (1L..5L).map { song(it) }
        player.setQueue(list, 0)
        player.setShuffleEnabled(true)
        val seen = mutableListOf(player.currentSong.value!!.id)
        player.playNext(); seen.add(player.currentSong.value!!.id)
        val notYet = list.map { it.id }.first { it !in seen }
        player.onSongDeleted(list.first { it.id == notYet })
        repeat(3) { player.playNext(); if (player.isPlaying.value) seen.add(player.currentSong.value!!.id) }
        assertEquals("khong lap lai bai da nghe: $seen", seen.size, seen.toSet().size)
    }

    // ---------- Khac ----------

    @Test
    fun `TC-X-01 crossfade bi kep 0-15 giay`() {
        player.setCrossfadeSeconds(-3); assertEquals(0, player.crossfadeSeconds.value)
        player.setCrossfadeSeconds(99); assertEquals(15, player.crossfadeSeconds.value)
    }

    @Test
    fun `TC-X-02 sleep timer null hoac 0 - tat`() {
        player.setSleepTimer(5)
        assertEquals(5, player.sleepTimerMinutesLeft.value)
        player.setSleepTimer(0)
        assertNull(player.sleepTimerMinutesLeft.value)
        player.setSleepTimer(null)
        assertNull(player.sleepTimerMinutesLeft.value)
    }

    @Test
    fun `TC-X-03 isSameSong - null va bai rong khong bi coi la trung`() {
        assertFalse(player.isSameSong(null, song(1)))
        val a = Song(id = 0, title = "A", artist = "", durationMs = 1, filePath = "")
        val b = Song(id = 0, title = "B", artist = "", durationMs = 1, filePath = "")
        assertFalse(player.isSameSong(a, b))
    }

    @Test
    fun `TC-X-04 stopPlaybackCompletely - reset het trang thai`() {
        player.setQueue(listOf(song(1), song(2)), 1)
        player.stopPlaybackCompletely()
        assertNull(player.currentSong.value)
        assertTrue(player.playQueue.value.isEmpty())
        assertEquals(0, player.currentIndex.value)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun `TC-SAMPLE-01 file bai hat bi mat - KHONG duoc tu tao nhac mau de phat thay (spec Quyet dinh 1)`() {
        val name = "spec_missing_${System.nanoTime()}.mp3"
        val missing = Song(id = 5, title = "Mat file", artist = "X", durationMs = 1000, filePath = "/khong/ton/tai/$name")
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val fake = File(base, "downloads/$name")
        player.playSong(missing)
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        assertFalse("App da tu tao file nhac mau: ${fake.absolutePath}", fake.exists())
    }
}
