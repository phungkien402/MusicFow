package com.example.spec

/*
 * HOP DONG (Claude viet). KHONG SUA FILE NAY.
 * Pham vi: MusicRepository tren Room in-memory - xoa bai/playlist, them vao playlist, sao luu & khoi phuc JSON.
 */

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDatabase
import com.example.data.model.Song
import com.example.data.model.SourceType
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryBackupSpecTest {

    private lateinit var context: Context
    private val dbs = mutableListOf<MusicDatabase>()

    private fun newRepo(): Pair<MusicDatabase, MusicRepository> {
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).allowMainThreadQueries().build()
        dbs.add(db)
        return db to MusicRepository(context, db.musicDao())
    }

    private fun song(title: String, artist: String = "Ca si", path: String = "", sourceUrl: String? = "https://web/$title.mp3") =
        Song(title = title, artist = artist, durationMs = 200_000, filePath = path, sourceUrl = sourceUrl, sourceType = SourceType.DIRECT_URL)

    @Before
    fun setUp() { context = ApplicationProvider.getApplicationContext() }

    @After
    fun tearDown() { dbs.forEach { it.close() } }

    // ---------- Xoa ----------

    @Test
    fun `TC-REPO-01 xoa bai - mat khoi DB va xoa file nhac + anh bia cuc bo`() = runBlocking {
        val (_, repo) = newRepo()
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }
        val audio = File(dir, "spec_del_${System.nanoTime()}.mp3").apply { writeBytes(ByteArray(6000)) }
        val thumb = File(dir, "spec_thumb_${System.nanoTime()}.jpg").apply { writeBytes(ByteArray(10)) }
        val id = repo.saveDownloadedSong(song("Xoa", path = audio.absolutePath).copy(thumbnailUrl = thumb.absolutePath), null)
        val saved = repo.allSongs.first().single { it.id == id }
        repo.deleteSong(saved)
        assertTrue(repo.allSongs.first().none { it.id == id })
        assertFalse(audio.exists())
        assertFalse(thumb.exists())
    }

    @Test
    fun `TC-REPO-02 xoa bai dang nam trong playlist - so bai cua playlist giam`() = runBlocking {
        val (_, repo) = newRepo()
        val pid = repo.createPlaylist("PL", "", "#00F5FF")
        val id = repo.saveDownloadedSong(song("Trong PL"), pid)
        assertEquals(1, repo.playlists.first().single { it.id == pid }.songCount)
        repo.deleteSong(repo.allSongs.first().single { it.id == id })
        assertEquals(0, repo.playlists.first().single { it.id == pid }.songCount)
    }

    @Test
    fun `TC-REPO-03 xoa playlist - khong de lai lien ket mo coi playlist_songs`() = runBlocking {
        val (db, repo) = newRepo()
        val pid = repo.createPlaylist("PL", "", "#00F5FF")
        repo.saveDownloadedSong(song("A"), pid)
        repo.deletePlaylist(pid)
        assertTrue(db.musicDao().getAllPlaylistSongsCrossRefList().none { it.playlistId == pid })
    }

    @Test
    fun `TC-REPO-04 saveDownloadedSong co playlist dich thi vao playlist, null thi khong`() = runBlocking {
        val (_, repo) = newRepo()
        val pid = repo.createPlaylist("PL", "", "#00F5FF")
        repo.saveDownloadedSong(song("Vao"), pid)
        repo.saveDownloadedSong(song("Khong vao"), null)
        assertEquals(listOf("Vao"), repo.getSongsForPlaylist(pid).first().map { it.title })
        assertEquals(2, repo.allSongs.first().size)
    }

    @Test
    fun `TC-REPO-05 them 1 bai vao playlist 2 lan - chi tinh 1`() = runBlocking {
        val (_, repo) = newRepo()
        val pid = repo.createPlaylist("PL", "", "#00F5FF")
        val id = repo.saveDownloadedSong(song("A"), null)
        repo.addSongToPlaylist(pid, id)
        repo.addSongToPlaylist(pid, id)
        assertEquals(1, repo.getSongsForPlaylist(pid).first().size)
    }

    // ---------- Sao luu / khoi phuc ----------

    private suspend fun seedBackup(): String {
        val (_, src) = newRepo()
        val pid = src.createPlaylist("Nhac chill", "mo ta", "#A855F7")
        val a = src.saveDownloadedSong(song("Bai A", sourceUrl = null), pid)
        src.saveDownloadedSong(song("Bai B"), null)
        src.toggleFavorite(a)
        return src.exportBackupJson()
    }

    @Test
    fun `TC-BAK-01 export roi import vao may trong - du bai, playlist, thanh vien, yeu thich`() = runBlocking {
        val json = seedBackup()
        val (_, dst) = newRepo()
        assertTrue(dst.importBackupJson(json).success)
        val songs = dst.allSongs.first()
        assertEquals(setOf("Bai A", "Bai B"), songs.map { it.title }.toSet())
        assertTrue(songs.single { it.title == "Bai A" }.isFavorite)
        val pl = dst.playlists.first().single { it.name == "Nhac chill" }
        assertEquals(listOf("Bai A"), dst.getSongsForPlaylist(pl.id).first().map { it.title })
    }

    @Test
    fun `TC-BAK-02 import vao may DA CO du lieu trung id - khong duoc ghi de bai, playlist dang co`() = runBlocking {
        val json = seedBackup()
        val (_, dst) = newRepo()
        dst.createPlaylist("Playlist cua toi", "", "#00F5FF")      // id 1, trung id playlist trong backup
        dst.saveDownloadedSong(song("Bai dang co", artist = "Khac"), null) // id 1, trung id "Bai A"
        dst.importBackupJson(json)
        val titles = dst.allSongs.first().map { it.title }.toSet()
        assertEquals(setOf("Bai dang co", "Bai A", "Bai B"), titles)
        val names = dst.playlists.first().map { it.name }.toSet()
        assertEquals(setOf("Playlist cua toi", "Nhac chill"), names)
    }

    @Test
    fun `TC-BAK-03 bai trong backup da co san (khac id) - playlist khoi phuc tro dung bai san co`() = runBlocking {
        val json = seedBackup()
        val (_, dst) = newRepo()
        // tao vai bai dem de "Bai A" san co mang id khac voi id trong backup
        repeat(4) { dst.saveDownloadedSong(song("Dem $it"), null) }
        dst.saveDownloadedSong(song("Bai A"), null)
        dst.importBackupJson(json)
        assertEquals(1, dst.allSongs.first().count { it.title == "Bai A" })
        val pl = dst.playlists.first().single { it.name == "Nhac chill" }
        assertEquals(listOf("Bai A"), dst.getSongsForPlaylist(pl.id).first().map { it.title })
    }

    @Test
    fun `TC-BAK-04 JSON hong - tra ve that bai, khong nem exception`() = runBlocking {
        val (_, dst) = newRepo()
        val r = dst.importBackupJson("{ day khong phai json")
        assertFalse(r.success)
    }

    @Test
    fun `TC-BAK-05 sourceUrl null khi export - import lai van null (khong thanh chuoi rong)`() = runBlocking {
        val json = seedBackup()
        val (_, dst) = newRepo()
        dst.importBackupJson(json)
        assertNull(dst.allSongs.first().single { it.title == "Bai A" }.sourceUrl)
    }

    @Test
    fun `TC-BAK-06 import cung 1 file 2 lan - khong nhan doi bai va playlist`() = runBlocking {
        val json = seedBackup()
        val (_, dst) = newRepo()
        dst.importBackupJson(json)
        dst.importBackupJson(json)
        assertEquals(2, dst.allSongs.first().size)
        assertEquals(1, dst.playlists.first().size)
    }
}
