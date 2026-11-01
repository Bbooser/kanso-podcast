package com.kanso.podcast.data

import com.kanso.podcast.data.db.PodcastEntity

/** A tile's rectangle in the grid's own coordinates (from onGloballyPositioned, so it follows scrolling). */
data class TileBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/** What a drop does. */
sealed interface DropPlan {
    /** Move [feeds] into the existing folder [name]. */
    data class IntoFolder(val name: String, val feeds: List<String>) : DropPlan
    /** Make a new folder (named [name], then offered for rename) holding [feeds]. */
    data class NewFolder(val name: String, val feeds: List<String>) : DropPlan
    data object None : DropPlan
}

object LibraryDrag {
    /** Tile under the finger, ignoring the dragged ones. */
    fun hitTest(x: Float, y: Float, bounds: Map<String, TileBounds>, exclude: Set<String>): String? =
        bounds.entries.firstOrNull { (k, b) -> k !in exclude && b.contains(x, y) }?.key

    /** Long-press on a selected tile drags the whole selection (in library order); otherwise only that podcast. */
    fun draggedFeeds(key: String, selection: Set<String>?, order: List<String>): List<String> =
        if (selection != null && key in selection) order.filter { it in selection } else listOf(key)

    /** Folder target adds the podcasts not already in it; podcast target makes a new folder with it and the dragged ones. */
    fun resolve(target: LibraryTile?, dragged: List<PodcastEntity>, folders: Collection<String>): DropPlan = when (target) {
        null -> DropPlan.None
        is LibraryTile.Folder -> dragged.filter { it.folder != target.name }.map { it.feedUrl }.let { if (it.isEmpty()) DropPlan.None else DropPlan.IntoFolder(target.name, it) }
        is LibraryTile.Podcast -> if (dragged.isEmpty() || dragged.any { it.feedUrl == target.podcast.feedUrl }) DropPlan.None
            else DropPlan.NewFolder(suggestFolderName(dragged.first(), target.podcast, folders), (listOf(target.podcast) + dragged).map { it.feedUrl }.distinct())
    }

    /** Pixels per frame to scroll when the finger is within [edge] of the top or bottom; negative scrolls up. */
    fun autoScroll(y: Float, height: Float, edge: Float, maxSpeed: Float): Float = when {
        height <= 0f || edge <= 0f -> 0f
        y < edge -> -maxSpeed * ((edge - y.coerceAtLeast(0f)) / edge).coerceIn(0f, 1f)
        y > height - edge -> maxSpeed * ((y.coerceAtMost(height) - (height - edge)) / edge).coerceIn(0f, 1f)
        else -> 0f
    }

    /** Bulk favorite: if every selected podcast is already a favorite, the action removes it; otherwise it marks all. */
    fun favoriteValue(selected: List<PodcastEntity>): Boolean = !selected.all { it.isFavorite }
}
