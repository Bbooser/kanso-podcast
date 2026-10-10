package com.kanso.podcast.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.kanso.podcast.model.FeedEpisode
import java.io.File
import java.net.URI

/** Single place for download naming, enqueueing and lookup, shared by the UI and the worker. */
object EpisodeDownloads {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "mp4", "m4b", "wav", "flac")

    fun extensionFor(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return if (ext in audioExtensions) ext else "mp3"
    }

    fun fileName(episodeId: String, audioUrl: String) = "${episodeId.hashCode()}.${extensionFor(audioUrl)}"

    fun filePrefix(episodeId: String) = "${episodeId.hashCode()}."

    /** Downloaded file for an episode, if any (also finds files saved with a legacy .mp3 name). */
    fun localFile(context: Context, episodeId: String): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) ?: return null
        val prefix = filePrefix(episodeId)
        return dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.length() > 0 }
    }

    fun playableUri(context: Context, episode: FeedEpisode): Uri =
        localFile(context, episode.id)?.let(Uri::fromFile) ?: Uri.parse(episode.audioUrl)

    fun wifiOnly(context: Context) = context.getSharedPreferences("automation", 0).getBoolean("wifi_only", true)

    fun enqueue(context: Context, episodeId: String, audioUrl: String, title: String, description: String?): Long {
        val name = fileName(episodeId, audioUrl)
        context.getSharedPreferences("download_names", 0).edit().putString(audioUrl, name).apply()
        val wifiOnly = wifiOnly(context)
        val request = DownloadManager.Request(Uri.parse(audioUrl)).setTitle(title)
            .apply { if (description != null) setDescription(description) }
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, name)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(!wifiOnly)
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    /** Re-enqueues a failed download keeping the same file name, so playback can find it later. */
    fun retry(context: Context, downloadId: Long, sourceUrl: String, title: String) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.remove(downloadId)
        val name = context.getSharedPreferences("download_names", 0).getString(sourceUrl, null)
            ?: "${sourceUrl.hashCode()}.${extensionFor(sourceUrl)}"
        val wifiOnly = wifiOnly(context)
        val request = DownloadManager.Request(Uri.parse(sourceUrl)).setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, name)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(!wifiOnly)
        manager.enqueue(request)
    }
}
