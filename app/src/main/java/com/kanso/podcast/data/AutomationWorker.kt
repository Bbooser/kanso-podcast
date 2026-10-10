package com.kanso.podcast.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
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
        val seen = seenPrefs.getStringSet("urls", emptySet())?.toMutableSet() ?: mutableSetOf()
        podcasts.map { podcast -> async {
            val podcastCount = automation.getInt("podcast_count_${podcast.feedUrl.hashCode()}", count)
            PodcastDirectory(applicationContext).loadEpisodes(podcast).episodes.take(podcastCount).forEach { episode ->
                if (seen.add(episode.audioUrl)) {
                    val request = DownloadManager.Request(Uri.parse(episode.audioUrl)).setTitle(episode.title).setDescription(podcast.title)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(applicationContext, Environment.DIRECTORY_PODCASTS, "${episode.id.hashCode()}.mp3")
                    (applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                }
            }
        } }.awaitAll()
        seenPrefs.edit().putStringSet("urls", seen.toList().takeLast(500).toSet()).apply()
    }

    private fun deleteExpired(days: Int) {
        if (days <= 0) return
        val manager = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val protectedNames = (applicationContext.getSharedPreferences("episode_state", 0).getStringSet("favorites", emptySet()) ?: emptySet()).map { "${it.hashCode()}.mp3" }.toSet()
        manager.query(DownloadManager.Query()).use { cursor ->
            val id = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val modified = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val uri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (cursor.moveToNext()) if (cursor.getLong(modified) < cutoff && protectedNames.none { cursor.getString(uri)?.endsWith(it) == true }) manager.remove(cursor.getLong(id))
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
