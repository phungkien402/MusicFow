package com.example.spec

/*
 * HOP DONG DOT C (Claude viet). KHONG SUA FILE NAY.
 *  - Migration DB 2 -> moi nhat KHONG mat du lieu (bo fallbackToDestructiveMigration), khoa ngoai CASCADE cho playlist_songs.
 *  - Xoa bai: chi xoa file nam trong thu muc rieng cua app.
 *  - findExistingDownload: nhan ra bai da tai theo SourceKey.
 *  - downloadAudio: dinh dang/duoi file lay theo noi dung THAT cua file tai ve.
 */

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.downloader.AudioLinkExtractor
import com.example.data.downloader.ParsedLinkMetadata
import com.example.data.local.MusicDatabase
import com.example.data.local.PlaylistSongCrossRef
import com.example.data.model.AudioFormat
import com.example.data.model.AudioQuality
import com.example.data.model.Song
import com.example.data.model.SourceType
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
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
class DataUpgradeSpecTest {

    private lateinit var context: Context
    private val toClose = mutableListOf<() -> Unit>()

    @Before
    fun setUp() { context = ApplicationProvider.getApplicationContext() }

    @After
    fun tearDown() { toClose.reversed().forEach { runCatching { it() } } }

    private fun memRepo(): Pair<MusicDatabase, MusicRepository> {
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).allowMainThreadQueries().build()
        toClose.add { db.close() }
        return db to MusicRepository(context, db.musicDao())
    }

    // ---------- Migration ----------

    /** Tao file DB dung cau truc phien ban 2 (truoc dot C) roi do du lieu vao. */
    private fun createV2Database(name: String): File {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS `songs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `filePath` TEXT NOT NULL, `sourceUrl` TEXT, `format` TEXT NOT NULL, `bitrate` TEXT NOT NULL, `fileSizeBytes` INTEGER NOT NULL, `isDownloaded` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `thumbnailUrl` TEXT, `addedTimestamp` INTEGER NOT NULL, `playCount` INTEGER NOT NULL, `lyrics` TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverColorHex` TEXT NOT NULL, `createdTimestamp` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_songs` (`playlistId` INTEGER NOT NULL, `songId` INTEGER NOT NULL, `addedTimestamp` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songId`))")
        db.execSQL("INSERT INTO songs VALUES (1,'Lac Troi','Son Tung','Al',200000,'/a.mp3','https://youtu.be/aaaaaaaaaaa','mp3','320 kbps',100,1,1,'YOUTUBE',NULL,1000,7,'[00:01.00]loi')")
        db.execSQL("INSERT INTO songs VALUES (2,'Bai 2','X','Al',100000,'/b.mp3',NULL,'mp3','320 kbps',100,1,0,'LOCAL_DEVICE',NULL,2000,0,NULL)")
        db.execSQL("INSERT INTO playlists VALUES (1,'Chill','mo ta','#00F5FF',500)")
        db.execSQL("INSERT INTO playlist_songs VALUES (1,1,10)")
        db.execSQL("INSERT INTO playlist_songs VALUES (1,2,20)")
        db.execSQL("INSERT INTO playlist_songs VALUES (1,99,30)") // lien ket mo coi tu ban cu (bai 99 khong ton tai)
        db.version = 2
        db.close()
        return file
    }

    private fun openMigrated(name: String): MusicDatabase {
        val db = Room.databaseBuilder(context, MusicDatabase::class.java, name)
            .addMigrations(*MusicDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        toClose.add { db.close() }
        return db
    }

    @Test
    fun `TC-MIG-01 nang cap tu DB phien ban 2 - giu nguyen bai, playlist, thanh vien, yeu thich, loi`() = runBlocking {
        createV2Database("mig1.db")
        val db = openMigrated("mig1.db")
        val dao = db.musicDao()
        val songs = dao.getAllSongsList().sortedBy { it.id }
        assertEquals(listOf("Lac Troi", "Bai 2"), songs.map { it.title })
        assertTrue(songs[0].isFavorite)
        assertEquals(7, songs[0].playCount)
        assertEquals("[00:01.00]loi", songs[0].lyrics)
        assertEquals(listOf("Chill"), dao.getAllPlaylistsList().map { it.name })
        assertEquals(listOf("Lac Troi", "Bai 2"), dao.getSongsForPlaylist(1).first().map { it.title })
    }

    @Test
    fun `TC-MIG-02 lien ket mo coi tu ban cu bi bo khi nang cap`() = runBlocking {
        createV2Database("mig2.db")
        val db = openMigrated("mig2.db")
        val refs = db.musicDao().getAllPlaylistSongsCrossRefList()
        assertEquals(setOf(1L, 2L), refs.map { it.songId }.toSet())
    }

    @Test
    fun `TC-MIG-03 sau nang cap - xoa bai hoac playlist bang DAO thi lien ket tu mat (CASCADE)`() = runBlocking {
        createV2Database("mig3.db")
        val db = openMigrated("mig3.db")
        val dao = db.musicDao()
        dao.deleteSong(1)
        assertEquals(listOf(2L), dao.getAllPlaylistSongsCrossRefList().map { it.songId })
        dao.deletePlaylist(1)
        assertTrue(dao.getAllPlaylistSongsCrossRefList().isEmpty())
    }

    @Test
    fun `TC-MIG-04 DB moi - them lien ket toi bai khong ton tai bi tu choi hoac bo qua, khong tao mo coi`() = runBlocking {
        val (db, repo) = memRepo()
        val pid = repo.createPlaylist("PL", "", "#00F5FF")
        runCatching { db.musicDao().addSongToPlaylist(PlaylistSongCrossRef(pid, 12345)) }
        assertTrue(db.musicDao().getAllPlaylistSongsCrossRefList().none { it.songId == 12345L })
    }

    @Test
    fun `TC-MIG-05 getInstance mo duoc DB phien ban 2 that ma khong xoa du lieu`() = runBlocking {
        createV2Database("soundflow_music.db")
        val db = MusicDatabase.getInstance(context)
        toClose.add { MusicDatabase.destroyInstance() }
        assertEquals(2, db.musicDao().getAllSongsList().size)
    }

    // ---------- Xoa bai chi xoa file cua app ----------

    @Test
    fun `TC-DELF-01 bai quet tu may (file ngoai thu muc app) - go khoi thu vien, KHONG xoa file goc`() = runBlocking {
        val (_, repo) = memRepo()
        val outside = File(System.getProperty("java.io.tmpdir"), "nhac_cua_toi_${System.nanoTime()}.mp3").apply { writeBytes(ByteArray(6000)) }
        toClose.add { outside.delete() }
        val id = repo.saveDownloadedSong(Song(title = "Cua toi", artist = "A", durationMs = 1, filePath = outside.absolutePath, sourceType = SourceType.LOCAL_DEVICE), null)
        repo.deleteSong(repo.allSongs.first().single { it.id == id })
        assertTrue(repo.allSongs.first().none { it.id == id })
        assertTrue("file goc cua nguoi dung bi xoa!", outside.exists())
    }

    @Test
    fun `TC-DELF-02 bai tai ve (trong thu muc app) - xoa ca file nhac va anh bia (moi he dieu hanh)`() = runBlocking {
        val (_, repo) = memRepo()
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }
        val audio = File(dir, "x_${System.nanoTime()}.mp3").apply { writeBytes(ByteArray(6000)) }
        val thumb = File(dir, "x_thumb_${System.nanoTime()}.jpg").apply { writeBytes(ByteArray(10)) }
        val id = repo.saveDownloadedSong(Song(title = "Tai ve", artist = "A", durationMs = 1, filePath = audio.absolutePath, thumbnailUrl = thumb.absolutePath, sourceType = SourceType.DIRECT_URL), null)
        repo.deleteSong(repo.allSongs.first().single { it.id == id })
        assertFalse(audio.exists())
        assertFalse(thumb.exists())
    }

    // ---------- Chong tai trung ----------

    @Test
    fun `TC-DUP-01 findExistingDownload nhan ra cung video du link khac dang, khong nham video khac`() = runBlocking {
        val (_, repo) = memRepo()
        repo.saveDownloadedSong(Song(title = "Da tai", artist = "A", durationMs = 1, filePath = "/x.mp3", sourceUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5", sourceType = SourceType.YOUTUBE), null)
        assertEquals("Da tai", repo.findExistingDownload("https://youtu.be/dQw4w9WgXcQ")?.title)
        assertNull(repo.findExistingDownload("https://youtu.be/aaaaaaaaaaa"))
        assertNull(repo.findExistingDownload("   "))
    }

    // ---------- Dinh dang that khi tai ----------

    private fun serveAndDownload(path: String, body: ByteArray, quality: AudioQuality): Song = runBlocking {
        val server = MockWebServer().apply { start() }
        toClose.add { server.shutdown() }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(body)))
        val url = server.url(path).toString()
        val meta = ParsedLinkMetadata(url, SourceType.DIRECT_URL, "Bai that", "Web", null, 180_000L, url)
        AudioLinkExtractor(context).downloadAudio(meta, quality) { _, _, _, _ -> }.getOrThrow()
    }

    private fun withHeader(header: ByteArray, size: Int = 20_000) = header + ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `TC-REAL-01 noi dung FLAC o link khong ro duoi - luu dung FLAC, duoi flac`() {
        val s = serveAndDownload("/stream/x.bin", withHeader("fLaC".toByteArray()), AudioQuality.AUTO)
        assertEquals(AudioFormat.FLAC, s.format)
        assertTrue(s.filePath, s.filePath.endsWith(".flac"))
        assertTrue(File(s.filePath).exists())
    }

    @Test
    fun `TC-REAL-02 link ghi mp3 nhung noi dung la WAV - tin noi dung that`() {
        val wav = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WAVEfmt ".toByteArray()
        val s = serveAndDownload("/bai.mp3", withHeader(wav), AudioQuality.MP3_320)
        assertEquals(AudioFormat.WAV, s.format)
        assertTrue(s.filePath, s.filePath.endsWith(".wav"))
    }

    @Test
    fun `TC-REAL-03 bitrate khong doc duoc thi khong bia so kbps`() {
        val s = serveAndDownload("/stream/y.bin", withHeader("OggS".toByteArray()), AudioQuality.AUTO)
        assertEquals(AudioFormat.OGG, s.format)
        assertFalse("bitrate bia: ${s.bitrate}", s.bitrate == "320 kbps" || s.bitrate == "256 kbps" || s.bitrate == "1411 kbps")
    }
}
