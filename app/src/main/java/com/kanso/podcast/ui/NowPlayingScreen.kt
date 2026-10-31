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
import com.kanso.podcast.data.Chapter
import com.kanso.podcast.data.EpisodeExtras
import com.kanso.podcast.data.PodcastDirectory
import com.kanso.podcast.data.TranscriptCue
import com.kanso.podcast.playback.KansoPlaybackService

@Composable internal fun MiniPlayer(player: PlayerConnection, onExpand: () -> Unit, onQueue: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(Kanso.art).clickable(onClick = onExpand), color = MaterialTheme.colorScheme.primary, shape = Kanso.art) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (player.artwork != null) Artwork(player.artwork, player.title, 46.dp, RoundedCornerShape(10.dp))
                else Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GraphicEq, null, tint = Color.White) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(player.title, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(player.podcast, color = MaterialTheme.colorScheme.onPrimary.copy(.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = player::skipBack) { Icon(Icons.Outlined.Replay30, "Retroceder 30 segundos", tint = MaterialTheme.colorScheme.onPrimary) }
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary).clickable(onClick = player::toggle), contentAlignment = Alignment.Center) {
                    Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pausar" else "Reproducir", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = player::skipForward) { Icon(Icons.Outlined.Forward30, "Adelantar 30 segundos", tint = MaterialTheme.colorScheme.onPrimary) }
                IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Outlined.QueueMusic, "A continuación", tint = MaterialTheme.colorScheme.onPrimary) }
            }
            LinearProgressIndicator(progress = { if (player.duration > 0) player.position.toFloat() / player.duration else 0f }, Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.secondary, trackColor = Color.Transparent)
        }
    }
}

internal class NowPlayingViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    private val directory = PodcastDirectory()
    private var loadedFor: String? = null
    var chapters by mutableStateOf(emptyList<Chapter>()); private set
    var transcript by mutableStateOf<List<TranscriptCue>?>(null); private set
    var hasTranscript by mutableStateOf(false); private set
    var transcriptError by mutableStateOf<String?>(null); private set
    private var transcriptSource: Pair<String, String?>? = null
    private val episodeId = MutableStateFlow("")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarks = episodeId.flatMapLatest { if (it.isBlank()) flowOf(emptyList()) else repo.bookmarksFor(it) }.stateIn(this, emptyList())

    fun load(id: String) {
        episodeId.value = id
        if (id.isBlank() || id == loadedFor) return
        loadedFor = id; chapters = emptyList(); transcript = null; hasTranscript = false; transcriptError = null; transcriptSource = null
        viewModelScope.launch {
            val e = repo.episode(id) ?: return@launch
            e.transcriptUrl?.let { transcriptSource = it to e.transcriptType; hasTranscript = true }
            e.chaptersUrl?.let { url -> runCatching { EpisodeExtras.parseChapters(directory.fetchText(url)) }.onSuccess { if (loadedFor == id) chapters = it } }
        }
    }
    fun loadTranscript() {
        val (url, type) = transcriptSource ?: return
        if (transcript != null) return
        viewModelScope.launch { runCatching { EpisodeExtras.parseTranscript(directory.fetchText(url), type) }.onSuccess { transcript = it }.onFailure { transcriptError = "No se pudo cargar la transcripción" } }
    }
    fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }
}

private enum class PlayerTab(val label: String) { Queue("Cola"), Chapters("Capítulos"), Transcript("Transcripción"), Bookmarks("Marcadores") }

