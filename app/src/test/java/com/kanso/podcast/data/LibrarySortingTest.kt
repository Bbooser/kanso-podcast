package com.kanso.podcast.data

import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.data.db.PodcastLibraryStat
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortingTest {
    private fun p(title: String, fav: Boolean = false, subscribed: Long = 0) = PodcastEntity("https://$title.mx/rss", title, title, "", null, "Podcast", subscribedAt = subscribed, isFavorite = fav)
    private val list = listOf(p("Zeta"), p("El Hilo"), p("Árbol", fav = true), p("Barco", fav = true), p("the Daily"), p("caja"))
    private val stats = mapOf(
        "https://Zeta.mx/rss" to PodcastLibraryStat("https://Zeta.mx/rss", 500, 0),
        "https://El Hilo.mx/rss" to PodcastLibraryStat("https://El Hilo.mx/rss", 100, 3),
        "https://Árbol.mx/rss" to PodcastLibraryStat("https://Árbol.mx/rss", 50, 1),
        "https://Barco.mx/rss" to PodcastLibraryStat("https://Barco.mx/rss", 900, 0),
        "https://the Daily.mx/rss" to PodcastLibraryStat("https://the Daily.mx/rss", 300, 2),
    )
    private fun titles(s: PodcastSort) = sortPodcasts(list, stats, s).map { it.title }

    @Test fun nameIsLocaleAwareIgnoresArticlesAndPinsFavorites() =
        assertEquals(listOf("Árbol", "Barco", "caja", "the Daily", "El Hilo", "Zeta"), titles(PodcastSort.NAME))

    @Test fun recentUsesLatestEpisodeThenFollowDate() =
        assertEquals(listOf("Barco", "Árbol", "Zeta", "the Daily", "El Hilo", "caja"), titles(PodcastSort.RECENT))

    @Test fun newEpisodesFirstThenRecent() =
        assertEquals(listOf("Árbol", "Barco", "the Daily", "El Hilo", "Zeta", "caja"), titles(PodcastSort.NEW_EPISODES))

    @Test fun favoritesAlwaysOnTop() = PodcastSort.entries.forEach { s ->
        assertEquals(setOf("Árbol", "Barco"), sortPodcasts(list, stats, s).take(2).map { it.title }.toSet())
    }

    @Test fun sortableTitleKeepsSingleWordArticles() {
        assertEquals("Hilo", sortableTitle("El Hilo")); assertEquals("La", sortableTitle("La")); assertEquals("Ahora", sortableTitle("Ahora"))
    }

    @Test fun parseUnknownSortIsNull() { assertEquals(null, PodcastSort.parse("X")); assertEquals(PodcastSort.NAME, PodcastSort.parse("NAME")) }

    private fun e(id: String, listened: Boolean = false, archived: Boolean = false, progress: Long = 0) =
        EpisodeEntity(id, "f", id, "", "https://x/$id.mp3", "", 1, "10:00", null, 0, listened = listened, archived = archived, progressMs = progress)

    @Test fun detailHidesPlayedAndArchived() {
        val eps = listOf(e("nuevo"), e("enCurso", progress = 5), e("oido", listened = true), e("arch", archived = true), e("ambos", listened = true, archived = true))
        assertEquals(listOf("nuevo", "enCurso"), detailEpisodes(eps, archivedView = false).map { it.id })
        assertEquals(listOf("oido", "arch", "ambos"), detailEpisodes(eps, archivedView = true).map { it.id })
    }
}
