@file:androidx.media3.common.util.UnstableApi

package com.kanso.podcast.ui

import androidx.compose.ui.draw.alpha
import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.kanso.podcast.data.EpisodeDownloads
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Xml
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.kanso.podcast.playback.KansoPlaybackService
import com.kanso.podcast.data.PodcastDirectory
import com.kanso.podcast.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.glance.appwidget.updateAll
import com.kanso.podcast.widget.KansoWidget
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.File
import org.xmlpull.v1.XmlPullParser

private enum class Section(val label: String, val icon: ImageVector) {
    Library("Biblioteca", Icons.Outlined.GridView), Downloads("Descargas", Icons.Outlined.Download), Profile("Perfil", Icons.Outlined.Person)
}

@Composable fun KansoApp(dark: Boolean, palette: KansoPalette, onDarkChange: (Boolean) -> Unit, onPaletteChange: (KansoPalette) -> Unit) {
    var section by remember { mutableStateOf(Section.Library) }
    var searching by remember { mutableStateOf(false) }
    var selectedPodcast by remember { mutableStateOf<PodcastSearchResult?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val subscriptionPrefs = remember { context.getSharedPreferences("subscriptions", 0) }
    val subscriptions = remember { mutableStateListOf<PodcastSearchResult>().apply { addAll(loadSubscriptions(subscriptionPrefs.getString("items", null))) } }
    fun subscribe(podcast: PodcastSearchResult) {
        if (subscriptions.none { it.feedUrl == podcast.feedUrl }) {
            subscriptions.add(podcast)
            subscriptionPrefs.edit().putString("items", saveSubscriptions(subscriptions)).apply()
        }
    }
    fun unsubscribe(podcast: PodcastSearchResult) {
        subscriptions.removeAll { it.feedUrl == podcast.feedUrl }
        subscriptionPrefs.edit().putString("items", saveSubscriptions(subscriptions)).apply()
    }
    fun importPodcasts(items: List<PodcastSearchResult>) {
        subscriptions.addAll(items.filter { imported -> subscriptions.none { it.feedUrl == imported.feedUrl } })
        subscriptionPrefs.edit().putString("items", saveSubscriptions(subscriptions)).apply()
    }
    val player = rememberPlayerConnection()
    LaunchedEffect(player.openRequest) { if (player.openRequest > 0) showPlayer = true }
    BackHandler(enabled = showPlayer || selectedPodcast != null || searching || section != Section.Library) {
        when { showPlayer -> showPlayer = false; selectedPodcast != null -> selectedPodcast = null; searching -> searching = false; else -> section = Section.Library }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (!showPlayer) Column { if (player.title.isNotBlank()) MiniPlayer(player, onExpand = { showPlayer = true }); if (!searching && selectedPodcast == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Section.entries.forEach { item -> NavigationBarItem(selected = section == item, onClick = { section = item }, icon = { Icon(item.icon, null) }, label = { Text(item.label) }) }
            } } }
    ) { padding ->
        if (showPlayer) NowPlayingScreen(player, onBack = { showPlayer = false }, Modifier.padding(padding))
        else if (selectedPodcast != null) PodcastDetailScreen(selectedPodcast!!, player, onBack = { selectedPodcast = null }, Modifier.padding(padding))
        else if (searching) SearchScreen(subscriptions, ::subscribe, onOpen = { selectedPodcast = it }, onBack = { searching = false }, Modifier.padding(padding))
        else when (section) {
            Section.Library -> LibraryScreen(subscriptions, onOpen = { selectedPodcast = it }, onUnsubscribe = ::unsubscribe, onSearch = { searching = true }, Modifier.padding(padding))
            Section.Downloads -> DownloadsScreen(Modifier.padding(padding))
            Section.Profile -> SettingsScreen(dark, palette, subscriptions, ::importPodcasts, onDarkChange, onPaletteChange, Modifier.padding(padding))
        }
    }
}

private data class LatestEpisode(val podcast: PodcastSearchResult, val episode: FeedEpisode)
private enum class EpisodeFilter(val label: String) { Pending("Pendientes"), Favorites("Favoritos"), Archived("Archivados") }

