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
) {
    fun toModel() = FeedEpisode(id, title, description, audioUrl, published, duration, artworkUrl, publishedAt)
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
)

data class EpisodeRow(
    @Embedded val episode: EpisodeEntity,
    @ColumnInfo(name = "podcastTitle") val podcastTitle: String?,
    @ColumnInfo(name = "podcastArtwork") val podcastArtwork: String?,
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
