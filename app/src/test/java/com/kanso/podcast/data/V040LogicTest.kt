package com.kanso.podcast.data

import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.ui.durationRangeLabel
import org.junit.Assert.*
import org.junit.Test

class AutoQueueTest {
    private fun e(id: String, at: Long, listened: Boolean = false, archived: Boolean = false) = EpisodeEntity(id, "f", id, "", "u", "", at, "", null, 0, listened = listened, archived = archived)

    @Test fun podcastSettingWinsOverGlobal() {
        assertEquals(AutoQueueMode.START, AutoQueueMode.resolve("START", "END"))
        assertEquals(AutoQueueMode.END, AutoQueueMode.resolve(null, "END"))
        assertEquals(AutoQueueMode.NONE, AutoQueueMode.resolve("NONE", "END"))
        assertEquals(AutoQueueMode.NONE, AutoQueueMode.resolve(null, null))
    }

    @Test fun skipsQueuedArchivedPlayedMissingAndDuplicates() {
        val plan = planAutoQueue(listOf("q"), listOf(e("q", 1) to false, e("arch", 2, archived = true) to false, e("done", 3, listened = true) to true,
            null to true, e("b", 5) to false, e("a", 4) to false, e("a", 4) to true, e("n", 6) to true))
        assertEquals(listOf("n"), plan.next)
        assertEquals(listOf("a", "b"), plan.last) // oldest first, the duplicate "a" only once
    }

    @Test fun emptyPlan() = assertTrue(planAutoQueue(emptyList(), emptyList()).isEmpty)
}

class LibrarySearchTest {
    @Test fun buildsPrefixQuery() {
        assertEquals("cafe* ciencia*", LibrarySearch.ftsQuery("  Cafe, ciencia! "))
        assertEquals("niño*", LibrarySearch.ftsQuery("Niño"))
        assertNull(LibrarySearch.ftsQuery(" \"*- "))
        assertEquals("a* or* b*", LibrarySearch.ftsQuery("a OR b"))
    }
}

class ShowNoteTimestampsTest {
    @Test fun findsMinutesAndHours() {
        val text = "00:00 Intro\n12:30 Tema principal\n1:02:03 Cierre"
        val found = ShowNoteTimestamps.find(text)
        assertEquals(listOf(0L, 750_000L, 3_723_000L), found.map { it.ms })
        assertEquals("1:02:03", text.substring(found[2].start, found[2].end))
    }

    @Test fun ignoresClockTimesInvalidAndLongerThanEpisode() {
        assertTrue(ShowNoteTimestamps.find("Nos vemos a las 10:30 pm o 9:15 hrs").isEmpty())
        assertTrue(ShowNoteTimestamps.find("Marcador 12:75 y 1:75:00").isEmpty())
        assertTrue(ShowNoteTimestamps.find("Versículo 3:16:1 y 1.12:30").isEmpty())
        assertEquals(listOf(60_000L), ShowNoteTimestamps.find("1:00 y 59:00", maxMs = 30 * 60_000L).map { it.ms })
        assertEquals(listOf(5_000L), ShowNoteTimestamps.find("(0:05)").map { it.ms })
    }
}

class DurationLabelTest {
    @Test fun labels() {
        assertEquals("Cualquier duración", durationRangeLabel(null, null))
        assertEquals("Hasta 20 min", durationRangeLabel(null, 20))
        assertEquals("De 15 a 45 min", durationRangeLabel(15, 45))
        assertEquals("60 min o más", durationRangeLabel(60, null))
    }
}