@Composable private fun HomeScreen(subscriptions: List<PodcastSearchResult>, player: PlayerConnection, onOpen: (PodcastSearchResult) -> Unit, modifier: Modifier = Modifier, onSearch: () -> Unit) {
    val context = LocalContext.current
    val folderPrefs = remember { context.getSharedPreferences("folders", 0) }
    val folderNames = remember(subscriptions.toList()) { (folderPrefs.getStringSet("names", emptySet()) ?: emptySet()).sorted() }
    val assignments = remember(subscriptions.toList()) { subscriptions.associateWith { folderPrefs.getString("podcast_${it.feedUrl.hashCode()}", null) } }
    var latest by remember { mutableStateOf(emptyList<LatestEpisode>()) }
    var filter by remember { mutableStateOf(EpisodeFilter.Pending) }
    val episodePrefs = remember { context.getSharedPreferences("episode_state", 0) }
    var favorites by remember { mutableStateOf(episodePrefs.getStringSet("favorites", emptySet()) ?: emptySet()) }
    var archived by remember { mutableStateOf(episodePrefs.getStringSet("archived", emptySet()) ?: emptySet()) }
    val listened = remember { episodePrefs.getStringSet("listened", emptySet()) ?: emptySet() }
    var loading by remember(subscriptions.size) { mutableStateOf(subscriptions.isNotEmpty()) }
    LaunchedEffect(subscriptions.toList()) {
        latest = subscriptions.mapNotNull { podcast -> runCatching { PodcastDirectory(context).loadEpisodes(podcast).episodes.take(3).map { LatestEpisode(podcast, it) } }.getOrNull() }.flatten().take(30)
        loading = false
    }
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE · d MMM", Locale("es", "MX"))).uppercase() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary); Text("Escucha sin prisa", style = MaterialTheme.typography.headlineMedium) }
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Buscar") }
        } }
        if (subscriptions.isEmpty()) item { EmptyLibrary(onSearch) }
        else {
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(EpisodeFilter.entries) { option -> FilterChip(filter == option, onClick = { filter = option }, label = { Text(option.label) }, leadingIcon = if (filter == option) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null) } } }
            val visible = when (filter) { EpisodeFilter.Pending -> latest.filter { it.episode.id !in listened && it.episode.id !in archived }; EpisodeFilter.Favorites -> latest.filter { it.episode.id in favorites }; EpisodeFilter.Archived -> latest.filter { it.episode.id in archived } }
            item { SectionTitle(filter.label, "${visible.size}") }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!loading && visible.isEmpty()) item { Text("No hay episodios en esta vista.", color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
            items(visible.take(12), key = { it.episode.id }) { item -> RealEpisodeRow(item, { player.play(item.episode, item.podcast.title) }, { onOpen(item.podcast) }, if (filter == EpisodeFilter.Archived) ({ archived = archived - item.episode.id; episodePrefs.edit().putStringSet("archived", archived).apply() }) else null) }
            folderNames.forEach { folder ->
                val grouped = subscriptions.filter { assignments[it] == folder }
                if (grouped.isNotEmpty()) { item { SectionTitle(folder, "${grouped.size}") }; item { PodcastGroupRow(grouped, onOpen) } }
            }
            val ungrouped = subscriptions.filter { assignments[it] == null }
            if (ungrouped.isNotEmpty()) { item { SectionTitle(if (folderNames.isEmpty()) "Tus podcasts" else "Sin carpeta", "${ungrouped.size}") }; item { PodcastGroupRow(ungrouped, onOpen) } }
        }
    }
}

@Composable private fun EmptyLibrary(onSearch: () -> Unit) { Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Podcasts, null, Modifier.size(58.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.height(14.dp)); Text("Tu biblioteca está tranquila", style = MaterialTheme.typography.titleLarge); Text("Busca un podcast para comenzar.", color = MaterialTheme.colorScheme.onSurface.copy(.55f)); Spacer(Modifier.height(18.dp)); Button(onClick = onSearch) { Text("Encontrar podcasts") } } }

@Composable private fun RealEpisodeRow(item: LatestEpisode, onPlay: () -> Unit, onOpen: () -> Unit, onRestore: (() -> Unit)? = null) { Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(item.episode.artworkUrl ?: item.podcast.artworkUrl, item.episode.title, Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(item.episode.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${item.podcast.title} · ${item.episode.published}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1) }; if (onRestore != null) IconButton(onClick = onRestore) { Icon(Icons.Outlined.Unarchive, "Restaurar") } else IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Reproducir") } } }

@Composable private fun PodcastGroupRow(podcasts: List<PodcastSearchResult>, onOpen: (PodcastSearchResult) -> Unit) { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(podcasts, key = { it.feedUrl }) { podcast -> Column(Modifier.width(132.dp).clickable { onOpen(podcast) }) { AsyncImage(podcast.artworkUrl, podcast.title, Modifier.size(132.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant)); Spacer(Modifier.height(8.dp)); Text(podcast.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(podcast.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1) } } } }

@Composable private fun SectionTitle(title: String, action: String) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); Text(action, color = Clay, style = MaterialTheme.typography.labelLarge) } }

