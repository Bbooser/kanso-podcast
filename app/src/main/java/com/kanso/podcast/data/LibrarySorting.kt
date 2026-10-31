package com.kanso.podcast.data

import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.data.db.PodcastLibraryStat
import java.text.Collator
import java.util.Locale

/** Sort modes for the Podcasts grid. Favorites are always pinned on top. */
enum class PodcastSort(val label: String) {
    RECENT("Recientes"), NEW_EPISODES("Con episodios nuevos"), NAME("Nombre");
    companion object { fun parse(v: String?) = entries.firstOrNull { it.name == v } }
}

private val ARTICLES = listOf("the ", "a ", "an ", "el ", "la ", "los ", "las ", "lo ", "un ", "una ", "unos ", "unas ")

/** Title used for A-Z: leading articles dropped ("El Hilo" sorts under H). */
fun sortableTitle(title: String): String {
    val t = title.trim(); val lower = t.lowercase(Locale.ROOT)
    val art = ARTICLES.firstOrNull { lower.startsWith(it) && t.length > it.length }
    return if (art != null) t.substring(art.length).trimStart() else t
}

/**
 * Favorites first, then the rest; both groups ordered by [sort].
 * RECENT: newest episode first (falls back to follow date). NEW_EPISODES: podcasts with new episodes first, then RECENT.
 * NAME: locale-aware A-Z ignoring leading articles.
 */
fun sortPodcasts(podcasts: List<PodcastEntity>, stats: Map<String, PodcastLibraryStat>, sort: PodcastSort, locale: Locale = Locale("es", "MX")): List<PodcastEntity> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    val byName = Comparator<PodcastEntity> { a, b -> collator.compare(sortableTitle(a.title), sortableTitle(b.title)) }
    fun latest(p: PodcastEntity) = stats[p.feedUrl]?.latestEpisodeAt ?: p.subscribedAt
    val recent = compareByDescending<PodcastEntity> { latest(it) }.then(byName)
    val inner = when (sort) {
        PodcastSort.RECENT -> recent
        PodcastSort.NEW_EPISODES -> compareByDescending<PodcastEntity> { (stats[it.feedUrl]?.newEpisodes ?: 0) > 0 }.then(recent)
        PodcastSort.NAME -> byName.thenBy { it.feedUrl }
    }
    return podcasts.sortedWith(compareByDescending<PodcastEntity> { it.isFavorite }.then(inner))
}

/** Podcast detail: the main list hides played and archived; the "Archivados" view shows only those. */
fun detailEpisodes(episodes: List<EpisodeEntity>, archivedView: Boolean): List<EpisodeEntity> =
    episodes.filter { (it.archived || it.listened) == archivedView }
