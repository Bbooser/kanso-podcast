@file:androidx.media3.common.util.UnstableApi
package com.kanso.podcast.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
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

internal enum class PlayedRule(val key: String, val label: String) { Any("ANY", "Todos"), NotPlayed("NOT_PLAYED", "Sin terminar"), Unplayed("UNPLAYED", "Sin empezar"), InProgress("IN_PROGRESS", "En curso"), Played("PLAYED", "Escuchados");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: Any } }

/** Applies a smart playlist's rules. Unknown durations are excluded only when a duration rule is set. */
internal fun PlaylistEntity.matching(rows: List<EpisodeRow>, downloaded: Set<String>, now: Long = System.currentTimeMillis()): List<EpisodeRow> {
    val feeds = feeds
    val result = rows.filter { row ->
        val e = row.episode
        val minutes = parseDurationMs(e.duration) / 60_000
        (feeds.isEmpty() || e.podcastFeedUrl in feeds) &&
            (includeArchived || !e.archived) &&
            when (PlayedRule.of(playedState)) {
                PlayedRule.Any -> true
                PlayedRule.NotPlayed -> !e.listened
                PlayedRule.Unplayed -> !e.listened && e.progressMs == 0L
                PlayedRule.InProgress -> !e.listened && e.progressMs > 0
                PlayedRule.Played -> e.listened
            } &&
            (!downloadedOnly || e.id in downloaded) &&
            (minMinutes == null || (minutes > 0 && minutes >= minMinutes)) &&
            (maxMinutes == null || (minutes > 0 && minutes <= maxMinutes)) &&
            (withinDays == null || (e.publishedAt > 0 && now - e.publishedAt <= withinDays * 86_400_000L))
    }
    return if (PlayedRule.of(playedState) == PlayedRule.InProgress) result.sortedByDescending { it.episode.lastPlayedAt } else result
}

internal class PlaylistsViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    val playlists = repo.playlists.stateIn(this, emptyList())
    val rows = repo.allRows.stateIn(this, emptyList())
    val downloaded = repo.downloadedIds.stateIn(this, emptySet())
    val podcasts = repo.podcastEntities.stateIn(this, emptyList())
    fun save(p: PlaylistEntity, then: (Long) -> Unit = {}) = viewModelScope.launch { val id = repo.savePlaylist(p); then(if (p.id == 0L) id else p.id) }
    fun delete(id: Long) = viewModelScope.launch { repo.deletePlaylist(id) }
}

@Composable internal fun PlaylistsScreen(onOpen: (Long) -> Unit, modifier: Modifier, vm: PlaylistsViewModel = viewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) RulesDialog(PlaylistEntity(name = ""), podcasts, onDismiss = { creating = false }, onSave = { vm.save(it) { id -> onOpen(id) }; creating = false }, onDelete = null)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenHeader("Playlists") { IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Nueva playlist", tint = MaterialTheme.colorScheme.secondary) } }; Spacer(Modifier.height(Kanso.itemGap)) }
        if (playlists.isEmpty()) item { EmptyState(Icons.AutoMirrored.Outlined.FormatListBulleted, "Sin playlists", "Crea una playlist inteligente con reglas.", "Nueva playlist") { creating = true } }
        items(playlists, key = { it.id }) { p ->
            val episodes = p.matching(rows, downloaded)
            Row(Modifier.fillMaxWidth().clip(Kanso.art).clickable { onOpen(p.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    val art = episodes.firstOrNull()?.let { it.episode.artworkUrl ?: it.podcastArtwork }
                    if (art != null) AsyncImage(art, null, Modifier.fillMaxSize()) else Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.width(Kanso.itemGap))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(playlistSummary(episodes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(.4f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(.15f))
        }
    }
}

private fun playlistSummary(rows: List<EpisodeRow>): String {
    val total = rows.sumOf { remainingMs(it.episode) }
    return "${rows.size} episodios" + if (total > 0) " · ${formatDuration(total)}" else ""
}

@Composable internal fun PlaylistDetailScreen(id: Long, player: PlayerConnection, onBack: () -> Unit, onOpenPodcast: (PodcastSearchResult) -> Unit, modifier: Modifier, vm: PlaylistsViewModel = viewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == id } ?: run { if (playlists.isNotEmpty()) LaunchedEffect(Unit) { onBack() }; return }
    var editing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    val all = playlist.matching(rows, downloaded)
    val visible = if (query.isBlank()) all else all.filter { it.episode.title.contains(query, true) || it.podcastTitle?.contains(query, true) == true }
    if (editing) RulesDialog(playlist, podcasts, onDismiss = { editing = false }, onSave = { vm.save(it); editing = false }, onDelete = { vm.delete(playlist.id); editing = false; onBack() })
    EpisodeSheetHost(selected, all, player, downloaded, onDismiss = { selected = null }, onOpenPodcast = onOpenPodcast)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Row(Modifier.fillMaxWidth()) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } } }
        item {
            val art = all.firstOrNull()?.let { it.episode.artworkUrl ?: it.podcastArtwork }
            Box(Modifier.size(170.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (art != null) AsyncImage(art, null, Modifier.fillMaxSize()) else Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(Kanso.itemGap))
            Text(playlist.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(playlistSummary(all), color = MaterialTheme.colorScheme.onSurface.copy(.55f))
            Spacer(Modifier.height(Kanso.itemGap))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { editing = true }, Modifier.weight(1f)) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Reglas inteligentes") }
                Button(onClick = { player.playAll(all) }, Modifier.weight(1f), enabled = all.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Reproducir todo") }
            }
            Spacer(Modifier.height(Kanso.itemGap))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = Kanso.art)
            Spacer(Modifier.height(8.dp))
        }
        if (all.isEmpty()) item { EmptyState(Icons.Outlined.AutoAwesome, "Ningún episodio cumple las reglas", "Ajusta las reglas inteligentes o actualiza tus feeds.") }
        episodeRows(visible, player, downloaded, onSelect = { selected = it })
    }
}

