package com.kanso.podcast.data

import android.database.sqlite.SQLiteDatabase
import com.kanso.podcast.data.db.PendingEpisodeState
import java.io.File

/**
 * Reads an AntennaPod database backup ("Exportar base de datos", file Antennapod.db).
 * Table and column names follow AntennaPod's PodDBAdapter: Feeds(id, title, custom_title, download_url, author, state),
 * FeedItems(id, feed, item_identifier, read: -1 new / 0 unplayed / 1 played), FeedMedia(feeditem, download_url, position)
 * and Favorites(feeditem). Feeds with state != 0 (not subscribed / archived) and local folders are skipped.
 */
object AntennaPodImport {
    data class Backup(val feeds: List<OpmlEntry>, val states: List<PendingEpisodeState>)

    fun isSqlite(file: File): Boolean = file.length() > 16 && file.inputStream().use { s -> val head = ByteArray(15); s.read(head) == 15 && String(head, Charsets.US_ASCII) == "SQLite format 3" }

    fun read(file: File): Backup {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            fun columns(table: String) = it.rawQuery("PRAGMA table_info($table)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1).lowercase()) } }
            val feedCols = columns("Feeds")
            require("download_url" in feedCols && columns("FeedItems").contains("read")) { "No es un respaldo de AntennaPod" }
            val subscribed = if ("state" in feedCols) "f.state = 0" else "1 = 1"
            val title = if ("custom_title" in feedCols) "COALESCE(NULLIF(f.custom_title, ''), f.title)" else "f.title"
            val feeds = it.rawQuery("SELECT f.download_url, $title FROM Feeds f WHERE $subscribed AND f.download_url LIKE 'http%'", null).use { c ->
                buildList { while (c.moveToNext()) Opml.normalizeUrl(c.getString(0).orEmpty())?.let { url -> add(OpmlEntry(url, c.getString(1) ?: url)) } }
            }
            val hasFavorites = it.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'Favorites'", null).use { c -> c.moveToFirst() }
            val favorite = if (hasFavorites) "EXISTS(SELECT 1 FROM Favorites fav WHERE fav.feeditem = i.id)" else "0"
            val sql = "SELECT i.item_identifier, m.download_url, i.read, COALESCE(m.position, 0), $favorite FROM FeedItems i " +
                "JOIN Feeds f ON f.id = i.feed LEFT JOIN FeedMedia m ON m.feeditem = i.id " +
                "WHERE $subscribed AND (i.read = 1 OR COALESCE(m.position, 0) > 0 OR $favorite)"
            val states = it.rawQuery(sql, null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val played = c.getInt(2) == 1; val position = if (played) 0L else c.getLong(3); val fav = c.getInt(4) == 1
                        val keys = listOfNotNull(c.getString(0)?.trim()?.takeIf(String::isNotEmpty), c.getString(1)?.trim()?.takeIf(String::isNotEmpty)).distinct()
                        keys.forEach { key -> add(PendingEpisodeState(key, played, fav, false, position)) }
                    }
                }
            }
            return Backup(feeds, states)
        }
    }
}
