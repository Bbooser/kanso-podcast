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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex

/** "A continuación": the current episode plus the queue, reorderable by dragging the handle. */
@Composable internal fun UpNextScreen(player: PlayerConnection, onOpenPlayer: () -> Unit, onDiscover: () -> Unit, modifier: Modifier) {
    val upNext = player.upNext
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    val remainingCurrent = (player.duration - player.position).coerceAtLeast(0)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = Kanso.screenPadding, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("A continuación", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            if (upNext.isNotEmpty()) TextButton(onClick = player::clearUpNext) { Text("Vaciar") }
            CastButton(player)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (player.title.isNotBlank()) item {
                Row(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpenPlayer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(player.artwork, player.title, 56.dp, RoundedCornerShape(10.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("REPRODUCIENDO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Text(player.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (player.duration > 0) Text("${formatDuration(remainingCurrent)} restantes", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                }
            }
            if (upNext.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.QueueMusic, null, Modifier.size(40.dp), tint = muted)
                    Spacer(Modifier.height(Kanso.itemGap))
                    Text("Prepara tu sesión de escucha", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Desliza a la derecha cualquier episodio, o toca “A la cola” en su ficha, para que suene a continuación.", color = muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Kanso.sectionGap))
                    Button(onClick = onDiscover, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("Ir a Descubrir") }
                }
            } else {
                item { SectionLabel("En cola", "${upNext.size}") }
                itemsIndexed(upNext, key = { i, q -> "$i:${q.id}" }) { index, q ->
                    val dragging = dragIndex == index
                    Row(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 12f else 0f }
                        .clip(Kanso.art).background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                        .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
                        .clickable { player.playQueued(index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DragHandle, "Arrastrar para reordenar", Modifier.size(40.dp).padding(8.dp).pointerInput(upNext.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragIndex = index; dragOffset = 0f },
                                onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                                onDragEnd = { val from = dragIndex ?: index; val to = (from + kotlin.math.round(dragOffset / (rowHeight + 8.dp.toPx())).toInt()).coerceIn(0, upNext.lastIndex); player.move(from, to); dragIndex = null; dragOffset = 0f },
                                onDragCancel = { dragIndex = null; dragOffset = 0f })
                        }, tint = muted)
                        Artwork(q.artwork, q.title, 48.dp, RoundedCornerShape(10.dp)); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(q.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); Text(q.podcast, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1) }
                        IconButton(onClick = { player.removeQueued(index) }) { Icon(Icons.Outlined.Close, "Quitar de la cola") }
                    }
                }
                item { Text("Mantén presionado ≡ y arrastra para reordenar.", Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center) }
            }
        }
    }
}
