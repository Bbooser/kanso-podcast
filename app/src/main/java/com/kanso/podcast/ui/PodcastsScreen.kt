@file:androidx.media3.common.util.UnstableApi
package com.kanso.podcast.ui

import com.kanso.podcast.data.planCategoryFolders
import com.kanso.podcast.data.FolderPlan
import com.kanso.podcast.data.suggestFolderName
import com.kanso.podcast.data.libraryTiles
import com.kanso.podcast.data.LibraryTile
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.kanso.podcast.data.PodcastSort
import com.kanso.podcast.data.sortPodcasts
import com.kanso.podcast.data.db.EpisodeEntity
import com.kanso.podcast.data.db.EpisodeRow
import com.kanso.podcast.data.db.PodcastEntity
import com.kanso.podcast.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal class PodcastsViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    private val prefs = app.getSharedPreferences("appearance", 0)
    // Older builds stored a boolean ("recientes" vs título); keep that choice on upgrade.
    var sort by mutableStateOf(PodcastSort.parse(prefs.getString("podcasts_sort", null)) ?: if (prefs.getBoolean("podcasts_sort_recent", false)) PodcastSort.RECENT else PodcastSort.NAME); private set
    val podcasts = repo.podcastEntities.stateIn(this, emptyList())
    val stats = repo.podcastLibraryStats.map { list -> list.associateBy { it.feedUrl } }.stateIn(this, emptyMap())
    fun toggleFavorite(p: PodcastEntity) = viewModelScope.launch { repo.setPodcastFavorite(p.feedUrl, !p.isFavorite) }
    val folders = repo.folders.stateIn(this, emptyList())
    val loaded = repo.podcastEntities.map { true }.stateIn(this, false)
    fun unsubscribe(p: PodcastEntity) = viewModelScope.launch { repo.unsubscribe(p.feedUrl) }
    fun move(p: PodcastEntity, folder: String?) = viewModelScope.launch { repo.setFolder(p.feedUrl, folder) }
    fun createFolder(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createFolder(name) }
    fun group(name: String, feeds: List<String>) = viewModelScope.launch { repo.groupIntoFolder(name, feeds) }
    fun renameFolder(from: String, to: String) = viewModelScope.launch { if (to.isNotBlank()) repo.renameFolder(from, to) }
    fun deleteFolder(name: String) = viewModelScope.launch { repo.deleteFolder(name) }
    /** Applies the plan and returns the previous layout for "Deshacer". */
    suspend fun organize(plan: FolderPlan): Pair<List<String>, Map<String, String?>> { val before = repo.folderLayout(); repo.applyFolderLayout(plan.folders, plan.assignments); return before }
    fun restore(layout: Pair<List<String>, Map<String, String?>>) = viewModelScope.launch { repo.applyFolderLayout(layout.first, layout.second) }
    /** 0 = automático (lo que había antes: tantas columnas como quepan de ~104 dp); 2..5 = fijo. */
    var columns by mutableIntStateOf(prefs.getInt("podcasts_columns", 0).let { if (it in 2..5) it else 0 }); private set
    fun chooseColumns(value: Int) { columns = value; prefs.edit().putInt("podcasts_columns", value).apply() }
    fun chooseSort(value: PodcastSort) { sort = value; prefs.edit().putString("podcasts_sort", value.name).apply() }
    fun refreshAll() { androidx.work.WorkManager.getInstance(getApplication()).enqueue(androidx.work.OneTimeWorkRequestBuilder<com.kanso.podcast.data.AutomationWorker>().build()) }
}

/** Folder name dialog, used for new folders, renames and right after a drag-made folder. */
@Composable private fun FolderNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

