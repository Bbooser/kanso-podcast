package com.kanso.podcast.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kanso.podcast.data.EpisodeDownloads
import com.kanso.podcast.data.KansoRepository
import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.QueueEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Single playback authority for the phone UI, notification, widget and Android Auto.
 * Owns per-episode progress, finished/archived marking, the persisted queue and the sleep timer.
 */
@UnstableApi
class KansoPlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var repo: KansoRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var restored = false
    private var sleepAt = 0L
    private var currentEpisodeId: String? = null
    private val sleepRunnable = Runnable { player.pause(); clearSleepTimer() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            saveEpisodeProgress()
            if (player.isPlaying) handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = KansoRepository.get(this)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("KansoPodcast/0.2.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!restored) return
                persistSession()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) persistQueue()
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
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && player.pauseAtEndOfMediaItems) clearSleepTimer()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { currentEpisodeId?.let(::markFinished); if (player.pauseAtEndOfMediaItems) clearSleepTimer() }
            }
        })
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        scope.launch { restorePlayback() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val id = player.currentMediaItem?.mediaId; val position = player.currentPosition
        if (id != null && player.playbackState != Player.STATE_ENDED) runBlocking { repo.setProgress(id, position.coerceAtLeast(0)) }
        if (restored) { persistSession(); runBlocking { repo.replaceQueue(snapshotQueue()) } }
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        saveEpisodeProgress()
        if (!player.playWhenReady) stopSelf()
    }

    /** Per-episode resume position, saved here so it works with the screen off or the UI closed. */
    private fun saveEpisodeProgress() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        val position = player.currentPosition.coerceAtLeast(0)
        scope.launch { repo.setProgress(id, position) }
    }

    private fun markFinished(episodeId: String) { scope.launch { repo.markFinished(episodeId) } }

    /** minutes > 0: pause after N minutes; END_OF_EPISODE: pause when the current episode ends; 0: cancel. Never stacks. */
    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        player.pauseAtEndOfMediaItems = false
        sleepAt = 0L
        when {
            minutes == END_OF_EPISODE -> player.pauseAtEndOfMediaItems = true
            minutes > 0 -> { sleepAt = System.currentTimeMillis() + minutes * 60_000L; handler.postDelayed(sleepRunnable, minutes * 60_000L) }
        }
        session.setSessionExtras(Bundle().apply { putLong(EXTRA_SLEEP_AT, sleepAt); putBoolean(EXTRA_SLEEP_END_OF_EPISODE, player.pauseAtEndOfMediaItems) })
    }
    private fun clearSleepTimer() = setSleepTimer(0)

    private fun snapshotQueue(): List<QueueEntry> = (0 until player.mediaItemCount).mapNotNull { index ->
        val item = player.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        if (uri.isBlank()) null else QueueEntry(index, item.mediaId, uri, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.artworkUri?.toString())
    }.mapIndexed { i, e -> e.copy(position = i) }

    private fun persistQueue() { val items = snapshotQueue(); scope.launch { repo.replaceQueue(items) } }

    private fun persistSession() {
        getSharedPreferences("playback_session", MODE_PRIVATE).edit()
            .putInt("index", player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putFloat("speed", player.playbackParameters.speed)
            .apply()
    }

    private suspend fun restorePlayback() {
        val prefs = getSharedPreferences("playback_session", MODE_PRIVATE)
        runCatching {
            val entries = repo.queue()
            if (entries.isNotEmpty() && player.mediaItemCount == 0) {
                val items = entries.map { e ->
                    val uri = if (e.uri.startsWith("file:") && EpisodeDownloads.localFile(this, e.episodeId) == null) repo.episode(e.episodeId)?.audioUrl ?: e.uri else e.uri
                    MediaItem.Builder().setMediaId(e.episodeId).setUri(uri)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).setArtist(e.artist).setArtworkUri(e.artworkUrl?.let(Uri::parse)).build()).build()
                }
                val index = prefs.getInt("index", 0).coerceIn(items.indices)
                player.setMediaItems(items, index, prefs.getLong("position", 0L).coerceAtLeast(0L))
                player.setPlaybackSpeed(prefs.getFloat("speed", 1f).takeIf { it in 0.5f..3f } ?: 1f)
                player.prepare()
            }
        }
        currentEpisodeId = player.currentMediaItem?.mediaId
        restored = true
    }

    private fun episodeItem(e: EpisodeEntity, podcastTitle: String?, podcastArtwork: String?): MediaItem =
        MediaItem.Builder().setMediaId(e.id).setUri(EpisodeDownloads.playableUri(this, e.id, e.audioUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).setArtist(podcastTitle)
                .setArtworkUri((e.artworkUrl ?: podcastArtwork)?.let(Uri::parse))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE).build()).build()

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
        /** Controllers such as Android Auto send only a media id; resolve it to a playable item. */
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else repo.episodeRow(item.mediaId)?.let { episodeItem(it.episode, it.podcastTitle, it.podcastArtwork) } ?: item
            }.toMutableList()
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder().setMediaId(ROOT).setMediaMetadata(MediaMetadata.Builder().setTitle("Kanso Podcast").setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS).build()).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = if (parentId == ROOT) podcastItems() else episodeItems(parentId.removePrefix(PODCAST_PREFIX))
            LibraryResult.ofItemList(items.drop(page * pageSize).take(pageSize), params)
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, searchItems(query).size, params)
            LibraryResult.ofVoid()
        }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(searchItems(query).drop(page * pageSize).take(pageSize), params)
        }
        private suspend fun podcastItems(): List<MediaItem> = repo.podcastList().map { p ->
            MediaItem.Builder().setMediaId(PODCAST_PREFIX + p.feedUrl).setMediaMetadata(MediaMetadata.Builder().setTitle(p.title).setArtist(p.author)
                .setArtworkUri(p.artworkUrl?.let(Uri::parse)).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST).build()).build()
        }
        private suspend fun episodeItems(feedUrl: String): List<MediaItem> {
            val podcast = repo.podcastList().firstOrNull { it.feedUrl == feedUrl }
            return repo.episodeList(feedUrl).filter { !it.archived }.map { episodeItem(it, podcast?.title, podcast?.artworkUrl) }
        }
        private suspend fun searchItems(query: String): List<MediaItem> =
            podcastItems().filter { it.mediaMetadata.title?.contains(query, true) == true } +
                repo.searchEpisodes(query).map { episodeItem(it.episode, it.podcastTitle, it.podcastArtwork) }
    }

    companion object {
        const val COMMAND_SLEEP_TIMER = "com.kanso.podcast.SLEEP_TIMER"
        const val ARG_MINUTES = "minutes"
        const val END_OF_EPISODE = -1
        const val EXTRA_SLEEP_AT = "sleep_at"
        const val EXTRA_SLEEP_END_OF_EPISODE = "sleep_end_of_episode"
        private const val ROOT = "root"
        private const val PODCAST_PREFIX = "podcast:"
        private const val PROGRESS_INTERVAL_MS = 5_000L
    }
}