@Composable internal fun NowPlayingScreen(player: PlayerConnection, onBack: () -> Unit, modifier: Modifier, vm: NowPlayingViewModel = viewModel()) {
    LaunchedEffect(player.episodeId) { vm.load(player.episodeId) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    var timerMenu by remember { mutableStateOf(false) }
    var effects by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var tab by remember { mutableStateOf(PlayerTab.Queue) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf(false) }
    LaunchedEffect(savedNote) { if (savedNote) { kotlinx.coroutines.delay(1_800); savedNote = false } }
    val timer = when { player.sleepEndOfEpisode -> "Fin del episodio"; player.sleepAt > 0 -> "${((player.sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000) + 1} min"; else -> "Temporizador" }
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    val currentChapter = vm.chapters.lastOrNull { it.startMs <= player.position }
    val tabs = PlayerTab.entries.filter { it != PlayerTab.Chapters || vm.chapters.isNotEmpty() }.filter { it != PlayerTab.Transcript || vm.hasTranscript }
    if (tab !in tabs) tab = PlayerTab.Queue

    if (effects) AlertDialog(onDismissRequest = { effects = false }, title = { Text("Efectos de audio") }, confirmButton = { TextButton(onClick = { effects = false }) { Text("Listo") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Recortar silencios", fontWeight = FontWeight.Medium); Text("Salta las pausas largas sin cambiar las voces.", style = MaterialTheme.typography.bodySmall, color = muted) }
                Switch(player.trimSilence, { player.setEffects(trim = it) })
            }
            Column {
                Text("Refuerzo de voz", fontWeight = FontWeight.Medium); Text("Sube el volumen de voces bajas.", style = MaterialTheme.typography.bodySmall, color = muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("No", "Suave", "Alto").forEachIndexed { i, label -> FilterChip(player.boost == i, onClick = { player.setEffects(boostLevel = i) }, label = { Text(label) }) } }
            }
        }
    })

    if (sheet) PlayerSheet(player, vm, tabs, tab, { tab = it; if (it == PlayerTab.Transcript) vm.loadTranscript() }, bookmarks, currentChapter, onDismiss = { sheet = false })

    // Fixed layout (no scroll): the artwork takes whatever room is left; side by side in landscape.
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = Kanso.screenPadding + 8.dp, vertical = Kanso.screenPadding)) {
        val landscape = maxWidth > maxHeight
        val compact = maxHeight < 560.dp
        val art = currentChapter?.imageUrl ?: player.artwork
        val artwork: @Composable (Modifier) -> Unit = { m ->
            Box(m, contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 340.dp).aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(if (compact) 20.dp else 32.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) {
                    if (art != null) AsyncImage(art, player.title, Modifier.fillMaxSize()) else Icon(Icons.Outlined.GraphicEq, null, Modifier.size(64.dp), tint = Color.White)
                }
            }
        }
        val header: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart)) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
                Text(if (player.casting) "EN CHROMECAST" else "REPRODUCIENDO", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Box(Modifier.align(Alignment.CenterEnd)) { CastButton(player) }
            }
        }
        val controls: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(player.title, style = (if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall).copy(fontFamily = MaterialTheme.typography.headlineMedium.fontFamily), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(player.podcast, Modifier.fillMaxWidth(), color = muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (currentChapter != null) Text(currentChapter.title, Modifier.fillMaxWidth().padding(top = 2.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(if (compact) 4.dp else Kanso.itemGap))
                val shown = dragging ?: player.position.toFloat()
                Slider(value = shown, onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { player.controller?.seekTo(it.toLong()) }; dragging = null }, valueRange = 0f..player.duration.coerceAtLeast(1).toFloat())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = muted)
                    Text("−${formatTime((player.duration - shown.toLong()).coerceAtLeast(0))}", style = MaterialTheme.typography.labelSmall, color = muted)
                }
                player.error?.let { message ->
                    Surface(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer); Spacer(Modifier.width(10.dp))
                            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = player::retry) { Text("Reintentar") }
                        }
                    }
                }
                Spacer(Modifier.height(if (compact) 2.dp else Kanso.itemGap))
                val play = if (compact) 64.dp else 80.dp
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { val prev = vm.chapters.lastOrNull { it.startMs < player.position - 3_000 }; if (prev != null) player.controller?.seekTo(prev.startMs) else player.controller?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Anterior") }
                    IconButton(onClick = player::skipBack, Modifier.size(56.dp)) { Icon(Icons.Outlined.Replay30, "Retroceder 30 segundos", Modifier.size(32.dp)) }
                    FilledIconButton(onClick = player::toggle, modifier = Modifier.size(play), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pausar" else "Reproducir", Modifier.size(play / 2), tint = Color.White)
                    }
                    IconButton(onClick = player::skipForward, Modifier.size(56.dp)) { Icon(Icons.Outlined.Forward30, "Adelantar 30 segundos", Modifier.size(32.dp)) }
                    IconButton(onClick = { val next = vm.chapters.firstOrNull { it.startMs > player.position + 1_000 }; if (next != null) player.controller?.seekTo(next.startMs) else player.controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
                }
                Spacer(Modifier.height(if (compact) 0.dp else Kanso.itemGap))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = player::cycleSpeed) { Text("${"%.1f".format(java.util.Locale.US, player.speed).removeSuffix(".0")}×", fontWeight = FontWeight.SemiBold) }
                    IconButton(onClick = { effects = true }) { Icon(Icons.Outlined.Tune, "Efectos de audio", tint = if (player.trimSilence || player.boost > 0) MaterialTheme.colorScheme.secondary else LocalContentColor.current) }
                    Box {
                        IconButton(onClick = { timerMenu = true }) { Icon(Icons.Outlined.Timer, "Temporizador", tint = if (player.sleepAt > 0 || player.sleepEndOfEpisode) MaterialTheme.colorScheme.secondary else LocalContentColor.current) }
                        DropdownMenu(timerMenu, { timerMenu = false }) {
                            listOf(5, 15, 30, 45, 60).forEach { minutes -> DropdownMenuItem({ Text("$minutes minutos") }, onClick = { timerMenu = false; player.setSleepTimer(minutes) }) }
                            DropdownMenuItem({ Text("Al terminar el episodio") }, onClick = { timerMenu = false; player.setSleepTimer(KansoPlaybackService.END_OF_EPISODE) })
                            if (player.sleepAt > 0 || player.sleepEndOfEpisode) DropdownMenuItem({ Text("Cancelar temporizador") }, onClick = { timerMenu = false; player.setSleepTimer(0) })
                        }
                    }
                    IconButton(onClick = { player.addBookmark(); savedNote = true }) { Icon(if (savedNote) Icons.Default.Bookmark else Icons.Outlined.BookmarkAdd, "Añadir marcador", tint = if (savedNote) MaterialTheme.colorScheme.secondary else LocalContentColor.current) }
                    BadgedBox(badge = { if (player.upNext.isNotEmpty()) Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text("${player.upNext.size}") } }) {
                        IconButton(onClick = { sheet = true }) { Icon(Icons.AutoMirrored.Outlined.QueueMusic, "Cola, ${player.upNext.size} episodios") }
                    }
                }
                Text(if (savedNote) "Marcador guardado" else if (player.sleepAt > 0 || player.sleepEndOfEpisode) "Temporizador: $timer" else " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (landscape) Column(Modifier.fillMaxSize()) {
            header()
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                artwork(Modifier.weight(.42f).fillMaxHeight().padding(8.dp))
                Spacer(Modifier.width(Kanso.sectionGap))
                Box(Modifier.weight(.58f)) { controls() }
            }
        } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            header()
            Spacer(Modifier.height(if (compact) 4.dp else Kanso.itemGap))
            artwork(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(if (compact) 6.dp else Kanso.sectionGap))
            controls()
        }
    }
}

