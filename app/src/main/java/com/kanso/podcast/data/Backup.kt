package com.kanso.podcast.data

import android.content.Context
import com.kanso.podcast.data.db.BookmarkEntity
import com.kanso.podcast.data.db.KansoDao
import com.kanso.podcast.data.db.PlaylistEntity
import com.kanso.podcast.data.db.PodcastEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Listening state of one episode, keyed by its stable id. */
data class EpisodeState(val episodeId: String, val listened: Boolean, val favorite: Boolean, val archived: Boolean, val progressMs: Long, val lastPlayedAt: Long)

/**
 * Contents of a .kansobackup file: a zip with one versioned JSON document. No accounts, tokens or downloads are included;
 * only feed URLs, settings and listening state.
 */
data class KansoBackup(
    val createdAt: Long,
    val appVersion: String,
    val podcasts: List<PodcastEntity>,
    val folders: List<String>,
    val episodes: List<EpisodeState>,
    val bookmarks: List<BookmarkEntity>,
    val playlists: List<PlaylistEntity>,
    /** SharedPreferences file -> key -> value (Boolean, Int, Long, Float or String). */
    val settings: Map<String, Map<String, Any>>,
) {
    fun write(out: OutputStream) = ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry(ENTRY)); zip.write(toJson().toString().toByteArray()); zip.closeEntry()
    }

    fun toJson(): JSONObject = JSONObject().put("format", FORMAT).put("schema", SCHEMA).put("createdAt", createdAt).put("appVersion", appVersion)
        .put("podcasts", JSONArray(podcasts.map { p -> JSONObject().put("feedUrl", p.feedUrl).put("id", p.id).put("title", p.title).put("author", p.author)
            .putOpt("artworkUrl", p.artworkUrl).put("genre", p.genre).putOpt("folder", p.folder).putOpt("autoDownloadCount", p.autoDownloadCount)
            .put("subscribedAt", p.subscribedAt).putOpt("playbackSpeed", p.playbackSpeed?.toDouble()).put("skipIntroSec", p.skipIntroSec).put("skipOutroSec", p.skipOutroSec)
            .put("episodeSort", p.episodeSort).put("notifyNewEpisodes", p.notifyNewEpisodes).put("isFavorite", p.isFavorite)
            .putOpt("autoArchive", p.autoArchive).putOpt("autoQueue", p.autoQueue) }))
        .put("folders", JSONArray(folders))
        .put("episodes", JSONArray(episodes.map { e -> JSONObject().put("id", e.episodeId).put("listened", e.listened).put("favorite", e.favorite)
            .put("archived", e.archived).put("progressMs", e.progressMs).put("lastPlayedAt", e.lastPlayedAt) }))
        .put("bookmarks", JSONArray(bookmarks.map { b -> JSONObject().put("episodeId", b.episodeId).put("positionMs", b.positionMs).put("title", b.title).put("createdAt", b.createdAt) }))
        .put("playlists", JSONArray(playlists.map { p -> JSONObject().put("name", p.name).put("podcastFeeds", p.podcastFeeds).put("playedState", p.playedState)
            .put("downloadedOnly", p.downloadedOnly).putOpt("minMinutes", p.minMinutes).putOpt("maxMinutes", p.maxMinutes).putOpt("withinDays", p.withinDays)
            .put("includeArchived", p.includeArchived).put("sortOrder", p.sortOrder) }))
        .put("settings", JSONObject().also { root -> settings.forEach { (file, values) ->
            root.put(file, JSONObject().also { o -> values.forEach { (k, v) -> o.put(k, JSONObject().put("t", typeOf(v)).put("v", if (v is Float) v.toDouble() else v)) } }) } })

    companion object {
        const val FORMAT = "kansobackup"
        const val SCHEMA = 1
        const val EXTENSION = "kansobackup"
        private const val ENTRY = "kanso-backup.json"
        /** Only user-facing preferences; playback session, caches and migration flags stay out. */
        val SETTINGS_FILES = listOf("appearance", "automation", "playback_effects")

        fun read(input: InputStream): KansoBackup = ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == ENTRY } ?: throw BackupException("El archivo no es un respaldo de Kanso")
            fromJson(JSONObject(zip.readBytes().decodeToString()))
        }

        fun fromJson(o: JSONObject): KansoBackup {
            if (o.optString("format") != FORMAT) throw BackupException("El archivo no es un respaldo de Kanso")
            val schema = o.optInt("schema", 0)
            if (schema < 1 || schema > SCHEMA) throw BackupException("Este respaldo es de una versión más nueva de Kanso; actualiza la app")
            fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)
            fun JSONObject.int(k: String) = if (has(k) && !isNull(k)) getInt(k) else null
            return KansoBackup(o.optLong("createdAt"), o.optString("appVersion"),
                o.optJSONArray("podcasts").objects().map { p -> PodcastEntity(p.getString("feedUrl"), p.optString("id", p.getString("feedUrl")), p.optString("title"), p.optString("author"),
                    p.str("artworkUrl"), p.optString("genre"), p.str("folder"), p.int("autoDownloadCount"), p.optLong("subscribedAt", System.currentTimeMillis()),
                    if (p.has("playbackSpeed") && !p.isNull("playbackSpeed")) p.getDouble("playbackSpeed").toFloat() else null, p.optInt("skipIntroSec"), p.optInt("skipOutroSec"),
                    p.optString("episodeSort", "NEWEST"), p.optBoolean("notifyNewEpisodes"), p.optBoolean("isFavorite"),
                    if (p.has("autoArchive") && !p.isNull("autoArchive")) p.getBoolean("autoArchive") else null, p.str("autoQueue")) },
                o.optJSONArray("folders").let { a -> if (a == null) emptyList() else (0 until a.length()).map(a::getString) },
                o.optJSONArray("episodes").objects().map { e -> EpisodeState(e.getString("id"), e.optBoolean("listened"), e.optBoolean("favorite"), e.optBoolean("archived"), e.optLong("progressMs"), e.optLong("lastPlayedAt")) },
                o.optJSONArray("bookmarks").objects().map { b -> BookmarkEntity(0, b.getString("episodeId"), b.optLong("positionMs"), b.optString("title"), b.optLong("createdAt")) },
                o.optJSONArray("playlists").objects().map { p -> PlaylistEntity(0, p.optString("name", "Mi playlist"), p.optString("podcastFeeds"), p.optString("playedState", "ANY"),
                    p.optBoolean("downloadedOnly"), p.int("minMinutes"), p.int("maxMinutes"), p.int("withinDays"), p.optBoolean("includeArchived"), p.optInt("sortOrder")) },
                o.optJSONObject("settings")?.let { s -> s.keys().asSequence().filter { it in SETTINGS_FILES }.associateWith { file ->
                    val values = s.getJSONObject(file); values.keys().asSequence().associateWith { k -> values.getJSONObject(k).let { v -> when (v.getString("t")) {
                        "b" -> v.getBoolean("v"); "i" -> v.getInt("v"); "l" -> v.getLong("v"); "f" -> v.getDouble("v").toFloat(); else -> v.getString("v") } } } } } ?: emptyMap())
        }

        private fun typeOf(v: Any) = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }
        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)
    }
}

