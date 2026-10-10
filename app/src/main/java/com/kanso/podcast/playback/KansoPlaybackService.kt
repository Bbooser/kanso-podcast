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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
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
    private val handler = Handler(Looper.getMainLooper())
    private var sleepAt = 0L
    private val sleepRunnable = Runnable { player.pause(); clearSleepTimer() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            saveEpisodeProgress()
            if (player.isPlaying) handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }
    private var currentEpisodeId: String? = null

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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(progressRunnable)
                saveEpisodeProgress()
                if (isPlaying) handler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = currentEpisodeId
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null) markFinished(previous)
                currentEpisodeId = mediaItem?.mediaId
                // "End of episode" sleep timer: stop once the episode that was playing has finished.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && player.pauseAtEndOfMediaItems) clearSleepTimer()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { currentEpisodeId?.let(::markFinished); if (player.pauseAtEndOfMediaItems) clearSleepTimer() }
            }
        })
        currentEpisodeId = player.currentMediaItem?.mediaId
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveEpisodeProgress()
        persistPlayback()
        session.release()
        player.release()
        super.onDestroy()
    }

    /** Per-episode resume position, saved by the service so it works with the screen off or the UI closed. */
    private fun saveEpisodeProgress() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        getSharedPreferences("progress", MODE_PRIVATE).edit().putLong(id, player.currentPosition.coerceAtLeast(0)).apply()
    }

    private fun markFinished(episodeId: String) {
        getSharedPreferences("progress", MODE_PRIVATE).edit().remove(episodeId).apply()
        val prefs = getSharedPreferences("episode_state", MODE_PRIVATE)
        val listened = (prefs.getStringSet("listened", emptySet()) ?: emptySet()) + episodeId
        var archived = prefs.getStringSet("archived", emptySet()) ?: emptySet()
        val favorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (getSharedPreferences("automation", MODE_PRIVATE).getBoolean("archive_finished", true) && episodeId !in favorites) archived = archived + episodeId
        prefs.edit().putStringSet("listened", listened).putStringSet("archived", archived).apply()
    }

    /** minutes > 0: pause after N minutes; END_OF_EPISODE: pause when the current episode ends; 0: cancel. Never stacks. */
    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        player.pauseAtEndOfMediaItems = false
        sleepAt = 0L
        when {
            minutes == END_OF_EPISODE -> player.pauseAtEndOfMediaItems = true
            minutes > 0 -> { sleepAt = System.currentTimeMillis() + minutes * 60_000L; handler.postDelayed(sleepRunnable, minutes * 60_000L) }
        }
        publishSleepState()
    }

    private fun clearSleepTimer() = setSleepTimer(0)

    private fun publishSleepState() {
        if (!::session.isInitialized) return
        session.setSessionExtras(Bundle().apply { putLong(EXTRA_SLEEP_AT, sleepAt); putBoolean(EXTRA_SLEEP_END_OF_EPISODE, player.pauseAtEndOfMediaItems) })
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
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SLEEP_TIMER, Bundle.EMPTY)).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_SLEEP_TIMER) {
                setSleepTimer(args.getInt(ARG_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
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

    companion object {
        const val COMMAND_SLEEP_TIMER = "com.kanso.podcast.SLEEP_TIMER"
        const val ARG_MINUTES = "minutes"
        const val END_OF_EPISODE = -1
        const val EXTRA_SLEEP_AT = "sleep_at"
        const val EXTRA_SLEEP_END_OF_EPISODE = "sleep_end_of_episode"
        private const val PROGRESS_INTERVAL_MS = 5_000L
    }
    private data class AutoPodcast(val title: String, val author: String, val feed: String, val artwork: String?)
}
