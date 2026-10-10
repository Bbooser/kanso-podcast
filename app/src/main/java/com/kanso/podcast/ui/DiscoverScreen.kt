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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

/** Apple Podcasts genre ids. */
internal val CATEGORIES = listOf<Pair<String, Int?>>(
    "Todas" to null, "Crímenes reales" to 1488, "Comedia" to 1303, "Noticias" to 1489, "Sociedad y cultura" to 1324, "Historia" to 1487,
    "Tecnología" to 1318, "Ciencia" to 1533, "Negocios" to 1321, "Salud" to 1512, "Deportes" to 1545, "Educación" to 1304,
    "Música" to 1310, "Ficción" to 1483, "Infantil" to 1305, "Arte" to 1301
)

internal class DiscoverViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    private val directory = com.kanso.podcast.data.PodcastDirectory()
    var query by mutableStateOf("")
    var category by mutableStateOf<Int?>(null); private set
    var searching by mutableStateOf(false); private set
    var results by mutableStateOf<List<PodcastSearchResult>?>(null); private set
    var trending by mutableStateOf(emptyList<PodcastSearchResult>()); private set
    var loadingTrending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private val cache = mutableMapOf<Int?, List<PodcastSearchResult>>()
    val subscribed = repo.podcastEntities.map { list -> list.map { it.feedUrl }.toSet() }.stateIn(this, emptySet())
    init { loadTrending(null) }
    fun loadTrending(genre: Int?) {
        category = genre; error = null
        cache[genre]?.let { trending = it; return }
        loadingTrending = true
        viewModelScope.launch {
            runCatching { directory.trending(genre) }.onSuccess { cache[genre] = it; if (category == genre) trending = it }.onFailure { error = "No pudimos cargar las tendencias" }
            loadingTrending = false
        }
    }
    fun submit() {
        if (query.isBlank()) { results = null; return }
        searching = true; error = null
        viewModelScope.launch { runCatching { repo.search(query.trim()) }.onSuccess { results = it }.onFailure { error = it.message ?: "No pudimos completar la búsqueda" }; searching = false }
    }
    fun clear() { query = ""; results = null }
    fun subscribe(p: PodcastSearchResult) = viewModelScope.launch { repo.subscribe(p) }
}

@Composable internal fun DiscoverScreen(onOpen: (PodcastSearchResult) -> Unit, modifier: Modifier, vm: DiscoverViewModel = viewModel()) {
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val results = vm.results
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = Kanso.screenPadding)) {
        item {
            OutlinedTextField(vm.query, { vm.query = it }, Modifier.fillMaxWidth().padding(horizontal = Kanso.screenPadding), singleLine = true,
                placeholder = { Text("Buscar podcasts o añadir URL de RSS") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Outlined.Close, "Borrar") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.submit() }), shape = Kanso.art)
            Spacer(Modifier.height(Kanso.itemGap))
        }
        if (vm.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Kanso.screenPadding), color = MaterialTheme.colorScheme.secondary) }
        vm.error?.let { item { Text(it, Modifier.padding(horizontal = Kanso.screenPadding, vertical = 8.dp), color = MaterialTheme.colorScheme.error) } }
        if (results != null) {
            item { Box(Modifier.padding(horizontal = Kanso.screenPadding)) { SectionLabel("Resultados", "${results.size}") } }
            if (results.isEmpty()) item { Text("Sin resultados para “${vm.query}”.", Modifier.padding(Kanso.screenPadding), color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
            items(results, key = { "r:" + it.feedUrl }) { p -> DiscoverRow(p, p.feedUrl in subscribed, { onOpen(p) }, { vm.subscribe(p) }) }
        } else {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = Kanso.screenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CATEGORIES) { (label, id) -> FilterChip(vm.category == id, onClick = { vm.loadTrending(id) }, label = { Text(label) }, shape = RoundedCornerShape(50)) }
                }
                Spacer(Modifier.height(Kanso.sectionGap))
                Text(if (vm.category == null) "Tendencia" else CATEGORIES.first { it.second == vm.category }.first, Modifier.padding(horizontal = Kanso.screenPadding), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
            }
            if (vm.loadingTrending) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Kanso.screenPadding), color = MaterialTheme.colorScheme.secondary) }
            itemsIndexed(vm.trending, key = { _, p -> "t:" + p.feedUrl }) { i, p -> DiscoverRow(p, p.feedUrl in subscribed, { onOpen(p) }, { vm.subscribe(p) }, rank = i + 1) }
        }
    }
}

@Composable private fun DiscoverRow(p: PodcastSearchResult, following: Boolean, onOpen: () -> Unit, onSubscribe: () -> Unit, rank: Int? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = Kanso.screenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) { Text("$rank", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(.45f)) }
        Artwork(p.artworkUrl, p.title, 58.dp, RoundedCornerShape(10.dp))
        Spacer(Modifier.width(Kanso.itemGap))
        Column(Modifier.weight(1f)) {
            Text(p.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(p.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onSubscribe, enabled = !following) { Icon(if (following) Icons.Default.Check else Icons.Default.Add, if (following) "Siguiendo" else "Seguir", tint = if (following) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(.7f)) }
    }
}
