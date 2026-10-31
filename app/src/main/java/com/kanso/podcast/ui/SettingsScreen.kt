@file:androidx.media3.common.util.UnstableApi
package com.kanso.podcast.ui

import androidx.compose.foundation.selection.selectable
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
    var autoQueue by remember { mutableStateOf(com.kanso.podcast.data.AutoQueueMode.resolve(null, prefs.getString("auto_queue", null))) }
    // Backup / restore (.kansobackup through the system file picker)
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<com.kanso.podcast.data.KansoBackup?>(null) }
    val repo = remember { com.kanso.podcast.data.KansoRepository.get(context.applicationContext) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let {
        scope.launch {
            runCatching { val b = repo.backups.create(appVersion(context)); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openOutputStream(it)!!.use(b::write) }; b }
                .onSuccess { b -> backupMessage = "Respaldo guardado: ${b.podcasts.size} podcasts, ${b.playlists.size} playlists, ${b.bookmarks.size} marcadores" }
                .onFailure { backupMessage = "No se pudo crear el respaldo" }
        }
    } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openInputStream(it)!!.use(com.kanso.podcast.data.KansoBackup::read) } }
                .onSuccess { pendingRestore = it }.onFailure { e -> backupMessage = (e as? com.kanso.podcast.data.BackupException)?.message ?: "No se pudo leer el archivo" }
        }
    } }
    pendingRestore?.let { b -> RestoreDialog(b, onDismiss = { pendingRestore = null }) { replace ->
        pendingRestore = null
        scope.launch {
            runCatching { repo.backups.restore(b, replace) }
                .onSuccess {
                    backupMessage = if (replace) "Biblioteca reemplazada con el respaldo" else "Respaldo combinado con tu biblioteca"
                    androidx.work.WorkManager.getInstance(context).enqueue(androidx.work.OneTimeWorkRequestBuilder<AutomationWorker>().build())
                }.onFailure { backupMessage = "No se pudo restaurar el respaldo" }
        }
    } }
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
        item {
            Text("Nuevos episodios a la cola", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.kanso.podcast.data.AutoQueueMode.entries.forEach { m -> FilterChip(autoQueue == m, onClick = { autoQueue = m; prefs.edit().putString("auto_queue", m.key).apply() }, label = { Text(m.label) }) }
            }
            Text("Se aplica a todos los podcasts salvo los que tengan su propio ajuste. Nunca se duplican ni se agregan archivados o escuchados.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
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
        item { SectionLabel("Respaldo") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { backupLauncher.launch("kanso-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.${com.kanso.podcast.data.KansoBackup.EXTENSION}") }, Modifier.weight(1f)) { Icon(Icons.Outlined.Backup, null); Spacer(Modifier.width(6.dp)); Text("Crear respaldo") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Icon(Icons.Outlined.SettingsBackupRestore, null); Spacer(Modifier.width(6.dp)); Text("Restaurar") }
            }
        }
        item { Text("Guarda suscripciones, carpetas, favoritos, ajustes por podcast, progreso, marcadores, playlists y ajustes en un archivo .kansobackup. No incluye descargas ni contraseñas.", style = MaterialTheme.typography.bodySmall, color = muted) }
        backupMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary) } }
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

private fun appVersion(context: android.content.Context): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/** Confirms a restore and asks whether to merge with or replace the current library. */
@Composable private fun RestoreDialog(b: com.kanso.podcast.data.KansoBackup, onDismiss: () -> Unit, onRestore: (replace: Boolean) -> Unit) {
    var replace by remember { mutableStateOf(false) }
    val date = remember(b.createdAt) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(b.createdAt)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("¿Restaurar respaldo?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Del $date (Kanso ${b.appVersion}): ${b.podcasts.size} podcasts, ${b.folders.size} carpetas, ${b.episodes.size} episodios con progreso, ${b.bookmarks.size} marcadores y ${b.playlists.size} playlists.")
                Row(Modifier.fillMaxWidth().selectable(!replace, role = androidx.compose.ui.semantics.Role.RadioButton) { replace = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!replace, null); Spacer(Modifier.width(8.dp)); Column { Text("Combinar", fontWeight = FontWeight.Medium); Text("Conserva lo que ya tienes; el respaldo gana en conflictos.", style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.fillMaxWidth().selectable(replace, role = androidx.compose.ui.semantics.Role.RadioButton) { replace = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(replace, null); Spacer(Modifier.width(8.dp)); Column { Text("Reemplazar", fontWeight = FontWeight.Medium); Text("Borra tu biblioteca actual y deja solo lo del respaldo.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRestore(replace) }) { Text(if (replace) "Reemplazar" else "Combinar", color = if (replace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
