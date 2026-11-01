@file:androidx.media3.common.util.UnstableApi
package com.kanso.podcast.ui

import com.kanso.podcast.data.planCategoryFolders
import com.kanso.podcast.data.LibraryDrag
import com.kanso.podcast.data.TileBounds
import com.kanso.podcast.data.DropPlan
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import com.kanso.podcast.data.FolderPlan
import com.kanso.podcast.data.suggestFolderName
import com.kanso.podcast.data.libraryTiles
import com.kanso.podcast.data.LibraryTile
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
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
    fun setFolders(feeds: List<String>, folder: String?) = viewModelScope.launch { repo.setFolders(feeds, folder) }
    fun setFavorites(feeds: List<String>, value: Boolean) = viewModelScope.launch { repo.setFavorites(feeds, value) }
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
@Composable private fun MoveToFolderDialog(current: String?, count: Int, folders: List<String>, onDismiss: () -> Unit, onMove: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) { FolderNameDialog("Nueva carpeta", "", "Crear y mover", onDismiss) { onMove(it) }; return }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (count > 1) "Mover $count podcasts a carpeta" else "Mover a carpeta") },
        text = {
            LazyColumn {
                items(folders.filter { it != current }) { f ->
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
@Composable internal fun PodcastsScreen(player: PlayerConnection, onOpen: (PodcastSearchResult) -> Unit, onOpenFolder: (String) -> Unit, onDiscover: () -> Unit, modifier: Modifier, folder: String? = null, onBack: (() -> Unit)? = null, onSearch: () -> Unit = onDiscover, vm: PodcastsViewModel = viewModel()) {
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
    moveTarget?.let { p -> MoveToFolderDialog(p.folder, 1, folders, { moveTarget = null }) { f -> vm.group(f, listOf(p.feedUrl)); moveTarget = null } }

    val tiles: List<LibraryTile> = remember(all, folders, stats, vm.sort, folder) {
        if (folder != null) sortPodcasts(all.filter { it.folder == folder }, stats, vm.sort).map { LibraryTile.Podcast(it) } else libraryTiles(all, folders, stats, vm.sort)
    }
    // Multi-select: feed URLs of the selected podcasts (null = not selecting).
    var selection by remember(folder) { mutableStateOf<Set<String>?>(null) }
    androidx.activity.compose.BackHandler(enabled = selection != null) { selection = null }
    val visiblePodcasts = tiles.filterIsInstance<LibraryTile.Podcast>().map { it.podcast }
    val selected = visiblePodcasts.filter { selection?.contains(it.feedUrl) == true }
    var bulkMove by remember { mutableStateOf<List<String>?>(null) }
    bulkMove?.let { feeds -> MoveToFolderDialog(all.filter { it.feedUrl in feeds }.map { it.folder }.distinct().singleOrNull(), feeds.size, folders, { bulkMove = null }) { f ->
        vm.group(f, feeds); bulkMove = null; selection = null; scope.launch { snackbar.showSnackbar("${feeds.size} podcasts en “$f”") }
    } }
    // Drag & drop: tile bounds (root coordinates, kept fresh by onGloballyPositioned) and the drag in progress.
    val bounds = remember { mutableStateMapOf<String, TileBounds>() }
    var drag by remember { mutableStateOf<TileDrag?>(null) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    val dropKey = drag?.let { d -> LibraryDrag.hitTest(d.pointer.x, d.pointer.y, bounds, d.feeds.toSet()) }
    fun applyDrop(d: TileDrag, target: String?) {
        val plan = LibraryDrag.resolve(tiles.firstOrNull { it.key == target }, d.feeds.mapNotNull { f -> all.firstOrNull { it.feedUrl == f } }, folders)
        when (plan) {
            is DropPlan.IntoFolder -> { vm.group(plan.name, plan.feeds); scope.launch { snackbar.showSnackbar(if (plan.feeds.size > 1) "${plan.feeds.size} podcasts en “${plan.name}”" else "Movido a “${plan.name}”") } }
            is DropPlan.NewFolder -> { vm.group(plan.name, plan.feeds); renameTarget = plan.name }
            DropPlan.None -> return
        }
        selection = null
    }

    Box(modifier.fillMaxSize()) {
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).zIndex(2f))
    Column(Modifier.fillMaxSize()) {
        if (selection != null) SelectionBar(selected, visiblePodcasts.size, onClose = { selection = null },
            onSelectAll = { selection = if (selected.size == visiblePodcasts.size) emptySet() else visiblePodcasts.map { it.feedUrl }.toSet() },
            onFavorite = { vm.setFavorites(selected.map { it.feedUrl }, LibraryDrag.favoriteValue(selected)) },
            onMove = { bulkMove = selected.map { it.feedUrl } },
            onLeaveFolder = { vm.setFolders(selected.map { it.feedUrl }, null); selection = null })
        else Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else Kanso.screenPadding, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text(folder ?: "Podcasts", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (folder == null) IconButton(onClick = { create = true }) { Icon(Icons.Outlined.CreateNewFolder, "Nueva carpeta", tint = MaterialTheme.colorScheme.secondary) }
            CastButton(player)
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Buscar episodios en tu biblioteca", tint = MaterialTheme.colorScheme.secondary) }
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
                    DropdownMenuItem({ Text("Seleccionar") }, onClick = { menu = false; selection = emptySet() }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) })
                    if (folder == null) DropdownMenuItem({ Text("Organizar por categoría") }, onClick = { menu = false; organizing = true }, leadingIcon = { Icon(Icons.Outlined.Category, null) })
                    DropdownMenuItem({ Text("Actualizar todos los feeds") }, onClick = { menu = false; vm.refreshAll() }, leadingIcon = { Icon(Icons.Outlined.Sync, null) })
                }
            }
        }
        if (loaded && all.isEmpty()) { EmptyState(Icons.Outlined.Podcasts, "Tu biblioteca está tranquila", "Encuentra podcasts para seguir en Descubrir.", "Ir a Descubrir", onDiscover); return@Column }
        if (folder != null && loaded && tiles.isEmpty()) { EmptyState(Icons.Outlined.Folder, "Carpeta vacía", "Mantén presionado un podcast y suéltalo sobre esta carpeta, o usa “Mover a carpeta…”."); return@Column }
        val dense = vm.columns >= 4
        val gap = if (vm.columns == 5) 6.dp else if (dense) 8.dp else 10.dp
        val gridState = rememberLazyGridState()
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        var area by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        // The gesture lives on the container (not the tile) so scrolling or recycling the dragged item never cancels it.
        val currentTiles by rememberUpdatedState(tiles)
        val currentSelection by rememberUpdatedState(selection)
        val canDrag = folder == null
        // Auto-scroll while the finger is near the top or bottom edge.
        LaunchedEffect(drag != null) {
            while (drag != null) {
                val d = drag ?: break
                val speed = with(density) { LibraryDrag.autoScroll(d.pointer.y - area.top, area.height, 72.dp.toPx(), 18.dp.toPx()) }
                if (speed != 0f) gridState.scrollBy(speed)
                androidx.compose.runtime.withFrameNanos { }
            }
        }
        Box(Modifier.fillMaxSize().onGloballyPositioned { area = it.boundsInRoot() }.pointerInput(canDrag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val origin = area.topLeft
                val start = origin + down.position
                val key = LibraryDrag.hitTest(start.x, start.y, bounds, emptySet()) ?: return@awaitEachGesture
                if (currentTiles.firstOrNull { it.key == key } !is LibraryTile.Podcast) return@awaitEachGesture
                // Own long-press wait (Initial pass): the tile's clickable consumes the down, which some helpers treat as cancelled.
                var cancelled = false
                val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) { cancelled = true; break }
                    }
                } == null
                if (cancelled || !timedOut) return@awaitEachGesture
                val long = down
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                val tile = bounds[key] ?: return@awaitEachGesture
                val order = currentTiles.map { it.key }
                val d0 = TileDrag(key, LibraryDrag.draggedFeeds(key, currentSelection, order), start, start - androidx.compose.ui.geometry.Offset(tile.left, tile.top), tile.right - tile.left, tile.bottom - tile.top)
                if (canDrag) drag = d0
                var travelled = 0f
                var pointer = start
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == long.id } ?: break
                    if (!change.pressed) { change.consume(); break }
                    val delta = change.position - change.previousPosition
                    travelled += delta.getDistance(); pointer += delta; change.consume()
                    if (canDrag) drag = drag?.copy(pointer = pointer)
                }
                val finished = drag; drag = null
                if (finished == null || travelled < viewConfiguration.touchSlop) menuKey = key // released in place: the menu
                else applyDrop(finished, LibraryDrag.hitTest(pointer.x, pointer.y, bounds, finished.feeds.toSet()))
            }
        }) {
        LazyVerticalGrid(if (vm.columns in 2..5) GridCells.Fixed(vm.columns) else GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), state = gridState, contentPadding = PaddingValues(Kanso.screenPadding - 4.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            items(tiles, key = { it.key }) { tile ->
                DisposableEffect(tile.key) { onDispose { bounds.remove(tile.key) } }
                val lifted = drag?.feeds?.contains(tile.key) == true
                val tracked = Modifier.onGloballyPositioned { val r = it.boundsInRoot(); bounds[tile.key] = TileBounds(r.left, r.top, r.right, r.bottom) }
                    .then(if (dropKey == tile.key) Modifier.border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(14.dp)).graphicsLayer { scaleX = 1.05f; scaleY = 1.05f } else Modifier)
                    .graphicsLayer { alpha = if (lifted) .3f else 1f }
                when (tile) {
                    is LibraryTile.Folder -> FolderTile(tile, tracked, vm.columns == 5) { if (selection == null) onOpenFolder(tile.name) }
                    is LibraryTile.Podcast -> {
                        val p = tile.podcast
                        PodcastTile(p, stats[p.feedUrl]?.newEpisodes ?: 0, vm.columns == 5, tracked, selected = selection?.contains(p.feedUrl),
                            menuOpen = menuKey == p.feedUrl, onMenuChange = { menuKey = if (it) p.feedUrl else null },
                            onTap = { val sel = selection; if (sel != null) selection = if (p.feedUrl in sel) sel - p.feedUrl else sel + p.feedUrl else onOpen(p.toModel()) },
                            onSelect = { selection = (selection ?: emptySet()) + p.feedUrl },
                            onRemove = { vm.unsubscribe(p) }, onMoveToFolder = { moveTarget = p },
                            onLeaveFolder = if (p.folder != null) ({ vm.move(p, null) }) else null, onFavorite = { vm.toggleFavorite(p) })
                    }
                }
            }
        }
        // The lifted tile follows the finger above everything else.
        drag?.let { d ->
            val first = all.firstOrNull { it.feedUrl == d.feeds.first() }
            val x = d.pointer.x - d.grab.x - area.left; val y = d.pointer.y - d.grab.y - area.top
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }.size(with(density) { d.width.toDp() }, with(density) { d.height.toDp() })
                .zIndex(10f).graphicsLayer { scaleX = 1.1f; scaleY = 1.1f; shadowElevation = 16f; shape = RoundedCornerShape(14.dp); clip = true }.background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (d.feeds.size > 1) Box(Modifier.matchParentSize().offset(6.dp, 6.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondary.copy(.35f)))
                first?.artworkUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize()) } ?: Text(first?.title.orEmpty(), Modifier.align(Alignment.Center).padding(6.dp), style = MaterialTheme.typography.labelSmall, maxLines = 3)
                if (d.feeds.size > 1) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {
                    Text("${d.feeds.size}", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        }
    }
}
}