class BackupException(message: String) : Exception(message)

/** Reads and writes backups against the database and preferences. */
class BackupManager(private val context: Context, private val dao: KansoDao) {
    suspend fun create(appVersion: String): KansoBackup = KansoBackup(System.currentTimeMillis(), appVersion, dao.podcastList(), dao.folderRows().map { it.name },
        dao.episodesWithState().map { EpisodeState(it.id, it.listened, it.favorite, it.archived, it.progressMs, it.lastPlayedAt) },
        dao.bookmarkList(), dao.playlistList(),
        KansoBackup.SETTINGS_FILES.associateWith { f -> context.getSharedPreferences(f, 0).all.filterValues { it is Boolean || it is Int || it is Long || it is Float || it is String }.mapValues { it.value!! } })

    suspend fun restore(backup: KansoBackup, replace: Boolean) {
        dao.restoreBackup(backup, replace)
        backup.settings.forEach { (file, values) ->
            val edit = context.getSharedPreferences(file, 0).edit()
            if (replace) edit.clear()
            values.forEach { (k, v) -> when (v) { is Boolean -> edit.putBoolean(k, v); is Int -> edit.putInt(k, v); is Long -> edit.putLong(k, v); is Float -> edit.putFloat(k, v); else -> edit.putString(k, v.toString()) } }
            edit.commit()
        }
    }
}
