package com.kanso.podcast.data

import android.app.DownloadManager
import android.content.Context
import androidx.work.*
import com.kanso.podcast.model.PodcastSearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val automation = applicationContext.getSharedPreferences("automation", 0)
        val count = automation.getInt("download_count", 0)
        downloadNewest(loadSubscriptions(), count)
        deleteExpired(automation.getInt("delete_days", 7))
        Result.success()
    }.getOrElse { Result.retry() }

    private fun loadSubscriptions(): List<PodcastSearchResult> {
        val raw = applicationContext.getSharedPreferences("subscriptions", 0).getString("items", null) ?: return emptyList()
        val array = JSONArray(raw)
        return List(array.length()) { i -> array.getJSONObject(i).run { PodcastSearchResult(getString("id"), getString("title"), getString("author"), getString("feed"), optString("artwork").ifBlank { null }, optString("genre", "Podcast")) } }
    }

    private suspend fun downloadNewest(podcasts: List<PodcastSearchResult>, count: Int) = coroutineScope {
        val seenPrefs = applicationContext.getSharedPreferences("auto_downloaded", 0)
        val automation = applicationContext.getSharedPreferences("automation", 0)
        // Ordered (oldest first) so trimming keeps the most recent URLs.
        val seen = LinkedHashSet<String>().apply {
            seenPrefs.getString("urls_ordered", null)?.let { raw -> runCatching { JSONArray(raw).let { a -> for (i in 0 until a.length()) add(a.getString(i)) } } }
            ?: addAll(seenPrefs.getStringSet("urls", emptySet()) ?: emptySet())
        }
        podcasts.map { podcast -> async {
            val podcastCount = automation.getInt("podcast_count_${podcast.feedUrl.hashCode()}", count)
            PodcastDirectory(applicationContext).loadEpisodes(podcast).episodes.take(podcastCount).forEach { episode ->
                if (synchronized(seen) { seen.add(episode.audioUrl) }) {
                    EpisodeDownloads.enqueue(applicationContext, episode.id, episode.audioUrl, episode.title, podcast.title)
                }
            }
        } }.awaitAll()
        seenPrefs.edit().putString("urls_ordered", JSONArray(seen.toList().takeLast(500)).toString()).remove("urls").apply()
    }

    /** Removes downloads older than [days] only for episodes already played and not marked favorite. 0 = never. */
    private fun deleteExpired(days: Int) {
        if (days <= 0) return
        val manager = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val state = applicationContext.getSharedPreferences("episode_state", 0)
        val favorites = state.getStringSet("favorites", emptySet()) ?: emptySet()
        val removable = ((state.getStringSet("listened", emptySet()) ?: emptySet()) - favorites).map { EpisodeDownloads.filePrefix(it) }
        if (removable.isEmpty()) return
        manager.query(DownloadManager.Query()).use { cursor ->
            val id = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val modified = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val uri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (cursor.moveToNext()) {
                val name = cursor.getString(uri)?.substringAfterLast('/') ?: continue
                if (cursor.getLong(modified) < cutoff && removable.any { name.startsWith(it) }) manager.remove(cursor.getLong(id))
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
