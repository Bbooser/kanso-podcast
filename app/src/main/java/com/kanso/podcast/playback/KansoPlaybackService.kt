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
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MimeTypes
import androidx.media3.session.CommandButton
import com.google.android.gms.cast.framework.CastContext

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
    private var castPlayer: CastPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    /** The player currently behind the session (local ExoPlayer or Chromecast). */
    private val active: Player get() = if (::session.isInitialized) session.player else player

    // Stats: compares content time advanced vs. wall time to measure time saved by speed and silence trimming.
    private var tickCount = 0
    private var lastTickWall = 0L; private var lastTickPos = 0L; private var lastTickId: String? = null
    private var pendingListened = 0L; private var pendingSaved = 0L
    private val progressRunnable = object : Runnable {
        override fun run() {
            trackStats()
            if (++tickCount % 5 == 0) saveEpisodeProgress()
            if (tickCount % 15 == 0) flushStats()
            if (active.isPlaying) handler.postDelayed(this, TICK_MS)
        }
    }
    private fun resetTick() { lastTickWall = System.currentTimeMillis(); lastTickPos = active.currentPosition; lastTickId = active.currentMediaItem?.mediaId }
    private fun trackStats() {
        val now = System.currentTimeMillis(); val pos = active.currentPosition; val id = active.currentMediaItem?.mediaId
        val dWall = now - lastTickWall; val dPos = pos - lastTickPos
        if (id == lastTickId && dWall in 1..5_000 && dPos in 0..(dWall * 4)) { pendingListened += dWall; pendingSaved += (dPos - dWall).coerceAtLeast(0) }
        lastTickWall = now; lastTickPos = pos; lastTickId = id
    }
    private fun flushStats() {
        if (pendingListened == 0L && pendingSaved == 0L) return
        val l = pendingListened; val sv = pendingSaved; pendingListened = 0; pendingSaved = 0
        scope.launch { repo.addListening(l, sv) }
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
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SKIP_MS).setSeekForwardIncrementMs(SKIP_MS)
            .build()
        applyEffects()
        player.addListener(listener)
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30).setDisplayName("Retroceder 30 s").setPlayerCommand(Player.COMMAND_SEEK_BACK).setSlots(CommandButton.SLOT_BACK).build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setDisplayName("Adelantar 30 s").setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setSlots(CommandButton.SLOT_FORWARD).build()
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).setMediaButtonPreferences(ImmutableList.of(back, forward)).build()
        publishExtras()
        scope.launch { restorePlayback() }
    }

    private val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!restored) return
                persistSession()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) persistQueue()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(progressRunnable)
                if (!isPlaying) trackStats()
                saveEpisodeProgress(); flushStats(); resetTick()
                if (isPlaying) handler.postDelayed(progressRunnable, TICK_MS)
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) { applyEffects() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = currentEpisodeId
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null) markFinished(previous)
                currentEpisodeId = mediaItem?.mediaId
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && player.pauseAtEndOfMediaItems) clearSleepTimer()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { currentEpisodeId?.let(::markFinished); if (player.pauseAtEndOfMediaItems) clearSleepTimer() }
            }
    }

    // --- Audio effects: trim silence + voice boost -------------------------------------------
    private fun applyEffects() {
        val prefs = getSharedPreferences("playback_effects", MODE_PRIVATE)
        player.skipSilenceEnabled = prefs.getBoolean("trim_silence", false)
        val boost = prefs.getInt("boost", 0)
        runCatching {
            loudness?.release(); loudness = null
            if (boost > 0 && player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) loudness = LoudnessEnhancer(player.audioSessionId).apply { setTargetGain(boost * 600); enabled = true }
        }
        if (::session.isInitialized) publishExtras()
    }

    // --- Chromecast: only initialised when the user taps the Cast button ---------------------
    private fun enableCast() {
        if (castPlayer != null) return
        runCatching {
            val cast = CastPlayer(CastContext.getSharedInstance(this))
            cast.addListener(listener)
            cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() { switchTo(cast) }
                override fun onCastSessionUnavailable() { switchTo(player) }
            })
            castPlayer = cast
            if (cast.isCastSessionAvailable) switchTo(cast)
        }.onFailure { android.util.Log.w("KansoCast", "Cast unavailable", it) }
    }

    private fun switchTo(target: Player) {
        val current = session.player
        if (current === target) return
        val count = current.mediaItemCount
        val items = (0 until count).map { current.getMediaItemAt(it) }
        val index = current.currentMediaItemIndex.coerceAtLeast(0); val position = current.currentPosition.coerceAtLeast(0); val play = current.playWhenReady
        saveEpisodeProgress()
        current.stop()
        scope.launch {
            val prepared = if (target === player) items.map(::localized) else items.map { remoteItem(it) }
            if (prepared.isNotEmpty()) { target.setMediaItems(prepared, index.coerceIn(prepared.indices), position); target.prepare(); target.playWhenReady = play }
            session.player = target
        }
    }

    private fun localized(item: MediaItem): MediaItem {
        val uri = EpisodeDownloads.localFile(this, item.mediaId)?.let(Uri::fromFile) ?: return item
        return item.buildUpon().setUri(uri).build()
    }

    /** Cast receivers cannot read local files and need a MIME type, so always send the remote URL. */
    private suspend fun remoteItem(item: MediaItem): MediaItem {
        val remote = repo.episode(item.mediaId)?.audioUrl ?: item.localConfiguration?.uri?.toString().orEmpty()
        val mime = when (EpisodeDownloads.extensionFor(remote)) { "m4a", "mp4", "m4b", "aac" -> MimeTypes.AUDIO_MP4; "ogg", "oga", "opus" -> MimeTypes.AUDIO_OGG; "wav" -> MimeTypes.AUDIO_WAV; "flac" -> MimeTypes.AUDIO_FLAC; else -> MimeTypes.AUDIO_MPEG }
        return item.buildUpon().setUri(remote).setMimeType(mime).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        trackStats(); val l = pendingListened; val sv = pendingSaved
        if (l > 0) runBlocking { repo.addListening(l, sv) }
        if (session.player !== player) { session.player = player }
        castPlayer?.setSessionAvailabilityListener(null); castPlayer?.release()
        runCatching { loudness?.release() }
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
        val p = active
        val id = p.currentMediaItem?.mediaId ?: return
        if (p.playbackState == Player.STATE_ENDED) return
        val position = p.currentPosition.coerceAtLeast(0)
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
        publishExtras()
    }

    private fun publishExtras() {
        val prefs = getSharedPreferences("playback_effects", MODE_PRIVATE)
        session.setSessionExtras(Bundle().apply {
            putLong(EXTRA_SLEEP_AT, sleepAt); putBoolean(EXTRA_SLEEP_END_OF_EPISODE, player.pauseAtEndOfMediaItems)
            putBoolean(EXTRA_TRIM_SILENCE, prefs.getBoolean("trim_silence", false)); putInt(EXTRA_BOOST, prefs.getInt("boost", 0))
            putBoolean(EXTRA_CASTING, session.player !== player)
        })
    }
    private fun clearSleepTimer() = setSleepTimer(0)

    private fun snapshotQueue(): List<QueueEntry> = (0 until active.mediaItemCount).mapNotNull { index ->
        val item = active.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        if (uri.isBlank()) null else QueueEntry(index, item.mediaId, uri, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.artworkUri?.toString())
    }.mapIndexed { i, e -> e.copy(position = i) }

    private fun persistQueue() { val items = snapshotQueue(); scope.launch { repo.replaceQueue(items) } }

    private fun persistSession() {
        getSharedPreferences("playback_session", MODE_PRIVATE).edit()
            .putInt("index", active.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", active.currentPosition.coerceAtLeast(0))
            .putFloat("speed", active.playbackParameters.speed)
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
                .add(SessionCommand(COMMAND_SLEEP_TIMER, Bundle.EMPTY)).add(SessionCommand(COMMAND_EFFECTS, Bundle.EMPTY)).add(SessionCommand(COMMAND_ENABLE_CAST, Bundle.EMPTY)).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_SLEEP_TIMER) {
                setSleepTimer(args.getInt(ARG_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == COMMAND_EFFECTS) {
                val edit = getSharedPreferences("playback_effects", MODE_PRIVATE).edit()
                if (args.containsKey(EXTRA_TRIM_SILENCE)) edit.putBoolean("trim_silence", args.getBoolean(EXTRA_TRIM_SILENCE))
                if (args.containsKey(EXTRA_BOOST)) edit.putInt("boost", args.getInt(EXTRA_BOOST).coerceIn(0, 2))
                edit.commit(); applyEffects()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == COMMAND_ENABLE_CAST) { enableCast(); return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
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
        const val COMMAND_EFFECTS = "com.kanso.podcast.EFFECTS"
        const val COMMAND_ENABLE_CAST = "com.kanso.podcast.ENABLE_CAST"
        const val EXTRA_TRIM_SILENCE = "trim_silence"
        const val EXTRA_BOOST = "boost"
        const val EXTRA_CASTING = "casting"
        const val SKIP_MS = 30_000L
        private const val TICK_MS = 1_000L
    }
}
