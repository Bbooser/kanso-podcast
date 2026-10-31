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

/** One tile in the Podcasts grid root: a folder or a podcast outside any folder. */
sealed interface LibraryTile {
    val key: String
    data class Folder(val name: String, val podcasts: List<PodcastEntity>, val newEpisodes: Int) : LibraryTile { override val key get() = "f:$name" }
    data class Podcast(val podcast: PodcastEntity) : LibraryTile { override val key get() = podcast.feedUrl }
}

/**
 * Root of the grid, strictly: folders, then favorite podcasts, then the rest; each group ordered by [sort]
 * (a folder counts as its newest episode / any new episode / its name). Podcasts inside a folder use [sortPodcasts].
 */
fun libraryTiles(podcasts: List<PodcastEntity>, folders: List<String>, stats: Map<String, PodcastLibraryStat>, sort: PodcastSort, locale: Locale = Locale("es", "MX")): List<LibraryTile> {
    val known = folders.toSet()
    val loose = sortPodcasts(podcasts.filter { it.folder == null || it.folder !in known }, stats, sort, locale)
    val folderTiles = folders.map { name -> val inside = sortPodcasts(podcasts.filter { it.folder == name }, stats, sort, locale)
        LibraryTile.Folder(name, inside, inside.sumOf { stats[it.feedUrl]?.newEpisodes ?: 0 }) }
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    fun name(t: LibraryTile) = sortableTitle(when (t) { is LibraryTile.Folder -> t.name; is LibraryTile.Podcast -> t.podcast.title })
    fun latest(t: LibraryTile) = when (t) { is LibraryTile.Folder -> t.podcasts.maxOfOrNull { stats[it.feedUrl]?.latestEpisodeAt ?: it.subscribedAt } ?: 0L; is LibraryTile.Podcast -> stats[t.podcast.feedUrl]?.latestEpisodeAt ?: t.podcast.subscribedAt }
    fun hasNew(t: LibraryTile) = when (t) { is LibraryTile.Folder -> t.newEpisodes > 0; is LibraryTile.Podcast -> (stats[t.podcast.feedUrl]?.newEpisodes ?: 0) > 0 }
    val byName = Comparator<LibraryTile> { a, b -> collator.compare(name(a), name(b)) }
    val recent = compareByDescending<LibraryTile> { latest(it) }.then(byName)
    val cmp = when (sort) { PodcastSort.RECENT -> recent; PodcastSort.NEW_EPISODES -> compareByDescending<LibraryTile> { hasNew(it) }.then(recent); PodcastSort.NAME -> byName.thenBy { it.key } }
    val (favs, rest) = loose.partition { it.isFavorite }
    return folderTiles.sortedWith(cmp) + favs.map { LibraryTile.Podcast(it) } + rest.map { LibraryTile.Podcast(it) }
}

/** Name for a folder made by dropping [a] onto [b]: their shared category, else "Carpeta"; numbered if taken. */
fun suggestFolderName(a: PodcastEntity, b: PodcastEntity, existing: Collection<String>): String {
    val base = a.genre.trim().takeIf { it.isNotBlank() && it.equals(b.genre.trim(), ignoreCase = true) && !it.equals("Podcast", ignoreCase = true) && !it.equals("Podcasts", ignoreCase = true) } ?: "Carpeta"
    val taken = existing.map { it.lowercase() }.toSet()
    if (base.lowercase() !in taken) return base
    return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in taken }
}

private val CATEGORY_ES = mapOf(
    "arts" to "Arte", "business" to "Negocios", "comedy" to "Comedia", "education" to "Educación", "fiction" to "Ficción",
    "government" to "Gobierno", "health & fitness" to "Salud y bienestar", "history" to "Historia", "kids & family" to "Niños y familia",
    "leisure" to "Ocio", "music" to "Música", "news" to "Noticias", "religion & spirituality" to "Religión y espiritualidad",
    "science" to "Ciencia", "society & culture" to "Sociedad y cultura", "sports" to "Deportes", "technology" to "Tecnología",
    "true crime" to "Crimen real", "tv & film" to "Cine y TV",
)

/** Spanish label for an Apple/iTunes category ("Society &amp; Culture" -> "Sociedad y cultura"); unknown ones pass through. null for none. */
fun localizedCategory(raw: String?): String? {
    val c = raw?.replace("&amp;", "&")?.trim().orEmpty()
    if (c.isBlank() || c.equals("Podcast", true) || c.equals("Podcasts", true)) return null
    return CATEGORY_ES[c.lowercase()] ?: c.replaceFirstChar { it.uppercase() }
}

/** Result of "Organizar por categoría": the whole folder list plus the folder of every podcast. */
data class FolderPlan(val folders: List<String>, val assignments: Map<String, String?>, val groups: Map<String, List<PodcastEntity>>)

/**
 * Groups podcasts by category. [keepExisting] leaves podcasts already in a folder (and those folders) alone; otherwise every
 * folder is replaced. Podcasts without a category, or alone in theirs, stay at the root so the grid doesn't fill with 1-item folders.
 */
fun planCategoryFolders(podcasts: List<PodcastEntity>, existingFolders: List<String>, keepExisting: Boolean): FolderPlan {
    val candidates = if (keepExisting) podcasts.filter { it.folder == null || it.folder !in existingFolders } else podcasts
    val groups = candidates.groupBy { localizedCategory(it.genre) }.filterKeys { it != null }.mapKeys { it.key!! }.filterValues { it.size >= 2 }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val kept = if (keepExisting) existingFolders else emptyList()
    val folders = (kept + groups.keys).distinctBy { it.lowercase() }
    val folderFor = folders.associateBy { it.lowercase() }
    val assignments = candidates.associate { p -> p.feedUrl to localizedCategory(p.genre)?.takeIf { it in groups }?.let { folderFor[it.lowercase()] } }
    return FolderPlan(folders, assignments, groups.mapKeys { folderFor[it.key.lowercase()]!! })
}
