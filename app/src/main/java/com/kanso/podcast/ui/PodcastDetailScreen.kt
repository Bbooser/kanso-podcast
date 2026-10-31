@file:androidx.media3.common.util.UnstableApi
package com.kanso.podcast.ui

import com.kanso.podcast.data.detailEpisodes
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    fun toggleFavorite() = viewModelScope.launch { subscription.value?.let { repo.setPodcastFavorite(it.feedUrl, !it.isFavorite) } }
    fun setAutoArchive(value: Boolean?) = viewModelScope.launch { podcast?.let { repo.setPodcastAutoArchive(it.feedUrl, value) } }
    fun bulk(ids: Set<String>, archive: Boolean?, markListened: Boolean) = viewModelScope.launch { repo.bulkEpisodes(ids, archive, markListened) }
    fun saveSettings(speed: Float?, intro: Int, outro: Int, sort: String, notify: Boolean) = viewModelScope.launch { podcast?.let { repo.savePodcastSettings(it.feedUrl, speed, intro, outro, sort, notify) } }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun PodcastDetailScreen(podcast: PodcastSearchResult, player: PlayerConnection, onBack: () -> Unit, modifier: Modifier, vm: PodcastViewModel = viewModel(key = podcast.feedUrl)) {
    LaunchedEffect(podcast.feedUrl) { vm.load(podcast) }
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val subscription by vm.subscription.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settings by remember { mutableStateOf(false) }
    var showArchived by rememberSaveable(podcast.feedUrl) { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    androidx.activity.compose.BackHandler(enabled = selection != null || showArchived) { if (selection != null) selection = null else showArchived = false }
    var selected by remember { mutableStateOf<String?>(null) }
    EpisodeSheetHost(selected, episodes.map { EpisodeRow(it, podcast.title, podcast.artworkUrl, podcast.author) }, player, downloaded, onDismiss = { selected = null }, onOpenPodcast = null)
    if (settings) subscription?.let { sub -> PodcastSettingsDialog(sub, context, onDismiss = { settings = false }) { count, speed, intro, outro, sort, notify, autoArchive ->
        vm.setAutoDownload(count); vm.setAutoArchive(autoArchive); vm.saveSettings(speed, intro, outro, sort, notify); settings = false
    } }
    val sorted = if (subscription?.episodeSort == "OLDEST") episodes.reversed() else episodes
    val archivedCount = detailEpisodes(episodes, archivedView = true).size
    val visible = detailEpisodes(sorted, showArchived)
    LaunchedEffect(showArchived) { selection = null }
    val sel = selection
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sel != null) stickyHeader(key = "selection") {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selection = null }) { Icon(Icons.Default.Close, "Salir de la selección") }
                Text("${sel.size} seleccionados", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = { selection = if (sel.size == visible.size) emptySet() else visible.map { it.id }.toSet() }) { Text(if (sel.size == visible.size && visible.isNotEmpty()) "Ninguno" else "Seleccionar todo") }
                if (!showArchived) IconButton(onClick = { vm.bulk(sel, null, markListened = true); selection = null }, enabled = sel.isNotEmpty()) { Icon(Icons.Outlined.DoneAll, "Marcar como escuchados") }
                IconButton(onClick = { vm.bulk(sel, archive = !showArchived, markListened = false); selection = null }, enabled = sel.isNotEmpty()) {
                    Icon(if (showArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (showArchived) "Desarchivar" else "Archivar")
                }
            }
        }
        if (showArchived) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showArchived = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver a episodios") }
                Column(Modifier.weight(1f)) {
                    Text("Archivados", style = MaterialTheme.typography.headlineSmall)
                    Text(podcast.title, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!showArchived) item {
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
        if (!showArchived) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (subscription == null) Button(onClick = { vm.subscribe() }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Seguir") }
                else FilledTonalButton(onClick = { settings = true }) { Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(6.dp)); Text("Ajustes") }
                Spacer(Modifier.weight(1f))
                subscription?.let { sub -> IconButton(onClick = { vm.toggleFavorite() }) {
                    Icon(if (sub.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, if (sub.isFavorite) "Quitar de favoritos" else "Marcar como favorito", tint = MaterialTheme.colorScheme.secondary)
                } }
                IconButton(onClick = vm::refresh, enabled = !vm.refreshing) { Icon(Icons.Outlined.Sync, "Actualizar feed") }
            }
        }
        if (!showArchived) item { SectionLabel("Episodios", if (episodes.isEmpty()) null else "${visible.size}") }
        if (vm.refreshing && episodes.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary) }
        vm.error?.let { item { Text(it, color = if (episodes.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(.55f), style = MaterialTheme.typography.bodySmall) } }
        if (!vm.refreshing && episodes.isEmpty() && vm.error == null) item { Text("Este feed no contiene episodios reproducibles.") }
        if (!showArchived && archivedCount > 0 && sel == null) item {
            TextButton(onClick = { showArchived = true }) { Icon(Icons.Outlined.Inventory2, null); Spacer(Modifier.width(6.dp)); Text("Archivados y escuchados · $archivedCount") }
        }
        if (showArchived && visible.isEmpty()) item { Text("No hay episodios archivados ni escuchados.", color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        if (showArchived && visible.isNotEmpty() && sel == null) item { Text("Mantén presionado un episodio para seleccionar varios.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        episodeRows(visible.map { EpisodeRow(it, podcast.title, podcast.artworkUrl, podcast.author) }, player, downloaded,
            onSelect = { id -> val cur = selection; if (cur != null) selection = if (id in cur) cur - id else cur + id else selected = id }, showArtwork = false,
            onLongSelect = { id -> selection = (selection ?: emptySet()) + id }, selection = sel)
    }
}

private val PODCAST_SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)

/** Per-podcast settings: downloads, new-episode notifications, speed, skip intro/outro and episode order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PodcastSettingsDialog(sub: PodcastEntity, context: Context, onDismiss: () -> Unit, onSave: (Int, Float?, Int, Int, String, Boolean, Boolean?) -> Unit) {
    val global = remember { context.getSharedPreferences("automation", 0).getInt("download_count", 0) }
    var count by remember { mutableIntStateOf(sub.autoDownloadCount ?: global) }
    var speed by remember { mutableStateOf(sub.playbackSpeed) }
    var intro by remember { mutableIntStateOf(sub.skipIntroSec) }
    var outro by remember { mutableIntStateOf(sub.skipOutroSec) }
    var sort by remember { mutableStateOf(sub.episodeSort) }
    var notify by remember { mutableStateOf(sub.notifyNewEpisodes) }
    var autoArchive by remember { mutableStateOf(sub.autoArchive) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> notify = granted }
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajustes del podcast") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sub.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Avisar de episodios nuevos", fontWeight = FontWeight.Medium); Text("Notificación al actualizar el feed", style = MaterialTheme.typography.bodySmall, color = muted) }
                    Switch(notify, { on ->
                        if (on && android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else notify = on
                    })
                }
                HorizontalDivider()
                Text(if (count == 0) "Descargas automáticas desactivadas" else "Descargar $count episodios nuevos", fontWeight = FontWeight.Medium)
                Slider(count.toFloat(), { count = it.toInt() }, valueRange = 0f..10f, steps = 9)
                HorizontalDivider()
                Text("Velocidad", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(speed == null, onClick = { speed = null }, label = { Text("General") })
                    PODCAST_SPEEDS.forEach { v -> FilterChip(speed == v, onClick = { speed = v }, label = { Text("${"%.1f".format(java.util.Locale.US, v).removeSuffix(".0")}×") }) }
                }
                HorizontalDivider()
                Text(if (intro == 0) "Saltar intro: no" else "Saltar los primeros $intro s", fontWeight = FontWeight.Medium)
                Slider(intro.toFloat(), { intro = (it / 5).toInt() * 5 }, valueRange = 0f..120f, steps = 23)
                Text(if (outro == 0) "Saltar cierre: no" else "Saltar los últimos $outro s", fontWeight = FontWeight.Medium)
                Slider(outro.toFloat(), { outro = (it / 5).toInt() * 5 }, valueRange = 0f..120f, steps = 23)
                HorizontalDivider()
                Text("Archivar al terminar", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(autoArchive == null, onClick = { autoArchive = null }, label = { Text("General") })
                    FilterChip(autoArchive == true, onClick = { autoArchive = true }, label = { Text("Sí") })
                    FilterChip(autoArchive == false, onClick = { autoArchive = false }, label = { Text("No") })
                }
                HorizontalDivider()
                Text("Orden de episodios", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(sort != "OLDEST", onClick = { sort = "NEWEST" }, label = { Text("Más nuevos primero") })
                    FilterChip(sort == "OLDEST", onClick = { sort = "OLDEST" }, label = { Text("Más viejos primero") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(count, speed, intro, outro, sort, notify, autoArchive) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