/** Preview for "Organizar por categoría": which folders it makes and with which podcasts. */
@Composable private fun OrganizeDialog(podcasts: List<PodcastEntity>, folders: List<String>, onDismiss: () -> Unit, onApply: (FolderPlan) -> Unit) {
    var keep by remember { mutableStateOf(true) }
    val plan = remember(podcasts, folders, keep) { planCategoryFolders(podcasts, folders, keep) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    val root = plan.assignments.count { it.value == null }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Organizar por categoría") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (folders.isNotEmpty()) item {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { keep = true }, verticalAlignment = Alignment.CenterVertically) { RadioButton(keep, { keep = true }); Text("Conservar mis carpetas") }
                        Row(Modifier.fillMaxWidth().clickable { keep = false }, verticalAlignment = Alignment.CenterVertically) { RadioButton(!keep, { keep = false }); Text("Reemplazar mis carpetas") }
                    }
                }
                item { Text(if (plan.groups.isEmpty()) "No hay categorías con dos o más podcasts para agrupar." else "Se crean ${plan.groups.size} carpetas:", fontWeight = FontWeight.Medium) }
                items(plan.groups.entries.toList()) { (name, list) ->
                    Column { Text("$name · ${list.size}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium); Text(list.joinToString(", ") { it.title }, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                if (root > 0) item { Text("$root podcasts sin categoría (o solos en la suya) se quedan fuera de carpetas.", style = MaterialTheme.typography.bodySmall, color = muted) }
                item { Text("Después puedes renombrar, mover o eliminar estas carpetas.", style = MaterialTheme.typography.bodySmall, color = muted) }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(plan) }, enabled = plan.groups.isNotEmpty() || !keep) { Text("Organizar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

/** "Mover a carpeta…": the non-drag path (TalkBack friendly). */
@Composable private fun MoveToFolderDialog(podcast: PodcastEntity, folders: List<String>, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) { FolderNameDialog("Nueva carpeta", "", "Crear y mover", onDismiss) { onMove(it) }; return }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Mover a carpeta") },
        text = {
            LazyColumn {
                items(folders.filter { it != podcast.folder }) { f ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onMove(f) }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(12.dp)); Text(f)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { creating = true }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CreateNewFolder, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(12.dp)); Text("Nueva carpeta…")
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

/**
 * "Podcasts": cover grid of followed shows and folders, ordered by the chosen sort with favorites first.
 * Long-press and drag a podcast onto another to make a folder, or onto a folder to add it. [folder] != null shows one folder.
 */
@Composable internal fun PodcastsScreen(player: PlayerConnection, onOpen: (PodcastSearchResult) -> Unit, onOpenFolder: (String) -> Unit, onDiscover: () -> Unit, modifier: Modifier, folder: String? = null, onBack: (() -> Unit)? = null, vm: PodcastsViewModel = viewModel()) {
    val all by vm.podcasts.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<PodcastEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    if (organizing) OrganizeDialog(all, folders, { organizing = false }) { plan ->
        organizing = false
        scope.launch {
            val before = vm.organize(plan)
            if (snackbar.showSnackbar("Biblioteca organizada en ${plan.groups.size} carpetas", actionLabel = "Deshacer", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) vm.restore(before)
        }
    }
    if (create) FolderNameDialog("Nueva carpeta", "", "Crear", { create = false }) { vm.createFolder(it); create = false }
    renameTarget?.let { from -> FolderNameDialog(if (folder == null) "Nombre de la carpeta" else "Renombrar carpeta", from, "Guardar", { renameTarget = null }) { to ->
        vm.renameFolder(from, to); renameTarget = null; if (folder != null && to != from) onBack?.invoke()
    } }
    if (confirmDelete && folder != null) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Eliminar carpeta") },
        text = { Text("Los podcasts de “$folder” regresan a tu biblioteca. No dejas de seguir ninguno.") },
        confirmButton = { TextButton(onClick = { vm.deleteFolder(folder); confirmDelete = false; onBack?.invoke() }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
    moveTarget?.let { p -> MoveToFolderDialog(p, folders, { moveTarget = null }) { f -> vm.group(f, listOf(p.feedUrl)); moveTarget = null } }

    val tiles: List<LibraryTile> = remember(all, folders, stats, vm.sort, folder) {
        if (folder != null) sortPodcasts(all.filter { it.folder == folder }, stats, vm.sort).map { LibraryTile.Podcast(it) } else libraryTiles(all, folders, stats, vm.sort)
    }
    // Drag & drop state: tile bounds in root coordinates, the dragged tile and the pointer position.
    val bounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var dragStart by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val dropKey = dragKey?.let { k -> val pos = dragStart + dragDelta; bounds.entries.firstOrNull { it.key != k && it.value.contains(pos) }?.key }
    fun drop(source: PodcastEntity) {
        val target = tiles.firstOrNull { it.key == dropKey } ?: return
        when (target) {
            is LibraryTile.Folder -> vm.group(target.name, listOf(source.feedUrl))
            is LibraryTile.Podcast -> { val name = suggestFolderName(source, target.podcast, folders); vm.group(name, listOf(target.podcast.feedUrl, source.feedUrl)); renameTarget = name }
        }
    }

    Box(modifier.fillMaxSize()) {
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).zIndex(2f))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else Kanso.screenPadding, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text(folder ?: "Podcasts", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (folder == null) IconButton(onClick = { create = true }) { Icon(Icons.Outlined.CreateNewFolder, "Nueva carpeta", tint = MaterialTheme.colorScheme.secondary) }
            CastButton(player)
            IconButton(onClick = onDiscover) { Icon(Icons.Outlined.Search, "Buscar", tint = MaterialTheme.colorScheme.secondary) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                DropdownMenu(menu, { menu = false }) {
                    Text("Ordenar por", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                    PodcastSort.entries.forEach { s ->
                        DropdownMenuItem({ Text(s.label) }, onClick = { menu = false; vm.chooseSort(s) },
                            leadingIcon = { if (vm.sort == s) Icon(Icons.Default.Check, "Seleccionado", tint = MaterialTheme.colorScheme.secondary) else Spacer(Modifier.size(24.dp)) })
                    }
                    Text("Los favoritos siempre van primero", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                    HorizontalDivider()
                    if (folder != null) {
                        DropdownMenuItem({ Text("Renombrar carpeta") }, onClick = { menu = false; renameTarget = folder }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) })
                        DropdownMenuItem({ Text("Eliminar carpeta") }, onClick = { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Outlined.FolderOff, null) })
                    }
                    Text("Podcasts por fila", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 12.dp).widthIn(min = 260.dp)) {
                        val options = listOf(0, 2, 3, 4, 5)
                        options.forEachIndexed { i, n ->
                            SegmentedButton(selected = vm.columns == n, onClick = { vm.chooseColumns(n) }, shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                icon = {}, modifier = Modifier.semantics { contentDescription = if (n == 0) "Columnas automáticas" else "$n podcasts por fila" }) {
                                Text(if (n == 0) "Auto" else "$n", maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    if (folder == null) DropdownMenuItem({ Text("Organizar por categoría") }, onClick = { menu = false; organizing = true }, leadingIcon = { Icon(Icons.Outlined.Category, null) })
                    DropdownMenuItem({ Text("Actualizar todos los feeds") }, onClick = { menu = false; vm.refreshAll() }, leadingIcon = { Icon(Icons.Outlined.Sync, null) })
                }
            }
        }
        if (loaded && all.isEmpty()) { EmptyState(Icons.Outlined.Podcasts, "Tu biblioteca está tranquila", "Encuentra podcasts para seguir en Descubrir.", "Ir a Descubrir", onDiscover); return@Column }
        if (folder != null && loaded && tiles.isEmpty()) { EmptyState(Icons.Outlined.Folder, "Carpeta vacía", "Mantén presionado un podcast y suéltalo sobre esta carpeta, o usa “Mover a carpeta…”."); return@Column }
        val dense = vm.columns >= 4
        val gap = if (vm.columns == 5) 6.dp else if (dense) 8.dp else 10.dp
        LazyVerticalGrid(if (vm.columns in 2..5) GridCells.Fixed(vm.columns) else GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding - 4.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            items(tiles, key = { it.key }) { tile ->
                val tracked = Modifier.onGloballyPositioned { bounds[tile.key] = it.boundsInRoot() }
                    .then(if (dropKey == tile.key) Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(14.dp)) else Modifier)
                when (tile) {
                    is LibraryTile.Folder -> FolderTile(tile, tracked, vm.columns == 5) { onOpenFolder(tile.name) }
                    is LibraryTile.Podcast -> {
                        val p = tile.podcast
                        val dragging = dragKey == tile.key
                        PodcastTile(p, stats[p.feedUrl]?.newEpisodes ?: 0, vm.columns == 5, tracked.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                                if (dragging) { translationX = dragDelta.x; translationY = dragDelta.y; scaleX = 1.08f; scaleY = 1.08f; alpha = .9f }
                            },
                            draggable = folder == null,
                            onDragStart = { local -> dragKey = tile.key; dragDelta = androidx.compose.ui.geometry.Offset.Zero; dragStart = (bounds[tile.key]?.topLeft ?: androidx.compose.ui.geometry.Offset.Zero) + local },
                            onDrag = { dragDelta += it },
                            onDragEnd = { moved -> if (moved) drop(p); dragKey = null; dragDelta = androidx.compose.ui.geometry.Offset.Zero; !moved },
                            onOpen = { onOpen(p.toModel()) }, onRemove = { vm.unsubscribe(p) }, onMoveToFolder = { moveTarget = p },
                            onLeaveFolder = if (p.folder != null) ({ vm.move(p, null) }) else null, onFavorite = { vm.toggleFavorite(p) })
                    }
                }
            }
        }
    }
}
}

@Composable private fun NewBadge(count: Int, modifier: Modifier, tiny: Boolean = false) {
    // At 5 per row there is no room for a number: just a dot (the tile's description still says how many).
    if (count > 0 && tiny) Box(modifier.padding(2.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
    else if (count > 0) Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {
        Text(if (count > 99) "99+" else "$count", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun FolderTile(tile: LibraryTile.Folder, modifier: Modifier, tiny: Boolean, onClick: () -> Unit) {
    val description = "Carpeta ${tile.name}, ${tile.podcasts.size} podcasts" + if (tile.newEpisodes > 0) ", ${tile.newEpisodes} episodios nuevos" else ""
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClickLabel = "Abrir carpeta", onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description }.padding(if (tiny) 4.dp else 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(if (tiny) 2.dp else 4.dp)) {
            listOf(0, 2).forEach { start -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (start..start + 1).forEach { i ->
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(.06f))) { tile.podcasts.getOrNull(i)?.artworkUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize()) } }
            } } }
        }
        Surface(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.primary.copy(.85f), shape = RoundedCornerShape(8.dp)) {
            Text(if (tiny) tile.name else "${tile.name} · ${tile.podcasts.size}", Modifier.padding(horizontal = if (tiny) 3.dp else 6.dp, vertical = if (tiny) 1.dp else 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        NewBadge(tile.newEpisodes, Modifier.align(Alignment.TopEnd), tiny)
    }
}

/** Tap opens; long-press without moving opens the menu; long-press and drag moves it onto another tile (root only). */
@Composable private fun PodcastTile(podcast: PodcastEntity, newEpisodes: Int, tiny: Boolean, modifier: Modifier, draggable: Boolean, onDragStart: (androidx.compose.ui.geometry.Offset) -> Unit, onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
                                    onDragEnd: (moved: Boolean) -> Boolean, onOpen: () -> Unit, onRemove: () -> Unit, onMoveToFolder: () -> Unit, onLeaveFolder: (() -> Unit)?, onFavorite: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(modifier) {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(podcast.feedUrl, draggable) {
                var travelled = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); travelled = 0f; if (draggable) onDragStart(it) },
                    onDrag = { change, amount -> change.consume(); travelled += amount.getDistance(); if (draggable) onDrag(amount) },
                    onDragEnd = { val moved = travelled > 24.dp.toPx(); if (if (draggable) onDragEnd(moved) else true) { if (!moved) menu = true } },
                    onDragCancel = { if (draggable) onDragEnd(false) })
            }
            .clickable(onClick = onOpen)
            .semantics { onLongClick("Opciones") { menu = true; true } },
            contentAlignment = Alignment.Center) {
            if (podcast.artworkUrl != null) AsyncImage(podcast.artworkUrl, podcast.title, Modifier.fillMaxSize())
            else Text(podcast.title, Modifier.padding(if (tiny) 3.dp else 8.dp), style = if (tiny) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = if (tiny) 3 else 4, overflow = TextOverflow.Ellipsis)
        }
        if (podcast.isFavorite) Surface(Modifier.align(Alignment.TopStart).padding(if (tiny) 3.dp else 6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.background.copy(.85f)) {
            Icon(Icons.Default.Star, "Favorito", Modifier.padding(if (tiny) 1.dp else 3.dp).size(if (tiny) 10.dp else 14.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        NewBadge(newEpisodes, Modifier.align(Alignment.TopEnd).padding(if (tiny) 2.dp else 4.dp), tiny)
        DropdownMenu(menu, { menu = false }) {
            Text(podcast.title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 240.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2)
            DropdownMenuItem({ Text(if (podcast.isFavorite) "Quitar de favoritos" else "Marcar como favorito") }, onClick = { menu = false; onFavorite() },
                leadingIcon = { Icon(if (podcast.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, null) })
            DropdownMenuItem({ Text("Mover a carpeta…") }, onClick = { menu = false; onMoveToFolder() }, leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) })
            if (onLeaveFolder != null) DropdownMenuItem({ Text("Quitar de la carpeta") }, onClick = { menu = false; onLeaveFolder() }, leadingIcon = { Icon(Icons.Outlined.FolderOff, null) })
            DropdownMenuItem({ Text("Dejar de seguir") }, onClick = { menu = false; onRemove() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
        }
    }
}
