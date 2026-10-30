package com.kanso.podcast.data

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import java.io.File

/** One downloaded episode on disk. */
data class StorageItem(
    val episodeId: String,
    val downloadId: Long,
    val feedUrl: String?,
    val podcastTitle: String,
    val bytes: Long,
    val listened: Boolean,
    val favorite: Boolean,
    val publishedAt: Long,
)

data class PodcastUsage(val feedUrl: String?, val title: String, val bytes: Long, val episodes: Int)
data class StorageReport(val items: List<StorageItem>, val limitBytes: Long) {
    val totalBytes get() = items.sumOf { it.bytes }
    val byPodcast: List<PodcastUsage> get() = items.groupBy { it.feedUrl }.map { (feed, list) -> PodcastUsage(feed, list.first().podcastTitle, list.sumOf { it.bytes }, list.size) }.sortedByDescending { it.bytes }
    val playedBytes get() = items.filter(KansoStorage::cleanable).sumOf { it.bytes }
    val overLimit get() = limitBytes > 0 && totalBytes > limitBytes
}

/** Space used by downloads, a size limit and one-tap cleanup. Favorites and unplayed episodes are never removed automatically. */
object KansoStorage {
    const val GB = 1024L * 1024 * 1024
    private const val PREFS = "automation"
    private const val KEY_LIMIT = "storage_limit_gb"

    fun limitGb(context: Context): Float = context.getSharedPreferences(PREFS, 0).getFloat(KEY_LIMIT, 0f)
    fun setLimitGb(context: Context, gb: Float) = context.getSharedPreferences(PREFS, 0).edit().putFloat(KEY_LIMIT, gb.coerceAtLeast(0f)).apply()

    fun cleanable(item: StorageItem) = item.listened && !item.favorite

    /** Oldest played, non-favorite downloads to delete until [items] fit in [limitBytes]. */
    fun pickForLimit(items: List<StorageItem>, limitBytes: Long): List<StorageItem> {
        if (limitBytes <= 0) return emptyList()
        var total = items.sumOf { it.bytes }
        if (total <= limitBytes) return emptyList()
        val picked = mutableListOf<StorageItem>()
        for (item in items.filter(::cleanable).sortedBy { it.publishedAt }) {
            if (total <= limitBytes) break
            picked += item; total -= item.bytes
        }
        return picked
    }

    suspend fun report(context: Context): StorageReport {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)
        val rows = KansoRepository.get(context).downloadRows()
        val items = rows.mapNotNull { row ->
            val file = dir?.let { File(it, row.download.fileName) }?.takeIf { it.exists() } ?: EpisodeDownloads.localFile(context, row.download.episodeId) ?: return@mapNotNull null
            StorageItem(row.download.episodeId, row.download.downloadId, row.podcastFeedUrl, row.podcastTitle ?: "Sin podcast", file.length(),
                row.listened ?: false, row.favorite ?: false, row.publishedAt ?: 0)
        }
        return StorageReport(items, (limitGb(context) * GB).toLong())
    }

    suspend fun delete(context: Context, items: List<StorageItem>): Long {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val repo = KansoRepository.get(context)
        var freed = 0L
        items.forEach { item ->
            runCatching { manager.remove(item.downloadId) }
            EpisodeDownloads.localFile(context, item.episodeId)?.delete()
            repo.removeDownload(item.downloadId); freed += item.bytes
        }
        return freed
    }

    suspend fun cleanupPlayed(context: Context): Long = report(context).let { r -> delete(context, r.items.filter(::cleanable)) }
    suspend fun deletePodcast(context: Context, feedUrl: String?): Long = report(context).let { r -> delete(context, r.items.filter { it.feedUrl == feedUrl }) }
    /** Enforces the limit; returns true when downloads still exceed it (new automatic downloads are then skipped). */
    suspend fun enforceLimit(context: Context): Boolean {
        val r = report(context)
        val freed = delete(context, pickForLimit(r.items, r.limitBytes))
        return r.limitBytes > 0 && r.totalBytes - freed > r.limitBytes
    }

    fun format(bytes: Long): String = when {
        bytes >= GB -> String.format(java.util.Locale.US, "%.1f GB", bytes / GB.toDouble())
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / 1024} KB"
    }
}
