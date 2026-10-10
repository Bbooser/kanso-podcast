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
import android.app.DownloadManager
import kotlinx.coroutines.delay

private data class LocalDownload(val id: Long, val title: String, val status: Int, val uri: String?, val source: String?, val downloaded: Long, val total: Long)

@Composable internal fun DownloadsScreen(modifier: Modifier, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloads by remember { mutableStateOf(queryDownloads(context)) }
    // Light polling while something is in flight, so progress moves without a manual refresh.
    LaunchedEffect(Unit) { while (true) { downloads = queryDownloads(context); delay(if (downloads.any { it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING }) 1_500 else 5_000) } }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(Kanso.itemGap)) {
        item { ScreenHeader("Descargas", "Tus episodios, listos sin conexión.", onBack = onBack) { IconButton(onClick = { downloads = queryDownloads(context) }) { Icon(Icons.Outlined.Refresh, "Actualizar") } } }
        if (downloads.isEmpty()) item { EmptyState(Icons.Outlined.DownloadForOffline, "Sin descargas", "Descarga episodios desde un podcast o activa las descargas automáticas en Perfil.") }
        items(downloads, key = { it.id }) { d ->
            Row(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (d.status == DownloadManager.STATUS_SUCCESSFUL) Icons.Default.OfflinePin else if (d.status == DownloadManager.STATUS_FAILED) Icons.Outlined.ErrorOutline else Icons.Outlined.Downloading, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(Kanso.itemGap))
                Column(Modifier.weight(1f)) {
                    Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(downloadStatus(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
                    if (d.status == DownloadManager.STATUS_RUNNING && d.total > 0) LinearProgressIndicator(progress = { d.downloaded.toFloat() / d.total }, Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.secondary)
                }
                if (d.status == DownloadManager.STATUS_FAILED && d.source != null) IconButton(onClick = { scope.launch { EpisodeDownloads.retry(context, d.id, d.source, d.title); downloads = queryDownloads(context) } }) { Icon(Icons.Outlined.Refresh, "Reintentar") }
                IconButton(onClick = { scope.launch { EpisodeDownloads.remove(context, d.id); downloads = queryDownloads(context) } }) { Icon(Icons.Outlined.Delete, "Eliminar") }
            }
        }
    }
}

internal fun downloadCount(context: Context) = queryDownloads(context).size

private fun queryDownloads(context: Context): List<LocalDownload> = runCatching {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    manager.query(DownloadManager.Query()).use { cursor -> buildList {
        val id = cursor.getColumnIndex(DownloadManager.COLUMN_ID); val title = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE); val status = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val uri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI); val source = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
        val bytes = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR); val total = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        while (cursor.moveToNext()) add(LocalDownload(cursor.getLong(id), cursor.getString(title) ?: "Episodio", cursor.getInt(status), cursor.getString(uri), cursor.getString(source), cursor.getLong(bytes), cursor.getLong(total)))
    } }
}.getOrDefault(emptyList())

private fun downloadStatus(item: LocalDownload) = when (item.status) {
    DownloadManager.STATUS_SUCCESSFUL -> "Disponible sin conexión"
    DownloadManager.STATUS_FAILED -> "Falló · toca reintentar"
    DownloadManager.STATUS_PAUSED -> "En pausa (esperando red)"
    DownloadManager.STATUS_PENDING -> "En espera"
    else -> if (item.total > 0) "${item.downloaded * 100 / item.total}%" else "Descargando…"
}
