package org.mdpeds.logbook.ui.student

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.data.RecordItem
import org.mdpeds.logbook.domain.logic.MonthlyPriority
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.model.CertificationState
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.TemplateCategory
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.UiState
import org.mdpeds.logbook.ui.common.formatDate
import org.mdpeds.logbook.ui.common.overview
import org.mdpeds.logbook.ui.components.Banner
import org.mdpeds.logbook.ui.components.InfoCard
import org.mdpeds.logbook.ui.components.KeyValue
import org.mdpeds.logbook.ui.components.OfflineBanner
import org.mdpeds.logbook.ui.components.ProgressLine
import org.mdpeds.logbook.ui.components.ScreenList
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StatTile
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.components.StatusChip
import org.mdpeds.logbook.ui.components.Tone
import org.mdpeds.logbook.ui.components.tone
import org.mdpeds.logbook.ui.forms.recordTitle
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.Routes
import org.mdpeds.logbook.ui.nav.appNav
import javax.inject.Inject

data class StudentHomeData(
    val overview: StudentOverview,
    val reference: ReferenceData,
    val records: List<RecordItem>,
    val certification: CertificationState?,
) {
    val month: Int? get() = overview.currentMonth?.takeIf { it > 0 }
    val currentMonthly: RecordItem? get() = month?.let { m -> records.firstOrNull { it.submission.templateCode == "MONTHLY" && it.submission.periodNo == m } }
    val returned: List<RecordItem> get() = records.filter { it.submission.status == SubmissionStatus.RETURNED }
    val drafts: List<RecordItem> get() = records.filter { it.submission.status == SubmissionStatus.DRAFT }
    val pendingReview: Int get() = records.count { it.submission.status.isAwaitingFaculty || it.submission.status.isAwaitingHod }
    val notSynced: Int get() = records.count { it.isLocalOnly }
    val priorities: List<MonthlyPriority> get() = month?.let { m -> reference.monthlyPriorities.filter { it.covers(m) } } ?: emptyList()
}

@HiltViewModel
class StudentHomeViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, StudentHomeData>(repo) {
    override suspend fun load(args: String): Loaded<StudentHomeData> {
        val ov = repo.overview(args)
        val recs = repo.records(args)
        val cert = runCatching { repo.backend.certificationStatus(args) }.getOrNull()
        return Loaded(StudentHomeData(ov.value, repo.reference(), recs.value, cert), ov.offline || recs.offline)
    }
}

