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

internal class PodcastViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    private val feedUrl = MutableStateFlow<String?>(null)
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val episodes = feedUrl.filterNotNull().flatMapLatest { repo.episodes(it) }.stateIn(this, emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val subscription = feedUrl.filterNotNull().flatMapLatest { repo.podcast(it) }.stateIn(this, null)
    val downloaded = repo.downloadedIds.stateIn(this, emptySet())
    private var podcast: PodcastSearchResult? = null
    fun load(p: PodcastSearchResult) { if (podcast?.feedUrl == p.feedUrl) return; podcast = p; feedUrl.value = p.feedUrl; refresh() }
    fun refresh() {
        val p = podcast ?: return
        refreshing = true; error = null
        viewModelScope.launch { repo.refresh(p).onFailure { error = if (episodes.value.isEmpty()) it.message ?: "No pudimos leer los episodios" else "Sin conexión: mostrando episodios guardados" }; refreshing = false }
    }
    fun subscribe() = viewModelScope.launch { podcast?.let { repo.subscribe(it) } }
    fun setAutoDownload(count: Int) = viewModelScope.launch { podcast?.let { repo.setAutoDownloadCount(it.feedUrl, count) } }
}

@Composable internal fun PodcastDetailScreen(podcast: PodcastSearchResult, player: PlayerConnection, onBack: () -> Unit, modifier: Modifier, vm: PodcastViewModel = viewModel(key = podcast.feedUrl)) {
    LaunchedEffect(podcast.feedUrl) { vm.load(podcast) }
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val subscription by vm.subscription.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settings by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    EpisodeSheetHost(selected, episodes.map { EpisodeRow(it, podcast.title, podcast.artworkUrl, podcast.author) }, player, downloaded, onDismiss = { selected = null }, onOpenPodcast = null)
    if (settings) {
        val global = context.getSharedPreferences("automation", 0).getInt("download_count", 0)
        var count by remember { mutableIntStateOf(subscription?.autoDownloadCount ?: global) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Automatización") },
            text = { Column { Text(podcast.title, maxLines = 1); Spacer(Modifier.height(14.dp)); Text(if (count == 0) "Descargas automáticas desactivadas" else "Descargar $count episodios nuevos"); Slider(count.toFloat(), { count = it.toInt() }, valueRange = 0f..10f, steps = 9) } },
            confirmButton = { TextButton(onClick = { vm.setAutoDownload(count); settings = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("Cancelar") } })
    }
    val archivedCount = episodes.count { it.archived }
    val visible = if (showArchived) episodes else episodes.filter { !it.archived }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                Artwork(podcast.artworkUrl, podcast.title, 76.dp, RoundedCornerShape(18.dp))
                Spacer(Modifier.width(Kanso.itemGap))
                Column(Modifier.weight(1f)) {
                    Text(podcast.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(podcast.author, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (subscription == null) Button(onClick = { vm.subscribe() }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Seguir") }
                else FilledTonalButton(onClick = { settings = true }) { Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(6.dp)); Text("Automatización") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = vm::refresh, enabled = !vm.refreshing) { Icon(Icons.Outlined.Sync, "Actualizar feed") }
            }
        }
        item { SectionLabel("Episodios", if (episodes.isEmpty()) null else "${visible.size}") }
        if (vm.refreshing && episodes.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary) }
        vm.error?.let { item { Text(it, color = if (episodes.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(.55f), style = MaterialTheme.typography.bodySmall) } }
        if (!vm.refreshing && episodes.isEmpty() && vm.error == null) item { Text("Este feed no contiene episodios reproducibles.") }
        if (archivedCount > 0) item {
            TextButton(onClick = { showArchived = !showArchived }) { Icon(Icons.Outlined.Inventory2, null); Spacer(Modifier.width(6.dp)); Text(if (showArchived) "Ocultar archivados" else "Mostrar $archivedCount archivados") }
        }
        episodeRows(visible.map { EpisodeRow(it, podcast.title, podcast.artworkUrl, podcast.author) }, player, downloaded, onSelect = { selected = it }, showArtwork = false)
    }
}