@Composable private fun LibraryScreen(subscriptions: List<PodcastSearchResult>, onOpen: (PodcastSearchResult) -> Unit, onUnsubscribe: (PodcastSearchResult) -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("folders", 0) }
    val folders = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("names", emptySet()) ?: emptySet()) } }
    val assignments = remember { mutableStateMapOf<String, String>().apply { subscriptions.forEach { p -> prefs.getString("podcast_${p.feedUrl.hashCode()}", null)?.let { put(p.feedUrl, it) } } } }
    var create by remember { mutableStateOf(false) }; var newName by remember { mutableStateOf("") }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("Nueva carpeta") }, text = { OutlinedTextField(newName, { newName = it }, label = { Text("Nombre") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (newName.isNotBlank() && newName !in folders) { folders.add(newName.trim()); prefs.edit().putStringSet("names", folders.toSet()).apply() }; newName = ""; create = false }) { Text("Crear") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("Cancelar") } })
    if (subscriptions.isEmpty()) { Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyLibrary(onSearch) }; return }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Tu biblioteca", style = MaterialTheme.typography.headlineMedium); Text("${subscriptions.size} podcasts", color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }; IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Buscar podcasts") }; IconButton(onClick = { create = true }) { Icon(Icons.Default.CreateNewFolder, "Nueva carpeta") } } }
        folders.forEach { folder -> item { Text(folder.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }; items(subscriptions.filter { assignments[it.feedUrl] == folder }, key = { "$folder:${it.feedUrl}" }) { podcast -> LibraryPodcastRow(podcast, folders, folder, { onOpen(podcast) }, { onUnsubscribe(podcast) }) { destination -> assignments[podcast.feedUrl] = destination; prefs.edit().putString("podcast_${podcast.feedUrl.hashCode()}", destination).apply() } } }
        item { Text("SIN CARPETA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
        items(subscriptions.filter { assignments[it.feedUrl] == null }, key = { it.feedUrl }) { podcast -> LibraryPodcastRow(podcast, folders, null, { onOpen(podcast) }, { onUnsubscribe(podcast) }) { destination -> assignments[podcast.feedUrl] = destination; prefs.edit().putString("podcast_${podcast.feedUrl.hashCode()}", destination).apply() } }
    }
}
@Composable private fun LibraryPodcastRow(podcast: PodcastSearchResult, folders: List<String>, assigned: String?, onOpen: () -> Unit, onRemove: () -> Unit, onAssign: (String) -> Unit) { var menu by remember { mutableStateOf(false) }; Box { Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(podcast.artworkUrl, podcast.title, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(podcast.title, fontWeight = FontWeight.SemiBold); Text(podcast.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }; IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") } }; DropdownMenu(menu, { menu = false }) { folders.filter { it != assigned }.forEach { folder -> DropdownMenuItem({ Text("Mover a $folder") }, onClick = { menu = false; onAssign(folder) }, leadingIcon = { Icon(Icons.Outlined.Folder, null) }) }; DropdownMenuItem({ Text("Dejar de seguir") }, onClick = { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }) } } }

@Composable private fun SettingsScreen(dark: Boolean, palette: KansoPalette, subscriptions: List<PodcastSearchResult>, onImport: (List<PodcastSearchResult>) -> Unit, onDarkChange: (Boolean) -> Unit, onPaletteChange: (KansoPalette) -> Unit, modifier: Modifier) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("automation", 0) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runCatching { context.contentResolver.openInputStream(it)!!.use(::parseOpml) }.onSuccess { podcasts -> onImport(podcasts); transferMessage = "${podcasts.size} podcasts importados" }.onFailure { transferMessage = "No se pudo leer el archivo OPML" } } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri -> uri?.let { runCatching { context.contentResolver.openOutputStream(it)!!.bufferedWriter().use { writer -> writer.write(createOpml(subscriptions)) } }.onSuccess { transferMessage = "Biblioteca exportada" }.onFailure { transferMessage = "No se pudo exportar" } } }
    var count by remember { mutableIntStateOf(prefs.getInt("download_count", 0)) }; var days by remember { mutableIntStateOf(prefs.getInt("delete_days", 7)) }
    var archive by remember { mutableStateOf(prefs.getBoolean("archive_finished", true)) }; var wifi by remember { mutableStateOf(prefs.getBoolean("wifi_only", true)) }
    fun persist() { prefs.edit().putInt("download_count", count).putInt("delete_days", days).putBoolean("archive_finished", archive).putBoolean("wifi_only", wifi).apply(); com.kanso.podcast.data.AutomationWorker.schedule(context, wifi) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Tu espacio", style = MaterialTheme.typography.headlineMedium); Text("Automatiza y hazlo tuyo.", color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        item { Text("APARIENCIA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
        item { ToggleSetting(if (dark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, "Modo oscuro", dark, onDarkChange) }
        item { PalettePicker(palette, onPaletteChange) }
        item { Text("AUTOMATIZACIÓN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
        item { NumberSetting(Icons.Outlined.Download, "Descargas por podcast", if (count == 0) "Desactivadas" else "$count episodios nuevos", count, 0..10) { count = it; persist() } }
        item { ToggleSetting(Icons.Outlined.Inventory2, "Archivar al terminar", archive) { archive = it; persist() } }
        item { NumberSetting(Icons.Outlined.DeleteSweep, "Borrado automático", if (days == 0) "Nunca" else "$days días después de escucharlo", days, 0..30) { days = it; persist() } }
        item { ToggleSetting(Icons.Outlined.Wifi, "Descargar solo con Wi‑Fi", wifi) { wifi = it; persist() } }
        item { Text("Kanso revisa tus feeds cada 6 horas. Solo se borran episodios ya escuchados; los favoritos nunca se archivan ni se borran automáticamente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        item { Text("BIBLIOTECA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/xml", "application/xml", "text/x-opml")) }, Modifier.weight(1f)) { Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Importar OPML") }; OutlinedButton(onClick = { exportLauncher.launch("kanso-podcasts.opml") }, Modifier.weight(1f), enabled = subscriptions.isNotEmpty()) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(6.dp)); Text("Exportar") } } }
        transferMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary) } }
        item { Text("MANTENIMIENTO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
        item { OutlinedButton(onClick = { androidx.work.WorkManager.getInstance(context).enqueue(androidx.work.OneTimeWorkRequestBuilder<com.kanso.podcast.data.AutomationWorker>().build()); refreshMessage = "Actualización programada" }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(8.dp)); Text("Actualizar todos los feeds") } }
        refreshMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary) } }
        item { DiagnosticsCard(context, subscriptions.size) }
    }
}
@Composable private fun DiagnosticsCard(context: Context, subscriptionCount: Int) { val cached = remember { context.getSharedPreferences("feed_cache", 0).all.keys.count { it.startsWith("feed_") } }; val downloads = remember { queryDownloads(context).size }; Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("Diagnóstico", fontWeight = FontWeight.SemiBold); DiagnosticLine("Biblioteca", "$subscriptionCount suscripciones"); DiagnosticLine("Caché offline", "$cached feeds"); DiagnosticLine("Descargas", "$downloads elementos"); DiagnosticLine("Android Auto", "Servicio disponible"); DiagnosticLine("Chromecast", "Selector habilitado"); DiagnosticLine("Widget", "Controles habilitados") } }
@Composable private fun DiagnosticLine(label: String, value: String) { Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(.65f)); Text(value, fontWeight = FontWeight.Medium) } }
@Composable private fun ToggleSetting(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(14.dp)); Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium); Switch(checked, onChange) } }
@Composable private fun NumberSetting(icon: ImageVector, title: String, value: String, number: Int, range: IntRange, onChange: (Int) -> Unit) { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.Medium); Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) } }; Slider(number.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0)) } }