@Composable
fun StudentHomeScreen() {
    val nav = appNav
    val me = nav.session
    val vm: StudentHomeViewModel = hiltViewModel()
    LaunchedEffect(me.userId) { vm.bind(me.userId) }
    val state by vm.state.collectAsStateWithLifecycle()
    AppScreen(
        title = "My logbook",
        subtitle = me.fullName,
        topLevel = true,
        actions = { IconButton(onClick = { nav.open(Routes.NOTIFICATIONS) }) { Icon(Icons.Filled.Notifications, "Notifications") } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val offline = (state as? UiState.Ready)?.offline == true
                ScreenList(Modifier.testTag("studentHome")) {
                    if (offline) item { OfflineBanner() }
                    item { ProgrammeCard(d) }
                    if (d.overview.programmeStartDate == null) item {
                        Banner("Your programme start date has not been set yet. Monthly assessments will open once the HOD sets it.", Tone.PENDING, title = "Programme not started")
                    }
                    d.month?.let { m ->
                        item {
                            val rec = d.currentMonthly
                            InfoCard(onClick = { nav.open(Routes.editor(me.userId, "MONTHLY", id = rec?.submission?.id, period = m)) }, modifier = Modifier.testTag("currentMonth")) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Monthly assessment · Month $m", style = MaterialTheme.typography.titleMedium)
                                        Text("Complete your self-assessment for this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    StatusChip(rec?.submission?.status ?: SubmissionStatus.NOT_STARTED)
                                }
                            }
                        }
                    }
                    if (d.priorities.isNotEmpty()) item {
                        Banner(d.priorities.joinToString("\n") { it.priority }, Tone.DRAFT, title = "This month's training priorities")
                    }
                    if (d.returned.isNotEmpty()) {
                        item { SectionTitle("Returned for correction") }
                        items(d.returned, key = { "r" + it.submission.id }) { r -> AttentionRow(d, r) }
                    }
                    if (d.drafts.isNotEmpty()) {
                        item { SectionTitle("Drafts not yet submitted") }
                        items(d.drafts.take(5), key = { "d" + it.submission.id }) { r -> AttentionRow(d, r) }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile("Awaiting review", "${d.pendingReview}", Modifier.weight(1f), tone = Tone.PENDING, onClick = { nav.open(Routes.category(me.userId, "LOG")) })
                            StatTile("Returned", "${d.returned.size}", Modifier.weight(1f), tone = if (d.returned.isEmpty()) null else Tone.RETURNED)
                            StatTile("Offline", "${d.notSynced}", Modifier.weight(1f), onClick = { nav.open(Routes.SYNC) })
                        }
                    }
                    item { ProgressCard(d) }
                    item { QuickActions(me.userId) }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCard(d: StudentHomeData) {
    val o = d.overview
    InfoCard {
        Text(o.fullName, style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(o.batchName, o.universityRegNo?.let { "Reg. $it" }).joinToString(" · ").ifBlank { "MD Pediatrics" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val duration = o.durationMonths ?: 36
        d.month?.let { ProgressLine("Programme month $it of $duration · PG Year ${o.pgYear ?: "—"}", it, duration) }
        KeyValue("Guide", o.guideName ?: "Not assigned")
        KeyValue("Current posting", o.currentPosting)
        KeyValue("Programme", "${formatDate(o.programmeStartDate)} – ${formatDate(o.completionDate)}")
    }
}

@Composable
private fun AttentionRow(d: StudentHomeData, r: RecordItem) {
    val nav = appNav
    val t = d.reference.template(r.submission.templateCode) ?: return
    InfoCard(onClick = { nav.open(Routes.editor(r.submission.studentId, t.code, id = r.submission.id, period = r.submission.periodNo, slot = r.submission.slotNo)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(recordTitle(t, r.submission, d.reference), style = MaterialTheme.typography.titleSmall)
                r.submission.returnReason?.takeIf { r.submission.status == SubmissionStatus.RETURNED }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
            }
            StatusChip(r.submission.status)
            Icon(Icons.Filled.ChevronRight, null)
        }
    }
}

@Composable
private fun ProgressCard(d: StudentHomeData) {
    val nav = appNav
    val o = d.overview
    val me = nav.session.userId
    InfoCard(onClick = { nav.open(Routes.targets(me)) }) {
        SectionTitle("Progress")
        ProgressLine("Monthly assessments completed", o.monthlyCompleted, maxOf(d.month ?: 0, o.monthlyCompleted), detail = if (o.monthlyOverdueOrOpen > 0) "${o.monthlyOverdueOrOpen} open or overdue" else null)
        ProgressLine("Competency sign-offs approved", o.signoffsApproved, o.signoffsTotal)
        ProgressLine("Annual reviews completed", o.annualReviewsCompleted, 3)
        val logs = d.records.count { r -> d.reference.template(r.submission.templateCode)?.category == TemplateCategory.LOG && r.submission.status != SubmissionStatus.NOT_STARTED }
        KeyValue("Clinical log entries", "$logs")
        d.certification?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Certificate", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                StatusChip(c.status.label, c.status.tone())
            }
        }
        Text("Tap for targets and detailed progress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun QuickActions(me: String) {
    val nav = appNav
    InfoCard {
        SectionTitle("Quick entry")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "CLINICAL_CASE" to "Clinical case",
                "NEONATAL_CASE" to "Neonatal case",
                "PICU_CASE" to "PICU case",
                "PROCEDURE" to "Procedure",
                "ACADEMIC_ACTIVITY" to "Academic activity",
                "TEACHING" to "Teaching",
                "REFLECTION" to "Reflection",
            ).forEach { (code, label) ->
                AssistChip(onClick = { nav.open(Routes.editor(me, code)) }, label = { Text(label) }, modifier = Modifier.testTag("quick:$code"))
            }
            AssistChip(onClick = { nav.open(Routes.records(me, "MINI_CEX")) }, label = { Text("Mini-CEX") })
            AssistChip(onClick = { nav.open(Routes.records(me, "DOPS")) }, label = { Text("DOPS") })
            AssistChip(onClick = { nav.open(Routes.records(me, "DISSERTATION_MILESTONE")) }, label = { Text("Dissertation") })
        }
    }
}