/** A drag in progress, in root coordinates: [grab] is where the finger holds the tile. */
private data class TileDrag(val key: String, val feeds: List<String>, val pointer: androidx.compose.ui.geometry.Offset, val grab: androidx.compose.ui.geometry.Offset, val width: Float, val height: Float)

/** Top bar while selecting podcasts. */
@Composable private fun SelectionBar(selected: List<PodcastEntity>, total: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onFavorite: () -> Unit, onMove: () -> Unit, onLeaveFolder: () -> Unit) {
    val any = selected.isNotEmpty()
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Salir de la selección") }
        Text(if (any) "${selected.size} seleccionados" else "Selecciona podcasts", Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium, maxLines = 1)
        TextButton(onClick = onSelectAll) { Text(if (any && selected.size == total) "Ninguno" else "Seleccionar todo") }
        IconButton(onClick = onFavorite, enabled = any) { Icon(if (any && selected.all { it.isFavorite }) Icons.Default.Star else Icons.Outlined.StarBorder, if (any && selected.all { it.isFavorite }) "Quitar de favoritos" else "Marcar como favoritos") }
        IconButton(onClick = onMove, enabled = any) { Icon(Icons.Outlined.DriveFileMove, "Mover a carpeta…") }
        IconButton(onClick = onLeaveFolder, enabled = selected.any { it.folder != null }) { Icon(Icons.Outlined.FolderOff, "Quitar de carpeta") }
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

/**
 * Tap opens (or toggles while selecting). Long-press is handled by the grid: drag to a folder/podcast, or release in place for the menu.
 * TalkBack gets the menu through the long-click action and "Mover a carpeta…".
 */
@Composable private fun PodcastTile(podcast: PodcastEntity, newEpisodes: Int, tiny: Boolean, modifier: Modifier, selected: Boolean?, menuOpen: Boolean, onMenuChange: (Boolean) -> Unit,
                                    onTap: () -> Unit, onSelect: () -> Unit, onRemove: () -> Unit, onMoveToFolder: () -> Unit, onLeaveFolder: (() -> Unit)?, onFavorite: () -> Unit) {
    val menu = menuOpen
    Box(modifier) {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected == true) Modifier.border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClickLabel = if (selected != null) (if (selected) "Quitar de la selección" else "Seleccionar") else "Abrir", onClick = onTap)
            .semantics { onLongClick("Opciones") { onMenuChange(true); true }; if (selected != null) this.selected = selected },
            contentAlignment = Alignment.Center) {
            if (podcast.artworkUrl != null) AsyncImage(podcast.artworkUrl, podcast.title, Modifier.fillMaxSize())
            else Text(podcast.title, Modifier.padding(if (tiny) 3.dp else 8.dp), style = if (tiny) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = if (tiny) 3 else 4, overflow = TextOverflow.Ellipsis)
            if (selected == true) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.BottomEnd).padding(4.dp).size(if (tiny) 14.dp else 20.dp).background(MaterialTheme.colorScheme.background, CircleShape), tint = MaterialTheme.colorScheme.secondary)
        }
        if (podcast.isFavorite) Surface(Modifier.align(Alignment.TopStart).padding(if (tiny) 3.dp else 6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.background.copy(.85f)) {
            Icon(Icons.Default.Star, "Favorito", Modifier.padding(if (tiny) 1.dp else 3.dp).size(if (tiny) 10.dp else 14.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        NewBadge(newEpisodes, Modifier.align(Alignment.TopEnd).padding(if (tiny) 2.dp else 4.dp), tiny)
        DropdownMenu(menu, { onMenuChange(false) }) {
            Text(podcast.title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 240.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2)
            DropdownMenuItem({ Text(if (podcast.isFavorite) "Quitar de favoritos" else "Marcar como favorito") }, onClick = { onMenuChange(false); onFavorite() },
                leadingIcon = { Icon(if (podcast.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, null) })
            if (selected == null) DropdownMenuItem({ Text("Seleccionar") }, onClick = { onMenuChange(false); onSelect() }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) })
            DropdownMenuItem({ Text("Mover a carpeta…") }, onClick = { onMenuChange(false); onMoveToFolder() }, leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) })
            if (onLeaveFolder != null) DropdownMenuItem({ Text("Quitar de la carpeta") }, onClick = { onMenuChange(false); onLeaveFolder() }, leadingIcon = { Icon(Icons.Outlined.FolderOff, null) })
            DropdownMenuItem({ Text("Dejar de seguir") }, onClick = { onMenuChange(false); onRemove() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
        }
    }
}
