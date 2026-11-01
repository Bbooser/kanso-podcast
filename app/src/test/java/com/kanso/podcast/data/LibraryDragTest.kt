package com.kanso.podcast.data

import com.kanso.podcast.data.db.PodcastEntity
import org.junit.Assert.*
import org.junit.Test

class LibraryDragTest {
    private fun p(t: String, folder: String? = null, fav: Boolean = false, genre: String = "Podcast") = PodcastEntity("https://$t.mx", t, t, "", null, genre, folder = folder, isFavorite = fav)
    // 3 columns of 100px tiles with 10px gaps, second row scrolled to y=110.
    private val bounds = mapOf("f:Ciencia" to TileBounds(0f, 0f, 100f, 100f), "https://a.mx" to TileBounds(110f, 0f, 210f, 100f), "https://b.mx" to TileBounds(220f, 0f, 320f, 100f),
        "https://c.mx" to TileBounds(0f, 110f, 100f, 210f))

    @Test fun hitTestUsesBoundsAndSkipsDraggedAndGaps() {
        assertEquals("f:Ciencia", LibraryDrag.hitTest(50f, 50f, bounds, emptySet()))
        assertEquals("https://c.mx", LibraryDrag.hitTest(5f, 205f, bounds, emptySet()))
        assertNull(LibraryDrag.hitTest(105f, 50f, bounds, emptySet())) // gap
        assertNull(LibraryDrag.hitTest(150f, 50f, bounds, setOf("https://a.mx"))) // over itself
        assertNull(LibraryDrag.hitTest(500f, 500f, bounds, emptySet()))
    }

    @Test fun draggedFeedsFollowSelection() {
        val order = listOf("f:Ciencia", "https://a.mx", "https://b.mx", "https://c.mx")
        assertEquals(listOf("https://b.mx"), LibraryDrag.draggedFeeds("https://b.mx", null, order))
        assertEquals(listOf("https://a.mx", "https://c.mx"), LibraryDrag.draggedFeeds("https://c.mx", setOf("https://c.mx", "https://a.mx"), order))
        assertEquals(listOf("https://b.mx"), LibraryDrag.draggedFeeds("https://b.mx", setOf("https://a.mx"), order)) // unselected tile drags alone
    }

    @Test fun resolveFolderPodcastAndNothing() {
        val folder = LibraryTile.Folder("Ciencia", listOf(p("x", "Ciencia")), 0)
        assertEquals(DropPlan.IntoFolder("Ciencia", listOf("https://a.mx", "https://b.mx")), LibraryDrag.resolve(folder, listOf(p("a"), p("b"), p("x", "Ciencia")), listOf("Ciencia")))
        assertEquals(DropPlan.None, LibraryDrag.resolve(folder, listOf(p("x", "Ciencia")), listOf("Ciencia")))
        val plan = LibraryDrag.resolve(LibraryTile.Podcast(p("c", genre = "Technology")), listOf(p("a", genre = "Technology"), p("b")), emptyList())
        assertTrue(plan is DropPlan.NewFolder)
        assertEquals(listOf("https://c.mx", "https://a.mx", "https://b.mx"), (plan as DropPlan.NewFolder).feeds)
        assertTrue(plan.name.isNotBlank())
        assertEquals(DropPlan.None, LibraryDrag.resolve(LibraryTile.Podcast(p("a")), listOf(p("a")), emptyList()))
        assertEquals(DropPlan.None, LibraryDrag.resolve(null, listOf(p("a")), emptyList()))
    }

    @Test fun autoScrollNearEdgesOnly() {
        assertEquals(0f, LibraryDrag.autoScroll(500f, 1000f, 100f, 20f))
        assertEquals(-20f, LibraryDrag.autoScroll(0f, 1000f, 100f, 20f))
        assertEquals(-10f, LibraryDrag.autoScroll(50f, 1000f, 100f, 20f))
        assertEquals(20f, LibraryDrag.autoScroll(1200f, 1000f, 100f, 20f))
        assertEquals(0f, LibraryDrag.autoScroll(10f, 0f, 100f, 20f))
    }

    @Test fun bulkFavoriteToggles() {
        assertTrue(LibraryDrag.favoriteValue(listOf(p("a", fav = true), p("b"))))
        assertFalse(LibraryDrag.favoriteValue(listOf(p("a", fav = true), p("b", fav = true))))
    }
}
