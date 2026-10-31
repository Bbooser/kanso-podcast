@file:androidx.media3.common.util.UnstableApi
package com.kanso.podcast.ui

import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.kanso.podcast.data.db.*
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
import androidx.compose.foundation.lazy.LazyListScope

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

@Composable internal fun Artwork(url: String?, description: String, size: Dp, shape: androidx.compose.ui.graphics.Shape = Kanso.art) {
    AsyncImage(url, description, Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
}

/** Remaining/total label in the Pocket style: "23 min restantes", "1 h 5 min", "Escuchado". */
internal fun remainingLabel(e: EpisodeEntity): String {
    val total = parseDurationMs(e.duration)
    return when {
        e.listened -> "Escuchado"
        total > 0 && e.progressMs > 0 -> "${formatDuration(total - e.progressMs)} restantes"
        e.progressMs > 0 -> "En progreso"
        total > 0 -> formatDuration(total)
        else -> ""
    }
}
internal fun progressFraction(e: EpisodeEntity): Float { val total = parseDurationMs(e.duration); return if (total > 0 && e.progressMs > 0 && !e.listened) (e.progressMs.toFloat() / total).coerceIn(0f, 1f) else 0f }
internal fun remainingMs(e: EpisodeEntity): Long { val total = parseDurationMs(e.duration); return if (e.listened) 0 else (total - e.progressMs).coerceAtLeast(0) }

/**
 * Compact episode row used in every list: artwork, date, title, remaining time and a play button ringed with progress.
 * Swipe right to add to "A continuación"; tap to open the episode sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable internal fun EpisodeListRow(row: EpisodeRow, current: Boolean, playing: Boolean, downloaded: Boolean, onPlay: () -> Unit, onQueue: () -> Unit, onClick: () -> Unit, showArtwork: Boolean = true, onLongClick: (() -> Unit)? = null, selected: Boolean? = null) {
    val e = row.episode
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value -> if (value == SwipeToDismissBoxValue.StartToEnd && selected == null) onQueue(); false })
    SwipeToDismissBox(state, enableDismissFromEndToStart = false, backgroundContent = {
        Row(Modifier.fillMaxSize().clip(Kanso.art).background(MaterialTheme.colorScheme.secondary.copy(.85f)).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("A continuación", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }) {
        Row(Modifier.fillMaxWidth().background(if (selected == true) MaterialTheme.colorScheme.secondary.copy(.12f) else MaterialTheme.colorScheme.background)
            .then(if (selected != null) Modifier.toggleable(selected, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { onClick() })
                  else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "Seleccionar" else null))
            .padding(vertical = 10.dp).alpha(if (e.listened || e.archived) .5f else 1f), verticalAlignment = Alignment.CenterVertically) {
            if (showArtwork) { Artwork(e.artworkUrl ?: row.podcastArtwork, e.title, 56.dp, RoundedCornerShape(10.dp)); Spacer(Modifier.width(Kanso.itemGap)) }
            Column(Modifier.weight(1f)) {
                Text(e.published.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(.5f), maxLines = 1)
                Text(e.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (downloaded) { Icon(Icons.Default.OfflinePin, "Descargado", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(4.dp)) }
                    if (e.favorite) { Icon(Icons.Default.Star, "Destacado", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(4.dp)) }
                    Text(remainingLabel(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (selected != null) Checkbox(selected, onCheckedChange = null)
            else PlayRing(progressFraction(e), current && playing, onPlay)
        }
    }
}

@Composable internal fun PlayRing(progress: Float, playing: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { progress }, Modifier.size(38.dp), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.secondary.copy(.25f), strokeWidth = 2.dp)
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pausar" else "Reproducir", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
    }
}

/** Episode details and actions. Looks up the latest state by id so toggles update immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EpisodeSheet(row: EpisodeRow, player: PlayerConnection, downloaded: Boolean, onDismiss: () -> Unit, onOpenPodcast: ((PodcastSearchResult) -> Unit)?) {
    val e = row.episode; val actions = rememberEpisodeActions(); val title = row.podcastTitle.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = Kanso.screenPadding, end = Kanso.screenPadding, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(e.artworkUrl ?: row.podcastArtwork, e.title, 72.dp)
                    Spacer(Modifier.width(Kanso.itemGap))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                        Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(listOf(e.published, remainingLabel(e)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { player.play(e, title, row.podcastArtwork); onDismiss() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (e.progressMs > 0 && !e.listened) "Continuar" else "Reproducir") }
                    FilledTonalButton(onClick = { player.enqueue(e, title, row.podcastArtwork); onDismiss() }, Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("A la cola") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SheetAction(if (e.favorite) Icons.Default.Star else Icons.Outlined.StarBorder, if (e.favorite) "Destacado" else "Destacar", e.favorite) { actions.toggleFavorite(e) }
                    SheetAction(if (e.listened) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle, if (e.listened) "Escuchado" else "Marcar", e.listened) { actions.toggleListened(e) }
                    SheetAction(if (downloaded) Icons.Default.OfflinePin else Icons.Outlined.Download, if (downloaded) "Descargado" else "Descargar", downloaded) { if (!downloaded) actions.download(e, title) }
                    SheetAction(if (e.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (e.archived) "Restaurar" else "Archivar", e.archived) { actions.toggleArchived(e) }
                }
            }
            if (onOpenPodcast != null) item {
                TextButton(onClick = { onDismiss(); onOpenPodcast(PodcastSearchResult(e.podcastFeedUrl, title, row.podcastAuthor.orEmpty(), e.podcastFeedUrl, row.podcastArtwork)) }) { Icon(Icons.Outlined.Podcasts, null); Spacer(Modifier.width(6.dp)); Text("Ir al podcast") }
            }
            if (e.description.isNotBlank()) item { HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(.3f)); Spacer(Modifier.height(4.dp)); Text(e.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.8f)) }
        }
    }
}

@Composable private fun SheetAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(Kanso.art).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(.75f))
        Spacer(Modifier.height(4.dp)); Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** A list of episode rows with its own sheet; used by playlists, profile pages and podcast detail. */
internal fun LazyListScope.episodeRows(rows: List<EpisodeRow>, player: PlayerConnection, downloaded: Set<String>, onSelect: (String) -> Unit, showArtwork: Boolean = true, keyPrefix: String = "", onLongSelect: ((String) -> Unit)? = null, selection: Set<String>? = null) {
    items(rows, key = { keyPrefix + it.episode.id }) { row ->
        val e = row.episode
        EpisodeListRow(row, player.episodeId == e.id, player.playing, e.id in downloaded,
            onPlay = { if (player.episodeId == e.id) player.toggle() else player.play(e, row.podcastTitle.orEmpty(), row.podcastArtwork) },
            onQueue = { player.enqueue(e, row.podcastTitle.orEmpty(), row.podcastArtwork) }, onClick = { onSelect(e.id) }, showArtwork = showArtwork,
            onLongClick = onLongSelect?.let { f -> { f(e.id) } }, selected = selection?.let { e.id in it })
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(.15f))
    }
}

@Composable internal fun EpisodeSheetHost(selectedId: String?, rows: List<EpisodeRow>, player: PlayerConnection, downloaded: Set<String>, onDismiss: () -> Unit, onOpenPodcast: ((PodcastSearchResult) -> Unit)?) {
    val row = selectedId?.let { id -> rows.firstOrNull { it.episode.id == id } } ?: return
    EpisodeSheet(row, player, row.episode.id in downloaded, onDismiss, onOpenPodcast)
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
