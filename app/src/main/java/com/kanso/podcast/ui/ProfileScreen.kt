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

internal enum class ProfilePage(val label: String, val icon: ImageVector) {
    Stats("Estadísticas", Icons.Outlined.Insights), Downloads("Descargas", Icons.Outlined.Download), Archived("Archivos", Icons.Outlined.Inventory2),
    Starred("Destacado", Icons.Outlined.StarBorder), Bookmarks("Marcadores", Icons.Outlined.BookmarkBorder), History("Historial de elementos escuchados", Icons.Outlined.History),
    Settings("Ajustes", Icons.Outlined.Settings)
}

internal class ProfileViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    val podcastCount = repo.podcastEntities.map { it.size }.stateIn(this, 0)
    val days = repo.daysListened.stateIn(this, 0)
    val savedMs = repo.totalSavedMs.stateIn(this, 0L)
    val listenedMs = repo.totalListenedMs.stateIn(this, 0L)
    val archived = repo.archived.stateIn(this, emptyList())
    val favorites = repo.favorites.stateIn(this, emptyList())
    val history = repo.history.stateIn(this, emptyList())
    val bookmarks = repo.bookmarks.stateIn(this, emptyList())
    val downloaded = repo.downloadedIds.stateIn(this, emptySet())
    fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }
    suspend fun episode(id: String) = repo.episodeRow(id)
}

@Composable internal fun ProfileScreen(onOpen: (ProfilePage) -> Unit, modifier: Modifier, vm: ProfileViewModel = viewModel()) {
    val podcasts by vm.podcastCount.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val saved by vm.savedMs.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) { IconButton(onClick = { onOpen(ProfilePage.Settings) }) { Icon(Icons.Outlined.Settings, "Ajustes", tint = MaterialTheme.colorScheme.secondary) } } }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface.copy(.6f)) }
                Spacer(Modifier.height(10.dp))
                Text("Escucha sin prisa", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Kanso.sectionGap))
                Row(Modifier.fillMaxWidth().padding(horizontal = Kanso.screenPadding), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("$podcasts", "PODCASTS"); Stat("$days", if (days == 1) "DÍA ESCUCHADO" else "DÍAS ESCUCHADOS"); Stat("${saved / 60_000}", "MINUTOS\nAHORRADOS")
                }
                Spacer(Modifier.height(Kanso.sectionGap))
            }
        }
        items(ProfilePage.entries.filter { it != ProfilePage.Settings } + ProfilePage.Settings) { page ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(.15f))
            Row(Modifier.fillMaxWidth().clickable { onOpen(page) }.padding(horizontal = Kanso.screenPadding, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(page.icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(20.dp)); Text(page.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), textAlign = TextAlign.Center)
    }
}

/** Archived / starred / history episode lists. */
@Composable internal fun EpisodeCollectionScreen(page: ProfilePage, player: PlayerConnection, onBack: () -> Unit, onOpenPodcast: (PodcastSearchResult) -> Unit, modifier: Modifier, vm: ProfileViewModel = viewModel()) {
    val rows by when (page) { ProfilePage.Archived -> vm.archived; ProfilePage.Starred -> vm.favorites; else -> vm.history }.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    EpisodeSheetHost(selected, rows, player, downloaded, onDismiss = { selected = null }, onOpenPodcast = onOpenPodcast)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding)) {
        item { ScreenHeader(page.label, if (rows.isEmpty()) null else "${rows.size} episodios", onBack = onBack); Spacer(Modifier.height(8.dp)) }
        if (rows.isEmpty()) item { when (page) {
            ProfilePage.Archived -> EmptyState(Icons.Outlined.Inventory2, "Nada archivado", "Los episodios terminados se archivan solos si lo activas en Ajustes.")
            ProfilePage.Starred -> EmptyState(Icons.Outlined.StarBorder, "Sin destacados", "Destaca episodios desde su ficha para guardarlos aquí.")
            else -> EmptyState(Icons.Outlined.History, "Aún no hay historial")
        } }
        episodeRows(rows, player, downloaded, onSelect = { selected = it })
    }
}

@Composable internal fun BookmarksScreen(player: PlayerConnection, onBack: () -> Unit, modifier: Modifier, vm: ProfileViewModel = viewModel()) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenHeader("Marcadores", onBack = onBack); Spacer(Modifier.height(8.dp)) }
        if (bookmarks.isEmpty()) item { EmptyState(Icons.Outlined.BookmarkBorder, "Sin marcadores", "Guarda un momento con el botón de marcador en la pantalla de reproducción.") }
        items(bookmarks, key = { it.bookmark.id }) { b ->
            Row(Modifier.fillMaxWidth().clip(Kanso.art).clickable {
                scope.launch { vm.episode(b.bookmark.episodeId)?.let { row -> player.play(row.episode, row.podcastTitle.orEmpty(), row.podcastArtwork, startMs = b.bookmark.positionMs) } }
            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(b.artwork, b.episodeTitle.orEmpty(), 52.dp, RoundedCornerShape(10.dp)); Spacer(Modifier.width(Kanso.itemGap))
                Column(Modifier.weight(1f)) {
                    Text(formatTime(b.bookmark.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(b.episodeTitle ?: "Episodio", fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(b.podcastTitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1)
                }
                IconButton(onClick = { vm.deleteBookmark(b.bookmark.id) }) { Icon(Icons.Outlined.Delete, "Borrar marcador") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(.15f))
        }
    }
}

@Composable internal fun StatsScreen(onBack: () -> Unit, modifier: Modifier, vm: ProfileViewModel = viewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    val saved by vm.savedMs.collectAsStateWithLifecycle()
    val listened by vm.listenedMs.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(Kanso.itemGap)) {
        item { ScreenHeader("Estadísticas", onBack = onBack) }
        item { StatCard(Icons.Outlined.Headphones, "Tiempo escuchado", formatDuration(listened.coerceAtLeast(60_000)).takeIf { listened > 0 } ?: "0 min") }
        item { StatCard(Icons.Outlined.Bolt, "Tiempo ahorrado", if (saved > 0) formatDuration(saved) else "0 min", "Con velocidad y recorte de silencios") }
        item { StatCard(Icons.Outlined.CalendarMonth, "Días con escucha", "$days") }
    }
}

@Composable private fun StatCard(icon: ImageVector, title: String, value: String, note: String? = null) {
    Row(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(Kanso.itemGap))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
