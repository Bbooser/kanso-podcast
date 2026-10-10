package com.kanso.podcast.data

import android.app.DownloadManager
import android.content.Context
import android.util.Log
import com.kanso.podcast.data.db.DownloadEntity
import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.FolderEntity
import com.kanso.podcast.data.db.KansoDao
import com.kanso.podcast.data.db.PendingEpisodeState
import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.data.db.QueueEntry
import org.json.JSONArray

/**
 * One-time import of the 0.1.x SharedPreferences data (subscriptions, folders, feed cache, episode state,
 * progress, downloads and queue) into Room. Inserts are idempotent, so a failed run is simply retried on the
 * next launch. The old preferences are left untouched as a safety net.
 */
object LegacyMigration {
    private const val TAG = "KansoMigration"

    suspend fun runIfNeeded(context: Context, dao: KansoDao) {
        val flag = context.getSharedPreferences("room_migration", 0)
        if (flag.getBoolean("done_v1", false)) return
        runCatching { migrate(context, dao) }
            .onSuccess { flag.edit().putBoolean("done_v1", true).apply() }
            .onFailure { Log.w(TAG, "Legacy migration failed, will retry", it) }
    }

    private suspend fun migrate(context: Context, dao: KansoDao) {
        fun prefs(name: String) = context.getSharedPreferences(name, 0)
        val folderPrefs = prefs("folders"); val automation = prefs("automation")

        // Podcasts + folders + per-podcast download count
        val subscriptions = prefs("subscriptions").getString("items", null)?.let { raw ->
            val array = JSONArray(raw)
            List(array.length()) { i -> array.getJSONObject(i).run {
                val feed = getString("feed"); val hash = feed.hashCode()
                PodcastEntity(feed, getString("id"), getString("title"), getString("author"), optString("artwork").ifBlank { null }, optString("genre", "Podcast"),
                    folder = folderPrefs.getString("podcast_$hash", null),
                    autoDownloadCount = if (automation.contains("podcast_count_$hash")) automation.getInt("podcast_count_$hash", 0) else null)
            } }
        } ?: emptyList()
        dao.insertPodcasts(subscriptions)
        val folders = (folderPrefs.getStringSet("names", emptySet()) ?: emptySet()) + subscriptions.mapNotNull { it.folder }
        dao.insertFolders(folders.map(::FolderEntity))

        // Episode state
        val state = prefs("episode_state")
        val favorites = state.getStringSet("favorites", emptySet()) ?: emptySet()
        val listened = state.getStringSet("listened", emptySet()) ?: emptySet()
        val archived = state.getStringSet("archived", emptySet()) ?: emptySet()
        val progress = prefs("progress").all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

        // Cached episodes
        val cache = prefs("feed_cache")
        val known = mutableMapOf<String, EpisodeEntity>()
        subscriptions.forEach { podcast ->
            val raw = cache.getString("feed_${podcast.feedUrl.hashCode()}", null) ?: return@forEach
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@forEach
            for (i in 0 until array.length()) array.getJSONObject(i).run {
                val id = getString("id")
                known[id] = EpisodeEntity(id, podcast.feedUrl, getString("title"), optString("description"), getString("audio"), optString("published"), 0L,
                    optString("duration", "—"), optString("artwork").ifBlank { null }, i,
                    listened = id in listened, favorite = id in favorites, archived = id in archived, progressMs = progress[id] ?: 0L)
            }
        }
        dao.insertEpisodes(known.values.toList())
        val orphanIds = (favorites + listened + archived + progress.keys) - known.keys
        dao.insertPending(orphanIds.map { PendingEpisodeState(it, it in listened, it in favorites, it in archived, progress[it] ?: 0L) })

        // Downloads tracked by the system DownloadManager, matched by the "<hash(id)>." file name
        val idsByPrefix = (known.keys + orphanIds).associateBy { EpisodeDownloads.filePrefix(it) }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloads = mutableListOf<DownloadEntity>()
        runCatching {
            manager.query(DownloadManager.Query()).use { c ->
                val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID); val local = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val src = c.getColumnIndex(DownloadManager.COLUMN_URI); val title = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                while (c.moveToNext()) {
                    val name = c.getString(local)?.substringAfterLast('/') ?: continue
                    val episodeId = idsByPrefix[name.substringBefore('.') + "."] ?: continue
                    downloads += DownloadEntity(episodeId, c.getLong(idCol), name, c.getString(src).orEmpty(), c.getString(title) ?: "Episodio")
                }
            }
        }
        if (downloads.isNotEmpty()) dao.upsertDownloads(downloads)

        // Playback queue (index/position/speed stay in the small "playback_session" preferences)
        prefs("playback_session").getString("queue", null)?.let { raw ->
            val array = JSONArray(raw)
            val entries = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { o ->
                val uri = o.optString("uri"); if (uri.isBlank()) null
                else QueueEntry(i, o.optString("id", uri), uri, o.optString("title"), o.optString("artist"), o.optString("artwork").ifBlank { null })
            } }
            if (entries.isNotEmpty()) dao.replaceQueue(entries.mapIndexed { i, e -> e.copy(position = i) })
        }
        Log.i(TAG, "Migrated ${subscriptions.size} podcasts, ${known.size} episodes, ${orphanIds.size} pending states, ${downloads.size} downloads")
    }
}
