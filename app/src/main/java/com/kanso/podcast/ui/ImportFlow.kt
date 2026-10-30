package com.kanso.podcast.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kanso.podcast.data.AntennaPodImport
import com.kanso.podcast.data.KansoRepository
import com.kanso.podcast.data.Opml
import com.kanso.podcast.data.OpmlDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files shared or opened with Kanso (ACTION_VIEW / ACTION_SEND) waiting to be imported. */
object IncomingImports {
    val pending = MutableStateFlow<Uri?>(null)
    @Suppress("DEPRECATION")
    fun accept(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        pending.value = uri
    }
}

internal sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val done: Int, val total: Int, val label: String) : ImportState
    data class Finished(val title: String, val lines: List<String>) : ImportState
}

/** Imports OPML from any podcast app, or an AntennaPod database backup with listening state. */
internal class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = KansoRepository.get(app)
    var state by mutableStateOf<ImportState>(ImportState.Idle); private set
    fun dismiss() { state = ImportState.Idle }

    fun import(uri: Uri) {
        if (state is ImportState.Running) return
        state = ImportState.Running(0, 0, "Leyendo archivo…")
        viewModelScope.launch {
            state = runCatching { run(uri) }.getOrElse { ImportState.Finished("No se pudo importar", listOf(it.message?.takeIf { m -> m.length < 120 } ?: "El archivo no es un OPML ni un respaldo de AntennaPod válido.")) }
        }
    }

    private suspend fun run(uri: Uri): ImportState {
        val context = getApplication<Application>()
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "import.tmp").also { f -> context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } } }
        }
        try {
            val progress: suspend (Int, Int) -> Unit = { done, total -> state = ImportState.Running(done, total, "Cargando podcasts…") }
            return if (withContext(Dispatchers.IO) { AntennaPodImport.isSqlite(file) }) {
                val backup = withContext(Dispatchers.IO) { AntennaPodImport.read(file) }
                val summary = repo.importOpml(OpmlDocument(backup.feeds, 0, 0), progress)
                state = ImportState.Running(1, 1, "Aplicando historial…")
                val episodes = repo.applyImportedState(backup.states)
                ImportState.Finished("Respaldo de AntennaPod importado", summaryLines(summary.added, summary.skipped, summary.failed, summary.unreachable) + "Estado de escucha: $episodes registros (escuchados, progreso y favoritos)")
            } else {
                val doc = withContext(Dispatchers.IO) { file.inputStream().use(Opml::read) }
                if (doc.entries.isEmpty() && doc.invalid == 0) return ImportState.Finished("Nada que importar", listOf("El archivo no contiene podcasts."))
                val summary = repo.importOpml(doc, progress)
                ImportState.Finished("Importación terminada", summaryLines(summary.added, summary.skipped, summary.failed, summary.unreachable))
            }
        } finally { withContext(Dispatchers.IO) { file.delete() } }
    }

    private fun summaryLines(added: Int, skipped: Int, failed: Int, unreachable: Int) = buildList {
        add("Añadidos: $added")
        add("Omitidos (ya estaban o repetidos): $skipped")
        add("Fallidos (enlaces no válidos): $failed")
        if (unreachable > 0) add("$unreachable feeds no respondieron; se quedaron en tu biblioteca y se reintentarán.")
    }
}

@Composable internal fun ImportDialog(vm: ImportViewModel) {
    when (val s = vm.state) {
        ImportState.Idle -> Unit
        is ImportState.Running -> AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Importando") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (s.total > 0) "${s.label} ${s.done} de ${s.total}" else s.label)
                if (s.total > 0) LinearProgressIndicator(progress = { s.done / s.total.toFloat() }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
            }
        })
        is ImportState.Finished -> AlertDialog(onDismissRequest = vm::dismiss, title = { Text(s.title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { s.lines.forEachIndexed { i, line -> Text(line, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal) } } },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Listo") } })
    }
}
