package com.kanso.podcast.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kanso.podcast.model.FeedEpisode
import com.kanso.podcast.model.PodcastSearchResult

/** A followed podcast. Podcasts opened from search but not followed are not stored here. */
@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val feedUrl: String,
    val id: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    val genre: String,
    val folder: String? = null,
    /** null = use the global automation setting. */
    val autoDownloadCount: Int? = null,
    val subscribedAt: Long = System.currentTimeMillis(),
    /** null = use the player's current speed. */
    @ColumnInfo(defaultValue = "NULL") val playbackSpeed: Float? = null,
    @ColumnInfo(defaultValue = "0") val skipIntroSec: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipOutroSec: Int = 0,
    /** NEWEST or OLDEST. */
    @ColumnInfo(defaultValue = "NEWEST") val episodeSort: String = "NEWEST",
    @ColumnInfo(defaultValue = "0") val notifyNewEpisodes: Boolean = false,
) {
    fun toModel() = PodcastSearchResult(id, title, author, feedUrl, artworkUrl, genre)
    companion object {
        fun from(p: PodcastSearchResult, folder: String? = null) = PodcastEntity(p.feedUrl, p.id, p.title, p.author, p.artworkUrl, p.genre, folder)
    }
}

/** Episode metadata from the feed plus the listener's state. Feed refreshes never overwrite the state columns. */
@Entity(tableName = "episodes", indices = [Index("podcastFeedUrl"), Index("publishedAt")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastFeedUrl: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val published: String,
    val publishedAt: Long,
    val duration: String,
    val artworkUrl: String?,
    val sortIndex: Int,
    val listened: Boolean = false,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val progressMs: Long = 0,
    val lastPlayedAt: Long = 0,
    /** Podcasting 2.0 <podcast:chapters> JSON url. */
    val chaptersUrl: String? = null,
    /** Podcasting 2.0 <podcast:transcript> url and mime type. */
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
) {
    fun toModel() = FeedEpisode(id, title, description, audioUrl, published, duration, artworkUrl, publishedAt, chaptersUrl, transcriptUrl, transcriptType)
}

/** Feed-owned columns, used to refresh metadata without touching listener state. */
data class EpisodeMetadata(
    val id: String,
    val podcastFeedUrl: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val published: String,
    val publishedAt: Long,
    val duration: String,
    val artworkUrl: String?,
    val sortIndex: Int,
    val chaptersUrl: String?,
    val transcriptUrl: String?,
    val transcriptType: String?,
)

data class EpisodeRow(
    @Embedded val episode: EpisodeEntity,
    @ColumnInfo(name = "podcastTitle") val podcastTitle: String?,
    @ColumnInfo(name = "podcastArtwork") val podcastArtwork: String?,
    @ColumnInfo(name = "podcastAuthor") val podcastAuthor: String? = null,
)

@Entity(tableName = "queue")
data class QueueEntry(
    @PrimaryKey val position: Int,
    val episodeId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
)

@Entity(tableName = "downloads", indices = [Index("downloadId")])
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val downloadId: Long,
    val fileName: String,
    val sourceUrl: String,
    val title: String,
)

@Entity(tableName = "folders")
data class FolderEntity(@PrimaryKey val name: String)

/** State migrated from SharedPreferences for episodes that were not in the offline cache yet; applied when the episode appears. */
@Entity(tableName = "pending_state")
data class PendingEpisodeState(
    @PrimaryKey val episodeId: String,
    val listened: Boolean,
    val favorite: Boolean,
    val archived: Boolean,
    val progressMs: Long,
)

/** Smart playlist: episodes from followed podcasts matching all rules. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Feed URLs separated by newlines; empty = all followed podcasts. */
    val podcastFeeds: String = "",
    /** ANY, UNPLAYED, IN_PROGRESS, PLAYED, NOT_PLAYED (unplayed or in progress). */
    val playedState: String = "ANY",
    val downloadedOnly: Boolean = false,
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    /** Only episodes published in the last N days. */
    val withinDays: Int? = null,
    val includeArchived: Boolean = false,
    val sortOrder: Int = 0,
) {
    val feeds: Set<String> get() = podcastFeeds.split('\n').filter { it.isNotBlank() }.toSet()
}

@Entity(tableName = "bookmarks", indices = [Index("episodeId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: String,
    val positionMs: Long,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class BookmarkRow(
    @Embedded val bookmark: BookmarkEntity,
    @ColumnInfo(name = "episodeTitle") val episodeTitle: String?,
    @ColumnInfo(name = "podcastTitle") val podcastTitle: String?,
    @ColumnInfo(name = "artwork") val artwork: String?,
)

/** Per-day listening stats; "saved" is time saved by speed and silence trimming. */
@Entity(tableName = "listening_days")
data class ListeningDay(
    @PrimaryKey val day: String,
    val listenedMs: Long = 0,
    val savedMs: Long = 0,
)

/** Total listening time per podcast, for the "top podcasts" chart. */
@Entity(tableName = "podcast_listening")
data class PodcastListening(
    @PrimaryKey val feedUrl: String,
    val listenedMs: Long = 0,
)

data class TopPodcastRow(val feedUrl: String, val title: String?, val artworkUrl: String?, val listenedMs: Long)

/** A finished download joined with its podcast, for storage management. */
data class DownloadRow(
    @Embedded val download: DownloadEntity,
    @ColumnInfo(name = "podcastFeedUrl") val podcastFeedUrl: String?,
    @ColumnInfo(name = "podcastTitle") val podcastTitle: String?,
    @ColumnInfo(name = "listened") val listened: Boolean?,
    @ColumnInfo(name = "favorite") val favorite: Boolean?,
    @ColumnInfo(name = "publishedAt") val publishedAt: Long?,
)
