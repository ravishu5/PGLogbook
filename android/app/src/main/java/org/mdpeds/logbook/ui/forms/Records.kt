package org.mdpeds.logbook.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.data.RecordItem
import org.mdpeds.logbook.data.local.OutboxState
import org.mdpeds.logbook.domain.logic.ProgrammeTimeline
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.model.BackendException
import org.mdpeds.logbook.domain.model.InstitutionSettings
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.TemplateCategory
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.display
import org.mdpeds.logbook.ui.common.overview
import org.mdpeds.logbook.ui.components.EmptyState
import org.mdpeds.logbook.ui.components.FilterRow
import org.mdpeds.logbook.ui.components.InfoCard
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
import org.mdpeds.logbook.ui.theme.AppTheme
import java.time.LocalDate
import javax.inject.Inject

// ================================================================== category (Logs / Forms tabs)

data class CategoryData(val reference: ReferenceData, val templates: List<Template>, val items: List<RecordItem>, val studentName: String?)

@HiltViewModel
class CategoryViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<Pair<String, String>, CategoryData>(repo) {
    override suspend fun load(args: Pair<String, String>): Loaded<CategoryData> {
        val (studentId, group) = args
        val ref = repo.reference()
        val cats = categoriesFor(group)
        val templates = ref.templates.filter { it.category in cats }
        val recs = repo.records(studentId)
        val me = repo.requireSession()
        val name = if (me.userId == studentId) null else runCatching { repo.overview(studentId).value.fullName }.getOrNull()
        return Loaded(CategoryData(ref, templates, recs.value.filter { r -> templates.any { it.code == r.submission.templateCode } }, name), recs.offline)
    }
}

