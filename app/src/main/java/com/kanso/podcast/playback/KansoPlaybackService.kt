package com.kanso.podcast.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single playback authority for the phone UI, notification, widget and Android Auto.
 * The Cast player will replace the local player behind this session when a route is selected.
 */
@UnstableApi
class KansoPlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("KansoPodcast/0.1.7.1 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(),
                true
            ).setHandleAudioBecomingNoisy(true).build()
        restorePlayback()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                persistPlayback()
            }
        })
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        persistPlayback()
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun persistPlayback() = runCatching {
        val queue = JSONArray()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri?.toString().orEmpty()
            if (uri.isNotBlank()) queue.put(JSONObject().apply {
                put("id", item.mediaId)
                put("uri", uri)
                put("title", item.mediaMetadata.title?.toString().orEmpty())
                put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                put("artwork", item.mediaMetadata.artworkUri?.toString().orEmpty())
            })
        }
        getSharedPreferences("playback_session", MODE_PRIVATE).edit()
            .putString("queue", queue.toString())
            .putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putFloat("speed", player.playbackParameters.speed)
            .apply()
    }

    private fun restorePlayback() {
        val prefs = getSharedPreferences("playback_session", MODE_PRIVATE)
        runCatching {
            val raw = prefs.getString("queue", null) ?: return
            val stored = JSONArray(raw)
            val items = buildList {
                for (index in 0 until stored.length()) {
                    val value = stored.optJSONObject(index) ?: continue
                    val uri = value.optString("uri")
                    if (uri.isBlank()) continue
                    add(MediaItem.Builder().setMediaId(value.optString("id", uri)).setUri(uri)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(value.optString("title"))
                            .setArtist(value.optString("artist"))
                            .setArtworkUri(value.optString("artwork").takeIf { it.isNotBlank() }?.let(android.net.Uri::parse))
                            .build()).build())
                }
            }
            if (items.isNotEmpty()) {
                val index = prefs.getInt("index", 0).coerceIn(items.indices)
                player.setMediaItems(items, index, prefs.getLong("position", 0L).coerceAtLeast(0L))
                player.setPlaybackSpeed(prefs.getFloat("speed", 1f).takeIf { it in 0.5f..3f } ?: 1f)
                player.prepare()
            }
        }.onFailure { prefs.edit().clear().apply() }
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        persistPlayback()
        if (!player.playWhenReady) stopSelf()
    }


    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder().setMediaId("root").setMediaMetadata(MediaMetadata.Builder().setTitle("Kanso Podcast").setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS).build()).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = if (parentId == "root") podcastItems() else episodeItems(parentId.removePrefix("podcast:"))
            return Futures.immediateFuture(LibraryResult.ofItemList(items.drop(page * pageSize).take(pageSize), params))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, searchItems(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(LibraryResult.ofItemList(searchItems(query).drop(page * pageSize).take(pageSize), params))

        private fun podcastItems(): List<MediaItem> = subscriptions().map { podcast -> MediaItem.Builder().setMediaId("podcast:${podcast.feed.hashCode()}").setMediaMetadata(MediaMetadata.Builder().setTitle(podcast.title).setArtist(podcast.author).setArtworkUri(podcast.artwork?.let(android.net.Uri::parse)).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS).build()).build() }
        private fun episodeItems(hash: String): List<MediaItem> { val raw = getSharedPreferences("feed_cache", 0).getString("feed_$hash", null) ?: return emptyList(); val array = JSONArray(raw); return List(array.length()) { i -> array.getJSONObject(i).run { MediaItem.Builder().setMediaId(getString("id")).setUri(getString("audio")).setMediaMetadata(MediaMetadata.Builder().setTitle(getString("title")).setArtworkUri(optString("artwork").takeIf(String::isNotBlank)?.let(android.net.Uri::parse)).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE).build()).build() } } }
        private fun searchItems(query: String): List<MediaItem> = podcastItems().filter { it.mediaMetadata.title?.contains(query, true) == true }
        private fun subscriptions(): List<AutoPodcast> { val raw = getSharedPreferences("subscriptions", 0).getString("items", null) ?: return emptyList(); val array = JSONArray(raw); return List(array.length()) { i -> array.getJSONObject(i).run { AutoPodcast(getString("title"), getString("author"), getString("feed"), optString("artwork").ifBlank { null }) } } }
    }

    private data class AutoPodcast(val title: String, val author: String, val feed: String, val artwork: String?)
}
