package com.kanso.podcast.model

data class PodcastSearchResult(
    val id: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String? = null,
    val genre: String = "Podcast"
)

data class FeedEpisode(
    val id: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val published: String,
    val duration: String,
    val artworkUrl: String? = null
)

data class PodcastFeed(val podcast: PodcastSearchResult, val episodes: List<FeedEpisode>)
