package com.kanso.podcast.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kanso.podcast.data.KansoRepository
import com.kanso.podcast.model.PodcastSearchResult
import com.kanso.podcast.data.db.EpisodeRow
import kotlinx.coroutines.delay

/** Full-text search over titles and show notes of every episode in the library. */
@Composable internal fun LibrarySearchScreen(player: PlayerConnection, onBack: () -> Unit, onOpenPodcast: (PodcastSearchResult) -> Unit, onDiscover: () -> Unit, modifier: Modifier, vm: ProfileViewModel = viewModel()) {
    val context = LocalContext.current; val repo = remember { KansoRepository.get(context.applicationContext) }
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<EpisodeRow>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isBlank()) { rows = emptyList(); searched = false; return@LaunchedEffect }
        delay(300) // debounce: one query per pause in typing
        rows = runCatching { repo.searchLibrary(query) }.getOrDefault(emptyList()); searched = true
    }
    EpisodeSheetHost(selected, rows, player, downloaded, onDismiss = { selected = null }, onOpenPodcast = onOpenPodcast)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding)) {
        item { ScreenHeader("Buscar en tu biblioteca", onBack = onBack); Spacer(Modifier.height(8.dp)) }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                placeholder = { Text("Título o notas del episodio") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Borrar búsqueda") } })
            Spacer(Modifier.height(8.dp))
            if (searched) Text(if (rows.isEmpty()) "Sin resultados" else "${rows.size} episodios", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
        }
        if (searched && rows.isEmpty()) item { EmptyState(Icons.Outlined.Search, "Nada por aquí", "Prueba otras palabras o busca podcasts nuevos.", "Ir a Descubrir", onDiscover) }
        if (!searched) item { TextButton(onClick = onDiscover) { Icon(Icons.Outlined.Explore, null); Spacer(Modifier.width(6.dp)); Text("Buscar podcasts nuevos en Descubrir") } }
        episodeRows(rows, player, downloaded, onSelect = { selected = it })
    }
}
