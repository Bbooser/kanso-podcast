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
    @Query("UPDATE podcasts SET isFavorite = :value WHERE feedUrl = :feedUrl") suspend fun setPodcastFavorite(feedUrl: String, value: Boolean)
    @Query("UPDATE podcasts SET autoArchive = :value WHERE feedUrl = :feedUrl") suspend fun setPodcastAutoArchive(feedUrl: String, value: Boolean?)
    /** "New" = unplayed, untouched, not archived and published since the podcast was followed. */
    @Query("SELECT p.feedUrl AS feedUrl, MAX(e.publishedAt) AS latestEpisodeAt, COALESCE(SUM(CASE WHEN e.listened = 0 AND e.archived = 0 AND e.progressMs = 0 AND e.publishedAt >= p.subscribedAt THEN 1 ELSE 0 END), 0) AS newEpisodes FROM podcasts p LEFT JOIN episodes e ON e.podcastFeedUrl = p.feedUrl GROUP BY p.feedUrl")
    fun podcastLibraryStats(): Flow<List<PodcastLibraryStat>>
    @Query("UPDATE podcasts SET autoDownloadCount = :count WHERE feedUrl = :feedUrl") suspend fun setAutoDownloadCount(feedUrl: String, count: Int?)
    @Query("UPDATE podcasts SET playbackSpeed = :speed, skipIntroSec = :intro, skipOutroSec = :outro, episodeSort = :sort, notifyNewEpisodes = :notify WHERE feedUrl = :feedUrl")
    suspend fun setPodcastSettings(feedUrl: String, speed: Float?, intro: Int, outro: Int, sort: String, notify: Boolean)
    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl") suspend fun podcastOnce(feedUrl: String): PodcastEntity?
    @Query("SELECT p.* FROM podcasts p JOIN episodes e ON e.podcastFeedUrl = p.feedUrl WHERE e.id = :episodeId") suspend fun podcastForEpisode(episodeId: String): PodcastEntity?
    @Query("UPDATE podcasts SET title = CASE WHEN title = '' OR title = feedUrl THEN :title ELSE title END, author = CASE WHEN author = '' OR author = :placeholder THEN :author ELSE author END, artworkUrl = COALESCE(artworkUrl, :artwork), genre = CASE WHEN (genre = '' OR genre = 'Podcast') AND :genre <> '' THEN :genre ELSE genre END WHERE feedUrl = :feedUrl")
    suspend fun fillPodcastMetadata(feedUrl: String, title: String, author: String, artwork: String?, placeholder: String, genre: String)
    @Query("SELECT COUNT(*) FROM episodes WHERE podcastFeedUrl = :feedUrl") suspend fun episodeCount(feedUrl: String): Int
    @Query("SELECT name FROM folders ORDER BY name COLLATE NOCASE") fun folders(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFolders(items: List<FolderEntity>)
    @Query("DELETE FROM folders WHERE name = :name") suspend fun deleteFolderRow(name: String)
    @Query("UPDATE podcasts SET folder = :to WHERE folder = :from") suspend fun reassignFolder(from: String, to: String?)
    /** Renaming onto an existing name merges both folders. */
    @Transaction suspend fun renameFolder(from: String, to: String) { if (from == to) return; insertFolders(listOf(FolderEntity(to))); reassignFolder(from, to); deleteFolderRow(from) }
    /** Podcasts go back to the root; nothing is unsubscribed. */
    @Transaction suspend fun deleteFolder(name: String) { reassignFolder(name, null); deleteFolderRow(name) }
    @Query("SELECT * FROM folders") suspend fun folderRows(): List<FolderEntity>
    @Query("DELETE FROM folders") suspend fun deleteAllFolders()
    /** Applies a folder layout (organize by category, or its undo) atomically: [folders] becomes the whole folder list. */
    @Transaction suspend fun applyFolderLayout(folders: List<String>, assignments: Map<String, String?>) {
        deleteAllFolders(); insertFolders(folders.map(::FolderEntity)); assignments.forEach { (feed, folder) -> setFolder(feed, folder) }
    }
    @Query("UPDATE podcasts SET folder = :folder WHERE feedUrl IN (:feeds)") suspend fun setFolders(feeds: List<String>, folder: String?)
    @Query("UPDATE podcasts SET isFavorite = :value WHERE feedUrl IN (:feeds)") suspend fun setFavorites(feeds: List<String>, value: Boolean)
    @Transaction suspend fun groupIntoFolder(name: String, feedUrls: List<String>) { insertFolders(listOf(FolderEntity(name))); feedUrls.forEach { setFolder(it, name) } }

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
    /** [match] is an FTS4 MATCH expression, see [com.kanso.podcast.data.LibrarySearch.ftsQuery]. */
    @Query("$ROW WHERE e.rowid IN (SELECT rowid FROM episodes_fts WHERE episodes_fts MATCH :match) $ORDER LIMIT 100") suspend fun searchLibrary(match: String): List<EpisodeRow>

    // Backup
    @Query("SELECT * FROM episodes WHERE listened = 1 OR favorite = 1 OR archived = 1 OR progressMs > 0") suspend fun episodesWithState(): List<EpisodeEntity>
    @Query("SELECT * FROM playlists ORDER BY sortOrder, id") suspend fun playlistList(): List<PlaylistEntity>
    @Query("SELECT * FROM bookmarks ORDER BY id") suspend fun bookmarkList(): List<BookmarkEntity>
    @Upsert suspend fun upsertPodcasts(items: List<PodcastEntity>)
    @Insert suspend fun insertPlaylists(items: List<PlaylistEntity>)
    @Insert suspend fun insertBookmarks(items: List<BookmarkEntity>)
    @Query("UPDATE episodes SET listened = :listened, favorite = :favorite, archived = :archived, progressMs = :progressMs, lastPlayedAt = MAX(lastPlayedAt, :lastPlayedAt) WHERE id = :id")
    suspend fun applyEpisodeState(id: String, listened: Boolean, favorite: Boolean, archived: Boolean, progressMs: Long, lastPlayedAt: Long): Int
    @Query("DELETE FROM podcasts") suspend fun deleteAllPodcasts()
    @Query("DELETE FROM episodes") suspend fun deleteAllEpisodes()
    @Query("DELETE FROM playlists") suspend fun deleteAllPlaylists()
    @Query("DELETE FROM bookmarks") suspend fun deleteAllBookmarks()
    @Query("DELETE FROM pending_state") suspend fun deleteAllPending()

    /** Restores a backup in one transaction. Replace wipes the library first; merge keeps what is here and lets the backup win on conflicts. */
    @Transaction suspend fun restoreBackup(b: com.kanso.podcast.data.KansoBackup, replace: Boolean) {
        if (replace) { deleteAllPodcasts(); deleteAllEpisodes(); deleteAllFolders(); deleteAllPlaylists(); deleteAllBookmarks(); deleteAllPending() }
        upsertPodcasts(b.podcasts)
        insertFolders(b.folders.map(::FolderEntity) + b.podcasts.mapNotNull { it.folder }.map(::FolderEntity))
        val missing = b.episodes.filter { s -> applyEpisodeState(s.episodeId, s.listened, s.favorite, s.archived, s.progressMs, s.lastPlayedAt) == 0 }
        // Episodes not downloaded from the feed yet get their state when the feed refreshes.
        insertPending(missing.map { PendingEpisodeState(it.episodeId, it.listened, it.favorite, it.archived, it.progressMs) })
        val names = if (replace) emptySet() else playlistList().map { it.name }.toSet()
        insertPlaylists(b.playlists.filter { it.name !in names }.map { it.copy(id = 0) })
        val marks = if (replace) emptySet() else bookmarkList().map { it.episodeId to it.positionMs }.toSet()
        insertBookmarks(b.bookmarks.filter { (it.episodeId to it.positionMs) !in marks }.map { it.copy(id = 0) })
    }

    // Auto-queue
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAutoQueue(items: List<AutoQueuePending>)
    @Query("SELECT * FROM auto_queue_pending ORDER BY addedAt") suspend fun autoQueuePending(): List<AutoQueuePending>
    @Query("SELECT * FROM auto_queue_pending ORDER BY addedAt") fun autoQueuePendingFlow(): Flow<List<AutoQueuePending>>
    @Query("DELETE FROM auto_queue_pending WHERE episodeId IN (:ids)") suspend fun deleteAutoQueue(ids: List<String>)
    @Query("UPDATE podcasts SET autoQueue = :value WHERE feedUrl = :feedUrl") suspend fun setPodcastAutoQueue(feedUrl: String, value: String?)

    @Query("UPDATE episodes SET progressMs = :ms, lastPlayedAt = :now WHERE id = :id") suspend fun setProgress(id: String, ms: Long, now: Long)
    @Query("UPDATE episodes SET listened = 1, progressMs = 0, archived = CASE WHEN :archive AND favorite = 0 THEN 1 ELSE archived END WHERE id = :id") suspend fun markFinished(id: String, archive: Boolean)
    @Query("UPDATE episodes SET listened = :value, progressMs = CASE WHEN :value THEN 0 ELSE progressMs END, archived = CASE WHEN :value THEN archived ELSE 0 END WHERE id = :id") suspend fun setListened(id: String, value: Boolean)
    @Query("UPDATE episodes SET favorite = :value WHERE id = :id") suspend fun setFavorite(id: String, value: Boolean)
    @Query("UPDATE episodes SET archived = :value WHERE id = :id") suspend fun setArchived(id: String, value: Boolean)
    @Query("UPDATE episodes SET archived = :value WHERE id IN (:ids)") suspend fun setArchivedIds(ids: List<String>, value: Boolean)
    @Query("UPDATE episodes SET listened = 1, progressMs = 0 WHERE id IN (:ids)") suspend fun markListenedIds(ids: List<String>)
    /** Bulk actions from multi-select, chunked under SQLite's variable limit, all in one transaction. */
    @Transaction suspend fun bulkEpisodes(ids: List<String>, archive: Boolean?, markListened: Boolean) {
        ids.chunked(500).forEach { chunk -> if (markListened) markListenedIds(chunk); if (archive != null) setArchivedIds(chunk, archive) }
    }
    @Query("SELECT id FROM episodes WHERE listened = 1 AND favorite = 0") suspend fun playedNonFavoriteIds(): List<String>

    // Pending state from the legacy migration
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPending(items: List<PendingEpisodeState>)
    @Query("SELECT * FROM pending_state WHERE episodeId IN (:ids)") suspend fun pendingFor(ids: List<String>): List<PendingEpisodeState>
    @Query("DELETE FROM pending_state WHERE episodeId IN (:ids)") suspend fun deletePending(ids: List<String>)
    @Query("UPDATE episodes SET listened = :listened, favorite = :favorite, archived = :archived, progressMs = :progress WHERE id = :id") suspend fun applyState(id: String, listened: Boolean, favorite: Boolean, archived: Boolean, progress: Long)

    @Query("SELECT id FROM episodes WHERE id IN (:ids)") suspend fun existingIds(ids: List<String>): List<String>
    @Query("SELECT id FROM episodes WHERE audioUrl = :audioUrl LIMIT 1") suspend fun idForAudio(audioUrl: String): String?
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
    @Query("SELECT * FROM listening_days WHERE day >= :since ORDER BY day") fun daysSince(since: String): Flow<List<ListeningDay>>
    @Query("INSERT OR IGNORE INTO podcast_listening(feedUrl, listenedMs) VALUES(:feedUrl, 0)") suspend fun ensurePodcastListening(feedUrl: String)
    @Query("UPDATE podcast_listening SET listenedMs = listenedMs + :ms WHERE feedUrl = :feedUrl") suspend fun addPodcastListening(feedUrl: String, ms: Long)
    @Query("SELECT p.feedUrl, p.id, p.title, p.genre, COALESCE(l.listenedMs, 0) AS listenedMs, p.isFavorite AS favorite, COALESCE((SELECT MAX(e.lastPlayedAt) FROM episodes e WHERE e.podcastFeedUrl = p.feedUrl), 0) AS lastPlayedAt FROM podcasts p LEFT JOIN podcast_listening l ON l.feedUrl = p.feedUrl")
    suspend fun listeningSignals(): List<com.kanso.podcast.data.ListeningSignal>
    @Query("SELECT l.feedUrl, p.title, p.artworkUrl, l.listenedMs FROM podcast_listening l LEFT JOIN podcasts p ON p.feedUrl = l.feedUrl WHERE l.listenedMs > 0 ORDER BY l.listenedMs DESC LIMIT :limit")
    fun topPodcasts(limit: Int): Flow<List<TopPodcastRow>>

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
    @Query("SELECT d.*, e.podcastFeedUrl AS podcastFeedUrl, p.title AS podcastTitle, e.listened AS listened, e.favorite AS favorite, e.publishedAt AS publishedAt FROM downloads d LEFT JOIN episodes e ON e.id = d.episodeId LEFT JOIN podcasts p ON p.feedUrl = e.podcastFeedUrl")
    suspend fun downloadRows(): List<DownloadRow>
    @Query("SELECT * FROM downloads") fun downloadsFlow(): Flow<List<DownloadEntity>>
}
