package com.kanso.podcast.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueEntry::class, DownloadEntity::class, FolderEntity::class, PendingEpisodeState::class,
        PlaylistEntity::class, BookmarkEntity::class, ListeningDay::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class KansoDatabase : RoomDatabase() {
    abstract fun dao(): KansoDao

    companion object {
        @Volatile private var instance: KansoDatabase? = null
        fun get(context: Context): KansoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, KansoDatabase::class.java, "kanso.db").build().also { instance = it }
        }
    }
}
