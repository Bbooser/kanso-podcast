package com.kanso.podcast.data

import com.kanso.podcast.data.db.ListeningDay
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    private val today = LocalDate.of(2026, 10, 29) // jueves

    @Test fun dailyBucketsFillGaps() {
        val days = listOf(ListeningDay("2026-10-29", 30 * 60_000L, 5 * 60_000L), ListeningDay("2026-10-27", 10 * 60_000L), ListeningDay("2026-10-01", 99 * 60_000L), ListeningDay("basura", 1))
        val b = StatsMath.daily(days, today, 7)
        assertEquals(7, b.size)
        assertEquals(LocalDate.of(2026, 10, 23), b.first().start)
        assertEquals(listOf(0L, 0L, 0L, 0L, 10L, 0L, 30L), b.map { it.minutes })
        assertEquals(5L, b.last().savedMinutes)
    }

    @Test fun weeklyBucketsStartOnMonday() {
        val days = listOf(ListeningDay("2026-10-26", 60_000L * 20), ListeningDay("2026-10-29", 60_000L * 10), ListeningDay("2026-10-25", 60_000L * 7))
        val w = StatsMath.weekly(days, today, 2)
        assertEquals(LocalDate.of(2026, 10, 19), w[0].start); assertEquals(7L, w[0].minutes)
        assertEquals(LocalDate.of(2026, 10, 26), w[1].start); assertEquals(30L, w[1].minutes)
    }

    private fun item(id: String, mb: Long, listened: Boolean, favorite: Boolean = false, published: Long = 0) =
        StorageItem(id, id.hashCode().toLong(), "f", "P", mb * 1024 * 1024, listened, favorite, published)

    @Test fun storageLimitDeletesOldestPlayedNonFavoritesOnly() {
        val items = listOf(item("nuevo", 400, false), item("fav", 300, true, favorite = true, published = 1), item("viejo", 200, true, published = 2), item("medio", 200, true, published = 3))
        val picked = KansoStorage.pickForLimit(items, 800L * 1024 * 1024)
        assertEquals(listOf("viejo", "medio"), picked.map { it.episodeId })
        assertTrue(KansoStorage.pickForLimit(items, 0).isEmpty())
        assertTrue(KansoStorage.pickForLimit(items, 2000L * 1024 * 1024).isEmpty())
    }

    @Test fun storageReportGroupsByPodcast() {
        val r = StorageReport(listOf(item("a", 1, true), item("b", 2, false).copy(feedUrl = "g", podcastTitle = "G")), 0)
        assertEquals(listOf("G", "P"), r.byPodcast.map { it.title })
        assertEquals(1L * 1024 * 1024, r.playedBytes)
        assertFalse(r.overLimit)
    }

    @Test fun notificationText() {
        assertEquals("Nuevo episodio de Café" to "Uno", NewEpisodeNotifier.text("Café", listOf("Uno")))
        assertEquals("2 episodios nuevos de Café", NewEpisodeNotifier.text("Café", listOf("Uno", "Dos")).first)
    }
}
