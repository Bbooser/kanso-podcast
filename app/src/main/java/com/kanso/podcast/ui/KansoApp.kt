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
import androidx.activity.compose.BackHandler

private enum class Section(val label: String, val icon: ImageVector) {
    Library("Biblioteca", Icons.Outlined.GridView), Listen("Escuchar", Icons.Outlined.Headphones), Downloads("Descargas", Icons.Outlined.Download), Profile("Perfil", Icons.Outlined.Person)
}

/** App shell: bottom navigation, mini player and the overlay screens (search, podcast, now playing). */
@Composable fun KansoApp(dark: Boolean, palette: KansoPalette, onDarkChange: (Boolean) -> Unit, onPaletteChange: (KansoPalette) -> Unit) {
    var section by rememberSaveable { mutableStateOf(Section.Library) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selectedPodcast by remember { mutableStateOf<PodcastSearchResult?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val player = rememberPlayerConnection()
    var lastOpen by remember { mutableIntStateOf(0) }
    LaunchedEffect(player.openRequest) { if (player.openRequest > lastOpen) { lastOpen = player.openRequest; showPlayer = true } }
    BackHandler(enabled = showPlayer || selectedPodcast != null || searching || section != Section.Library) {
        when { showPlayer -> showPlayer = false; selectedPodcast != null -> selectedPodcast = null; searching -> searching = false; else -> section = Section.Library }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showPlayer) Column {
                if (player.title.isNotBlank()) MiniPlayer(player, onExpand = { showPlayer = true })
                if (!searching && selectedPodcast == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Section.entries.forEach { item -> NavigationBarItem(selected = section == item, onClick = { section = item }, icon = { Icon(item.icon, null) }, label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondary.copy(.18f))) }
                }
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        when {
            showPlayer -> NowPlayingScreen(player, onBack = { showPlayer = false }, m)
            selectedPodcast != null -> PodcastDetailScreen(selectedPodcast!!, player, onBack = { selectedPodcast = null }, m)
            searching -> SearchScreen(onOpen = { selectedPodcast = it }, onBack = { searching = false }, m)
            else -> when (section) {
                Section.Library -> LibraryScreen(onOpen = { selectedPodcast = it }, onSearch = { searching = true }, m)
                Section.Listen -> ListenScreen(player, onOpenPlayer = { showPlayer = true }, onOpenPodcast = { selectedPodcast = it }, onSearch = { searching = true }, m)
                Section.Downloads -> DownloadsScreen(m)
                Section.Profile -> SettingsScreen(dark, palette, onDarkChange, onPaletteChange, m)
            }
        }
    }
}
