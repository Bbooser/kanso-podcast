package com.kanso.podcast.data

import com.kanso.podcast.model.PodcastSearchResult

/** What we know about one subscription, computed on-device. Never sent anywhere. */
data class ListeningSignal(val feedUrl: String, val id: String, val title: String, val genre: String, val listenedMs: Long, val favorite: Boolean, val lastPlayedAt: Long)

/** A candidate fetched from Apple, with its position in the source list (0 = top). */
data class Candidate(val podcast: PodcastSearchResult, val genre: String, val rank: Int, val total: Int)

data class Recommendation(val podcast: PodcastSearchResult, val score: Double, val reason: String)

/** On-device "Para ti": only genre names and the country leave the phone. */
object Recommender {
    const val CACHE_MS = 24L * 60 * 60 * 1000
    private const val RECENT_MS = 14L * 24 * 60 * 60 * 1000

    /** Weight per subscription: base 1, +1 per listened hour (capped at 5), +2 favorite, +1.5 played in the last 14 days. */
    fun weight(s: ListeningSignal, now: Long): Double =
        1.0 + (s.listenedMs / 3_600_000.0).coerceAtMost(5.0) + (if (s.favorite) 2.0 else 0.0) + (if (s.lastPlayedAt > 0 && now - s.lastPlayedAt <= RECENT_MS) 1.5 else 0.0)

    /** Genre → summed weight, ignoring the generic "Podcast(s)" label. */
    fun profile(signals: List<ListeningSignal>, now: Long): Map<String, Double> =
        signals.filter { it.genre.isNotBlank() && !it.genre.equals("podcast", true) && !it.genre.equals("podcasts", true) }
            .groupBy { it.genre }.mapValues { (_, list) -> list.sumOf { weight(it, now) } }

    /** Top genres to ask Apple about. */
    fun topGenres(profile: Map<String, Double>, n: Int = 3): List<String> = profile.entries.sortedByDescending { it.value }.take(n).map { it.key }

    fun rank(candidates: List<Candidate>, signals: List<ListeningSignal>, dismissed: Set<String>, now: Long, limit: Int = 12): List<Recommendation> {
        val prof = profile(signals, now)
        val max = prof.values.maxOrNull() ?: 1.0
        val feeds = signals.map { it.feedUrl.normalizedFeed() }.toSet()
        val ids = signals.map { it.id }.filter { it.isNotBlank() }.toSet()
        val best = HashMap<String, Recommendation>()
        for (c in candidates) {
            val p = c.podcast
            if (p.feedUrl.isBlank() || p.feedUrl.normalizedFeed() in feeds || p.id in ids || p.id in dismissed || p.feedUrl in dismissed) continue
            val affinity = (prof[c.genre] ?: 0.0) / max
            val popularity = if (c.total <= 1) 1.0 else 1.0 - c.rank.toDouble() / c.total
            val score = affinity + 0.5 * popularity
            val anchor = signals.filter { it.genre == c.genre }.maxByOrNull { weight(it, now) }
            val reason = if (anchor != null && (anchor.listenedMs > 0 || anchor.favorite)) "Porque escuchas ${anchor.title}" else "Popular en ${c.genre}"
            val key = if (p.id.isNotBlank()) p.id else p.feedUrl.normalizedFeed()
            val prev = best[key]
            if (prev == null || prev.score < score) best[key] = Recommendation(p, score, reason)
        }
        return best.values.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.podcast.title }).take(limit)
    }

    fun isStale(fetchedAt: Long, now: Long) = fetchedAt <= 0 || now - fetchedAt >= CACHE_MS
}

internal fun String.normalizedFeed() = trim().lowercase().removePrefix("https://").removePrefix("http://").removeSuffix("/")
