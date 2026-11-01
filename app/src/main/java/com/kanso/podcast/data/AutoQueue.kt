package com.kanso.podcast.data

import com.kanso.podcast.data.db.EpisodeEntity

/** Where new episodes of a podcast land in "A continuación". */
enum class AutoQueueMode(val key: String, val label: String) {
    NONE("NONE", "No"), START("START", "Al inicio"), END("END", "Al final");
    companion object {
        fun of(key: String?): AutoQueueMode? = entries.firstOrNull { it.key == key }
        /** Per-podcast value wins; null falls back to the global default. */
        fun resolve(podcastValue: String?, globalValue: String?): AutoQueueMode = of(podcastValue) ?: of(globalValue) ?: NONE
    }
}

/** Ids to insert right after the current episode ([next]) and at the end ([last]), in play order. */
data class AutoQueuePlan(val next: List<String>, val last: List<String>) { val isEmpty get() = next.isEmpty() && last.isEmpty() }

/**
 * Skips episodes already queued, archived, played, missing or repeated. Oldest first so a batch keeps release order.
 * [pending] pairs each episode with whether it goes at the start.
 */
fun planAutoQueue(queued: Collection<String>, pending: List<Pair<EpisodeEntity?, Boolean>>): AutoQueuePlan {
    val seen = queued.toHashSet()
    val ok = pending.filter { (e, _) -> e != null && !e.archived && !e.listened }.sortedBy { it.first!!.publishedAt }.filter { seen.add(it.first!!.id) }
    return AutoQueuePlan(ok.filter { it.second }.map { it.first!!.id }, ok.filterNot { it.second }.map { it.first!!.id })
}
