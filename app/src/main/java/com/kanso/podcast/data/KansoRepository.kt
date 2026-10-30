package com.kanso.podcast.data

import android.content.Context
import com.kanso.podcast.data.db.*
import com.kanso.podcast.data.db.DownloadEntity
import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.EpisodeMetadata
import com.kanso.podcast.data.db.EpisodeRow
import com.kanso.podcast.data.db.FolderEntity
import com.kanso.podcast.data.db.KansoDatabase
import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.data.db.QueueEntry
import com.kanso.podcast.model.PodcastFeed
import com.kanso.podcast.model.PodcastSearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Single source of truth for library, episodes, queue and downloads, backed by Room. */
data class ImportSummary(val added: Int, val skipped: Int, val failed: Int, val unreachable: Int = 0)

class KansoRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val dao = KansoDatabase.get(app).dao()
    private val directory = PodcastDirectory()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The one-time SharedPreferences import runs before anything reads the database. */
    private val ready = scope.async { LegacyMigration.runIfNeeded(app, dao) }

    suspend fun awaitReady() = ready.await()
    private fun <T> gated(block: () -> Flow<T>): Flow<T> = flow { ready.await(); emitAll(block()) }
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { ready.await(); block() }

    // Library
    val podcastEntities: Flow<List<PodcastEntity>> = gated { dao.podcasts() }
    val podcasts: Flow<List<PodcastSearchResult>> = podcastEntities.map { list -> list.map(PodcastEntity::toModel) }
    val folders: Flow<List<String>> = gated { dao.folders() }
    fun podcast(feedUrl: String): Flow<PodcastEntity?> = gated { dao.podcast(feedUrl) }
    suspend fun podcastList() = io { dao.podcastList() }
    suspend fun subscribe(podcast: PodcastSearchResult) = io { dao.insertPodcasts(listOf(PodcastEntity.from(podcast))) }
    suspend fun unsubscribe(feedUrl: String) = io { dao.deletePodcast(feedUrl) }
    suspend fun import(items: List<PodcastSearchResult>): Int = io {
        val existing = dao.podcastList().map { it.feedUrl }.toSet()
        val fresh = items.filter { it.feedUrl !in existing }
        dao.insertPodcasts(fresh.map { PodcastEntity.from(it) }); fresh.size
    }
    /** Adds OPML entries (keeping folders), then loads each new feed reporting progress as (done, total). */
    suspend fun importOpml(doc: OpmlDocument, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): ImportSummary {
        val fresh = io {
            val existing = dao.podcastList().map { Opml.dedupeKey(it.feedUrl) }.toSet()
            val fresh = doc.entries.filter { Opml.dedupeKey(it.feedUrl) !in existing }
            dao.insertFolders(fresh.mapNotNull { it.folder?.trim()?.takeIf(String::isNotBlank) }.distinct().map(::FolderEntity))
            dao.insertPodcasts(fresh.map { PodcastEntity(it.feedUrl, it.feedUrl.hashCode().toString(), it.title, FeedParser.IMPORTED_AUTHOR, null, "Podcast", it.folder?.trim()?.takeIf(String::isNotBlank)) })
            fresh
        }
        var unreachable = 0
        onProgress(0, fresh.size)
        fresh.forEachIndexed { i, e ->
            if (refresh(PodcastSearchResult(e.feedUrl.hashCode().toString(), e.title, FeedParser.IMPORTED_AUTHOR, e.feedUrl)).isFailure) unreachable++
            onProgress(i + 1, fresh.size)
        }
        return ImportSummary(added = fresh.size, skipped = doc.entries.size - fresh.size + doc.duplicates, failed = doc.invalid, unreachable = unreachable)
    }
    suspend fun createFolder(name: String) = io { dao.insertFolders(listOf(FolderEntity(name.trim()))) }
    suspend fun setFolder(feedUrl: String, folder: String?) = io { dao.setFolder(feedUrl, folder) }
    suspend fun setAutoDownloadCount(feedUrl: String, count: Int?) = io { dao.setAutoDownloadCount(feedUrl, count) }

    // Episodes
    fun episodes(feedUrl: String): Flow<List<EpisodeEntity>> = gated { dao.episodesFor(feedUrl) }
    val inProgress: Flow<List<EpisodeRow>> = gated { dao.inProgress() }
    val newEpisodes: Flow<List<EpisodeRow>> = gated { dao.newEpisodes() }
    val favorites: Flow<List<EpisodeRow>> = gated { dao.favorites() }
    val archived: Flow<List<EpisodeRow>> = gated { dao.archived() }
    val downloadedIds: Flow<Set<String>> = gated { dao.downloadedIds() }.map { it.toSet() }
    suspend fun episode(id: String) = io { dao.episode(id) }
    suspend fun episodeRow(id: String) = io { dao.episodeRow(id) }
    suspend fun episodeList(feedUrl: String) = io { dao.episodeListFor(feedUrl) }
    suspend fun newestUnplayed(feedUrl: String, limit: Int) = io { dao.newestUnplayed(feedUrl, limit) }
    suspend fun searchEpisodes(query: String) = io { dao.searchEpisodes(query) }

    /** Downloads the feed and stores it; on failure the stored episodes stay available offline. */
    suspend fun refresh(podcast: PodcastSearchResult): Result<Int> = runCatching { val feed = directory.fetchEpisodes(podcast); saveFeed(feed); feed.episodes.size }
    /** Refreshes and returns the ids of episodes that were not stored before (empty on a podcast's first load). */
    suspend fun refreshNew(podcast: PodcastSearchResult): Result<List<String>> = runCatching {
        val known = io { dao.episodeCount(podcast.feedUrl) }
        val inserted = saveFeed(directory.fetchEpisodes(podcast))
        if (known == 0) emptyList() else inserted
    }
    suspend fun search(query: String) = directory.search(query)

    suspend fun saveFeed(feed: PodcastFeed): List<String> = io {
        val url = feed.podcast.feedUrl
        feed.podcast.let { dao.fillPodcastMetadata(url, it.title, it.author, it.artworkUrl, FeedParser.IMPORTED_AUTHOR) }
        val entities = feed.episodes.mapIndexed { i, e -> EpisodeEntity(e.id, url, e.title, e.description, e.audioUrl, e.published, e.publishedAt, e.duration, e.artworkUrl, i, chaptersUrl = e.chaptersUrl, transcriptUrl = e.transcriptUrl, transcriptType = e.transcriptType) }
        val rowIds = dao.insertEpisodes(entities)
        dao.updateMetadata(entities.map { EpisodeMetadata(it.id, it.podcastFeedUrl, it.title, it.description, it.audioUrl, it.published, it.publishedAt, it.duration, it.artworkUrl, it.sortIndex, it.chaptersUrl, it.transcriptUrl, it.transcriptType) })
        val inserted = entities.filterIndexed { i, _ -> rowIds.getOrNull(i) != -1L }.map { it.id }
        if (inserted.isNotEmpty()) {
            val pending = inserted.chunked(500).flatMap { dao.pendingFor(it) }
            pending.forEach { dao.applyState(it.episodeId, it.listened, it.favorite, it.archived, it.progressMs) }
            if (pending.isNotEmpty()) dao.deletePending(pending.map { it.episodeId })
        }
        inserted
    }

    suspend fun setProgress(id: String, ms: Long) = io { dao.setProgress(id, ms, System.currentTimeMillis()) }
    suspend fun markFinished(id: String) = io { dao.markFinished(id, app.getSharedPreferences("automation", 0).getBoolean("archive_finished", true)) }
    suspend fun setListened(id: String, value: Boolean) = io { dao.setListened(id, value) }
    suspend fun setFavorite(id: String, value: Boolean) = io { dao.setFavorite(id, value) }
    suspend fun setArchived(id: String, value: Boolean) = io { dao.setArchived(id, value) }
    suspend fun playedNonFavoriteIds() = io { dao.playedNonFavoriteIds() }

    val history: Flow<List<EpisodeRow>> = gated { dao.history() }
    val allRows: Flow<List<EpisodeRow>> = gated { dao.allRows() }

    // Smart playlists
    val playlists: Flow<List<PlaylistEntity>> = flow { ready.await(); seedPlaylists(); emitAll(dao.playlists()) }
    fun playlist(id: Long): Flow<PlaylistEntity?> = gated { dao.playlist(id) }
    suspend fun savePlaylist(p: PlaylistEntity) = io { dao.upsertPlaylist(p) }
    suspend fun deletePlaylist(id: Long) = io { dao.deletePlaylist(id) }
    private suspend fun seedPlaylists() = io {
        val prefs = app.getSharedPreferences("room_migration", 0)
        if (prefs.getBoolean("playlists_seeded", false)) return@io
        if (dao.playlistCount() == 0) {
            dao.upsertPlaylist(PlaylistEntity(name = "En curso", playedState = "IN_PROGRESS", sortOrder = 0))
            dao.upsertPlaylist(PlaylistEntity(name = "Nuevos", playedState = "UNPLAYED", withinDays = 14, sortOrder = 1))
            dao.upsertPlaylist(PlaylistEntity(name = "Para un rato", playedState = "NOT_PLAYED", maxMinutes = 20, sortOrder = 2))
            dao.upsertPlaylist(PlaylistEntity(name = "Descargados", playedState = "NOT_PLAYED", downloadedOnly = true, sortOrder = 3))
        }
        prefs.edit().putBoolean("playlists_seeded", true).apply()
    }

    // Bookmarks
    val bookmarks: Flow<List<BookmarkRow>> = gated { dao.bookmarks() }
    fun bookmarksFor(episodeId: String): Flow<List<BookmarkEntity>> = gated { dao.bookmarksFor(episodeId) }
    suspend fun addBookmark(episodeId: String, positionMs: Long, title: String) = io { dao.insertBookmark(BookmarkEntity(episodeId = episodeId, positionMs = positionMs, title = title)) }
    suspend fun deleteBookmark(id: Long) = io { dao.deleteBookmark(id) }

    // Stats
    val daysListened: Flow<Int> = gated { dao.daysListened() }
    val totalSavedMs: Flow<Long> = gated { dao.totalSavedMs() }
    val totalListenedMs: Flow<Long> = gated { dao.totalListenedMs() }
    suspend fun addListening(listenedMs: Long, savedMs: Long, feedUrl: String? = null) = io {
        val day = java.time.LocalDate.now().toString()
        dao.ensureDay(day); dao.addToDay(day, listenedMs, savedMs)
        if (feedUrl != null && listenedMs > 0) { dao.ensurePodcastListening(feedUrl); dao.addPodcastListening(feedUrl, listenedMs) }
    }
    fun daysSince(since: java.time.LocalDate): Flow<List<ListeningDay>> = gated { dao.daysSince(since.toString()) }
    fun topPodcasts(limit: Int = 5): Flow<List<TopPodcastRow>> = gated { dao.topPodcasts(limit) }

    // Per-podcast settings
    suspend fun podcastForEpisode(episodeId: String) = io { dao.podcastForEpisode(episodeId) }
    suspend fun podcastOnce(feedUrl: String) = io { dao.podcastOnce(feedUrl) }
    suspend fun savePodcastSettings(feedUrl: String, speed: Float?, intro: Int, outro: Int, sort: String, notify: Boolean) =
        io { dao.setPodcastSettings(feedUrl, speed, intro.coerceAtLeast(0), outro.coerceAtLeast(0), sort, notify) }

    // Listening state imported from other apps
    /** Applies state to stored episodes now and parks the rest until their feed is loaded. Returns how many episodes changed. */
    suspend fun applyImportedState(states: List<PendingEpisodeState>): Int = io {
        val existing = states.map { it.episodeId }.chunked(500).flatMap { dao.existingIds(it) }.toSet()
        states.filter { it.episodeId in existing }.forEach { dao.applyState(it.episodeId, it.listened, it.favorite, it.archived, it.progressMs) }
        dao.insertPending(states.filter { it.episodeId !in existing })
        states.size
    }

    // Queue
    suspend fun queue() = io { dao.queue() }
    suspend fun replaceQueue(items: List<QueueEntry>) = io { dao.replaceQueue(items) }

    // Downloads
    suspend fun recordDownload(item: DownloadEntity) = io { dao.upsertDownload(item) }
    suspend fun downloadById(id: Long) = io { dao.downloadById(id) }
    suspend fun removeDownload(id: Long) = io { dao.deleteDownload(id) }
    suspend fun downloadRows() = io { dao.downloadRows() }
    val downloadsFlow: Flow<List<DownloadEntity>> = gated { dao.downloadsFlow() }

    companion object {
        @Volatile private var instance: KansoRepository? = null
        fun get(context: Context): KansoRepository = instance ?: synchronized(this) { instance ?: KansoRepository(context).also { instance = it } }
    }
}
