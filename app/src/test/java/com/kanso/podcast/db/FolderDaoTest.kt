package com.kanso.podcast.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kanso.podcast.data.Opml
import com.kanso.podcast.data.OpmlEntry
import com.kanso.podcast.data.db.KansoDatabase
import com.kanso.podcast.data.db.PodcastEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KansoDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private fun p(t: String) = PodcastEntity("https://$t.mx", t, t, "", null, "Podcast")
    @After fun close() = db.close()

    @Test fun groupRenameDeleteNeverUnsubscribes() = runBlocking {
        dao.insertPodcasts(listOf(p("a"), p("b"), p("c")))
        dao.groupIntoFolder("Carpeta", listOf("https://a.mx", "https://b.mx"))
        assertEquals(listOf("Carpeta", "Carpeta", null), dao.podcastList().map { it.folder })
        dao.renameFolder("Carpeta", "Noticias")
        assertEquals(listOf("Noticias"), dao.folderRows().map { it.name })
        assertEquals(2, dao.podcastList().count { it.folder == "Noticias" })
        dao.deleteFolder("Noticias")
        assertTrue(dao.folderRows().isEmpty()); assertEquals(3, dao.podcastList().size); assertTrue(dao.podcastList().all { it.folder == null })
    }

    @Test fun layoutUndoRestoresFolders() = runBlocking {
        dao.insertPodcasts(listOf(p("a"), p("b")))
        dao.groupIntoFolder("Míos", listOf("https://a.mx"))
        val before = dao.folderRows().map { it.name } to dao.podcastList().associate { it.feedUrl to it.folder }
        dao.applyFolderLayout(listOf("Noticias"), mapOf("https://a.mx" to "Noticias", "https://b.mx" to "Noticias"))
        assertEquals(listOf("Noticias"), dao.folderRows().map { it.name })
        dao.applyFolderLayout(before.first, before.second)
        assertEquals(listOf("Míos"), dao.folderRows().map { it.name }); assertEquals(mapOf("https://a.mx" to "Míos", "https://b.mx" to null), dao.podcastList().associate { it.feedUrl to it.folder })
    }

    @Test fun opmlFoldersRoundTrip() {
        val xml = Opml.export(listOf(OpmlEntry("https://a.mx", "A", "Noticias"), OpmlEntry("https://b.mx", "B"), OpmlEntry("https://c.mx", "C", "Noticias")))
        val back = Opml.read(xml.byteInputStream()).entries.associate { it.feedUrl to it.folder }
        assertEquals(mapOf("https://a.mx" to "Noticias", "https://b.mx" to null, "https://c.mx" to "Noticias"), back)
    }
}