@Composable private fun PalettePicker(selected: KansoPalette, onSelect: (KansoPalette) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Text("Combinación de colores", fontWeight = FontWeight.Medium); Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { KansoPalette.entries.forEach { item ->
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(item) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(item.accent), contentAlignment = Alignment.Center) { if (selected == item) Icon(Icons.Default.Check, null, tint = Color.White) }
                Text(item.label, style = MaterialTheme.typography.labelSmall)
            }
        } }
    }
}

@Composable private fun SearchScreen(subscriptions: List<PodcastSearchResult>, onSubscribe: (PodcastSearchResult) -> Unit, onOpen: (PodcastSearchResult) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    var query by remember { mutableStateOf("") }; var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var results by remember { mutableStateOf(emptyList<PodcastSearchResult>()) }
    val scope = rememberCoroutineScope(); val context = LocalContext.current; val directory = remember { PodcastDirectory(context) }
    fun submit() { if (query.isBlank()) return; loading = true; error = null; scope.launch { runCatching { directory.search(query) }.onSuccess { results = it }.onFailure { error = it.message ?: "No pudimos completar la búsqueda" }; loading = false } }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver") }; Text("Encontrar podcasts", style = MaterialTheme.typography.headlineMedium) } }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nombre o URL del RSS") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { IconButton(onClick = { submit() }) { Icon(Icons.Default.ArrowForward, "Buscar") } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() })) }
        item { Text("Busca por título, autor o pega directamente https://…/feed.xml", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (!loading && results.isEmpty() && error == null) item { SimpleSearchHint() }
        items(results, key = { it.id }) { podcast -> PodcastResultRow(podcast, subscriptions.any { it.feedUrl == podcast.feedUrl }, { onSubscribe(podcast) }, { onOpen(podcast) }) }
    }
}

@Composable private fun SimpleSearchHint() { Column(Modifier.fillMaxWidth().padding(vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.RssFeed, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.height(12.dp)); Text("Tu próxima escucha puede estar aquí", style = MaterialTheme.typography.titleLarge) } }

@Composable private fun PodcastResultRow(podcast: PodcastSearchResult, subscribed: Boolean, onSubscribe: () -> Unit, onOpen: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(podcast.artworkUrl, podcast.title, Modifier.size(62.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(podcast.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(podcast.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1) }
        FilledTonalButton(onClick = onSubscribe, enabled = !subscribed, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (subscribed) "Siguiendo" else "Seguir") }
    }
}

private fun loadSubscriptions(raw: String?): List<PodcastSearchResult> = runCatching {
    if (raw == null) return emptyList()
    val array = JSONArray(raw)
    List(array.length()) { i -> array.getJSONObject(i).run { PodcastSearchResult(getString("id"), getString("title"), getString("author"), getString("feed"), optString("artwork").ifBlank { null }, optString("genre", "Podcast")) } }
}.getOrDefault(emptyList())

private fun saveSubscriptions(items: List<PodcastSearchResult>): String = JSONArray().apply {
    items.forEach { item -> put(JSONObject().apply { put("id", item.id); put("title", item.title); put("author", item.author); put("feed", item.feedUrl); put("artwork", item.artworkUrl ?: ""); put("genre", item.genre) }) }
}.toString()

private val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f, 2.5f, 3f)
private class PlayerConnection(private val context: Context) {
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
    private var pendingEpisode: FeedEpisode? = null
    private var pendingPodcast: String? = null
    val queue = mutableStateListOf<String>()
    var speed by mutableFloatStateOf(1f)

