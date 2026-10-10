package com.kanso.podcast.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val ROW = "SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtwork, p.author AS podcastAuthor FROM episodes e JOIN podcasts p ON p.feedUrl = e.podcastFeedUrl"
private const val ORDER = "ORDER BY e.publishedAt DESC, e.sortIndex ASC"

@Dao
interface KansoDao {
    // Podcasts & folders
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE") fun podcasts(): Flow<List<PodcastEntity>>
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE") suspend fun podcastList(): List<PodcastEntity>
    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl") fun podcast(feedUrl: String): Flow<PodcastEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPodcasts(items: List<PodcastEntity>)
    @Query("DELETE FROM podcasts WHERE feedUrl = :feedUrl") suspend fun deletePodcast(feedUrl: String)
    @Query("UPDATE podcasts SET folder = :folder WHERE feedUrl = :feedUrl") suspend fun setFolder(feedUrl: String, folder: String?)
    @Query("UPDATE podcasts SET autoDownloadCount = :count WHERE feedUrl = :feedUrl") suspend fun setAutoDownloadCount(feedUrl: String, count: Int?)
    @Query("SELECT name FROM folders ORDER BY name COLLATE NOCASE") fun folders(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFolders(items: List<FolderEntity>)

    // Episodes
    @Query("SELECT * FROM episodes WHERE podcastFeedUrl = :feedUrl ORDER BY publishedAt DESC, sortIndex ASC") fun episodesFor(feedUrl: String): Flow<List<EpisodeEntity>>
    @Query("SELECT * FROM episodes WHERE podcastFeedUrl = :feedUrl ORDER BY publishedAt DESC, sortIndex ASC") suspend fun episodeListFor(feedUrl: String): List<EpisodeEntity>
    @Query("SELECT * FROM episodes WHERE id = :id") suspend fun episode(id: String): EpisodeEntity?
    @Query("$ROW WHERE e.id = :id") suspend fun episodeRow(id: String): EpisodeRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEpisodes(items: List<EpisodeEntity>): List<Long>
    @Update(entity = EpisodeEntity::class) suspend fun updateMetadata(items: List<EpisodeMetadata>)

    @Query("$ROW WHERE e.progressMs > 0 AND e.listened = 0 ORDER BY e.lastPlayedAt DESC LIMIT 20") fun inProgress(): Flow<List<EpisodeRow>>
    @Query("$ROW WHERE e.listened = 0 AND e.archived = 0 AND e.progressMs = 0 $ORDER LIMIT 40") fun newEpisodes(): Flow<List<EpisodeRow>>
    @Query("$ROW WHERE e.favorite = 1 $ORDER") fun favorites(): Flow<List<EpisodeRow>>
    @Query("$ROW WHERE e.archived = 1 $ORDER LIMIT 100") fun archived(): Flow<List<EpisodeRow>>
    @Query("$ROW WHERE e.listened = 0 AND e.archived = 0 AND e.podcastFeedUrl = :feedUrl $ORDER LIMIT :limit") suspend fun newestUnplayed(feedUrl: String, limit: Int): List<EpisodeRow>
    @Query("$ROW WHERE e.title LIKE '%' || :query || '%' $ORDER LIMIT 50") suspend fun searchEpisodes(query: String): List<EpisodeRow>

    @Query("UPDATE episodes SET progressMs = :ms, lastPlayedAt = :now WHERE id = :id") suspend fun setProgress(id: String, ms: Long, now: Long)
    @Query("UPDATE episodes SET listened = 1, progressMs = 0, archived = CASE WHEN :archive AND favorite = 0 THEN 1 ELSE archived END WHERE id = :id") suspend fun markFinished(id: String, archive: Boolean)
    @Query("UPDATE episodes SET listened = :value, progressMs = CASE WHEN :value THEN 0 ELSE progressMs END, archived = CASE WHEN :value THEN archived ELSE 0 END WHERE id = :id") suspend fun setListened(id: String, value: Boolean)
    @Query("UPDATE episodes SET favorite = :value WHERE id = :id") suspend fun setFavorite(id: String, value: Boolean)
    @Query("UPDATE episodes SET archived = :value WHERE id = :id") suspend fun setArchived(id: String, value: Boolean)
    @Query("SELECT id FROM episodes WHERE listened = 1 AND favorite = 0") suspend fun playedNonFavoriteIds(): List<String>

    // Pending state from the legacy migration
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPending(items: List<PendingEpisodeState>)
    @Query("SELECT * FROM pending_state WHERE episodeId IN (:ids)") suspend fun pendingFor(ids: List<String>): List<PendingEpisodeState>
    @Query("DELETE FROM pending_state WHERE episodeId IN (:ids)") suspend fun deletePending(ids: List<String>)
    @Query("UPDATE episodes SET listened = :listened, favorite = :favorite, archived = :archived, progressMs = :progress WHERE id = :id") suspend fun applyState(id: String, listened: Boolean, favorite: Boolean, archived: Boolean, progress: Long)

    @Query("$ROW WHERE e.lastPlayedAt > 0 ORDER BY e.lastPlayedAt DESC LIMIT 200") fun history(): Flow<List<EpisodeRow>>
    /** Candidate episodes for smart playlists; rules are applied in Kotlin. */
    @Query("$ROW $ORDER LIMIT 3000") fun allRows(): Flow<List<EpisodeRow>>

    // Playlists
    @Query("SELECT * FROM playlists ORDER BY sortOrder, id") fun playlists(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlists WHERE id = :id") fun playlist(id: Long): Flow<PlaylistEntity?>
    @Query("SELECT COUNT(*) FROM playlists") suspend fun playlistCount(): Int
    @Upsert suspend fun upsertPlaylist(item: PlaylistEntity): Long
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)

    // Bookmarks
    @Insert suspend fun insertBookmark(item: BookmarkEntity): Long
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
    @Query("SELECT b.*, e.title AS episodeTitle, p.title AS podcastTitle, COALESCE(e.artworkUrl, p.artworkUrl) AS artwork FROM bookmarks b LEFT JOIN episodes e ON e.id = b.episodeId LEFT JOIN podcasts p ON p.feedUrl = e.podcastFeedUrl ORDER BY b.createdAt DESC")
    fun bookmarks(): Flow<List<BookmarkRow>>
    @Query("SELECT * FROM bookmarks WHERE episodeId = :episodeId ORDER BY positionMs") fun bookmarksFor(episodeId: String): Flow<List<BookmarkEntity>>

    // Stats
    @Query("INSERT OR IGNORE INTO listening_days(day, listenedMs, savedMs) VALUES(:day, 0, 0)") suspend fun ensureDay(day: String)
    @Query("UPDATE listening_days SET listenedMs = listenedMs + :listened, savedMs = savedMs + :saved WHERE day = :day") suspend fun addToDay(day: String, listened: Long, saved: Long)
    @Query("SELECT COUNT(*) FROM listening_days WHERE listenedMs >= 60000") fun daysListened(): Flow<Int>
    @Query("SELECT COALESCE(SUM(savedMs), 0) FROM listening_days") fun totalSavedMs(): Flow<Long>
    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM listening_days") fun totalListenedMs(): Flow<Long>

    // Queue
    @Query("SELECT * FROM queue ORDER BY position") suspend fun queue(): List<QueueEntry>
    @Query("DELETE FROM queue") suspend fun clearQueue()
    @Insert suspend fun insertQueue(items: List<QueueEntry>)
    @Transaction suspend fun replaceQueue(items: List<QueueEntry>) { clearQueue(); insertQueue(items) }

    // Downloads
    @Upsert suspend fun upsertDownload(item: DownloadEntity)
    @Upsert suspend fun upsertDownloads(items: List<DownloadEntity>)
    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId") suspend fun downloadById(downloadId: Long): DownloadEntity?
    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId") suspend fun downloadFor(episodeId: String): DownloadEntity?
    @Query("DELETE FROM downloads WHERE downloadId = :downloadId") suspend fun deleteDownload(downloadId: Long)
    @Query("SELECT episodeId FROM downloads") fun downloadedIds(): Flow<List<String>>
}
