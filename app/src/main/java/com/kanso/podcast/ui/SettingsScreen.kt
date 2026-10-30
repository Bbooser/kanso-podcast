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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.kanso.podcast.data.AutomationWorker
import com.kanso.podcast.data.Opml

internal class SettingsViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    val podcasts = repo.podcasts.stateIn(this, emptyList())
    val entities = repo.podcastEntities.stateIn(this, emptyList())
}

@Composable internal fun SettingsScreen(dark: Boolean, palette: KansoPalette, onDarkChange: (Boolean) -> Unit, onPaletteChange: (KansoPalette) -> Unit, modifier: Modifier, onBack: (() -> Unit)? = null, vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("automation", 0) }
    val scope = rememberCoroutineScope()
    val subscriptions by vm.podcasts.collectAsStateWithLifecycle()
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    val importer: ImportViewModel = viewModel()
    val entities by vm.entities.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(importer::import) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri -> uri?.let {
        runCatching { context.contentResolver.openOutputStream(it)!!.bufferedWriter().use { w -> w.write(Opml.export(entities.map { p -> com.kanso.podcast.data.OpmlEntry(p.feedUrl, p.title, p.folder) })) } }
            .onSuccess { transferMessage = "Biblioteca exportada (${entities.size} podcasts)" }.onFailure { transferMessage = "No se pudo exportar" }
    } }
    var count by remember { mutableIntStateOf(prefs.getInt("download_count", 0)) }; var days by remember { mutableIntStateOf(prefs.getInt("delete_days", 7)) }
    var archive by remember { mutableStateOf(prefs.getBoolean("archive_finished", true)) }; var wifi by remember { mutableStateOf(prefs.getBoolean("wifi_only", true)) }
    fun persist() { prefs.edit().putInt("download_count", count).putInt("delete_days", days).putBoolean("archive_finished", archive).putBoolean("wifi_only", wifi).apply(); AutomationWorker.schedule(context, wifi) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(.55f)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Kanso.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Ajustes", "Automatiza y hazlo tuyo.", onBack = onBack) }
        item { SectionLabel("Apariencia") }
        item { ToggleSetting(if (dark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, "Modo oscuro", dark, onDarkChange) }
        item { PalettePicker(palette, onPaletteChange) }
        item { SectionLabel("Automatización") }
        item { NumberSetting(Icons.Outlined.Download, "Descargas por podcast", if (count == 0) "Desactivadas" else "$count episodios nuevos", count, 0..10) { count = it; persist() } }
        item { ToggleSetting(Icons.Outlined.Inventory2, "Archivar al terminar", archive) { archive = it; persist() } }
        item { NumberSetting(Icons.Outlined.DeleteSweep, "Borrado automático", if (days == 0) "Nunca" else "$days días después de escucharlo", days, 0..30) { days = it; persist() } }
        item { ToggleSetting(Icons.Outlined.Wifi, "Descargar solo con Wi‑Fi", wifi) { wifi = it; persist() } }
        item { Text("Kanso revisa tus feeds cada 6 horas. Solo se borran episodios ya escuchados; los favoritos nunca se archivan ni se borran automáticamente.", style = MaterialTheme.typography.bodySmall, color = muted) }
        item { SectionLabel("Biblioteca") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Importar") }
                OutlinedButton(onClick = { exportLauncher.launch("kanso-podcasts.opml") }, Modifier.weight(1f), enabled = subscriptions.isNotEmpty()) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(6.dp)); Text("Exportar") }
            }
        }
        item { Text("Importa el OPML exportado desde Pocket Casts, AntennaPod, Podcast Addict, Overcast o Apple Podcasts (se conservan las carpetas), o el respaldo de base de datos de AntennaPod para traer también lo escuchado, el progreso y los favoritos. También puedes compartir el archivo directo a Kanso.", style = MaterialTheme.typography.bodySmall, color = muted) }
        transferMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary) } }
        item { SectionLabel("Almacenamiento") }
        item { StorageCard() }
        item { SectionLabel("Mantenimiento") }
        item {
            OutlinedButton(onClick = { androidx.work.WorkManager.getInstance(context).enqueue(androidx.work.OneTimeWorkRequestBuilder<AutomationWorker>().build()); refreshMessage = "Actualización programada" }, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(8.dp)); Text("Actualizar todos los feeds")
            }
        }
        refreshMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary) } }
        item { DiagnosticsCard(context, subscriptions.size) }
    }
}

@Composable private fun DiagnosticsCard(context: Context, subscriptionCount: Int) {
    val downloads = remember { downloadCount(context) }
    Column(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Diagnóstico", fontWeight = FontWeight.SemiBold)
        DiagnosticLine("Biblioteca", "$subscriptionCount suscripciones")
        DiagnosticLine("Descargas", "$downloads elementos")
        DiagnosticLine("Almacenamiento", "Base de datos local (Room)")
        DiagnosticLine("Android Auto", "Navegación y búsqueda")
        DiagnosticLine("Widget", "Controles habilitados")
    }
}
@Composable private fun DiagnosticLine(label: String, value: String) { Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(.65f)); Text(value, fontWeight = FontWeight.Medium) } }
@Composable private fun ToggleSetting(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onChange(!checked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(Kanso.itemGap)); Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium); Switch(checked, onChange)
    }
}
@Composable private fun NumberSetting(icon: ImageVector, title: String, value: String, number: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(Kanso.itemGap)); Column { Text(title, fontWeight = FontWeight.Medium); Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.55f)) } }
        Slider(number.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0))
    }
}
@Composable private fun PalettePicker(selected: KansoPalette, onSelect: (KansoPalette) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Kanso.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Text("Combinación de colores", fontWeight = FontWeight.Medium); Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { KansoPalette.entries.forEach { item ->
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(item) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(item.accent), contentAlignment = Alignment.Center) { if (selected == item) Icon(Icons.Default.Check, null, tint = Color.White) }
                Text(item.label, style = MaterialTheme.typography.labelSmall)
            }
        } }
    }
}
