package com.kanso.podcast.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueEntry::class, DownloadEntity::class, FolderEntity::class, PendingEpisodeState::class,
        PlaylistEntity::class, BookmarkEntity::class, ListeningDay::class, PodcastListening::class, AutoQueuePending::class, EpisodeFts::class],
    version = 5,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5, spec = KansoDatabase.IndexEpisodes::class)],
    exportSchema = true,
)
abstract class KansoDatabase : RoomDatabase() {
    abstract fun dao(): KansoDao

    /** Fills the new full-text index with the episodes already stored. */
    class IndexEpisodes : androidx.room.migration.AutoMigrationSpec {
        override fun onPostMigrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("INSERT INTO episodes_fts(episodes_fts) VALUES('rebuild')") }
    }

    companion object {
        @Volatile private var instance: KansoDatabase? = null
        fun get(context: Context): KansoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, KansoDatabase::class.java, "kanso.db").build().also { instance = it }
        }
    }
}
