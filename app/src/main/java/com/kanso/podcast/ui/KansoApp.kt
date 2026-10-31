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
import androidx.activity.compose.BackHandler

private enum class Section(val label: String, val icon: ImageVector) {
    Podcasts("Podcasts", Icons.Outlined.GridView), Playlists("Playlists", Icons.AutoMirrored.Outlined.FormatListBulleted), Discover("Descubrir", Icons.Outlined.Search),
    UpNext("A continuación", Icons.AutoMirrored.Outlined.QueueMusic), Profile("Perfil", Icons.Outlined.Person)
}

/** Screens stacked on top of a tab; Back pops them in order. */
private sealed interface Overlay {
    data class Podcast(val podcast: PodcastSearchResult) : Overlay
    data class Folder(val name: String) : Overlay
    data class Playlist(val id: Long) : Overlay
    data class Page(val page: ProfilePage) : Overlay
    data object Search : Overlay
}

/** App shell: five tabs, the floating mini player and stacked detail screens. */
@Composable fun KansoApp(dark: Boolean, palette: KansoPalette, onDarkChange: (Boolean) -> Unit, onPaletteChange: (KansoPalette) -> Unit) {
    var section by rememberSaveable { mutableStateOf(Section.Podcasts) }
    val stack = remember { mutableStateListOf<Overlay>() }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val player = rememberPlayerConnection()
    val importer: ImportViewModel = viewModel()
    val incoming by IncomingImports.pending.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) { incoming?.let { importer.import(it); IncomingImports.pending.value = null } }
    ImportDialog(importer)
    var lastOpen by remember { mutableIntStateOf(0) }
    LaunchedEffect(player.openRequest) { if (player.openRequest > lastOpen) { lastOpen = player.openRequest; showPlayer = true } }
    fun push(o: Overlay) { stack.add(o) }
    fun go(s: Section) { stack.clear(); showPlayer = false; section = s }
    val openPodcast: (PodcastSearchResult) -> Unit = { push(Overlay.Podcast(it)) }
    BackHandler(enabled = showPlayer || stack.isNotEmpty() || section != Section.Podcasts) {
        when { showPlayer -> showPlayer = false; stack.isNotEmpty() -> stack.removeAt(stack.lastIndex); else -> section = Section.Podcasts }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showPlayer) Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                if (player.title.isNotBlank()) MiniPlayer(player, onExpand = { showPlayer = true }, onQueue = { go(Section.UpNext) })
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Section.entries.forEach { item ->
                        NavigationBarItem(selected = section == item && stack.isEmpty(), onClick = { go(item) }, icon = { Icon(item.icon, null) },
                            label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.secondary, selectedTextColor = MaterialTheme.colorScheme.secondary, indicatorColor = MaterialTheme.colorScheme.secondary.copy(.14f)))
                    }
                }
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
        val top = stack.lastOrNull()
        if (showPlayer) NowPlayingScreen(player, onBack = { showPlayer = false }, m)
        else when (top) {
            is Overlay.Podcast -> PodcastDetailScreen(top.podcast, player, onBack = pop, m)
            is Overlay.Folder -> PodcastsScreen(player, openPodcast, onOpenFolder = { push(Overlay.Folder(it)) }, onDiscover = { go(Section.Discover) }, m, folder = top.name, onBack = pop, onSearch = { push(Overlay.Search) })
            Overlay.Search -> LibrarySearchScreen(player, onBack = pop, onOpenPodcast = openPodcast, onDiscover = { go(Section.Discover) }, m)
            is Overlay.Playlist -> PlaylistDetailScreen(top.id, player, onBack = pop, onOpenPodcast = openPodcast, m)
            is Overlay.Page -> when (top.page) {
                ProfilePage.Stats -> StatsScreen(pop, m)
                ProfilePage.Downloads -> DownloadsScreen(m, onBack = pop)
                ProfilePage.Bookmarks -> BookmarksScreen(player, pop, m)
                ProfilePage.Settings -> SettingsScreen(dark, palette, onDarkChange, onPaletteChange, m, onBack = pop)
                else -> EpisodeCollectionScreen(top.page, player, pop, openPodcast, m)
            }
            null -> when (section) {
                Section.Podcasts -> PodcastsScreen(player, openPodcast, onOpenFolder = { push(Overlay.Folder(it)) }, onDiscover = { go(Section.Discover) }, m, onSearch = { push(Overlay.Search) })
                Section.Playlists -> PlaylistsScreen(onOpen = { push(Overlay.Playlist(it)) }, m)
                Section.Discover -> DiscoverScreen(openPodcast, m)
                Section.UpNext -> UpNextScreen(player, onOpenPlayer = { showPlayer = true }, onDiscover = { go(Section.Discover) }, m)
                Section.Profile -> ProfileScreen(onOpen = { push(Overlay.Page(it)) }, m)
            }
        }
    }
}
