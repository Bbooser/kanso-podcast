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

/** Shared spacing and shapes so every screen breathes the same way. */
object Kanso {
    val screenPadding = 20.dp
    val itemGap = 14.dp
    val sectionGap = 24.dp
    val card = RoundedCornerShape(20.dp)
    val art = RoundedCornerShape(16.dp)
}

private val SharingStarted5s = SharingStarted.WhileSubscribed(5_000)
internal fun <T> Flow<T>.stateIn(vm: AndroidViewModel, initial: T): StateFlow<T> = stateIn(vm.viewModelScope, SharingStarted5s, initial)

@Composable internal fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
        }
        actions()
    }
}

@Composable internal fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
    }
}

@Composable internal fun EmptyState(icon: ImageVector, title: String, message: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (message != null) Text(message, color = MaterialTheme.colorScheme.onSurface.copy(.55f), textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(18.dp)); Button(onClick = onAction) { Text(action) } }
    }
}

@Composable internal fun Artwork(url: String?, description: String, size: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape = Kanso.art) {
    AsyncImage(url, description, Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
}

/** Human summary of where the listener is in an episode: "Quedan 12 min", "Escuchado", "45 min". */
internal fun episodeMeta(e: EpisodeEntity): Pair<String, Float> {
    val total = parseDurationMs(e.duration)
    val fraction = if (total > 0 && e.progressMs > 0 && !e.listened) (e.progressMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val tail = when {
        e.listened -> "Escuchado"
        fraction > 0f -> "Quedan ${formatDuration(total - e.progressMs)}"
        e.progressMs > 0 -> "En progreso"
        total > 0 -> formatDuration(total)
        e.duration != "—" -> e.duration
        else -> null
    }
    return listOfNotNull(e.published.takeIf { it.isNotBlank() }, tail).joinToString(" · ") to fraction
}

/**
 * One episode, used everywhere. Played/archived episodes are dimmed; in-progress ones show a thin progress bar.
 * [podcastTitle]/[artwork] are shown in cross-podcast lists; [onOpenPodcast] makes the row open its podcast.
 */
@Composable internal fun EpisodeCard(
    episode: EpisodeEntity, current: Boolean, downloaded: Boolean,
    onPlay: () -> Unit, onQueue: () -> Unit, onListened: () -> Unit, onFavorite: () -> Unit, onDownload: () -> Unit, onArchive: () -> Unit,
    podcastTitle: String? = null, artwork: String? = null, onOpenPodcast: (() -> Unit)? = null, showDescription: Boolean = true,
) {
    val (meta, fraction) = episodeMeta(episode)
    val dim = episode.listened || episode.archived
    Column(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (onOpenPodcast != null) Modifier.clickable(onClick = onOpenPodcast) else Modifier).padding(16.dp)) {
        Row(Modifier.alpha(if (dim) .55f else 1f), verticalAlignment = Alignment.CenterVertically) {
            if (podcastTitle != null) { Artwork(episode.artworkUrl ?: artwork, episode.title, 52.dp, RoundedCornerShape(12.dp)); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                if (podcastTitle != null) Text(podcastTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(episode.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = onPlay, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Icon(if (current) Icons.Default.GraphicEq else Icons.Default.PlayArrow, "Reproducir", tint = Color.White)
            }
        }
        if (fraction > 0f) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().padding(top = 12.dp).height(3.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.onSurface.copy(.1f))
        if (showDescription && episode.description.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(episode.description, Modifier.alpha(if (dim) .55f else 1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(.75f)) }
        Row(Modifier.padding(top = 4.dp).offset(x = (-12).dp)) {
            IconButton(onClick = onFavorite) { Icon(if (episode.favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, "Favorito", tint = if (episode.favorite) MaterialTheme.colorScheme.secondary else LocalContentColor.current) }
            IconButton(onClick = onListened) { Icon(if (episode.listened) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle, if (episode.listened) "Marcar como no escuchado" else "Marcar como escuchado") }
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, "Añadir a la cola") }
            IconButton(onClick = onDownload, enabled = !downloaded) { Icon(if (downloaded) Icons.Default.OfflinePin else Icons.Outlined.Download, if (downloaded) "Descargado" else "Descargar") }
            IconButton(onClick = onArchive) { Icon(if (episode.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (episode.archived) "Restaurar" else "Archivar") }
        }
    }
}

/** Episode actions wired to the repository; shared by every list. */
internal class EpisodeActions(private val context: Context, private val scope: kotlinx.coroutines.CoroutineScope) {
    private val repo = KansoRepository.get(context)
    fun toggleListened(e: EpisodeEntity) { scope.launch { repo.setListened(e.id, !e.listened) } }
    fun toggleFavorite(e: EpisodeEntity) { scope.launch { repo.setFavorite(e.id, !e.favorite) } }
    fun toggleArchived(e: EpisodeEntity) { scope.launch { repo.setArchived(e.id, !e.archived) } }
    fun download(e: EpisodeEntity, podcastTitle: String?) { scope.launch { EpisodeDownloads.enqueue(context, e.id, e.audioUrl, e.title, podcastTitle) } }
}

@Composable internal fun rememberEpisodeActions(): EpisodeActions { val context = LocalContext.current; val scope = rememberCoroutineScope(); return remember { EpisodeActions(context.applicationContext, scope) } }

/** Parses RSS itunes:duration ("HH:MM:SS", "MM:SS" or seconds) into milliseconds; 0 if unknown. */
internal fun parseDurationMs(value: String): Long {
    val parts = value.trim().split(":").map { it.trim().toLongOrNull() ?: return 0L }
    if (parts.isEmpty() || parts.size > 3) return 0L
    return parts.fold(0L) { acc, n -> acc * 60 + n } * 1000
}
internal fun formatDuration(ms: Long): String { val minutes = (ms / 60_000).coerceAtLeast(1); return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min" }
internal fun formatTime(ms: Long): String { val total = ms / 1000; val h = total / 3600; return if (h > 0) "%d:%02d:%02d".format(h, (total % 3600) / 60, total % 60) else "%d:%02d".format(total / 60, total % 60) }
