package com.kanso.podcast.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Uses the table layout from AntennaPod's PodDBAdapter (subset of columns). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AntennaPodImportTest {
    private fun backup(withState: Boolean = true): File {
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "Antennapod.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE Feeds (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, custom_title TEXT, download_url TEXT, author TEXT" + (if (withState) ", state INTEGER DEFAULT 0" else "") + ")")
            db.execSQL("CREATE TABLE FeedItems (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, read INTEGER, feed INTEGER, item_identifier TEXT)")
            db.execSQL("CREATE TABLE FeedMedia (id INTEGER PRIMARY KEY AUTOINCREMENT, download_url TEXT, position INTEGER, feeditem INTEGER)")
            db.execSQL("CREATE TABLE Favorites (id INTEGER PRIMARY KEY, feeditem INTEGER, feed INTEGER)")
            db.execSQL("INSERT INTO Feeds(id, title, custom_title, download_url) VALUES(1, 'Uno', 'Mi uno', 'https://uno.mx/rss')")
            db.execSQL("INSERT INTO Feeds(id, title, download_url) VALUES(2, 'Local', 'antennapod_local:content://x')")
            if (withState) db.execSQL("INSERT INTO Feeds(id, title, download_url, state) VALUES(3, 'Viejo', 'https://viejo.mx/rss', 1)")
            db.execSQL("INSERT INTO FeedItems(id, title, read, feed, item_identifier) VALUES(10, 'oído', 1, 1, 'guid-10')")
            db.execSQL("INSERT INTO FeedItems(id, title, read, feed, item_identifier) VALUES(11, 'a medias', 0, 1, 'guid-11')")
            db.execSQL("INSERT INTO FeedItems(id, title, read, feed, item_identifier) VALUES(12, 'nuevo', -1, 1, 'guid-12')")
            db.execSQL("INSERT INTO FeedItems(id, title, read, feed, item_identifier) VALUES(13, 'favorito', -1, 1, NULL)")
            db.execSQL("INSERT INTO FeedMedia(download_url, position, feeditem) VALUES('https://uno.mx/10.mp3', 999, 10)")
            db.execSQL("INSERT INTO FeedMedia(download_url, position, feeditem) VALUES('https://uno.mx/11.mp3', 60000, 11)")
            db.execSQL("INSERT INTO FeedMedia(download_url, position, feeditem) VALUES('https://uno.mx/12.mp3', 0, 12)")
            db.execSQL("INSERT INTO FeedMedia(download_url, position, feeditem) VALUES('https://uno.mx/13.mp3', 0, 13)")
            db.execSQL("INSERT INTO Favorites(id, feeditem, feed) VALUES(1, 13, 1)")
        }
        return file
    }

    @Test fun readsSubscriptionsAndListeningState() {
        val file = backup()
        assertTrue(AntennaPodImport.isSqlite(file))
        val b = AntennaPodImport.read(file)
        assertEquals(listOf(OpmlEntry("https://uno.mx/rss", "Mi uno")), b.feeds)
        val byId = b.states.associateBy { it.episodeId }
        assertTrue(byId.getValue("guid-10").listened); assertEquals(0L, byId.getValue("guid-10").progressMs)
        assertEquals(60000L, byId.getValue("guid-11").progressMs)
        assertTrue(byId.getValue("https://uno.mx/13.mp3").favorite)
        assertFalse(byId.containsKey("guid-12"))
    }

    @Test fun olderBackupsWithoutStateColumn() {
        assertEquals(1, AntennaPodImport.read(backup(withState = false)).feeds.size)
    }

    @Test fun opmlIsNotSqlite() {
        val f = File.createTempFile("kanso", ".opml").apply { writeText("<opml><body/></opml>") }
        assertFalse(AntennaPodImport.isSqlite(f))
    }
}
