package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class
    ],
    version = 3,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

    companion object {
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_songs_new` (`playlistId` INTEGER NOT NULL, `songId` INTEGER NOT NULL, `addedTimestamp` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `songId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL(
                    """
                    INSERT INTO `playlist_songs_new` (`playlistId`, `songId`, `addedTimestamp`)
                    SELECT `playlistId`, `songId`, `addedTimestamp`
                    FROM `playlist_songs`
                    WHERE `playlistId` IN (SELECT `id` FROM `playlists`)
                      AND `songId` IN (SELECT `id` FROM `songs`)
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `playlist_songs`")
                db.execSQL("ALTER TABLE `playlist_songs_new` RENAME TO `playlist_songs`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_songs_songId` ON `playlist_songs` (`songId`)")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3)

        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "soundflow_music.db"
                ).addMigrations(*ALL_MIGRATIONS).build()
                INSTANCE = instance
                instance
            }
        }

        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