@Composable
fun CategoryScreen(studentId: String, group: String) {
    val nav = appNav
    val vm: CategoryViewModel = hiltViewModel()
    LaunchedEffect(studentId, group) { vm.bind(studentId to group) }
    val state by vm.state.collectAsStateWithLifecycle()
    val route = Routes.category(studentId, group)
    val name = (state as? org.mdpeds.logbook.ui.common.UiState.Ready)?.value?.studentName
    AppScreen(groupTitle(group), subtitle = name, topLevel = isTopLevel(route)) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { data ->
                ScreenList {
                    items(data.templates, key = { it.code }) { t ->
                        val mine = data.items.filter { it.submission.templateCode == t.code && it.submission.status != SubmissionStatus.NOT_STARTED }
                        val verified = mine.count { it.submission.status.isVerified }
                        val pending = mine.count { it.submission.status.isAwaitingFaculty || it.submission.status.isAwaitingHod }
                        val returned = mine.count { it.submission.status == SubmissionStatus.RETURNED }
                        val drafts = mine.count { it.submission.status == SubmissionStatus.DRAFT }
                        InfoCard(onClick = { nav.open(Routes.records(studentId, t.code)) }, modifier = Modifier.testTag("template:${t.code}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                                    t.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Icon(Icons.Filled.ChevronRight, null)
                            }
                            if (t.hasSlots) {
                                val doneSlots = data.items.count { it.submission.templateCode == t.code && it.submission.slotNo != null && it.submission.status.isVerified }
                                ProgressLine("Required (verified)", doneSlots, t.requiredInitial)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${mine.size} entries", style = MaterialTheme.typography.bodySmall)
                                if (verified > 0) StatusChip("$verified verified", Tone.APPROVED)
                                if (pending > 0) StatusChip("$pending pending", Tone.PENDING)
                            }
                            if (returned > 0 || drafts > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (returned > 0) StatusChip("$returned returned", Tone.RETURNED)
                                if (drafts > 0) StatusChip("$drafts drafts", Tone.DRAFT)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== records of one template

data class RecordsData(
    val reference: ReferenceData,
    val template: Template,
    val items: List<RecordItem>,
    val overview: StudentOverview?,
    val settings: InstitutionSettings,
    val role: Role,
    val isSelf: Boolean,
    val today: LocalDate,
)

@HiltViewModel
class RecordsViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<Pair<String, String>, RecordsData>(repo) {
    override suspend fun load(args: Pair<String, String>): Loaded<RecordsData> {
        val (studentId, code) = args
        val me = repo.requireSession()
        val ref = repo.reference()
        val t = ref.template(code) ?: throw BackendException(404, "Unknown logbook section")
        val recs = repo.records(studentId, code)
        val ov = runCatching { repo.overview(studentId).value }.getOrNull()
        val settings = repo.settings()
        return Loaded(RecordsData(ref, t, recs.value, ov, settings, me.role, me.userId == studentId, LocalDate.now()), recs.offline)
    }
}

private enum class RecFilter(val label: String) { ALL("All"), DRAFT("Drafts"), PENDING("Pending"), VERIFIED("Verified"), RETURNED("Returned") }

private fun RecFilter.matches(s: SubmissionStatus) = when (this) {
    RecFilter.ALL -> true
    RecFilter.DRAFT -> s == SubmissionStatus.DRAFT || s == SubmissionStatus.NOT_STARTED
    RecFilter.PENDING -> s.isAwaitingFaculty || s.isAwaitingHod
    RecFilter.VERIFIED -> s.isVerified
    RecFilter.RETURNED -> s == SubmissionStatus.RETURNED
}

@Composable
fun RecordsScreen(studentId: String, code: String) {
    val nav = appNav
    val vm: RecordsViewModel = hiltViewModel()
    LaunchedEffect(studentId, code) { vm.bind(studentId to code) }
    val state by vm.state.collectAsStateWithLifecycle()
    val ready = (state as? org.mdpeds.logbook.ui.common.UiState.Ready)?.value
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { m -> scope.launch { nav.snackbar.showSnackbar(m) } }
    val canCreate = ready != null && ready.isSelf && ready.role == Role.STUDENT &&
        ready.template.category !in listOf(TemplateCategory.MONTHLY, TemplateCategory.ANNUAL) &&
        (ready.template.slots.isEmpty())

    AppScreen(
        title = ready?.template?.title ?: "Records",
        subtitle = ready?.let { if (it.isSelf) it.template.subtitle else it.overview?.fullName },
        topLevel = isTopLevel(Routes.records(studentId, code)),
        fab = {
            if (canCreate) {
                ExtendedFloatingActionButton(
                    onClick = { nav.open(Routes.editor(studentId, code)) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(if (ready!!.template.hasSlots) "Additional" else "New entry") },
                    modifier = Modifier.testTag("newEntry"),
                )
            }
        },
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { data ->
                when (data.template.category) {
                    TemplateCategory.MONTHLY -> MonthlyList(data, studentId, toast)
                    TemplateCategory.ANNUAL -> AnnualList(data, studentId, toast)
                    else -> if (data.template.hasSlots) SlotList(data, studentId) else PlainList(data, studentId)
                }
            }
        }
    }
}

@Composable
private fun RecordCard(data: RecordsData, item: RecordItem, title: String? = null, onClick: () -> Unit) {
    val s = item.submission
    InfoCard(onClick = onClick, modifier = Modifier.testTag("record:${s.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title ?: recordTitle(data.template, s, data.reference), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = recordSubtitle(data.template, s, data.reference)
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(s.status)
        }
        SyncBadge(item)
        if (s.status == SubmissionStatus.RETURNED && !s.returnReason.isNullOrBlank()) {
            Text("Returned: ${s.returnReason}", style = MaterialTheme.typography.bodySmall, color = AppTheme.status.returned, maxLines = 2)
        }
    }
}

@Composable
fun SyncBadge(item: RecordItem) {
    val sync = item.sync ?: return
    val (icon, text, color) = when (sync.state) {
        OutboxState.CONFLICT -> Triple(Icons.Filled.SyncProblem, "Conflict — open to resolve", AppTheme.status.returned)
        OutboxState.FAILED -> Triple(Icons.Filled.SyncProblem, "Not saved to server: ${sync.error ?: "error"}", AppTheme.status.returned)
        else -> Triple(Icons.Filled.CloudOff, if (sync.pendingSubmit) "Waiting to sync and submit" else "Saved on device — waiting to sync", AppTheme.status.pending)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(end = 6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2)
    }
}

@Composable
private fun PlainList(data: RecordsData, studentId: String) {
    val nav = appNav
    var filter by rememberSaveable { mutableStateOf(RecFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = data.items.filter { filter.matches(it.submission.status) }
        .filter { query.isBlank() || it.submission.allData.values.any { v -> v.contains(query, ignoreCase = true) } }
        .sortedWith(compareByDescending<RecordItem> { it.isLocalOnly }.thenByDescending { it.submission.entryDate ?: "" }.thenByDescending { it.submission.updatedAt ?: "" })
    ScreenList {
        data.template.notice?.let { n -> item { org.mdpeds.logbook.ui.components.Banner(n, Tone.DRAFT) } }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth(),
            )
        }
        item { FilterRow(RecFilter.entries.map { it to it.label }, filter) { filter = it } }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    if (data.items.isEmpty()) "No entries yet" else "No matching entries",
                    if (data.items.isEmpty() && data.isSelf) "Tap “New entry” to record your first ${data.template.title.lowercase()}." else null,
                )
            }
        }
        items(shown, key = { it.submission.id }) { item ->
            RecordCard(data, item) { nav.open(Routes.editor(studentId, data.template.code, id = item.submission.id)) }
        }
    }
}

@Composable
private fun SlotList(data: RecordsData, studentId: String) {
    val nav = appNav
    val t = data.template
    val bySlot = data.items.filter { it.submission.slotNo != null }.associateBy { it.submission.slotNo!! }
    val extra = data.items.filter { it.submission.slotNo == null }
    val verified = (1..t.requiredInitial).count { bySlot[it]?.submission?.status?.isVerified == true }
    ScreenList {
        item {
            InfoCard {
                ProgressLine("Required assessments verified", verified, t.requiredInitial)
                t.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { SectionTitle(if (t.slots.isNotEmpty()) "Milestones" else "Required assessments") }
        items((1..t.requiredInitial).toList(), key = { "slot$it" }) { slot ->
            val rec = bySlot[slot]
            val label = slotLabel(t, slot)
            if (rec != null) {
                RecordCard(data, rec, title = "$slot. $label") { nav.open(Routes.editor(studentId, t.code, id = rec.submission.id, slot = slot)) }
            } else {
                InfoCard(onClick = { if (data.isSelf) nav.open(Routes.editor(studentId, t.code, slot = slot)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$slot. $label", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusChip(SubmissionStatus.NOT_STARTED)
                    }
                }
            }
        }
        if (t.slots.isEmpty()) {
            item { SectionTitle("Additional assessments") }
            if (extra.isEmpty()) item { Text("No additional assessments recorded.", style = MaterialTheme.typography.bodyMedium) }
            items(extra, key = { it.submission.id }) { item ->
                RecordCard(data, item) { nav.open(Routes.editor(studentId, t.code, id = item.submission.id)) }
            }
        }
    }
}

@Composable
private fun MonthlyList(data: RecordsData, studentId: String, toast: (String) -> Unit) {
    val nav = appNav
    val start = data.overview?.programmeStartDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    if (start == null) {
        EmptyState("Programme start date not set", "Monthly assessments become available once the HOD records the programme start date.")
        return
    }
    val duration = data.overview?.durationMonths ?: data.settings.programmeDurationMonths
    val byMonth = data.items.filter { it.submission.periodNo != null }.associateBy { it.submission.periodNo!! }
    val current = ProgrammeTimeline.currentMonth(start, data.today, duration)
    val months = ProgrammeTimeline.months(start, duration)
    val completed = byMonth.values.count { it.submission.status == SubmissionStatus.COMPLETED }
    ScreenList {
        item {
            InfoCard {
                Text("Programme month $current of $duration · PG Year ${ProgrammeTimeline.pgYear(current)}", style = MaterialTheme.typography.titleSmall)
                ProgressLine("Monthly assessments completed", completed, duration)
                Text("Months open on their start date. Future months stay locked.", style = MaterialTheme.typography.bodySmall)
            }
        }
        months.groupBy { it.pgYear }.forEach { (year, list) ->
            item(key = "year$year") { SectionTitle("PG Year $year") }
            items(list, key = { "m${it.number}" }) { m ->
                val rec = byMonth[m.number]
                val open = ProgrammeTimeline.isMonthOpen(start, m.number, data.today, data.settings.allowFutureMonths)
                val status = rec?.submission?.status ?: SubmissionStatus.NOT_STARTED
                InfoCard(
                    modifier = Modifier.testTag("month:${m.number}"),
                    onClick = {
                        when {
                            data.isSelf && !open -> toast("Month ${m.number} opens on ${m.start.display()}")
                            data.isSelf -> nav.open(Routes.editor(studentId, data.template.code, id = rec?.submission?.id, period = m.number))
                            rec == null || status == SubmissionStatus.NOT_STARTED -> toast("The student hasn't started month ${m.number} yet")
                            else -> nav.open(Routes.editor(studentId, data.template.code, id = rec.submission.id, period = m.number))
                        }
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Month ${m.number}" + (if (m.number == current) " · current" else ""), style = MaterialTheme.typography.titleSmall)
                            Text("${m.start.display()} – ${m.end.display()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            rec?.submission?.studentData?.get("posting_unit")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (!open) {
                            Icon(Icons.Filled.Lock, "Locked", tint = AppTheme.status.notStarted)
                        } else {
                            StatusChip(status)
                        }
                    }
                    rec?.let { SyncBadge(it) }
                }
            }
        }
    }
}

@Composable
private fun AnnualList(data: RecordsData, studentId: String, toast: (String) -> Unit) {
    val nav = appNav
    val years = (1..(data.template.periodCount ?: 3)).toList()
    val byYear = data.items.filter { it.submission.periodNo != null }.associateBy { it.submission.periodNo!! }
    ScreenList {
        item {
            InfoCard {
                Text("Annual comprehensive review", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (data.isSelf) "Completed by your faculty at the end of each PG year. Completed reviews appear here."
                    else "Rate each domain 1–9 with comments. Completed reviews can be corrected only with a reason.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(years, key = { "y$it" }) { y ->
            val rec = byYear[y]
            val status = rec?.submission?.status ?: SubmissionStatus.NOT_STARTED
            InfoCard(
                modifier = Modifier.testTag("year:$y"),
                onClick = {
                    if (data.isSelf && rec == null) toast("PG Year $y review has not been completed yet")
                    else nav.open(Routes.editor(studentId, data.template.code, id = rec?.submission?.id, period = y))
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PG Year $y", style = MaterialTheme.typography.titleSmall)
                        rec?.submission?.reviewedByName?.let { Text("By $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    StatusChip(if (status == SubmissionStatus.COMPLETED) "Completed" else if (status == SubmissionStatus.NOT_STARTED) "Not started" else "In progress", if (status == SubmissionStatus.COMPLETED) Tone.APPROVED else if (status == SubmissionStatus.NOT_STARTED) Tone.NOT_STARTED else Tone.DRAFT)
                }
            }
        }
    }
}