    fun play(episode: FeedEpisode, podcastTitle: String) {
        if (controller == null) { pendingEpisode = episode; pendingPodcast = podcastTitle; title = episode.title; podcast = podcastTitle; artwork = episode.artworkUrl; openRequest++; return }
        val item = mediaItemFor(episode, podcastTitle)
        controller?.apply {
            error = null
            val saved = context.getSharedPreferences("progress", 0).getLong(episode.id, 0L).coerceAtLeast(0L)
            val existing = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == episode.id }
            when {
                existing != null -> seekTo(existing, saved)
                mediaItemCount == 0 -> setMediaItem(item, saved)
                else -> { val target = currentMediaItemIndex + 1; addMediaItem(target, item); seekTo(target, saved) }
            }
            prepare()
            playWhenReady = true
        }
        episodeId = episode.id; title = episode.title; podcast = podcastTitle; artwork = episode.artworkUrl
        context.getSharedPreferences("now_playing", 0).edit().putString("title", episode.title).putString("podcast", podcastTitle).apply()
        CoroutineScope(Dispatchers.IO).launch { KansoWidget().updateAll(context) }
        openRequest++
    }
    private fun mediaItemFor(episode: FeedEpisode, podcastTitle: String) = MediaItem.Builder().setMediaId(episode.id).setUri(EpisodeDownloads.playableUri(context, episode))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).setArtist(podcastTitle).setArtworkUri(episode.artworkUrl?.let(Uri::parse)).build()).build()
    fun playPendingIfAny() { val episode = pendingEpisode ?: return; val podcastTitle = pendingPodcast ?: ""; pendingEpisode = null; pendingPodcast = null; play(episode, podcastTitle) }
    fun enqueue(episode: FeedEpisode, podcastTitle: String) {
        val c = controller ?: return
        if ((0 until c.mediaItemCount).any { c.getMediaItemAt(it).mediaId == episode.id }) return
        c.addMediaItem(mediaItemFor(episode, podcastTitle))
        if (c.mediaItemCount == 1) c.prepare()
    }
    /** [displayIndex] is relative to the "Up next" list, i.e. items after the current one. */
    fun removeQueued(displayIndex: Int) { val c = controller ?: return; val actual = c.currentMediaItemIndex + 1 + displayIndex; if (actual in 0 until c.mediaItemCount) c.removeMediaItem(actual) }
    var currentIndex by mutableIntStateOf(0)
    var sleepAt by mutableLongStateOf(0L)
    var sleepEndOfEpisode by mutableStateOf(false)
    fun setSleepTimer(minutes: Int) { controller?.sendCustomCommand(SessionCommand(KansoPlaybackService.COMMAND_SLEEP_TIMER, Bundle.EMPTY), Bundle().apply { putInt(KansoPlaybackService.ARG_MINUTES, minutes) }) }
    fun readSleepExtras(extras: Bundle) { sleepAt = extras.getLong(KansoPlaybackService.EXTRA_SLEEP_AT, 0L); sleepEndOfEpisode = extras.getBoolean(KansoPlaybackService.EXTRA_SLEEP_END_OF_EPISODE, false) }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekBy(delta: Long) { controller?.let { it.seekTo((it.currentPosition + delta).coerceAtLeast(0)) } }
    fun cycleSpeed() { speed = SPEEDS.firstOrNull { it > speed + 0.01f } ?: SPEEDS.first(); controller?.setPlaybackSpeed(speed) }
}

@Composable private fun rememberPlayerConnection(): PlayerConnection {
    val context = LocalContext.current
    val connection = remember { PlayerConnection(context) }
    DisposableEffect(Unit) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, KansoPlaybackService::class.java)))
            .setListener(object : MediaController.Listener { override fun onExtrasChanged(controller: MediaController, extras: Bundle) { connection.readSleepExtras(extras) } })
            .buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess { localPlayer ->
        connection.controller = localPlayer
        fun syncQueue() { connection.currentIndex = localPlayer.currentMediaItemIndex.coerceAtLeast(0); connection.queue.clear(); for (i in 0 until localPlayer.mediaItemCount) connection.queue.add(localPlayer.getMediaItemAt(i).mediaMetadata.title?.toString().orEmpty()) }
        syncQueue(); localPlayer.currentMediaItem?.mediaMetadata?.let { metadata -> connection.title = metadata.title?.toString().orEmpty(); connection.podcast = metadata.artist?.toString().orEmpty(); connection.artwork = metadata.artworkUri?.toString() }; connection.episodeId = localPlayer.currentMediaItem?.mediaId.orEmpty(); connection.speed = localPlayer.playbackParameters.speed; connection.readSleepExtras(localPlayer.sessionExtras)
        connection.playPendingIfAny()
        localPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { connection.playing = isPlaying; context.getSharedPreferences("now_playing", 0).edit().putBoolean("is_playing", isPlaying).apply(); CoroutineScope(Dispatchers.IO).launch { KansoWidget().updateAll(context) } }
                    override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                        connection.title = metadata.title?.toString().orEmpty(); connection.podcast = metadata.artist?.toString().orEmpty(); connection.artwork = metadata.artworkUri?.toString(); connection.episodeId = localPlayer.currentMediaItem?.mediaId.orEmpty(); syncQueue()
                    }
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { syncQueue() }
                    override fun onPlayerError(error: PlaybackException) { connection.error = when (error.errorCode) { PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "El servidor rechazó la reproducción."; PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No hay conexión con el servidor del podcast."; PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Este formato de audio no es compatible."; else -> error.localizedMessage ?: "No se pudo reproducir este episodio." } }
                })
        } }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future); connection.controller = null }
    }
    LaunchedEffect(Unit) { while (true) { connection.controller?.let { connection.position = it.currentPosition.coerceAtLeast(0); connection.duration = it.duration.coerceAtLeast(0) }; delay(500) } }
    return connection
}

