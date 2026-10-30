package com.kanso.podcast.ui

import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.EpisodeRow
import com.kanso.podcast.data.db.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRulesTest {
    private val now = 100L * 86_400_000L
    private fun row(id: String, feed: String = "a", listened: Boolean = false, progress: Long = 0, duration: String = "30:00", daysAgo: Long = 1, archived: Boolean = false, lastPlayed: Long = 0) =
        EpisodeRow(EpisodeEntity(id, feed, id, "", "https://x/$id.mp3", "", now - daysAgo * 86_400_000L, duration, null, 0, listened = listened, archived = archived, progressMs = progress, lastPlayedAt = lastPlayed), "P", null)

    private val rows = listOf(
        row("nuevo"), row("enCurso", progress = 5_000, lastPlayed = 10), row("enCurso2", progress = 1, lastPlayed = 20), row("oido", listened = true),
        row("largo", duration = "1:30:00"), row("viejo", daysAgo = 40), row("otro", feed = "b"), row("archivado", archived = true), row("sinDuracion", duration = "—"),
    )
    private fun ids(p: PlaylistEntity, downloaded: Set<String> = emptySet()) = p.matching(rows, downloaded, now).map { it.episode.id }

    @Test fun playedStates() {
        assertEquals(listOf("enCurso2", "enCurso"), ids(PlaylistEntity(name = "x", playedState = "IN_PROGRESS")))
        assertEquals(listOf("oido"), ids(PlaylistEntity(name = "x", playedState = "PLAYED")))
        assertEquals(listOf("nuevo", "largo", "viejo", "otro", "sinDuracion"), ids(PlaylistEntity(name = "x", playedState = "UNPLAYED")))
        assertEquals(8, ids(PlaylistEntity(name = "x", playedState = "ANY")).size)
        assertEquals(9, ids(PlaylistEntity(name = "x", includeArchived = true)).size)
    }

    @Test fun feedsDurationAgeAndDownloads() {
        assertEquals(listOf("otro"), ids(PlaylistEntity(name = "x", podcastFeeds = "b\n")))
        assertEquals(listOf("largo"), ids(PlaylistEntity(name = "x", minMinutes = 60)))
        assertEquals(listOf("nuevo", "enCurso", "enCurso2", "oido", "viejo", "otro"), ids(PlaylistEntity(name = "x", maxMinutes = 30)))
        assertEquals(false, ids(PlaylistEntity(name = "x", withinDays = 14)).contains("viejo"))
        assertEquals(listOf("nuevo"), ids(PlaylistEntity(name = "x", downloadedOnly = true), setOf("nuevo", "archivado")))
    }
}
