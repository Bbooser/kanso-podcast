package com.kanso.podcast.data

import android.app.DownloadManager
import android.content.Context
import androidx.work.*
import com.kanso.podcast.data.db.PodcastEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val repo = KansoRepository.get(applicationContext)

    override suspend fun doWork(): Result = runCatching {
        val automation = applicationContext.getSharedPreferences("automation", 0)
        val podcasts = repo.podcastList()
        val fresh = coroutineScope { podcasts.map { p -> async { p to repo.refreshNew(p.toModel()).getOrDefault(emptyList()) } }.awaitAll() }
        fresh.filter { (p, ids) -> p.notifyNewEpisodes && ids.isNotEmpty() }.forEach { (p, ids) ->
            val titles = ids.mapNotNull { repo.episode(it) }.sortedByDescending { it.publishedAt }.map { it.title }
            NewEpisodeNotifier.notify(applicationContext, p.feedUrl, p.title, titles)
        }
        queueNew(fresh, automation.getString("auto_queue", null))
        deleteExpired(automation.getInt("delete_days", 7))
        val overLimit = KansoStorage.enforceLimit(applicationContext)
        if (!overLimit) downloadNewest(podcasts, automation.getInt("download_count", 0))
        Result.success()
    }.getOrElse { Result.retry() }

    /** Hands new episodes to the playback service, which owns the live queue (see KansoPlaybackService.consumeAutoQueue). */
    private suspend fun queueNew(fresh: List<Pair<PodcastEntity, List<String>>>, global: String?) {
        val pending = fresh.flatMap { (p, ids) ->
            val mode = AutoQueueMode.resolve(p.autoQueue, global)
            if (mode == AutoQueueMode.NONE) emptyList() else ids.map { com.kanso.podcast.data.db.AutoQueuePending(it, mode == AutoQueueMode.START) }
        }
        if (pending.isNotEmpty()) repo.addAutoQueue(pending)
    }

    private suspend fun downloadNewest(podcasts: List<PodcastEntity>, globalCount: Int) {
        val seenPrefs = applicationContext.getSharedPreferences("auto_downloaded", 0)
        // Ordered (oldest first) so trimming keeps the most recent URLs.
        val seen = LinkedHashSet<String>().apply {
            seenPrefs.getString("urls_ordered", null)?.let { raw -> runCatching { JSONArray(raw).let { a -> for (i in 0 until a.length()) add(a.getString(i)) } } }
                ?: addAll(seenPrefs.getStringSet("urls", emptySet()) ?: emptySet())
        }
        podcasts.forEach { podcast ->
            val count = podcast.autoDownloadCount ?: globalCount
            if (count <= 0) return@forEach
            repo.newestUnplayed(podcast.feedUrl, count).forEach { row ->
                val episode = row.episode
                if (seen.add(episode.audioUrl)) EpisodeDownloads.enqueue(applicationContext, episode.id, episode.audioUrl, episode.title, podcast.title)
            }
        }
        seenPrefs.edit().putString("urls_ordered", JSONArray(seen.toList().takeLast(500)).toString()).remove("urls").apply()
    }

    /** Removes downloads older than [days] only for episodes already played and not marked favorite. 0 = never. */
    private suspend fun deleteExpired(days: Int) {
        if (days <= 0) return
        val manager = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val removable = repo.playedNonFavoriteIds().map { EpisodeDownloads.filePrefix(it) }
        if (removable.isEmpty()) return
        manager.query(DownloadManager.Query()).use { cursor ->
            val id = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val modified = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val uri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (cursor.moveToNext()) {
                val name = cursor.getString(uri)?.substringAfterLast('/') ?: continue
                if (cursor.getLong(modified) < cutoff && removable.any { name.startsWith(it) }) { val downloadId = cursor.getLong(id); manager.remove(downloadId); repo.removeDownload(downloadId) }
            }
        }
    }
    companion object {
        fun schedule(context: Context, wifiOnly: Boolean) {
            val constraints = Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<AutomationWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("kanso_feed_automation", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
