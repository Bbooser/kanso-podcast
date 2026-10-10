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

@Composable internal fun MiniPlayer(player: PlayerConnection, onExpand: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).clickable(onClick = onExpand)) {
        LinearProgressIndicator(progress = { if (player.duration > 0) player.position.toFloat() / player.duration else 0f }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.onPrimary.copy(.15f))
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (player.artwork != null) Artwork(player.artwork, player.title, 56.dp, RoundedCornerShape(14.dp))
            else Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GraphicEq, null, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(player.title, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(player.podcast, color = MaterialTheme.colorScheme.onPrimary.copy(.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { player.seekBy(-10_000) }) { Icon(Icons.Outlined.Replay10, "Retroceder 10 segundos", tint = MaterialTheme.colorScheme.onPrimary) }
            IconButton(onClick = player::toggle, modifier = Modifier.size(52.dp)) { Icon(if (player.playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, if (player.playing) "Pausar" else "Reproducir", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp)) }
        }
    }
}

@Composable internal fun NowPlayingScreen(player: PlayerConnection, onBack: () -> Unit, modifier: Modifier) {
    var timerMenu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val timer = when { player.sleepEndOfEpisode -> "Fin del episodio"; player.sleepAt > 0 -> "${((player.sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000) + 1} min"; else -> "Temporizador" }
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Kanso.screenPadding + 8.dp, vertical = Kanso.screenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart)) { Icon(Icons.Default.KeyboardArrowDown, "Cerrar") }
                Text("REPRODUCIENDO", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        item {
            Spacer(Modifier.height(Kanso.sectionGap))
            Box(Modifier.fillMaxWidth().widthIn(max = 340.dp).aspectRatio(1f).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) {
                if (player.artwork != null) AsyncImage(player.artwork, player.title, Modifier.fillMaxSize())
                else Icon(Icons.Outlined.GraphicEq, null, Modifier.size(90.dp), tint = Color.White)
            }
        }
        item {
            Spacer(Modifier.height(Kanso.sectionGap + 4.dp))
            Text(player.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MaterialTheme.typography.headlineMedium.fontFamily), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(player.podcast, Modifier.fillMaxWidth(), color = muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item {
            Spacer(Modifier.height(Kanso.sectionGap))
            val shown = dragging ?: player.position.toFloat()
            Slider(value = shown, onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { player.controller?.seekTo(it.toLong()) }; dragging = null }, valueRange = 0f..player.duration.coerceAtLeast(1).toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = muted)
                Text("−${formatTime((player.duration - shown.toLong()).coerceAtLeast(0))}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        player.error?.let { message -> item {
            Spacer(Modifier.height(Kanso.itemGap))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer); Spacer(Modifier.width(10.dp))
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = player::retry) { Text("Reintentar") }
                }
            }
        } }
        item {
            Spacer(Modifier.height(Kanso.itemGap))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.controller?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Anterior") }
                IconButton(onClick = { player.seekBy(-10_000) }, Modifier.size(56.dp)) { Icon(Icons.Outlined.Replay10, "Retroceder 10 segundos", Modifier.size(32.dp)) }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(80.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                    Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pausar" else "Reproducir", Modifier.size(40.dp), tint = Color.White)
                }
                IconButton(onClick = { player.seekBy(30_000) }, Modifier.size(56.dp)) { Icon(Icons.Outlined.Forward30, "Adelantar 30 segundos", Modifier.size(32.dp)) }
                IconButton(onClick = { player.controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Siguiente") }
            }
        }
        item {
            Spacer(Modifier.height(Kanso.sectionGap))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                FilledTonalButton(onClick = player::cycleSpeed) { Icon(Icons.Outlined.Speed, null); Spacer(Modifier.width(6.dp)); Text("${"%.1f".format(java.util.Locale.US, player.speed).removeSuffix(".0")}×") }
                Box {
                    FilledTonalButton(onClick = { timerMenu = true }) { Icon(Icons.Outlined.Timer, null); Spacer(Modifier.width(6.dp)); Text(timer) }
                    DropdownMenu(timerMenu, { timerMenu = false }) {
                        listOf(5, 15, 30, 45, 60).forEach { minutes -> DropdownMenuItem({ Text("$minutes minutos") }, onClick = { timerMenu = false; player.setSleepTimer(minutes) }) }
                        DropdownMenuItem({ Text("Al terminar el episodio") }, onClick = { timerMenu = false; player.setSleepTimer(com.kanso.podcast.playback.KansoPlaybackService.END_OF_EPISODE) })
                        if (player.sleepAt > 0 || player.sleepEndOfEpisode) DropdownMenuItem({ Text("Cancelar temporizador") }, onClick = { timerMenu = false; player.setSleepTimer(0) })
                    }
                }
            }
        }
        val upNext = player.upNext
        if (upNext.isNotEmpty()) {
            item { Spacer(Modifier.height(Kanso.sectionGap)); HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(.3f)); Spacer(Modifier.height(Kanso.itemGap)); SectionLabel("A continuación", "${upNext.size}") }
            itemsIndexed(upNext) { index, title ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { player.playQueued(index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge, color = muted)
                    Text(title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { player.removeQueued(index) }) { Icon(Icons.Outlined.Close, "Quitar de la cola") }
                }
            }
        }
        item { Spacer(Modifier.height(Kanso.sectionGap)) }
    }
}
