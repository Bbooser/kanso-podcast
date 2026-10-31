package com.kanso.podcast.data

import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.data.db.PodcastLibraryStat
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderLogicTest {
    private fun p(title: String, folder: String? = null, fav: Boolean = false, genre: String = "Podcast") = PodcastEntity("https://$title.mx", title, title, "", null, genre, folder, isFavorite = fav)
    private val pods = listOf(p("Alfa", fav = true), p("Beta", "Noticias"), p("Gama", "Noticias", fav = true), p("Delta"), p("Zeta"), p("Omega", "Huérfana"))
    private val stats = listOf(stat("Alfa", 10, 0), stat("Beta", 900, 2), stat("Gama", 50, 0), stat("Delta", 500, 1), stat("Zeta", 100, 0), stat("Omega", 1, 0)).associateBy { it.feedUrl }
    private fun stat(t: String, latest: Long, new: Int) = PodcastLibraryStat("https://$t.mx", latest, new)
    private fun keys(s: PodcastSort) = libraryTiles(pods, listOf("Noticias"), stats, s).map { when (it) { is LibraryTile.Folder -> "[${it.name}]"; is LibraryTile.Podcast -> it.podcast.title } }

    @Test fun favoritesPinnedFoldersMixedByName() = assertEquals(listOf("Alfa", "Delta", "[Noticias]", "Omega", "Zeta"), keys(PodcastSort.NAME))
    @Test fun folderUsesNewestMember() = assertEquals(listOf("Alfa", "[Noticias]", "Delta", "Zeta", "Omega"), keys(PodcastSort.RECENT))
    @Test fun folderWithNewEpisodesFirst() = assertEquals(listOf("Alfa", "[Noticias]", "Delta", "Zeta", "Omega"), keys(PodcastSort.NEW_EPISODES))

    @Test fun folderContentsSortedWithFavoritesFirstAndCountsNew() {
        val f = libraryTiles(pods, listOf("Noticias"), stats, PodcastSort.RECENT).filterIsInstance<LibraryTile.Folder>().single()
        assertEquals(listOf("Gama", "Beta"), f.podcasts.map { it.title }); assertEquals(2, f.newEpisodes)
    }

    @Test fun suggestedNames() {
        assertEquals("Carpeta", suggestFolderName(p("a"), p("b"), emptyList()))
        assertEquals("Carpeta 3", suggestFolderName(p("a"), p("b"), listOf("carpeta", "Carpeta 2")))
        assertEquals("Comedia", suggestFolderName(p("a", genre = "Comedia"), p("b", genre = "comedia"), emptyList()))
        assertEquals("Carpeta", suggestFolderName(p("a", genre = "Comedia"), p("b", genre = "Noticias"), emptyList()))
    }
}

class CategoryFoldersTest {
    private fun p(t: String, genre: String, folder: String? = null) = PodcastEntity("https://$t.mx", t, t, "", null, genre, folder)
    private val pods = listOf(p("a", "News"), p("b", "News"), p("c", "Comedy"), p("d", "Comedy"), p("e", "Podcast"), p("f", "Technology"), p("g", "News", folder = "Míos"), p("h", "Society &amp; Culture"), p("i", "society & culture"))

    @Test fun localizes() {
        assertEquals("Noticias", localizedCategory("News")); assertEquals("Sociedad y cultura", localizedCategory("Society &amp; Culture"))
        assertEquals("Anime", localizedCategory("anime")); assertEquals(null, localizedCategory("Podcast")); assertEquals(null, localizedCategory(" "))
    }

    @Test fun keepExistingLeavesFoldersAndSingletonsAlone() {
        val plan = planCategoryFolders(pods, listOf("Míos"), keepExisting = true)
        assertEquals(listOf("Míos", "Comedia", "Noticias", "Sociedad y cultura"), plan.folders)
        assertEquals("Noticias", plan.assignments["https://a.mx"]); assertEquals(null, plan.assignments["https://e.mx"]); assertEquals(null, plan.assignments["https://f.mx"])
        assertEquals(false, "https://g.mx" in plan.assignments)
        assertEquals(listOf("a", "b"), plan.groups["Noticias"]!!.map { it.title })
    }

    @Test fun replaceRegroupsEverything() {
        val plan = planCategoryFolders(pods, listOf("Míos"), keepExisting = false)
        assertEquals(listOf("Comedia", "Noticias", "Sociedad y cultura"), plan.folders)
        assertEquals("Noticias", plan.assignments["https://g.mx"]); assertEquals(3, plan.groups["Noticias"]!!.size)
    }
}
