package com.kanso.podcast.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kanso.podcast.data.KansoStorage
import com.kanso.podcast.data.StatsMath
import com.kanso.podcast.data.StorageReport
import com.kanso.podcast.data.db.TopPodcastRow
import kotlinx.coroutines.launch

/** Space used by downloads, per podcast, with a size limit and one-tap cleanup. */
@Composable internal fun StorageCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableFloatStateOf(KansoStorage.limitGb(context)) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { report = KansoStorage.report(context) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    Column(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val r = report
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Descargas", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(r?.let { KansoStorage.format(it.totalBytes) } ?: "…", style = MaterialTheme.typography.titleMedium)
        }
        if (r != null && r.limitBytes > 0) LinearProgressIndicator(progress = { (r.totalBytes / r.limitBytes.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(),
            color = if (r.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        r?.byPodcast?.take(8)?.forEach { usage ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(usage.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${usage.episodes} episodios · ${KansoStorage.format(usage.bytes)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                IconButton(onClick = { scope.launch { val freed = KansoStorage.deletePodcast(context, usage.feedUrl); message = "Liberaste ${KansoStorage.format(freed)}"; version++ } }) { Icon(Icons.Outlined.Delete, "Borrar descargas de ${usage.title}") }
            }
        }
        if (r != null && r.items.isEmpty()) Text("No tienes episodios descargados.", color = muted)
        Text(if (limit <= 0f) "Sin límite de espacio" else "Límite: ${limit.toInt()} GB", fontWeight = FontWeight.Medium)
        Slider(limit, { limit = it.toInt().toFloat() }, onValueChangeFinished = { KansoStorage.setLimitGb(context, limit); version++ }, valueRange = 0f..20f, steps = 19)
        Text("Al pasar el límite, Kanso borra primero los episodios escuchados más viejos (nunca favoritos ni pendientes) y pausa las descargas automáticas.", style = MaterialTheme.typography.bodySmall, color = muted)
        FilledTonalButton(onClick = { scope.launch { val freed = KansoStorage.cleanupPlayed(context); message = if (freed > 0) "Liberaste ${KansoStorage.format(freed)}" else "No había episodios escuchados descargados"; version++ } },
            Modifier.fillMaxWidth(), enabled = (r?.playedBytes ?: 0) > 0) {
            Icon(Icons.Outlined.CleaningServices, null); Spacer(Modifier.width(8.dp)); Text("Borrar escuchados" + (r?.playedBytes?.takeIf { it > 0 }?.let { " (${KansoStorage.format(it)})" } ?: ""))
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
    }
}

/** Calm bar chart drawn with Canvas; [highlightLast] tints the current day/week. */
@Composable internal fun BarChart(buckets: List<StatsMath.Bucket>, label: (StatsMath.Bucket) -> String, highlightLast: Boolean = true) {
    val accent = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.onSurface.copy(.08f)
    val max = (buckets.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / buckets.size.coerceAtLeast(1); val barW = slot * .56f
            buckets.forEachIndexed { i, b ->
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), CornerRadius(barW / 2))
                val h = size.height * b.minutes / max
                if (h > 0) drawRoundRect(if (!highlightLast || i == buckets.lastIndex) accent else accent.copy(.55f), Offset(x, size.height - h), Size(barW, h), CornerRadius(barW / 2))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) { buckets.forEach { Text(label(it), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
    }
}

@Composable internal fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        content()
    }
}

@Composable internal fun TopPodcastBars(rows: List<TopPodcastRow>) {
    val max = (rows.maxOfOrNull { it.listenedMs } ?: 1L).coerceAtLeast(1L)
    if (rows.isEmpty()) Text("Escucha un poco y aquí verás tus favoritos.", color = MaterialTheme.colorScheme.onSurface.copy(.55f))
    rows.forEach { row ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row { Text(row.title ?: "Podcast eliminado", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatDuration(row.listenedMs), style = MaterialTheme.typography.bodySmall) }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurface.copy(.08f))) {
                Box(Modifier.fillMaxWidth(row.listenedMs / max.toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.secondary))
            }
        }
    }
}