/** Editor for a smart playlist's rules. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RulesDialog(initial: PlaylistEntity, podcasts: List<PodcastEntity>, onDismiss: () -> Unit, onSave: (PlaylistEntity) -> Unit, onDelete: (() -> Unit)?) {
    var p by remember { mutableStateOf(initial) }
    var pickPodcasts by remember { mutableStateOf(false) }
    val durations = listOf<Triple<String, Int?, Int?>>(Triple("Cualquiera", null, null), Triple("< 20 min", null, 20), Triple("20–60 min", 20, 60), Triple("> 60 min", 60, null))
    val dates = listOf<Pair<String, Int?>>("Siempre" to null, "24 h" to 1, "3 días" to 3, "1 semana" to 7, "2 semanas" to 14, "1 mes" to 30)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Nueva playlist" else "Reglas inteligentes") },
        confirmButton = { TextButton(onClick = { onSave(p.copy(name = p.name.ifBlank { "Mi playlist" })) }) { Text("Guardar") } },
        dismissButton = { Row { if (onDelete != null) TextButton(onClick = onDelete) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text("Cancelar") } } },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    RuleLabel("Podcasts")
                    val feeds = p.feeds
                    OutlinedButton(onClick = { pickPodcasts = !pickPodcasts }, Modifier.fillMaxWidth()) { Text(if (feeds.isEmpty()) "Todos los podcasts" else "${feeds.size} seleccionados") }
                    if (pickPodcasts) Column {
                        Row(Modifier.fillMaxWidth().clickable { p = p.copy(podcastFeeds = "") }, verticalAlignment = Alignment.CenterVertically) { Checkbox(feeds.isEmpty(), { p = p.copy(podcastFeeds = "") }); Text("Todos") }
                        podcasts.forEach { pod ->
                            val on = pod.feedUrl in feeds
                            fun toggle() { val next = if (on) feeds - pod.feedUrl else feeds + pod.feedUrl; p = p.copy(podcastFeeds = next.joinToString("\n")) }
                            Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) { Checkbox(on, { toggle() }); Text(pod.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                item { RuleLabel("Estado"); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { PlayedRule.entries.forEach { r -> FilterChip(PlayedRule.of(p.playedState) == r, onClick = { p = p.copy(playedState = r.key) }, label = { Text(r.label) }) } } }
                item {
                    RuleLabel("Duración"); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { durations.forEach { (label, min, max) -> FilterChip(p.minMinutes == min && p.maxMinutes == max, onClick = { p = p.copy(minMinutes = min, maxMinutes = max) }, label = { Text(label) }) } }
                    // Fine range: 0–180 min in 5 min steps; the ends mean "no limit".
                    Text(durationRangeLabel(p.minMinutes, p.maxMinutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
                    RangeSlider(value = (p.minMinutes ?: 0).toFloat()..(p.maxMinutes ?: DURATION_CAP).toFloat(), valueRange = 0f..DURATION_CAP.toFloat(), steps = DURATION_CAP / 5 - 1,
                        onValueChange = { r -> val lo = (r.start / 5).roundToInt() * 5; val hi = (r.endInclusive / 5).roundToInt() * 5
                            p = p.copy(minMinutes = lo.takeIf { it > 0 }, maxMinutes = hi.takeIf { it < DURATION_CAP }) },
                        modifier = Modifier.semantics { contentDescription = "Rango de duración: " + durationRangeLabel(p.minMinutes, p.maxMinutes) })
                }
                item { RuleLabel("Publicado"); FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { dates.forEach { (label, days) -> FilterChip(p.withinDays == days, onClick = { p = p.copy(withinDays = days) }, label = { Text(label) }) } } }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Solo descargados", Modifier.weight(1f)); Switch(p.downloadedOnly, { p = p.copy(downloadedOnly = it) }) } }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Incluir archivados", Modifier.weight(1f)); Switch(p.includeArchived, { p = p.copy(includeArchived = it) }) } }
            }
        })
}

@Composable private fun RuleLabel(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary); Spacer(Modifier.height(4.dp)) }

private const val DURATION_CAP = 180
internal fun durationRangeLabel(min: Int?, max: Int?): String = when {
    min == null && max == null -> "Cualquier duración"
    min == null -> "Hasta $max min"
    max == null -> "$min min o más"
    else -> "De $min a $max min"
}
