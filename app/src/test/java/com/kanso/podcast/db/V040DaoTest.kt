package com.kanso.podcast.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kanso.podcast.data.BackupManager
import com.kanso.podcast.data.KansoBackup
import com.kanso.podcast.data.LibrarySearch
import com.kanso.podcast.data.BackupException
import com.kanso.podcast.data.db.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V040DaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, KansoDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    @After fun close() = db.close()
    private fun ep(id: String, title: String, notes: String = "") = EpisodeEntity(id, "https://a.mx", title, notes, "https://a.mx/$id.mp3", "", 1, "", null, 0)
    private suspend fun search(q: String) = dao.searchLibrary(LibrarySearch.ftsQuery(q)!!).map { it.episode.id }

    @Test fun fullTextSearchCoversTitleNotesAccentsAndUpdates() = runBlocking {
        dao.insertPodcasts(listOf(PodcastEntity("https://a.mx", "a", "A", "", null, "Podcast")))
        dao.insertEpisodes(listOf(ep("1", "Café con ciencia", "Volcanes y sismos"), ep("2", "Música", "Entrevista con Natalia")))
        assertEquals(listOf("1"), search("volcan"))
        assertEquals(listOf("1"), search("cafe CIENCIA"))
        assertEquals(listOf("2"), search("musica"))
        assertTrue(search("cafe natalia").isEmpty())
        dao.updateMetadata(listOf(EpisodeMetadata("2", "https://a.mx", "Música nueva", "Ahora hablamos de volcanes", "u", "", 1, "", null, 0, null, null, null)))
        assertEquals(setOf("1", "2"), search("volcanes").toSet())
    }

    @Test fun autoQueuePendingIgnoresRepeats() = runBlocking {
        dao.insertAutoQueue(listOf(AutoQueuePending("x", true, 1), AutoQueuePending("y", false, 2)))
        dao.insertAutoQueue(listOf(AutoQueuePending("x", false, 3)))
        assertEquals(listOf("x" to true, "y" to false), dao.autoQueuePending().map { it.episodeId to it.atStart })
        dao.deleteAutoQueue(listOf("x", "y")); assertTrue(dao.autoQueuePending().isEmpty())
    }

    private suspend fun seed() {
        dao.insertPodcasts(listOf(PodcastEntity("https://a.mx", "a", "A", "", null, "Podcast", folder = "Ciencia", playbackSpeed = 1.3f, skipIntroSec = 30, isFavorite = true, autoArchive = false, autoQueue = "START")))
        dao.insertFolders(listOf(FolderEntity("Ciencia"), FolderEntity("Vacía")))
        dao.insertEpisodes(listOf(ep("1", "Uno"), ep("2", "Dos")))
        dao.applyEpisodeState("1", true, true, false, 0, 50); dao.applyEpisodeState("2", false, false, false, 12_000, 60)
        dao.insertBookmark(BookmarkEntity(episodeId = "2", positionMs = 9_000, title = "Dato"))
        dao.upsertPlaylist(PlaylistEntity(name = "Cortos", maxMinutes = 20, withinDays = 3, downloadedOnly = true))
        context.getSharedPreferences("automation", 0).edit().putInt("download_count", 2).putString("auto_queue", "END").putBoolean("wifi_only", false).commit()
        context.getSharedPreferences("appearance", 0).edit().putInt("podcasts_columns", 4).putFloat("x", 1.5f).putLong("y", 7L).commit()
        context.getSharedPreferences("playback_session", 0).edit().putInt("index", 3).commit()
    }

    @Test fun backupRoundTripThroughZipReplacesEverything() = runBlocking {
        seed()
        val manager = BackupManager(context, dao)
        val original = manager.create("0.4.0")
        val bytes = ByteArrayOutputStream().also(original::write).toByteArray()
        assertFalse(original.settings.containsKey("playback_session"))
        val read = KansoBackup.read(ByteArrayInputStream(bytes))
        assertEquals(original.copy(bookmarks = emptyList(), playlists = emptyList()), read.copy(bookmarks = emptyList(), playlists = emptyList()))
        assertEquals(original.bookmarks.map { it.copy(id = 0) }, read.bookmarks)
        assertEquals(original.playlists.map { it.copy(id = 0) }, read.playlists)

        // Wipe and restore into an empty library: episodes are not fetched yet, so state goes to pending_state.
        dao.deleteAllPodcasts(); dao.deleteAllEpisodes(); dao.deleteAllFolders(); dao.deleteAllPlaylists(); dao.deleteAllBookmarks()
        context.getSharedPreferences("automation", 0).edit().clear().commit()
        manager.restore(read, replace = true)
        assertEquals(original.podcasts, dao.podcastList())
        assertEquals(setOf("Ciencia", "Vacía"), dao.folderRows().map { it.name }.toSet())
        assertEquals(listOf("1", "2"), dao.pendingFor(listOf("1", "2")).map { it.episodeId }.sorted())
        assertEquals(12_000L, dao.pendingFor(listOf("2")).single().progressMs)
        assertEquals("Cortos", dao.playlistList().single().name); assertEquals(3, dao.playlistList().single().withinDays)
        assertEquals(9_000L, dao.bookmarkList().single().positionMs)
        assertEquals("END", context.getSharedPreferences("automation", 0).getString("auto_queue", null))
        assertEquals(1.5f, context.getSharedPreferences("appearance", 0).getFloat("x", 0f)); assertEquals(7L, context.getSharedPreferences("appearance", 0).getLong("y", 0))
        // The same backup again round-trips to the same document.
        assertEquals(original.copy(createdAt = 0, episodes = emptyList()).toJson().toString(), manager.create("0.4.0").copy(createdAt = 0, episodes = emptyList()).toJson().toString())
    }

    @Test fun mergeKeepsLocalDataAndDoesNotDuplicate() = runBlocking {
        seed()
        val manager = BackupManager(context, dao)
        val backup = manager.create("0.4.0")
        dao.insertPodcasts(listOf(PodcastEntity("https://b.mx", "b", "B", "", null, "Podcast")))
        dao.applyEpisodeState("2", false, false, false, 0, 0)
        manager.restore(backup, replace = false)
        assertEquals(2, dao.podcastList().size)
        assertEquals(1, dao.playlistList().size); assertEquals(1, dao.bookmarkList().size)
        assertEquals(12_000L, dao.episode("2")!!.progressMs)
        assertEquals(60L, dao.episode("2")!!.lastPlayedAt)
    }

    @Test fun rejectsForeignAndNewerFiles() {
        assertThrows(BackupException::class.java) { KansoBackup.fromJson(JSONObject().put("format", "otro")) }
        assertThrows(BackupException::class.java) { KansoBackup.fromJson(JSONObject().put("format", "kansobackup").put("schema", 99)) }
        assertThrows(BackupException::class.java) { KansoBackup.read(ByteArrayInputStream(ByteArrayOutputStream().also { java.util.zip.ZipOutputStream(it).use { z -> z.putNextEntry(java.util.zip.ZipEntry("x.txt")); z.closeEntry() } }.toByteArray())) }
    }
}
