package org.mdpeds.logbook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.logic.AccessPolicy
import org.mdpeds.logbook.domain.logic.Relation
import org.mdpeds.logbook.domain.model.AuditEntry
import org.mdpeds.logbook.domain.model.Competency
import org.mdpeds.logbook.domain.model.FeedbackEntry
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.Signoff
import org.mdpeds.logbook.domain.model.SignoffAction
import org.mdpeds.logbook.domain.model.SignoffStatus
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.TargetCategory
import org.mdpeds.logbook.domain.model.TargetProgress
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.UiState
import org.mdpeds.logbook.ui.common.enumLabel
import org.mdpeds.logbook.ui.common.formatDateTime
import org.mdpeds.logbook.ui.common.overview
import org.mdpeds.logbook.ui.components.Banner
import org.mdpeds.logbook.ui.components.CollectMessages
import org.mdpeds.logbook.ui.components.EmptyState
import org.mdpeds.logbook.ui.components.FilterRow
import org.mdpeds.logbook.ui.components.InfoCard
import org.mdpeds.logbook.ui.components.KeyValue
import org.mdpeds.logbook.ui.components.OfflineBanner
import org.mdpeds.logbook.ui.components.ProgressLine
import org.mdpeds.logbook.ui.components.ScreenList
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.components.StatusChip
import org.mdpeds.logbook.ui.components.Tone
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.Routes
import org.mdpeds.logbook.ui.nav.appNav
import org.mdpeds.logbook.ui.nav.isTopLevel
import javax.inject.Inject

private fun UiState<*>.isOffline() = (this as? UiState.Ready<*>)?.offline == true

// ================================================================== targets

data class TargetsData(val name: String, val progress: List<TargetProgress>, val disclaimer: String?)

@HiltViewModel
class TargetsViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, TargetsData>(repo) {
    override suspend fun load(args: String): Loaded<TargetsData> {
        val ov = runCatching { repo.overview(args).value.fullName }.getOrDefault("")
        val p = repo.cached("targets:$args", ListSerializer(TargetProgress.serializer())) { repo.backend.targetProgress(args) }
        return Loaded(TargetsData(ov, p.value.sortedBy { it.sortOrder }, repo.reference().targetsDisclaimer), p.offline)
    }
}

