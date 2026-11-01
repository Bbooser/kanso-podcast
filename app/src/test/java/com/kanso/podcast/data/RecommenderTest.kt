package com.kanso.podcast.data

import com.kanso.podcast.model.PodcastSearchResult
import org.junit.Assert.*
import org.junit.Test

class RecommenderTest {
    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L
    private fun sig(t: String, g: String, ms: Long = 0, fav: Boolean = false, last: Long = 0) = ListeningSignal("https://$t.mx/feed", "id-$t", t, g, ms, fav, last)
    private fun cand(t: String, g: String, rank: Int, total: Int = 10, feed: String = "https://$t.mx/feed") = Candidate(PodcastSearchResult("id-$t", t, "a", feed, null, g), g, rank, total)

    @Test fun weightCombinesListeningFavoriteAndRecency() {
        assertEquals(1.0, Recommender.weight(sig("a", "Tecnología"), now), 1e-9)
        assertEquals(1.0 + 2 + 2 + 1.5, Recommender.weight(sig("a", "Tecnología", 2 * hour, true, now - hour), now), 1e-9)
        assertEquals(6.0, Recommender.weight(sig("a", "Tecnología", 40 * hour), now), 1e-9) // capped
        assertEquals(1.0, Recommender.weight(sig("a", "Tecnología", last = now - 30L * 24 * hour), now), 1e-9) // old play
    }

    @Test fun profileRanksGenresAndIgnoresGenericLabel() {
        val p = Recommender.profile(listOf(sig("a", "Tecnología", 3 * hour), sig("b", "Comedia"), sig("c", "Podcasts", 99 * hour)), now)
        assertEquals(listOf("Tecnología", "Comedia"), Recommender.topGenres(p))
    }

    @Test fun excludesSubscribedDismissedAndDedupes() {
        val signals = listOf(sig("a", "Tecnología", 5 * hour))
        val c = listOf(cand("a", "Tecnología", 0), cand("x", "Tecnología", 0, feed = "HTTP://A.mx/feed/"), cand("n", "Tecnología", 1), cand("d", "Tecnología", 2), cand("n", "Tecnología", 5))
        val out = Recommender.rank(c, signals, setOf("id-d"), now)
        assertEquals(listOf("n"), out.map { it.podcast.title })
        assertEquals(1.0 + 0.5 * 0.9, out[0].score, 1e-9) // best duplicate kept
    }

    @Test fun ranksByAffinityThenPopularityWithReasons() {
        val signals = listOf(sig("a", "Tecnología", 4 * hour), sig("b", "Comedia"))
        val out = Recommender.rank(listOf(cand("c1", "Comedia", 0), cand("t2", "Tecnología", 5), cand("t1", "Tecnología", 0)), signals, emptySet(), now)
        assertEquals(listOf("t1", "t2", "c1"), out.map { it.podcast.title })
        assertEquals("Porque escuchas a", out[0].reason)
        assertEquals("Popular en Comedia", out[2].reason)
    }

    @Test fun cacheExpiresAfter24h() {
        assertTrue(Recommender.isStale(0, now))
        assertFalse(Recommender.isStale(now - 23 * hour, now))
        assertTrue(Recommender.isStale(now - 24 * hour, now))
    }
}
