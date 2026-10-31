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
import com.kanso.podcast.data.PodcastSort
import com.kanso.podcast.data.sortPodcasts
import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.EpisodeRow
import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal class PodcastsViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    private val prefs = app.getSharedPreferences("appearance", 0)
    // Older builds stored a boolean ("recientes" vs título); keep that choice on upgrade.
    var sort by mutableStateOf(PodcastSort.parse(prefs.getString("podcasts_sort", null)) ?: if (prefs.getBoolean("podcasts_sort_recent", false)) PodcastSort.RECENT else PodcastSort.NAME); private set
    val podcasts = repo.podcastEntities.stateIn(this, emptyList())
    val stats = repo.podcastLibraryStats.map { list -> list.associateBy { it.feedUrl } }.stateIn(this, emptyMap())
    fun toggleFavorite(p: PodcastEntity) = viewModelScope.launch { repo.setPodcastFavorite(p.feedUrl, !p.isFavorite) }
    val folders = repo.folders.stateIn(this, emptyList())
    val loaded = repo.podcastEntities.map { true }.stateIn(this, false)
    fun unsubscribe(p: PodcastEntity) = viewModelScope.launch { repo.unsubscribe(p.feedUrl) }
    fun move(p: PodcastEntity, folder: String?) = viewModelScope.launch { repo.setFolder(p.feedUrl, folder) }
    fun createFolder(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createFolder(name) }
    fun chooseSort(value: PodcastSort) { sort = value; prefs.edit().putString("podcasts_sort", value.name).apply() }
    fun refreshAll() { androidx.work.WorkManager.getInstance(getApplication()).enqueue(androidx.work.OneTimeWorkRequestBuilder<com.kanso.podcast.data.AutomationWorker>().build()) }
}

private sealed interface GridTile { data class Folder(val name: String, val covers: List<String?>, val count: Int) : GridTile; data class Podcast(val p: PodcastEntity) : GridTile }

/** "Podcasts": cover grid of followed shows, folders first. [folder] != null shows one folder. */
@Composable internal fun PodcastsScreen(player: PlayerConnection, onOpen: (PodcastSearchResult) -> Unit, onOpenFolder: (String) -> Unit, onDiscover: () -> Unit, modifier: Modifier, folder: String? = null, onBack: (() -> Unit)? = null, vm: PodcastsViewModel = viewModel()) {
    val all by vm.podcasts.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }; var newName by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("Nueva carpeta") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("Nombre") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.createFolder(newName); newName = ""; create = false }) { Text("Crear") } },
        dismissButton = { TextButton(onClick = { create = false }) { Text("Cancelar") } })
    val sorted = remember(all, stats, vm.sort) { sortPodcasts(all, stats, vm.sort) }
    val tiles: List<GridTile> = if (folder != null) sorted.filter { it.folder == folder }.map { GridTile.Podcast(it) } else
        folders.map { name -> val inside = sorted.filter { it.folder == name }; GridTile.Folder(name, inside.take(4).map { it.artworkUrl }, inside.size) } +
            sorted.filter { it.folder == null || it.folder !in folders }.map { GridTile.Podcast(it) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else Kanso.screenPadding, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text(folder ?: "Podcasts", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (folder == null) IconButton(onClick = { create = true }) { Icon(Icons.Outlined.CreateNewFolder, "Nueva carpeta", tint = MaterialTheme.colorScheme.secondary) }
            CastButton(player)
            IconButton(onClick = onDiscover) { Icon(Icons.Outlined.Search, "Buscar", tint = MaterialTheme.colorScheme.secondary) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                DropdownMenu(menu, { menu = false }) {
                    Text("Ordenar por", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                    PodcastSort.entries.forEach { s ->
                        DropdownMenuItem({ Text(s.label) }, onClick = { menu = false; vm.chooseSort(s) },
                            leadingIcon = { if (vm.sort == s) Icon(Icons.Default.Check, "Seleccionado", tint = MaterialTheme.colorScheme.secondary) else Spacer(Modifier.size(24.dp)) })
                    }
                    Text("Los favoritos siempre van primero", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Actualizar todos los feeds") }, onClick = { menu = false; vm.refreshAll() }, leadingIcon = { Icon(Icons.Outlined.Sync, null) })
                }
            }
        }
        if (loaded && all.isEmpty()) { EmptyState(Icons.Outlined.Podcasts, "Tu biblioteca está tranquila", "Encuentra podcasts para seguir en Descubrir.", "Ir a Descubrir", onDiscover); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding - 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tiles, key = { when (it) { is GridTile.Folder -> "f:" + it.name; is GridTile.Podcast -> it.p.feedUrl } }) { tile ->
                when (tile) {
                    is GridTile.Folder -> FolderTile(tile) { onOpenFolder(tile.name) }
                    is GridTile.Podcast -> PodcastTile(tile.p, folders, { onOpen(tile.p.toModel()) }, { vm.unsubscribe(tile.p) }, { vm.move(tile.p, it) }, { vm.toggleFavorite(tile.p) })
                }
            }
        }
    }
}

@Composable private fun FolderTile(tile: GridTile.Folder, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).padding(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0, 2).forEach { start -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (start..start + 1).forEach { i ->
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(.06f))) { tile.covers.getOrNull(i)?.let { AsyncImage(it, null, Modifier.fillMaxSize()) } }
            } } }
        }
        Surface(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.primary.copy(.85f), shape = RoundedCornerShape(8.dp)) {
            Text("${tile.name} · ${tile.count}", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PodcastTile(podcast: PodcastEntity, folders: List<String>, onOpen: () -> Unit, onRemove: () -> Unit, onAssign: (String?) -> Unit, onFavorite: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = onOpen, onLongClick = { menu = true }), contentAlignment = Alignment.Center) {
            if (podcast.artworkUrl != null) AsyncImage(podcast.artworkUrl, podcast.title, Modifier.fillMaxSize())
            else Text(podcast.title, Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 4)
        }
        if (podcast.isFavorite) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.background.copy(.85f)) {
            Icon(Icons.Default.Star, "Favorito", Modifier.padding(3.dp).size(14.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        DropdownMenu(menu, { menu = false }) {
            Text(podcast.title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 240.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2)
            DropdownMenuItem({ Text(if (podcast.isFavorite) "Quitar de favoritos" else "Marcar como favorito") }, onClick = { menu = false; onFavorite() },
                leadingIcon = { Icon(if (podcast.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, null) })
            folders.filter { it != podcast.folder }.forEach { f -> DropdownMenuItem({ Text("Mover a $f") }, onClick = { menu = false; onAssign(f) }, leadingIcon = { Icon(Icons.Outlined.Folder, null) }) }
            if (podcast.folder != null) DropdownMenuItem({ Text("Quitar de la carpeta") }, onClick = { menu = false; onAssign(null) }, leadingIcon = { Icon(Icons.Outlined.FolderOff, null) })
            DropdownMenuItem({ Text("Dejar de seguir") }, onClick = { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
        }
    }
}