@Composable
fun TargetsScreen(studentId: String) {
    val vm: TargetsViewModel = hiltViewModel()
    LaunchedEffect(studentId) { vm.bind(studentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TargetCategory.EXPOSURE) }
    AppScreen("Progress & targets", subtitle = (state as? UiState.Ready)?.value?.name, topLevel = isTopLevel(Routes.targets(studentId))) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                ScreenList {
                    if (state.isOffline()) item { OfflineBanner() }
                    d.disclaimer?.let { item { Banner(it, Tone.DRAFT, title = "Suggested departmental targets") } }
                    item { FilterRow(listOf(TargetCategory.EXPOSURE to "Clinical exposure", TargetCategory.PROCEDURE to "Procedures"), tab) { tab = it } }
                    val list = d.progress.filter { it.category == tab }
                    if (list.isEmpty()) item { EmptyState("No targets configured") }
                    items(list, key = { it.targetKey }) { p ->
                        InfoCard {
                            ProgressLine(p.label, p.verified, p.targetValue, detail = "${p.recorded} recorded · ${p.submitted} submitted · ${p.verified} verified (${p.verifiedPercent}%)")
                        }
                    }
                    item {
                        Text(
                            "Only faculty-verified entries count towards a target. Targets are a guide to breadth of training, not a pass mark.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ================================================================== competency sign-off

data class SignoffRow(val competency: Competency, val signoff: Signoff?) {
    val status: SignoffStatus get() = signoff?.status ?: SignoffStatus.NOT_STARTED
}

data class SignoffsData(
    val session: UserSession,
    val overview: StudentOverview?,
    val rows: List<SignoffRow>,
    val relation: Relation,
) {
    val isSelf get() = relation.isSelf
    val canSignoff get() = AccessPolicy.canSignoff(session.role, relation)
    val canRevoke get() = AccessPolicy.canRevokeSignoff(session.role)
}

@HiltViewModel
class SignoffsViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, SignoffsData>(repo) {
    override suspend fun load(args: String): Loaded<SignoffsData> {
        val me = repo.requireSession()
        val ov = runCatching { repo.overview(args) }.getOrNull()
        val list = repo.cached("signoffs:$args", ListSerializer(Signoff.serializer())) { repo.backend.signoffs(args) }
        val byCode = list.value.associateBy { it.competencyCode }
        val rows = repo.reference().competencies.filter { it.active }.sortedBy { it.sortOrder }.map { SignoffRow(it, byCode[it.code]) }
        val rel = Relation.of(me.role, me.userId, args, ov?.value?.guideId, ov?.value?.myAssignmentRole)
        return Loaded(SignoffsData(me, ov?.value, rows, rel), list.offline || ov?.offline == true)
    }

    fun request(code: String, note: String?) = action(success = "Final sign-off requested") {
        repo.online { requestSignoff(code, note?.ifBlank { null }) }
    }

    fun act(row: SignoffRow, a: SignoffAction, comment: String?) = action(success = a.label + " — saved") {
        repo.online { facultySignoff(boundArgs, row.competency.code, a, comment?.ifBlank { null }, row.signoff?.version) }
    }
}

@Composable
fun SignoffsScreen(studentId: String) {
    val nav = appNav
    val vm: SignoffsViewModel = hiltViewModel()
    LaunchedEffect(studentId) { vm.bind(studentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    AppScreen("Competency sign-off", subtitle = (state as? UiState.Ready)?.value?.overview?.fullName, topLevel = isTopLevel(Routes.signoffs(studentId))) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val approved = d.rows.count { it.status == SignoffStatus.APPROVED }
                val shown = d.rows.filter { r ->
                    when (filter) {
                        "AWAITING" -> r.status == SignoffStatus.AWAITING_APPROVAL
                        "PROGRESS" -> r.status == SignoffStatus.IN_PROGRESS || r.status == SignoffStatus.RETURNED
                        "APPROVED" -> r.status == SignoffStatus.APPROVED
                        "NOT_STARTED" -> r.status == SignoffStatus.NOT_STARTED
                        else -> true
                    } && (query.isBlank() || r.competency.code.contains(query, true) || r.competency.title.contains(query, true))
                }
                ScreenList {
                    if (state.isOffline()) item { OfflineBanner() }
                    item {
                        InfoCard {
                            ProgressLine("Final sign-offs approved", approved, d.rows.size)
                            Text(
                                "Progression: observed → performed under supervision → performed independently, then final sign-off by assigned faculty or the HOD.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        OutlinedTextField(query, { query = it }, label = { Text("Search competencies") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        FilterRow(
                            listOf("ALL" to "All", "AWAITING" to "Awaiting", "PROGRESS" to "In progress", "APPROVED" to "Approved", "NOT_STARTED" to "Not started"),
                            filter,
                        ) { filter = it }
                    }
                    if (shown.isEmpty()) item { EmptyState("No competencies match") }
                    var lastDomain: String? = null
                    shown.forEach { r ->
                        if (r.competency.domain != lastDomain) {
                            lastDomain = r.competency.domain
                            item(key = "h:" + r.competency.code) { SectionTitle(r.competency.domain) }
                        }
                        item(key = r.competency.code) {
                            InfoCard(onClick = { open = r.competency.code }, modifier = Modifier.testTag("signoff:${r.competency.code}")) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.competency.code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(r.competency.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(stages(r.signoff), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    StatusChip(r.status)
                                }
                            }
                        }
                    }
                }
                open?.let { code -> d.rows.firstOrNull { it.competency.code == code } }?.let { r ->
                    SignoffDialog(d, r, onDismiss = { open = null }, vm = vm)
                }
            }
        }
    }
}

private fun stages(s: Signoff?): String {
    if (s == null) return "Not started"
    return listOf("Observed" to s.observedAt, "Supervised" to s.supervisedAt, "Independent" to s.independentAt)
        .joinToString("  ") { (l, at) -> (if (at != null) "✓ " else "○ ") + l }
}

@Composable
private fun SignoffDialog(d: SignoffsData, r: SignoffRow, onDismiss: () -> Unit, vm: SignoffsViewModel) {
    var comment by rememberSaveable(r.competency.code) { mutableStateOf("") }
    val s = r.signoff
    val busy by vm.busy.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${r.competency.code} · ${r.competency.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(r.status)
                KeyValue("Expected progression", r.competency.progression)
                KeyValue("Observed", s?.observedAt?.let { "${formatDateTime(it)} · ${s.observedByName.orEmpty()}" } ?: "—")
                KeyValue("Performed under supervision", s?.supervisedAt?.let { "${formatDateTime(it)} · ${s.supervisedByName.orEmpty()}" } ?: "—")
                KeyValue("Performed independently", s?.independentAt?.let { "${formatDateTime(it)} · ${s.independentByName.orEmpty()}" } ?: "—")
                s?.studentNote?.let { KeyValue("Student note", it) }
                s?.comment?.let { KeyValue("Faculty comment", it) }
                if (s?.status == SignoffStatus.APPROVED) {
                    KeyValue("Final sign-off", listOfNotNull(s.finalByName, s.finalByDesignation).joinToString(", ") + " · electronically verified " + formatDateTime(s.finalAt))
                }
                val studentCanRequest = d.isSelf && d.session.role == Role.STUDENT &&
                    r.status != SignoffStatus.APPROVED && r.status != SignoffStatus.AWAITING_APPROVAL
                val staffCanAct = d.canSignoff && r.status != SignoffStatus.APPROVED
                val hodRevoke = d.canRevoke && r.status == SignoffStatus.APPROVED
                if (studentCanRequest || staffCanAct || hodRevoke) {
                    OutlinedTextField(
                        comment, { comment = it },
                        label = { Text(if (studentCanRequest) "Note to faculty (optional)" else if (hodRevoke) "Reason for revoking" else "Comment") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                }
                if (studentCanRequest) {
                    Button(onClick = { vm.request(r.competency.code, comment); onDismiss() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Request final sign-off") }
                }
                if (staffCanAct) {
                    listOf(SignoffAction.OBSERVED, SignoffAction.SUPERVISED, SignoffAction.INDEPENDENT).forEach { a ->
                        OutlinedButton(onClick = { vm.act(r, a, comment); onDismiss() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(a.label) }
                    }
                    Button(
                        onClick = { vm.act(r, SignoffAction.APPROVE, comment); onDismiss() },
                        enabled = !busy && s?.independentAt != null,
                        modifier = Modifier.fillMaxWidth().testTag("approveSignoff"),
                    ) { Text(SignoffAction.APPROVE.label) }
                    if (s?.independentAt == null) Text("Record independent performance before the final sign-off.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { vm.act(r, SignoffAction.RETURN, comment); onDismiss() }, enabled = !busy && comment.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(SignoffAction.RETURN.label)
                    }
                }
                if (hodRevoke) {
                    OutlinedButton(onClick = { vm.act(r, SignoffAction.REVOKE, comment); onDismiss() }, enabled = !busy && comment.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(SignoffAction.REVOKE.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ================================================================== feedback

data class FeedbackData(val name: String, val entries: List<FeedbackEntry>)

@HiltViewModel
class FeedbackViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, FeedbackData>(repo) {
    override suspend fun load(args: String): Loaded<FeedbackData> {
        val name = runCatching { repo.overview(args).value.fullName }.getOrDefault("")
        val f = repo.cached("feedback:$args", ListSerializer(FeedbackEntry.serializer())) { repo.backend.feedback(args) }
        return Loaded(FeedbackData(name, f.value.sortedByDescending { it.createdAt }), f.offline)
    }
}

@Composable
fun FeedbackScreen(studentId: String) {
    val vm: FeedbackViewModel = hiltViewModel()
    LaunchedEffect(studentId) { vm.bind(studentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("ALL") }
    AppScreen("Faculty feedback", subtitle = (state as? UiState.Ready)?.value?.name, topLevel = isTopLevel(Routes.feedback(studentId))) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val shown = d.entries.filter {
                    when (filter) {
                        "COMMENTS" -> !it.comment.isNullOrBlank()
                        "RETURNED" -> it.action.contains("RETURN")
                        else -> true
                    }
                }
                ScreenList {
                    if (state.isOffline()) item { OfflineBanner() }
                    item { FilterRow(listOf("ALL" to "All", "COMMENTS" to "With comments", "RETURNED" to "Returned"), filter) { filter = it } }
                    if (shown.isEmpty()) item { EmptyState("No feedback yet", "Comments from faculty reviews appear here.") }
                    items(shown, key = { it.id }) { f ->
                        InfoCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(listOfNotNull(f.facultyName, f.facultyDesignation).joinToString(", "), style = MaterialTheme.typography.titleSmall)
                                    Text("${enumLabel(f.entityType)} · ${formatDateTime(f.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusChip(enumLabel(f.action), if (f.action.contains("RETURN") || f.action.contains("REVOKE")) Tone.RETURNED else Tone.APPROVED)
                            }
                            f.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== audit log

data class AuditData(val entries: List<AuditEntry>, val names: Map<String, String>)

@HiltViewModel
class AuditViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, AuditData>(repo) {
    override suspend fun load(args: String): Loaded<AuditData> {
        val studentId = args.ifBlank { null }
        val list = repo.online { auditLog(studentId) }
        val names = runCatching { repo.backend.studentOverviews().associate { it.studentId to it.fullName } }.getOrDefault(emptyMap())
        return fresh(AuditData(list.sortedByDescending { it.at }, names))
    }
}

@Composable
fun AuditScreen(studentId: String?) {
    val vm: AuditViewModel = hiltViewModel()
    LaunchedEffect(studentId) { vm.bind(studentId.orEmpty()) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val title = if (appNav.session.role == Role.HOD) "Audit log" else "Activity history"
    AppScreen(title, subtitle = studentId?.let { (state as? UiState.Ready)?.value?.names?.get(it) }, topLevel = isTopLevel(Routes.audit(studentId))) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val shown = remember(d, query) {
                    if (query.isBlank()) d.entries
                    else d.entries.filter { e ->
                        e.action.contains(query, true) || e.entityType.contains(query, true) ||
                            (e.studentId?.let { d.names[it] }?.contains(query, true) == true) || (e.reason?.contains(query, true) == true)
                    }
                }
                ScreenList {
                    item { OutlinedTextField(query, { query = it }, label = { Text("Filter by action, student or reason") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    if (shown.isEmpty()) item { EmptyState("No activity recorded") }
                    items(shown, key = { it.id }) { e ->
                        InfoCard {
                            Text(enumLabel(e.action), style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(formatDateTime(e.at), e.actorRole?.let(::enumLabel), enumLabel(e.entityType), e.studentId?.let { d.names[it] }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            e.reason?.takeIf { it.isNotBlank() }?.let { Text("Reason: $it", style = MaterialTheme.typography.bodyMedium) }
                            (e.changes as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { c ->
                                Text(
                                    c.entries.take(6).joinToString("\n") { (k, v) -> "$k: " + ((v as? JsonPrimitive)?.content ?: v.toString()).take(80) },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