@Composable private fun MiniPlayer(player: PlayerConnection, onExpand: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).clickable(onClick = onExpand)) { LinearProgressIndicator(progress = { if (player.duration > 0) player.position.toFloat() / player.duration else 0f }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.onPrimary.copy(.15f)); Row(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (player.artwork != null) AsyncImage(player.artwork, player.title, Modifier.size(60.dp).clip(RoundedCornerShape(14.dp))) else Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GraphicEq, null, tint = Color.White) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(player.title, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(player.podcast, color = MaterialTheme.colorScheme.onPrimary.copy(.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        IconButton(onClick = { player.seekBy(-10_000) }) { Icon(Icons.Outlined.Replay10, "Retroceder", tint = MaterialTheme.colorScheme.onPrimary) }
        IconButton(onClick = player::toggle, modifier = Modifier.size(52.dp)) { Icon(if (player.playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Reproducir o pausar", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(38.dp)) }
    } }
}

@Composable private fun NowPlayingScreen(player: PlayerConnection, onBack: () -> Unit, modifier: Modifier) {
    var timerMenu by remember { mutableStateOf(false) }
    val timer = when { player.sleepEndOfEpisode -> "Fin del episodio"; player.sleepAt > 0 -> "${((player.sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000) + 1} min"; else -> "Temporizador" }
    fun setTimer(minutes: Int) { timerMenu = false; player.setSleepTimer(minutes) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Box(Modifier.fillMaxWidth()) { IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }; Text("REPRODUCIENDO", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) } }
        item { Spacer(Modifier.height(20.dp)); if (player.artwork != null) AsyncImage(player.artwork, player.title, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) else Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GraphicEq, null, Modifier.size(90.dp), tint = Color.White) } }
        item { Spacer(Modifier.height(28.dp)); Text(player.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(player.podcast, color = MaterialTheme.colorScheme.onSurface.copy(.55f)); Spacer(Modifier.height(20.dp)); Slider(value = player.position.toFloat(), onValueChange = { player.controller?.seekTo(it.toLong()) }, valueRange = 0f..player.duration.coerceAtLeast(1).toFloat()); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(player.position), style = MaterialTheme.typography.labelSmall); Text("−${formatTime((player.duration-player.position).coerceAtLeast(0))}", style = MaterialTheme.typography.labelSmall) } }
        player.error?.let { message -> item { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer); Spacer(Modifier.width(10.dp)); Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer); TextButton(onClick = { player.controller?.prepare(); player.controller?.playWhenReady = true }) { Text("Reintentar") } } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { player.controller?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Anterior") }; IconButton(onClick = { player.seekBy(-10_000) }) { Icon(Icons.Outlined.Replay10, "Retroceder") }; FilledIconButton(onClick = player::toggle, modifier = Modifier.size(72.dp)) { Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Pausa", Modifier.size(36.dp)) }; IconButton(onClick = { player.seekBy(30_000) }) { Icon(Icons.Outlined.Forward30, "Adelantar") }; IconButton(onClick = { player.controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Siguiente") } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { FilledTonalButton(onClick = player::cycleSpeed) { Icon(Icons.Outlined.Speed, null); Spacer(Modifier.width(6.dp)); Text("${"%.1f".format(java.util.Locale.US, player.speed).removeSuffix(".0")}×") }; Box { FilledTonalButton(onClick = { timerMenu = true }) { Icon(Icons.Outlined.Timer, null); Spacer(Modifier.width(6.dp)); Text(timer) }; DropdownMenu(timerMenu, { timerMenu = false }) { listOf(5, 15, 30, 45, 60).forEach { minutes -> DropdownMenuItem({ Text("$minutes minutos") }, onClick = { setTimer(minutes) }) }; DropdownMenuItem({ Text("Al terminar el episodio") }, onClick = { setTimer(KansoPlaybackService.END_OF_EPISODE) }); if (player.sleepAt > 0 || player.sleepEndOfEpisode) DropdownMenuItem({ Text("Cancelar temporizador") }, onClick = { setTimer(0) }) } } } }
        if (player.queue.size > player.currentIndex + 1) { item { HorizontalDivider(); Text("A continuación", Modifier.fillMaxWidth().padding(top = 20.dp), style = MaterialTheme.typography.titleLarge) }; itemsIndexed(player.queue.drop(player.currentIndex + 1)) { index, title -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.DragHandle, null, tint = MaterialTheme.colorScheme.onSurface.copy(.4f)); Spacer(Modifier.width(12.dp)); Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); IconButton(onClick = { player.removeQueued(index) }) { Icon(Icons.Outlined.Close, "Quitar de la cola") } } } }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

