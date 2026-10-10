@file:androidx.media3.common.util.UnstableApi
package com.kanso.podcast.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.kanso.podcast.data.EpisodeDownloads
import com.kanso.podcast.data.KansoRepository
import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.EpisodeRow
import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.kanso.podcast.playback.KansoPlaybackService
import com.kanso.podcast.widget.KansoWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

internal val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f, 2.5f, 3f)

/** UI-side mirror of the playback session. State lives in the service; this only observes and sends commands. */
internal class PlayerConnection(private val context: Context) {
    private val repo = KansoRepository.get(context)
    val scope: CoroutineScope = MainScope()
    var controller: MediaController? = null
    var title by mutableStateOf("")
    var podcast by mutableStateOf("")
    var playing by mutableStateOf(false)
    var episodeId by mutableStateOf("")
    var artwork by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var openRequest by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var speed by mutableFloatStateOf(1f)
    var currentIndex by mutableIntStateOf(0)
    var sleepAt by mutableLongStateOf(0L)
    var sleepEndOfEpisode by mutableStateOf(false)
    val queue = mutableStateListOf<String>()
    val upNext: List<String> get() = queue.drop(currentIndex + 1)
    private var pending: Triple<EpisodeEntity, String, String?>? = null

    private fun mediaItemFor(e: EpisodeEntity, podcastTitle: String, fallbackArtwork: String?) = MediaItem.Builder().setMediaId(e.id).setUri(EpisodeDownloads.playableUri(context, e.id, e.audioUrl))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).setArtist(podcastTitle).setArtworkUri((e.artworkUrl ?: fallbackArtwork)?.let(Uri::parse)).build()).build()

    /** Plays [e] next to the current item without clearing the queue (or jumps to it if already queued). */
    fun play(e: EpisodeEntity, podcastTitle: String, fallbackArtwork: String? = null) {
        title = e.title; podcast = podcastTitle; artwork = e.artworkUrl ?: fallbackArtwork; episodeId = e.id; openRequest++
        val c = controller ?: run { pending = Triple(e, podcastTitle, fallbackArtwork); return }
        scope.launch {
            val saved = repo.episode(e.id)?.takeIf { !it.listened }?.progressMs ?: 0L
            error = null
            val existing = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == e.id }
            val item = mediaItemFor(e, podcastTitle, fallbackArtwork)
            when {
                existing != null -> c.seekTo(existing, saved)
                c.mediaItemCount == 0 -> c.setMediaItem(item, saved)
                else -> { val target = c.currentMediaItemIndex + 1; c.addMediaItem(target, item); c.seekTo(target, saved) }
            }
            c.prepare(); c.playWhenReady = true
            context.getSharedPreferences("now_playing", 0).edit().putString("title", e.title).putString("podcast", podcastTitle).apply()
            launch(Dispatchers.IO) { KansoWidget().updateAll(context) }
        }
    }
    fun enqueue(e: EpisodeEntity, podcastTitle: String, fallbackArtwork: String? = null) {
        val c = controller ?: return
        if ((0 until c.mediaItemCount).any { c.getMediaItemAt(it).mediaId == e.id }) return
        c.addMediaItem(mediaItemFor(e, podcastTitle, fallbackArtwork))
        if (c.mediaItemCount == 1) c.prepare()
    }
    /** [displayIndex] is relative to [upNext]. */
    fun removeQueued(displayIndex: Int) { val c = controller ?: return; val actual = c.currentMediaItemIndex + 1 + displayIndex; if (actual in 0 until c.mediaItemCount) c.removeMediaItem(actual) }
    fun playQueued(displayIndex: Int) { val c = controller ?: return; val actual = c.currentMediaItemIndex + 1 + displayIndex; if (actual in 0 until c.mediaItemCount) { c.seekTo(actual, 0); c.play() } }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekBy(delta: Long) { controller?.let { it.seekTo((it.currentPosition + delta).coerceAtLeast(0)) } }
    fun cycleSpeed() { speed = SPEEDS.firstOrNull { it > speed + 0.01f } ?: SPEEDS.first(); controller?.setPlaybackSpeed(speed) }
    fun setSleepTimer(minutes: Int) { controller?.sendCustomCommand(SessionCommand(KansoPlaybackService.COMMAND_SLEEP_TIMER, Bundle.EMPTY), Bundle().apply { putInt(KansoPlaybackService.ARG_MINUTES, minutes) }) }
    fun readSleepExtras(extras: Bundle) { sleepAt = extras.getLong(KansoPlaybackService.EXTRA_SLEEP_AT, 0L); sleepEndOfEpisode = extras.getBoolean(KansoPlaybackService.EXTRA_SLEEP_END_OF_EPISODE, false) }
    fun retry() { controller?.prepare(); controller?.playWhenReady = true }

    fun attach(c: MediaController) {
        controller = c
        fun syncQueue() { currentIndex = c.currentMediaItemIndex.coerceAtLeast(0); queue.clear(); for (i in 0 until c.mediaItemCount) queue.add(c.getMediaItemAt(i).mediaMetadata.title?.toString().orEmpty()) }
        fun syncMetadata() { c.currentMediaItem?.let { item -> title = item.mediaMetadata.title?.toString().orEmpty(); podcast = item.mediaMetadata.artist?.toString().orEmpty(); artwork = item.mediaMetadata.artworkUri?.toString(); episodeId = item.mediaId } }
        syncQueue(); syncMetadata(); playing = c.isPlaying; speed = c.playbackParameters.speed; readSleepExtras(c.sessionExtras)
        pending?.let { (e, t, a) -> pending = null; play(e, t, a) }
        c.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; context.getSharedPreferences("now_playing", 0).edit().putBoolean("is_playing", isPlaying).apply(); scope.launch(Dispatchers.IO) { KansoWidget().updateAll(context) } }
            override fun onMediaMetadataChanged(metadata: MediaMetadata) { syncMetadata(); syncQueue() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { syncMetadata(); syncQueue() }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { syncQueue() }
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) { speed = playbackParameters.speed }
            override fun onPlayerError(e: PlaybackException) { error = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "El servidor rechazó la reproducción."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No hay conexión con el servidor del podcast."
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "Este episodio usa una conexión no segura (http)."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Este formato de audio no es compatible."
                else -> e.localizedMessage ?: "No se pudo reproducir este episodio."
            } }
        })
    }
}

@Composable internal fun rememberPlayerConnection(): PlayerConnection {
    val context = LocalContext.current
    val connection = remember { PlayerConnection(context.applicationContext) }
    DisposableEffect(Unit) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, KansoPlaybackService::class.java)))
            .setListener(object : MediaController.Listener { override fun onExtrasChanged(controller: MediaController, extras: Bundle) { connection.readSleepExtras(extras) } })
            .buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess(connection::attach) }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future); connection.controller = null; connection.scope.cancel() }
    }
    LaunchedEffect(Unit) { while (true) { connection.controller?.let { connection.position = it.currentPosition.coerceAtLeast(0); connection.duration = it.duration.coerceAtLeast(0) }; delay(500) } }
    return connection
}
