package com.kanso.podcast.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kanso.podcast.data.db.KansoDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val name = "migration-test.db"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KansoDatabase::class.java)

    @Test fun migrate2To3KeepsDataAndAddsPodcastSettings() {
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO podcasts(feedUrl, id, title, author, artworkUrl, genre, folder, autoDownloadCount, subscribedAt) VALUES('https://a.mx/rss', '1', 'A', 'Autor', NULL, 'Podcast', 'Noticias', 2, 5)")
            execSQL("INSERT INTO episodes(id, podcastFeedUrl, title, description, audioUrl, published, publishedAt, duration, artworkUrl, sortIndex, listened, favorite, archived, progressMs, lastPlayedAt) VALUES('e1', 'https://a.mx/rss', 'E', '', 'https://a.mx/e1.mp3', '', 1, '10:00', NULL, 0, 1, 1, 0, 42, 7)")
            execSQL("INSERT INTO listening_days(day, listenedMs, savedMs) VALUES('2026-10-29', 1000, 10)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 3, true)
        db.query("SELECT title, folder, autoDownloadCount, playbackSpeed, skipIntroSec, skipOutroSec, episodeSort, notifyNewEpisodes FROM podcasts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("A", c.getString(0)); assertEquals("Noticias", c.getString(1)); assertEquals(2, c.getInt(2))
            assertTrue(c.isNull(3)); assertEquals(0, c.getInt(4)); assertEquals(0, c.getInt(5)); assertEquals("NEWEST", c.getString(6)); assertEquals(0, c.getInt(7))
        }
        db.query("SELECT listened, favorite, progressMs FROM episodes WHERE id = 'e1'").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); assertEquals(1, c.getInt(1)); assertEquals(42, c.getLong(2)) }
        db.query("SELECT COUNT(*) FROM podcast_listening").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        db.query("SELECT listenedMs FROM listening_days").use { c -> assertTrue(c.moveToFirst()); assertEquals(1000, c.getLong(0)) }
    }

    @Test fun migrate1To3() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO podcasts(feedUrl, id, title, author, artworkUrl, genre, folder, autoDownloadCount, subscribedAt) VALUES('https://b.mx/rss', '2', 'B', '', NULL, 'Podcast', NULL, NULL, 1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true).query("SELECT COUNT(*) FROM podcasts").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
    }

    @Test fun freshDatabaseOpens() {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KansoDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM podcast_listening").use { assertTrue(it.moveToFirst()) }
        db.close()
    }
}
