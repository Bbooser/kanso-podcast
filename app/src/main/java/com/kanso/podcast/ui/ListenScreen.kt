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

internal enum class ListenFilter(val label: String) { ForYou("Para escuchar"), Favorites("Favoritos"), Archived("Archivados") }

internal class ListenViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    val inProgress = repo.inProgress.stateIn(this, emptyList())
    val newEpisodes = repo.newEpisodes.stateIn(this, emptyList())
    val favorites = repo.favorites.stateIn(this, emptyList())
    val archived = repo.archived.stateIn(this, emptyList())
    val downloaded = repo.downloadedIds.stateIn(this, emptySet())
    val hasPodcasts = repo.podcastEntities.map { it.isNotEmpty() }.stateIn(this, true)
}

/** "Escuchar": what's queued, what you're in the middle of, and what's new from your subscriptions. */
@Composable internal fun ListenScreen(player: PlayerConnection, onOpenPlayer: () -> Unit, onOpenPodcast: (PodcastSearchResult) -> Unit, onSearch: () -> Unit, modifier: Modifier, vm: ListenViewModel = viewModel()) {
    var filter by rememberSaveable { mutableStateOf(ListenFilter.ForYou) }
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val newEpisodes by vm.newEpisodes.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val archived by vm.archived.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val hasPodcasts by vm.hasPodcasts.collectAsStateWithLifecycle()
    val actions = rememberEpisodeActions()
    val date = remember { java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE · d MMM", java.util.Locale("es", "MX"))).uppercase() }

    @Composable fun EpisodeItem(row: EpisodeRow) {
        val e = row.episode; val title = row.podcastTitle.orEmpty()
        EpisodeCard(e, player.episodeId == e.id, e.id in downloaded,
            onPlay = { player.play(e, title, row.podcastArtwork) }, onQueue = { player.enqueue(e, title, row.podcastArtwork) },
            onListened = { actions.toggleListened(e) }, onFavorite = { actions.toggleFavorite(e) },
            onDownload = { actions.download(e, title) }, onArchive = { actions.toggleArchived(e) },
            podcastTitle = title, artwork = row.podcastArtwork, showDescription = false,
            onOpenPodcast = { onOpenPodcast(PodcastSearchResult(e.podcastFeedUrl, title, "", e.podcastFeedUrl, row.podcastArtwork)) })
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(Kanso.itemGap)) {
        item {
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text("Escucha sin prisa", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ListenFilter.entries) { option -> FilterChip(filter == option, onClick = { filter = option }, label = { Text(option.label) }, leadingIcon = if (filter == option) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null) }
            }
        }
        if (!hasPodcasts) { item { EmptyState(Icons.Outlined.Headphones, "Nada por escuchar aún", "Sigue algunos podcasts y aquí aparecerán sus episodios nuevos.", "Encontrar podcasts", onSearch) }; return@LazyColumn }
        when (filter) {
            ListenFilter.ForYou -> {
                val upNext = player.upNext
                if (upNext.isNotEmpty()) {
                    item { SectionLabel("A continuación", "${upNext.size}") }
                    item {
                        Column(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpenPlayer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            upNext.take(3).forEachIndexed { i, t -> Text("${i + 1}.  $t", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (upNext.size > 3) Text("y ${upNext.size - 3} más", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                        }
                    }
                }
                if (inProgress.isNotEmpty()) { item { SectionLabel("En progreso", "${inProgress.size}") }; items(inProgress, key = { "p:" + it.episode.id }) { EpisodeItem(it) } }
                item { SectionLabel("Nuevos", if (newEpisodes.isEmpty()) null else "${newEpisodes.size}") }
                if (newEpisodes.isEmpty()) item { Text("Estás al día. Abre un podcast o actualiza tus feeds en Perfil.", color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
                items(newEpisodes, key = { "n:" + it.episode.id }) { EpisodeItem(it) }
            }
            ListenFilter.Favorites -> {
                if (favorites.isEmpty()) item { EmptyState(Icons.Outlined.FavoriteBorder, "Sin favoritos", "Marca con ♥ los episodios que quieras conservar.") }
                items(favorites, key = { "f:" + it.episode.id }) { EpisodeItem(it) }
            }
            ListenFilter.Archived -> {
                if (archived.isEmpty()) item { EmptyState(Icons.Outlined.Inventory2, "Nada archivado") }
                items(archived, key = { "a:" + it.episode.id }) { EpisodeItem(it) }
            }
        }
    }
}
