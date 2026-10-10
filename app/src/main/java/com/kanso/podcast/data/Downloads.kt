package com.kanso.podcast.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.kanso.podcast.data.db.DownloadEntity
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

    fun playableUri(context: Context, episodeId: String, audioUrl: String): Uri =
        localFile(context, episodeId)?.let(Uri::fromFile) ?: Uri.parse(audioUrl)

    private fun wifiOnly(context: Context) = context.getSharedPreferences("automation", 0).getBoolean("wifi_only", true)

    private fun request(context: Context, url: String, name: String, title: String, description: String?): DownloadManager.Request {
        val wifiOnly = wifiOnly(context)
        return DownloadManager.Request(Uri.parse(url)).setTitle(title)
            .apply { if (description != null) setDescription(description) }
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, name)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(!wifiOnly)
    }

    suspend fun enqueue(context: Context, episodeId: String, audioUrl: String, title: String, description: String?): Long {
        val name = fileName(episodeId, audioUrl)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request(context, audioUrl, name, title, description))
        KansoRepository.get(context).recordDownload(DownloadEntity(episodeId, id, name, audioUrl, title))
        return id
    }

    /** Re-enqueues a failed download keeping the same file name, so playback can find it later. */
    suspend fun retry(context: Context, downloadId: Long, sourceUrl: String, title: String) {
        val repo = KansoRepository.get(context)
        val known = repo.downloadById(downloadId)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.remove(downloadId); repo.removeDownload(downloadId)
        val name = known?.fileName ?: "${sourceUrl.hashCode()}.${extensionFor(sourceUrl)}"
        val newId = manager.enqueue(request(context, sourceUrl, name, title, null))
        if (known != null) repo.recordDownload(known.copy(downloadId = newId))
    }

    suspend fun remove(context: Context, downloadId: Long) {
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
        KansoRepository.get(context).removeDownload(downloadId)
    }
}