private fun formatTime(ms: Long): String { val total = ms / 1000; return "%d:%02d".format(total / 60, total % 60) }

@Composable private fun PodcastDetailScreen(podcast: PodcastSearchResult, player: PlayerConnection, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val automation = remember { context.getSharedPreferences("automation", 0) }; var settings by remember { mutableStateOf(false) }; var autoCount by remember { mutableIntStateOf(automation.getInt("podcast_count_${podcast.feedUrl.hashCode()}", automation.getInt("download_count", 0))) }
    var feed by remember(podcast.feedUrl) { mutableStateOf<PodcastFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val listened = remember { mutableStateListOf<String>().apply { addAll(context.getSharedPreferences("episode_state", 0).getStringSet("listened", emptySet()) ?: emptySet()) } }
    val favorites = remember { mutableStateListOf<String>().apply { addAll(context.getSharedPreferences("episode_state", 0).getStringSet("favorites", emptySet()) ?: emptySet()) } }
    val archived = remember { mutableStateListOf<String>().apply { addAll(context.getSharedPreferences("episode_state", 0).getStringSet("archived", emptySet()) ?: emptySet()) } }
    var showArchived by remember { mutableStateOf(false) }
    val progressPrefs = remember { context.getSharedPreferences("progress", 0) }
    LaunchedEffect(podcast.feedUrl) { runCatching { PodcastDirectory(context).loadEpisodes(podcast) }.onSuccess { feed = it }.onFailure { error = it.message ?: "No pudimos leer los episodios" } }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Automatización") }, text = { Column { Text(podcast.title, maxLines = 1); Spacer(Modifier.height(14.dp)); Text(if (autoCount == 0) "Descargas automáticas desactivadas" else "Descargar $autoCount episodios nuevos"); Slider(autoCount.toFloat(), { autoCount = it.toInt() }, valueRange = 0f..10f, steps = 9) } }, confirmButton = { TextButton(onClick = { automation.edit().putInt("podcast_count_${podcast.feedUrl.hashCode()}", autoCount).apply(); settings = false }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = { settings = false }) { Text("Cancelar") } })
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver") }; AsyncImage(podcast.artworkUrl, podcast.title, Modifier.size(74.dp).clip(RoundedCornerShape(18.dp))); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(podcast.title, style = MaterialTheme.typography.titleLarge, maxLines = 2); Text(podcast.author, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1) }; IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Tune, "Ajustes del podcast") } } }
        item { Text("EPISODIOS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
        if (feed == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        feed?.let { loaded ->
            val archivedCount = loaded.episodes.count { it.id in archived }
            if (archivedCount > 0) item { TextButton(onClick = { showArchived = !showArchived }) { Icon(Icons.Outlined.Inventory2, null); Spacer(Modifier.width(6.dp)); Text(if (showArchived) "Ocultar archivados" else "Mostrar $archivedCount archivados") } }
            val visible = if (showArchived) loaded.episodes else loaded.episodes.filter { it.id !in archived }
            if (loaded.episodes.isEmpty()) item { Text("Este feed no contiene episodios reproducibles.") } else items(visible, key = { it.id }) { episode ->
            EpisodeFeedRow(episode, episode.id in listened || episode.id in archived, episode.id in favorites, progressPrefs.getLong(episode.id, 0L), player.episodeId == episode.id,
                onPlay = { player.play(episode, podcast.title) }, onQueue = { player.enqueue(episode, podcast.title) },
                onListened = { toggleStored(context, "listened", episode.id, listened); if (episode.id in archived && episode.id !in listened) toggleStored(context, "archived", episode.id, archived) },
                onFavorite = { toggleStored(context, "favorites", episode.id, favorites) },
                onDownload = { downloadEpisode(context, podcast.title, episode) })
        } }
    }
}

@Composable private fun EpisodeFeedRow(episode: FeedEpisode, listened: Boolean, favorite: Boolean, progressMs: Long, current: Boolean, onPlay: () -> Unit, onQueue: () -> Unit, onListened: () -> Unit, onFavorite: () -> Unit, onDownload: () -> Unit) {
    val totalMs = parseDurationMs(episode.duration)
    val fraction = if (totalMs > 0 && progressMs > 0) (progressMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val meta = buildString {
        append(episode.published)
        when { listened -> append(" · Escuchado"); fraction > 0f -> append(" · Quedan ${formatDuration(totalMs - progressMs)}"); totalMs > 0 -> append(" · ${formatDuration(totalMs)}"); episode.duration != "—" -> append(" · ${episode.duration}") }
    }
    Column(Modifier.fillMaxWidth().alpha(if (listened) .6f else 1f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(episode.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface); Spacer(Modifier.height(2.dp)); Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }; Spacer(Modifier.width(12.dp)); FilledIconButton(onClick = onPlay, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Icon(if (current) Icons.Default.GraphicEq else Icons.Default.PlayArrow, "Reproducir", tint = Color.White) } }
        if (fraction > 0f && !listened) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.onSurface.copy(.1f))
        if (episode.description.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(episode.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(.75f)) }
        Row(Modifier.padding(top = 4.dp)) { IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, "Favorito", tint = if (favorite) MaterialTheme.colorScheme.secondary else LocalContentColor.current) }; IconButton(onClick = onListened) { Icon(if (listened) Icons.Default.CheckCircle else Icons.Outlined.CheckCircle, "Escuchado") }; IconButton(onClick = onQueue) { Icon(Icons.Outlined.PlaylistAdd, "Añadir a la cola") }; IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, "Descargar") } }
    }
}
/** Parses RSS itunes:duration ("HH:MM:SS", "MM:SS" or seconds) into milliseconds; 0 if unknown. */
private fun parseDurationMs(value: String): Long {
    val parts = value.trim().split(":").map { it.trim().toLongOrNull() ?: return 0L }
    if (parts.isEmpty() || parts.size > 3) return 0L
    return parts.fold(0L) { acc, n -> acc * 60 + n } * 1000
}
private fun formatDuration(ms: Long): String { val minutes = (ms / 60_000).coerceAtLeast(1); return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min" }
private fun toggleStored(context: Context, key: String, id: String, state: MutableList<String>) {
    if (id in state) state.remove(id) else state.add(id)
    context.getSharedPreferences("episode_state", 0).edit().putStringSet(key, state.toSet()).apply()
}

private fun downloadEpisode(context: Context, podcast: String, episode: FeedEpisode) { EpisodeDownloads.enqueue(context, episode.id, episode.audioUrl, episode.title, podcast) }
private data class LocalDownload(val id: Long, val title: String, val status: Int, val uri: String?, val source: String?, val downloaded: Long, val total: Long)

@Composable private fun DownloadsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }; val downloads = remember(refresh) { queryDownloads(context) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Descargas", style = MaterialTheme.typography.headlineMedium); Text("Tus episodios, listos sin conexión.", color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }; IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "Actualizar") } } }
        if (downloads.isEmpty()) item { SimpleSearchHint() }
        items(downloads) { download -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (download.status == DownloadManager.STATUS_SUCCESSFUL) Icons.Default.OfflinePin else Icons.Outlined.Downloading, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(download.title, fontWeight = FontWeight.SemiBold); Text(downloadStatus(download), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)); if (download.status == DownloadManager.STATUS_RUNNING && download.total > 0) LinearProgressIndicator(progress = { download.downloaded.toFloat() / download.total }, Modifier.fillMaxWidth().padding(top = 6.dp)) }; if (download.status == DownloadManager.STATUS_FAILED && download.source != null) IconButton(onClick = { retryDownload(context, download); refresh++ }) { Icon(Icons.Outlined.Refresh, "Reintentar") }; IconButton(onClick = { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(download.id); refresh++ }) { Icon(Icons.Outlined.Delete, "Eliminar") }
        } }
    }
}

