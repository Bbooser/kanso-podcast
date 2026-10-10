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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

internal class SearchViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    var query by mutableStateOf("")
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var results by mutableStateOf(emptyList<PodcastSearchResult>()); private set
    val subscribed = repo.podcastEntities.map { list -> list.map { it.feedUrl }.toSet() }.stateIn(this, emptySet())
    fun submit() {
        if (query.isBlank()) return
        loading = true; error = null
        viewModelScope.launch { runCatching { repo.search(query) }.onSuccess { results = it }.onFailure { error = it.message ?: "No pudimos completar la búsqueda" }; loading = false }
    }
    fun subscribe(p: PodcastSearchResult) = viewModelScope.launch { repo.subscribe(p) }
}

@Composable internal fun SearchScreen(onOpen: (PodcastSearchResult) -> Unit, onBack: () -> Unit, modifier: Modifier, vm: SearchViewModel = viewModel()) {
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(Kanso.itemGap)) {
        item { ScreenHeader("Encontrar podcasts", onBack = onBack) }
        item {
            OutlinedTextField(vm.query, { vm.query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nombre o URL del RSS") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = vm::submit) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Buscar") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.submit() }), shape = Kanso.art)
            Spacer(Modifier.height(6.dp))
            Text("Busca por título o autor, o pega directamente https://…/feed.xml", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
        }
        if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary) }
        vm.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (!vm.loading && vm.results.isEmpty() && vm.error == null) item { EmptyState(Icons.Outlined.RssFeed, "Tu próxima escucha puede estar aquí") }
        items(vm.results, key = { it.feedUrl }) { p ->
            Row(Modifier.fillMaxWidth().clip(Kanso.art).clickable { onOpen(p) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(p.artworkUrl, p.title, 62.dp)
                Spacer(Modifier.width(Kanso.itemGap))
                Column(Modifier.weight(1f)) {
                    Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(p.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1)
                }
                val following = p.feedUrl in subscribed
                FilledTonalButton(onClick = { vm.subscribe(p) }, enabled = !following, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (following) "Siguiendo" else "Seguir") }
            }
        }
    }
}
