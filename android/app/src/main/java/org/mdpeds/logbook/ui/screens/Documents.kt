package org.mdpeds.logbook.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.model.AcademicDocument
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.formatDate
import org.mdpeds.logbook.ui.components.CollectMessages
import org.mdpeds.logbook.ui.components.InfoCard
import org.mdpeds.logbook.ui.components.ScreenList
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.Routes
import org.mdpeds.logbook.ui.nav.appNav
import org.mdpeds.logbook.util.Files
import org.mdpeds.logbook.util.PdfRenderer
import javax.inject.Inject

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    repo: LogbookRepository,
    @ApplicationContext private val context: Context,
) : LoadViewModel<String, ReferenceData>(repo) {
    private val _files = MutableSharedFlow<FileEvent>(extraBufferCapacity = 2)
    val files = _files.asSharedFlow()

    override suspend fun load(args: String): Loaded<ReferenceData> {
        val fresh = runCatching { repo.refreshReference() }.getOrNull()
        return Loaded(fresh ?: repo.reference(), offline = fresh == null)
    }

    fun openPdf(doc: AcademicDocument) {
        val path = doc.storagePath ?: return
        action {
            val bytes = repo.backend.downloadDocumentFile(path)
            val file = withContext(Dispatchers.IO) { Files.write(context, doc.fileName ?: "${doc.code}.pdf", bytes) }
            _files.tryEmit(FileEvent.View(file))
        }
    }

    /** Renders the structured document as a printable PDF table (for offline use / printing). */
    fun exportStructured(doc: AcademicDocument, ref: ReferenceData) {
        action {
            val (headers, rows) = documentTable(doc, ref)
            val file = withContext(Dispatchers.Default) {
                Files.write(context, "${doc.code}.pdf", PdfRenderer.table(doc.title, "Version ${doc.versionNo} · ${doc.description.orEmpty()}", headers, rows))
            }
            _files.tryEmit(FileEvent.Share(file))
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun documentTable(doc: AcademicDocument, ref: ReferenceData): Pair<List<String>, List<List<String>>> {
    val c = doc.content ?: JsonObject(emptyMap())
    return when (c.str("type")) {
        "ROTATION_PLAN" -> listOf("Year", "Area", "Duration", "Focus") to
            c.objects("rows").map { listOf(it.str("year").orEmpty(), it.str("area").orEmpty(), it.str("duration").orEmpty(), it.str("focus").orEmpty()) }
        "COMPETENCY_INDEX" -> listOf("Code", "Competency", "Domain", "Progression") to
            ref.competencies.filter { it.active }.map { listOf(it.code, it.title, it.domain, it.progression) }
        "TARGETS" -> listOf("Target", "Minimum", "Notes") to
            ref.targets.filter { it.category == c.str("category") }.map { listOf(it.label, it.targetValue.toString(), it.notes.orEmpty()) }
        else -> listOf("Section", "Content") to c.objects("sections").map { s ->
            val body = (s.strings("paragraphs") + s.strings("bullets").map { "• $it" } +
                ((s["table"] as? JsonArray)?.mapNotNull { r -> (r as? JsonArray)?.joinToString(" — ") { it.jsonPrimitive.content } } ?: emptyList()))
            listOf(s.str("heading").orEmpty(), body.joinToString("\n"))
        }
    }
}

@Composable
fun DocumentsScreen() {
    val nav = appNav
    val vm: DocumentsViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("all") }
    val state by vm.state.collectAsStateWithLifecycle()
    AppScreen("Academic documents", topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { ref ->
                ScreenList {
                    item {
                        Text(
                            "Reference documents published by the department. They are read-only and update automatically when the HOD publishes a new version.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(ref.documents.sortedBy { it.sortOrder }, key = { it.code }) { d ->
                        InfoCard(onClick = { nav.open(Routes.document(d.code)) }, modifier = Modifier.testTag("doc:${d.code}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.title, style = MaterialTheme.typography.titleMedium)
                                    d.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text("Version ${d.versionNo}" + (d.publishedAt?.let { " · ${formatDate(it)}" } ?: ""), style = MaterialTheme.typography.labelSmall)
                                }
                                if (d.storagePath != null) Icon(Icons.Filled.PictureAsPdf, "PDF available")
                                Icon(Icons.Filled.ChevronRight, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentScreen(code: String) {
    val nav = appNav
    val vm: DocumentsViewModel = hiltViewModel()
    LaunchedEffect(code) { vm.bind(code) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    FileEventsHandler(vm.files)
    val doc = (state as? org.mdpeds.logbook.ui.common.UiState.Ready)?.value?.documents?.firstOrNull { it.code == code }
    AppScreen(doc?.title ?: "Document", subtitle = doc?.let { "Version ${it.versionNo}" }) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { ref ->
                val d = ref.documents.firstOrNull { it.code == code }
                ScreenList {
                    if (d == null) {
                        item { Text("This document is not available.") }
                        return@ScreenList
                    }
                    d.description?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (d.storagePath != null) Button(onClick = { vm.openPdf(d) }, enabled = !busy) { Text("Open published PDF") }
                            if (d.content != null) androidx.compose.material3.OutlinedButton(onClick = { vm.exportStructured(d, ref) }, enabled = !busy) { Text("Export PDF") }
                        }
                    }
                    val c = d.content
                    if (c != null) {
                        when (c.str("type")) {
                            "ROTATION_PLAN" -> {
                                c.objects("rows").groupBy { it.str("year").orEmpty() }.forEach { (year, rows) ->
                                    item(key = "y:$year") {
                                        InfoCard {
                                            SectionTitle(year)
                                            rows.forEach { r ->
                                                Text(r.str("area").orEmpty(), style = MaterialTheme.typography.titleSmall)
                                                Text("${r.str("duration").orEmpty()} · ${r.str("focus").orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                val pr = c.objects("monthlyPriorities")
                                if (pr.isNotEmpty()) item(key = "pr") {
                                    InfoCard {
                                        SectionTitle("Month-wise priorities")
                                        pr.forEach { p ->
                                            Text("${p.str("year")} · Months ${p.str("fromMonth")}–${p.str("toMonth")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(p.str("priority").orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                                notes(c)
                            }
                            "COMPETENCY_INDEX" -> {
                                ref.competencies.filter { it.active }.sortedBy { it.sortOrder }.groupBy { it.domain }.forEach { (domain, list) ->
                                    item(key = "d:$domain") {
                                        InfoCard {
                                            SectionTitle(domain)
                                            list.forEach { comp ->
                                                Row {
                                                    Text(comp.code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(72.dp))
                                                    Text("${comp.title}  (${comp.progression})", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                }
                                notes(c)
                            }
                            "TARGETS" -> {
                                item(key = "t") {
                                    InfoCard {
                                        ref.targets.filter { it.category == c.str("category") }.sortedBy { it.sortOrder }.forEach { t ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(t.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                                Text("${t.targetValue}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            }
                                            t.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            HorizontalDivider()
                                        }
                                    }
                                }
                                listOf("distribution" to "Distribution", "guidance" to "Documentation guidance").forEach { (k, title) ->
                                    val lines = c.strings(k)
                                    if (lines.isNotEmpty()) item(key = k) {
                                        InfoCard {
                                            SectionTitle(title)
                                            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                                        }
                                    }
                                }
                                notes(c)
                            }
                            else -> {
                                c.objects("sections").forEachIndexed { i, s ->
                                    item(key = "s$i") {
                                        InfoCard {
                                            s.str("heading")?.let { SectionTitle(it) }
                                            s.strings("paragraphs").forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                            s.strings("bullets").forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                                            (s["table"] as? JsonArray)?.forEach { row ->
                                                val cells = (row as? JsonArray)?.map { it.jsonPrimitive.content } ?: return@forEach
                                                Row {
                                                    Text(cells.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                                                    Text(cells.drop(1).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                                notes(c)
                            }
                        }
                    }
                    d.notes?.let { item(key = "pubnotes") { Text("Publication note: $it", style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.notes(c: JsonObject) {
    val n = c.strings("notes")
    if (n.isNotEmpty()) item(key = "notes") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            n.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