private fun queryDownloads(context: Context): List<LocalDownload> = runCatching {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    manager.query(DownloadManager.Query()).use { cursor -> buildList {
        val id = cursor.getColumnIndex(DownloadManager.COLUMN_ID); val title = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE); val status = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS); val uri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI); val source = cursor.getColumnIndex(DownloadManager.COLUMN_URI); val bytes = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR); val total = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        while (cursor.moveToNext()) add(LocalDownload(cursor.getLong(id), cursor.getString(title) ?: "Episodio", cursor.getInt(status), cursor.getString(uri), cursor.getString(source), cursor.getLong(bytes), cursor.getLong(total)))
    } }
}.getOrDefault(emptyList())

private fun downloadStatus(item: LocalDownload) = when (item.status) { DownloadManager.STATUS_SUCCESSFUL -> "Disponible sin conexión"; DownloadManager.STATUS_FAILED -> "Falló · toca eliminar y vuelve a descargar"; DownloadManager.STATUS_PAUSED -> "En pausa"; DownloadManager.STATUS_PENDING -> "En espera"; else -> if (item.total > 0) "${item.downloaded * 100 / item.total}%" else "Descargando…" }
private fun retryDownload(context: Context, item: LocalDownload) { EpisodeDownloads.retry(context, item.id, item.source ?: return, item.title) }
private fun parseOpml(stream: java.io.InputStream): List<PodcastSearchResult> {
    val parser = Xml.newPullParser().apply { setInput(stream, null) }; val result = mutableListOf<PodcastSearchResult>(); var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) { if (event == XmlPullParser.START_TAG && parser.name.equals("outline", true)) { val feed = parser.getAttributeValue(null, "xmlUrl") ?: parser.getAttributeValue(null, "xmlurl"); if (!feed.isNullOrBlank()) { val title = parser.getAttributeValue(null, "title") ?: parser.getAttributeValue(null, "text") ?: feed; result += PodcastSearchResult(feed.hashCode().toString(), title, "Importado por OPML", feed) } }; event = parser.next() }
    return result.distinctBy { it.feedUrl }
}

private fun createOpml(items: List<PodcastSearchResult>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml version=\"2.0\"><head><title>Kanso Podcast</title></head><body>")
    items.forEach { append("<outline type=\"rss\" text=\"").append(xmlEscape(it.title)).append("\" title=\"").append(xmlEscape(it.title)).append("\" xmlUrl=\"").append(xmlEscape(it.feedUrl)).append("\"/>") }
    append("</body></opml>")
}
private fun xmlEscape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