/** Queue, chapters, transcript and bookmarks, in a bottom sheet so the player itself never scrolls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerSheet(player: PlayerConnection, vm: NowPlayingViewModel, tabs: List<PlayerTab>, tab: PlayerTab, onTab: (PlayerTab) -> Unit, bookmarks: List<BookmarkEntity>, currentChapter: Chapter?, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = MaterialTheme.colorScheme.background) {
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), containerColor = Color.Transparent, edgePadding = Kanso.screenPadding, divider = {}) {
            tabs.forEach { t -> Tab(tab == t, onClick = { onTab(t) }, text = { Text(t.label) }) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentPadding = PaddingValues(horizontal = Kanso.screenPadding, vertical = Kanso.itemGap)) {
            when (tab) {
                PlayerTab.Queue -> {
                    val upNext = player.upNext
                    if (upNext.isEmpty()) item { Text("No hay nada en la cola. Desliza a la derecha un episodio para añadirlo.", color = muted, textAlign = TextAlign.Center) }
                    itemsIndexed(upNext, key = { i, q -> "q$i:${q.id}" }) { index, q ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.playQueued(index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(q.artwork, q.title, 44.dp, RoundedCornerShape(10.dp)); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(q.title, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(q.podcast, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1) }
                            IconButton(onClick = { player.move(index, index - 1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Subir en la cola") }
                            IconButton(onClick = { player.move(index, index + 1) }, enabled = index < upNext.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Bajar en la cola") }
                            IconButton(onClick = { player.removeQueued(index) }) { Icon(Icons.Outlined.Close, "Quitar de la cola") }
                        }
                    }
                }
                PlayerTab.Chapters -> items(vm.chapters, key = { "c" + it.startMs }) { c ->
                    val active = c == currentChapter
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.controller?.seekTo(c.startMs) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(c.startMs), Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.secondary else muted)
                        Text(c.title, Modifier.weight(1f), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                PlayerTab.Transcript -> {
                    val cues = vm.transcript
                    when {
                        vm.transcriptError != null -> item { Text(vm.transcriptError!!, color = MaterialTheme.colorScheme.error) }
                        cues == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary) }
                        cues.isEmpty() -> item { Text("La transcripción está vacía.", color = muted) }
                        else -> items(cues.size) { i ->
                            val cue = cues[i]; val timed = cue.startMs >= 0
                            val active = timed && player.position in cue.startMs until (cue.endMs.takeIf { it > cue.startMs } ?: (cue.startMs + 5_000))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (timed) Modifier.clickable { player.controller?.seekTo(cue.startMs) } else Modifier).padding(vertical = 6.dp, horizontal = 4.dp)) {
                                if (cue.speaker != null || timed) Text(listOfNotNull(cue.speaker, if (timed) formatTime(cue.startMs) else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                Text(cue.text, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(if (timed) .6f else .85f), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                PlayerTab.Bookmarks -> {
                    if (bookmarks.isEmpty()) item { Text("Toca el marcador para guardar un momento de este episodio.", color = muted, textAlign = TextAlign.Center) }
                    items(bookmarks, key = { "b" + it.id }) { b ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.controller?.seekTo(b.positionMs) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bookmark, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(12.dp))
                            Text(b.title, Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteBookmark(b.id) }) { Icon(Icons.Outlined.Delete, "Borrar marcador") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(Kanso.sectionGap)) }
        }
    }
}

/** Chromecast button: initialises Cast only when tapped, so devices without Play Services are unaffected. */
@Composable internal fun CastButton(player: PlayerConnection) {
    val context = LocalContext.current
    IconButton(onClick = {
        runCatching {
            val cast = com.google.android.gms.cast.framework.CastContext.getSharedInstance(context)
            player.enableCast()
            val themed = android.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)
            if (cast.castState == com.google.android.gms.cast.framework.CastState.CONNECTED) androidx.mediarouter.app.MediaRouteControllerDialog(themed).show()
            else androidx.mediarouter.app.MediaRouteChooserDialog(themed).apply { cast.mergedSelector?.let { routeSelector = it } }.show()
        }.onFailure { android.widget.Toast.makeText(context, "Chromecast no está disponible en este dispositivo", android.widget.Toast.LENGTH_SHORT).show() }
    }) { Icon(if (player.casting) Icons.Default.CastConnected else Icons.Outlined.Cast, "Chromecast", tint = if (player.casting) MaterialTheme.colorScheme.secondary else LocalContentColor.current) }
}
