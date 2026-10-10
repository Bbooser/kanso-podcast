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

internal class LibraryViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    val podcasts = repo.podcastEntities.stateIn(this, emptyList())
    val folders = repo.folders.stateIn(this, emptyList())
    val loaded = repo.podcastEntities.map { true }.stateIn(this, false)
    fun unsubscribe(p: PodcastEntity) = viewModelScope.launch { repo.unsubscribe(p.feedUrl) }
    fun move(p: PodcastEntity, folder: String?) = viewModelScope.launch { repo.setFolder(p.feedUrl, folder) }
    fun createFolder(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createFolder(name) }
}

@Composable internal fun LibraryScreen(onOpen: (PodcastSearchResult) -> Unit, onSearch: () -> Unit, modifier: Modifier, vm: LibraryViewModel = viewModel()) {
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }; var newName by remember { mutableStateOf("") }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("Nueva carpeta") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("Nombre") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.createFolder(newName); newName = ""; create = false }) { Text("Crear") } },
        dismissButton = { TextButton(onClick = { create = false }) { Text("Cancelar") } })
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(Kanso.itemGap)) {
        item {
            ScreenHeader("Tu biblioteca", if (podcasts.isEmpty()) null else "${podcasts.size} podcasts") {
                IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Buscar podcasts") }
                IconButton(onClick = { create = true }) { Icon(Icons.Default.CreateNewFolder, "Nueva carpeta") }
            }
        }
        if (loaded && podcasts.isEmpty()) { item { EmptyState(Icons.Outlined.Podcasts, "Tu biblioteca está tranquila", "Busca un podcast para comenzar.", "Encontrar podcasts", onSearch) }; return@LazyColumn }
        folders.forEach { folder ->
            val inFolder = podcasts.filter { it.folder == folder }
            item(key = "folder:$folder") { SectionLabel(folder, "${inFolder.size}") }
            items(inFolder, key = { "$folder:${it.feedUrl}" }) { p -> LibraryPodcastRow(p, folders, { onOpen(p.toModel()) }, { vm.unsubscribe(p) }, { vm.move(p, it) }) }
        }
        val loose = podcasts.filter { it.folder == null || it.folder !in folders }
        if (loose.isNotEmpty()) {
            item(key = "folder:none") { SectionLabel(if (folders.isEmpty()) "Tus podcasts" else "Sin carpeta", "${loose.size}") }
            items(loose, key = { it.feedUrl }) { p -> LibraryPodcastRow(p, folders, { onOpen(p.toModel()) }, { vm.unsubscribe(p) }, { vm.move(p, it) }) }
        }
    }
}

@Composable private fun LibraryPodcastRow(podcast: PodcastEntity, folders: List<String>, onOpen: () -> Unit, onRemove: () -> Unit, onAssign: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(Kanso.art).clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(podcast.artworkUrl, podcast.title, 64.dp)
            Spacer(Modifier.width(Kanso.itemGap))
            Column(Modifier.weight(1f)) {
                Text(podcast.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(podcast.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
        }
        DropdownMenu(menu, { menu = false }) {
            folders.filter { it != podcast.folder }.forEach { folder -> DropdownMenuItem({ Text("Mover a $folder") }, onClick = { menu = false; onAssign(folder) }, leadingIcon = { Icon(Icons.Outlined.Folder, null) }) }
            if (podcast.folder != null) DropdownMenuItem({ Text("Quitar de la carpeta") }, onClick = { menu = false; onAssign(null) }, leadingIcon = { Icon(Icons.Outlined.FolderOff, null) })
            DropdownMenuItem({ Text("Dejar de seguir") }, onClick = { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
        }
    }
}
