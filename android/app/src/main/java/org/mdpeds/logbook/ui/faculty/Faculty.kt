package org.mdpeds.logbook.ui.faculty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.builtins.ListSerializer
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.model.Assignment
import org.mdpeds.logbook.domain.model.AssignmentRole
import org.mdpeds.logbook.domain.model.Batch
import org.mdpeds.logbook.domain.model.ReviewQueueItem
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.SignoffStatus
import org.mdpeds.logbook.domain.model.StaffMember
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.UiState
import org.mdpeds.logbook.ui.common.anyOffline
import org.mdpeds.logbook.ui.common.enumLabel
import org.mdpeds.logbook.ui.common.formatDate
import org.mdpeds.logbook.ui.common.formatDateTime
import org.mdpeds.logbook.ui.common.overview
import org.mdpeds.logbook.ui.common.overviews
import org.mdpeds.logbook.ui.common.staffList
import org.mdpeds.logbook.ui.components.Banner
import org.mdpeds.logbook.ui.components.CollectMessages
import org.mdpeds.logbook.ui.components.ConfirmDialog
import org.mdpeds.logbook.ui.components.DateField
import org.mdpeds.logbook.ui.components.DropdownField
import org.mdpeds.logbook.ui.components.EmptyState
import org.mdpeds.logbook.ui.components.FilterRow
import org.mdpeds.logbook.ui.components.InfoCard
import org.mdpeds.logbook.ui.components.KeyValue
import org.mdpeds.logbook.ui.components.OfflineBanner
import org.mdpeds.logbook.ui.components.ProgressLine
import org.mdpeds.logbook.ui.components.ScreenList
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StatTile
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.components.StatusChip
import org.mdpeds.logbook.ui.components.TextInput
import org.mdpeds.logbook.ui.components.Tone
import org.mdpeds.logbook.ui.components.tone
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.Routes
import org.mdpeds.logbook.ui.nav.appNav
import javax.inject.Inject

/** Templates that count as workplace-based assessments. */
val ASSESSMENT_CODES = setOf("MINI_CEX", "DOPS")

fun ReviewQueueItem.route(): String =
    if (isSignoff) Routes.signoffs(studentId) else Routes.editor(studentId, templateCode, id = id, period = periodNo, slot = slotNo)

fun ReviewQueueItem.subtitle(): String = listOfNotNull(
    periodNo?.let { if (templateCode == "ANNUAL_REVIEW") "PG Year $it" else "Month $it" },
    slotNo?.let { "#$it" },
    entryDate?.let { formatDate(it) },
    submittedAt?.let { "submitted ${formatDateTime(it)}" },
).joinToString(" · ")

@Composable
fun QueueCard(q: ReviewQueueItem, showStudent: Boolean = true) {
    val nav = appNav
    InfoCard(onClick = { nav.open(q.route()) }, modifier = Modifier.testTag("queue:${q.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (showStudent) Text(q.studentName, style = MaterialTheme.typography.titleSmall)
                Text(if (q.isSignoff) "Competency sign-off · ${q.templateTitle}" else q.templateTitle, style = MaterialTheme.typography.bodyMedium)
                Text(q.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (q.isSignoff) StatusChip(SignoffStatus.from(q.status)) else StatusChip(SubmissionStatus.from(q.status))
            Icon(Icons.Filled.ChevronRight, null)
        }
    }
}

@Composable
fun StudentCard(s: StudentOverview) {
    val nav = appNav
    InfoCard(onClick = { nav.open(Routes.student(s.studentId)) }, modifier = Modifier.testTag("student:${s.studentId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.fullName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(s.batchName, s.pgYear?.let { "PGY-$it" }, s.currentMonth?.let { "Month $it" }, s.myAssignmentRole?.let(AssignmentRole::label)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!s.active) StatusChip("Inactive", Tone.NOT_STARTED)
            Icon(Icons.Filled.ChevronRight, null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.pendingReviews > 0) StatusChip("${s.pendingReviews} pending", Tone.PENDING)
            if (s.monthlyOverdueOrOpen > 0) StatusChip("${s.monthlyOverdueOrOpen} monthly open", Tone.RETURNED)
            StatusChip("${s.signoffsApproved}/${s.signoffsTotal} sign-offs", Tone.APPROVED)
            if (s.certificateNo != null) StatusChip("Certified", Tone.APPROVED)
        }
    }
}

// ================================================================== faculty dashboard

data class StaffHomeData(val students: List<StudentOverview>, val queue: List<ReviewQueueItem>)

@HiltViewModel
class StaffHomeViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, StaffHomeData>(repo) {
    override suspend fun load(args: String): Loaded<StaffHomeData> {
        val st = repo.overviews()
        val q = repo.cached("queue", ListSerializer(ReviewQueueItem.serializer())) { repo.backend.reviewQueue() }
        return Loaded(StaffHomeData(st.value.sortedBy { it.fullName }, q.value.sortedBy { it.submittedAt }), anyOffline(st, q))
    }
}

@Composable
fun FacultyHomeScreen() {
    val nav = appNav
    val vm: StaffHomeViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("faculty") }
    val state by vm.state.collectAsStateWithLifecycle()
    AppScreen(
        "Dashboard", subtitle = nav.session.fullName, topLevel = true,
        actions = { IconButton(onClick = { nav.open(Routes.NOTIFICATIONS) }) { Icon(Icons.Filled.Notifications, "Notifications") } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val assessments = d.queue.count { it.templateCode in ASSESSMENT_CODES }
                val signoffs = d.queue.count { it.isSignoff }
                ScreenList(Modifier.testTag("facultyHome")) {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile("My students", "${d.students.size}", Modifier.weight(1f), onClick = { nav.open(Routes.STUDENTS) })
                            StatTile("Pending reviews", "${d.queue.size}", Modifier.weight(1f), tone = if (d.queue.isEmpty()) null else Tone.PENDING, onClick = { nav.open(Routes.QUEUE) })
                            StatTile("Assessments", "$assessments", Modifier.weight(1f), onClick = { nav.open(Routes.ASSESSMENTS) })
                            StatTile("Sign-offs", "$signoffs", Modifier.weight(1f), onClick = { nav.open(Routes.ASSESSMENTS) })
                        }
                    }
                    item { SectionTitle("Oldest pending items") }
                    if (d.queue.isEmpty()) item { EmptyState("Nothing waiting for you", "Submitted entries from your students will appear here.") }
                    items(d.queue.take(6), key = { "q" + it.id }) { QueueCard(it) }
                    val attention = d.students.filter { it.monthlyOverdueOrOpen > 1 || it.pendingReviews > 0 }
                    if (attention.isNotEmpty()) {
                        item { SectionTitle("Students needing attention") }
                        items(attention.take(8), key = { "s" + it.studentId }) { StudentCard(it) }
                    }
                }
            }
        }
    }
}

// ================================================================== students list

@HiltViewModel
class StudentsViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, List<StudentOverview>>(repo) {
    override suspend fun load(args: String): Loaded<List<StudentOverview>> {
        val st = repo.overviews()
        return Loaded(st.value.sortedWith(compareBy({ !it.active }, { it.batchName }, { it.fullName })), st.offline)
    }
}

@Composable
fun StudentsScreen() {
    val nav = appNav
    val vm: StudentsViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("all") }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf(0) }
    var batch by rememberSaveable { mutableStateOf("") }
    AppScreen(if (nav.session.role == Role.HOD) "Students" else "My students", topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { all ->
                val batches = all.mapNotNull { it.batchName }.distinct().sorted()
                val shown = all.filter { s ->
                    (year == 0 || s.pgYear == year) && (batch.isBlank() || s.batchName == batch) &&
                        (query.isBlank() || s.fullName.contains(query, true) || s.universityRegNo?.contains(query, true) == true)
                }
                ScreenList {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    item { OutlinedTextField(query, { query = it }, label = { Text("Search by name or registration no.") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("studentSearch")) }
                    item { FilterRow(listOf(0 to "All years", 1 to "PGY-1", 2 to "PGY-2", 3 to "PGY-3"), year) { year = it } }
                    if (batches.size > 1) item { FilterRow(listOf("" to "All batches") + batches.map { it to it }, batch) { batch = it } }
                    if (shown.isEmpty()) item { EmptyState(if (all.isEmpty()) "No students assigned yet" else "No students match") }
                    items(shown, key = { it.studentId }) { StudentCard(it) }
                }
            }
        }
    }
}

// ================================================================== review queue

@HiltViewModel
class QueueViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, List<ReviewQueueItem>>(repo) {
    override suspend fun load(args: String): Loaded<List<ReviewQueueItem>> {
        val q = repo.cached("queue", ListSerializer(ReviewQueueItem.serializer())) { repo.backend.reviewQueue() }
        return Loaded(q.value.sortedBy { it.submittedAt }, q.offline)
    }
}

@Composable
fun ReviewQueueScreen(assessmentsOnly: Boolean) {
    val nav = appNav
    val vm: QueueViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("queue") }
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("ALL") }
    val title = when {
        assessmentsOnly -> "Assessments & sign-offs"
        nav.session.role == Role.HOD -> "Reviews & approvals"
        else -> "Pending approvals"
    }
    AppScreen(title, topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { all ->
                val base = if (assessmentsOnly) all.filter { it.isSignoff || it.templateCode in ASSESSMENT_CODES } else all
                val kinds = buildList {
                    add("ALL" to "All (${base.size})")
                    base.groupBy { if (it.isSignoff) "SIGNOFF" else it.templateCode }.forEach { (k, v) ->
                        add(k to "${if (k == "SIGNOFF") "Sign-offs" else v.first().templateTitle} (${v.size})")
                    }
                    if (nav.session.role == Role.HOD) {
                        val hod = base.count { !it.isSignoff && SubmissionStatus.from(it.status).isAwaitingHod }
                        if (hod > 0) add("HOD" to "Awaiting HOD ($hod)")
                    }
                }
                val shown = base.filter {
                    when (filter) {
                        "ALL" -> true
                        "SIGNOFF" -> it.isSignoff
                        "HOD" -> !it.isSignoff && SubmissionStatus.from(it.status).isAwaitingHod
                        else -> !it.isSignoff && it.templateCode == filter
                    }
                }
                ScreenList {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    if (kinds.size > 2) item { FilterRow(kinds, if (kinds.any { it.first == filter }) filter else "ALL") { filter = it } }
                    if (shown.isEmpty()) item { EmptyState("All caught up", "There is nothing waiting for your review.") }
                    items(shown, key = { it.kind + it.id }) { QueueCard(it) }
                }
            }
        }
    }
}

// ================================================================== student detail

data class StudentDetailData(
    val session: UserSession,
    val overview: StudentOverview,
    val assignments: List<Assignment>,
    val staff: List<StaffMember>,
    val batches: List<Batch>,
) {
    val isHod get() = session.role == Role.HOD
    fun name(id: String?) = staff.firstOrNull { it.id == id }?.fullName ?: "—"
}

@HiltViewModel
class StudentDetailViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, StudentDetailData>(repo) {
    override suspend fun load(args: String): Loaded<StudentDetailData> {
        val me = repo.requireSession()
        val ov = repo.overview(args)
        val staff = runCatching { repo.staffList() }.getOrNull()
        val asg = runCatching { repo.backend.assignments(args) }.getOrDefault(emptyList())
        val batches = if (me.role == Role.HOD) runCatching { repo.backend.batches() }.getOrDefault(emptyList()) else emptyList()
        return Loaded(StudentDetailData(me, ov.value, asg.filter { it.active }, staff?.value ?: emptyList(), batches), ov.offline)
    }

    fun assign(facultyId: String, role: String) = action(success = "Faculty assigned") { repo.online { assignFaculty(boundArgs, facultyId, role) } }
    fun unassign(facultyId: String) = action(success = "Assignment removed") { repo.online { unassignFaculty(boundArgs, facultyId) } }
    fun setStart(start: String, duration: Int?, reason: String) =
        action(success = "Programme start date updated") { repo.online { setProgrammeStart(boundArgs, start, duration, reason) } }
    fun updateDetails(data: Map<String, String?>) = action(success = "Details updated") { repo.online { updateStudentDetails(boundArgs, data) } }
    fun setActive(active: Boolean) = action(success = if (active) "Account activated" else "Account deactivated") { repo.online { setUserActive(boundArgs, active) } }
}

private enum class DetailDialog { NONE, ASSIGN, START, DETAILS, ACTIVE }

@Composable
fun StudentDetailScreen(studentId: String) {
    val nav = appNav
    val vm: StudentDetailViewModel = hiltViewModel()
    LaunchedEffect(studentId) { vm.bind(studentId) }
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(DetailDialog.NONE) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val data = (state as? UiState.Ready)?.value
    AppScreen(data?.overview?.fullName ?: "Student", subtitle = data?.overview?.batchName) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val o = d.overview
                ScreenList {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    if (!o.active) item { Banner("This account is deactivated. The student cannot sign in.", Tone.NOT_STARTED) }
                    item {
                        InfoCard {
                            KeyValue("Email", o.email)
                            KeyValue("University reg. no.", o.universityRegNo)
                            KeyValue("Guide", o.guideName ?: "Not assigned")
                            KeyValue("Current posting", o.currentPosting)
                            KeyValue("Programme", "${formatDate(o.programmeStartDate)} – ${formatDate(o.completionDate)}")
                            KeyValue("Status", o.enrolmentStatus?.let(::enumLabel))
                            o.currentMonth?.let { ProgressLine("Month $it · PGY-${o.pgYear ?: "—"}", it, o.durationMonths ?: 36) }
                            ProgressLine("Monthly assessments completed", o.monthlyCompleted, maxOf(o.currentMonth ?: 0, o.monthlyCompleted))
                            ProgressLine("Sign-offs approved", o.signoffsApproved, o.signoffsTotal)
                            ProgressLine("Annual reviews", o.annualReviewsCompleted, 3)
                            o.certificateNo?.let { KeyValue("Certificate", "$it · ${formatDate(o.certificateIssuedAt)}") }
                        }
                    }
                    item { SectionTitle("Logbook") }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            val links = listOf(
                                "Clinical logs" to Routes.category(studentId, "LOG"),
                                "Assessments & forms" to Routes.category(studentId, "FORM"),
                                "Reflections" to Routes.records(studentId, "REFLECTION"),
                                "Monthly" to Routes.records(studentId, "MONTHLY"),
                                "Annual review" to Routes.records(studentId, "ANNUAL_REVIEW"),
                                "Targets" to Routes.targets(studentId),
                                "Sign-offs" to Routes.signoffs(studentId),
                                "Certificate" to Routes.certificate(studentId),
                                "Feedback" to Routes.feedback(studentId),
                                "History" to Routes.audit(studentId),
                            )
                            links.forEach { (label, route) ->
                                OutlinedButton(onClick = { nav.open(route) }, modifier = Modifier.testTag("link:$label")) { Text(label) }
                            }
                        }
                    }
                    item {
                        InfoCard {
                            SectionTitle("Assigned faculty")
                            if (d.assignments.isEmpty() && o.guideId == null) Text("No faculty assigned.", style = MaterialTheme.typography.bodyMedium)
                            if (o.guideId != null && d.assignments.none { it.facultyId == o.guideId }) KeyValue("Guide", o.guideName)
                            d.assignments.forEach { a ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name(a.facultyId), style = MaterialTheme.typography.bodyLarge)
                                        Text(AssignmentRole.label(a.assignmentRole) + (a.assignedAt?.let { " · since ${formatDate(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (d.isHod) TextButton(onClick = { removing = a.facultyId }, enabled = !busy) { Text("Remove") }
                                }
                            }
                            if (d.isHod) Button(onClick = { dialog = DetailDialog.ASSIGN }, enabled = !busy, modifier = Modifier.testTag("assignFaculty")) { Text("Assign faculty") }
                        }
                    }
                    if (d.isHod) item {
                        InfoCard {
                            SectionTitle("HOD actions")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { dialog = DetailDialog.START }, enabled = !busy, modifier = Modifier.testTag("setStart")) { Text("Programme start date") }
                                OutlinedButton(onClick = { dialog = DetailDialog.DETAILS }, enabled = !busy) { Text("Edit details") }
                                OutlinedButton(onClick = { dialog = DetailDialog.ACTIVE }, enabled = !busy) { Text(if (o.active) "Deactivate" else "Activate") }
                            }
                        }
                    }
                }
            }
        }
    }
    val d = data ?: return
    when (dialog) {
        DetailDialog.NONE -> Unit
        DetailDialog.ASSIGN -> AssignDialog(d, onDismiss = { dialog = DetailDialog.NONE }) { f, r -> dialog = DetailDialog.NONE; vm.assign(f, r) }
        DetailDialog.START -> StartDialog(d, onDismiss = { dialog = DetailDialog.NONE }) { s, m, r -> dialog = DetailDialog.NONE; vm.setStart(s, m, r) }
        DetailDialog.DETAILS -> DetailsDialog(d, onDismiss = { dialog = DetailDialog.NONE }) { dialog = DetailDialog.NONE; vm.updateDetails(it) }
        DetailDialog.ACTIVE -> ConfirmDialog(
            title = if (d.overview.active) "Deactivate account?" else "Activate account?",
            message = if (d.overview.active) "${d.overview.fullName} will no longer be able to sign in. Records are kept." else "${d.overview.fullName} will be able to sign in again.",
            confirm = if (d.overview.active) "Deactivate" else "Activate",
            onDismiss = { dialog = DetailDialog.NONE },
            onConfirm = { dialog = DetailDialog.NONE; vm.setActive(!d.overview.active) },
        )
    }
    removing?.let { fid ->
        ConfirmDialog(
            title = "Remove assignment?", message = "${d.name(fid)} will no longer review this student's logbook.", confirm = "Remove",
            onDismiss = { removing = null }, onConfirm = { removing = null; vm.unassign(fid) },
        )
    }
}

@Composable
private fun AssignDialog(d: StudentDetailData, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var faculty by rememberSaveable { mutableStateOf<String?>(null) }
    var role by rememberSaveable { mutableStateOf(AssignmentRole.UNIT_FACULTY) }
    val options = d.staff.filter { it.active && it.role != Role.STUDENT && d.assignments.none { a -> a.facultyId == it.id } }
        .map { it.id to (it.fullName + (it.designation?.let { x -> " · $x" } ?: "")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign faculty") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Faculty member", faculty, options, { faculty = it })
                DropdownField("Role", role, AssignmentRole.all.map { it to AssignmentRole.label(it) }, { role = it })
                if (role == AssignmentRole.GUIDE) Text("Assigning a guide replaces the current guide.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { faculty?.let { onConfirm(it, role) } }, enabled = faculty != null) { Text("Assign") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StartDialog(d: StudentDetailData, onDismiss: () -> Unit, onConfirm: (String, Int?, String) -> Unit) {
    var start by rememberSaveable { mutableStateOf(d.overview.programmeStartDate?.take(10).orEmpty()) }
    var months by rememberSaveable { mutableStateOf((d.overview.durationMonths ?: 36).toString()) }
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Programme start date") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The official start date drives the 36 monthly assessments, annual reviews and completion date.", style = MaterialTheme.typography.bodySmall)
                DateField("Start date", start, { start = it })
                TextInput("Duration (months)", months, { months = it.filter(Char::isDigit).take(2) }, keyboardType = KeyboardType.Number)
                TextInput("Reason (recorded in the audit log)", reason, { reason = it }, singleLine = false)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(start, months.toIntOrNull(), reason.trim()) }, enabled = start.isNotBlank() && reason.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailsDialog(d: StudentDetailData, onDismiss: () -> Unit, onConfirm: (Map<String, String?>) -> Unit) {
    val o = d.overview
    var name by rememberSaveable { mutableStateOf(o.fullName) }
    var reg by rememberSaveable { mutableStateOf(o.universityRegNo.orEmpty()) }
    var inst by rememberSaveable { mutableStateOf(o.institution.orEmpty()) }
    var uni by rememberSaveable { mutableStateOf(o.university.orEmpty()) }
    var posting by rememberSaveable { mutableStateOf(o.currentPosting.orEmpty()) }
    var batch by rememberSaveable { mutableStateOf(o.batchId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Student details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Full name", name, { name = it })
                TextInput("University registration no.", reg, { reg = it })
                TextInput("Institution", inst, { inst = it })
                TextInput("University", uni, { uni = it })
                TextInput("Current posting", posting, { posting = it })
                if (d.batches.isNotEmpty()) DropdownField("Batch", batch, d.batches.map { it.id to "${it.name} (${it.academicYear})" }, { batch = it }, allowClear = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        mapOf(
                            "full_name" to name.trim(),
                            "university_reg_no" to reg.trim().ifBlank { null },
                            "institution" to inst.trim().ifBlank { null },
                            "university" to uni.trim().ifBlank { null },
                            "current_posting" to posting.trim().ifBlank { null },
                            "batch_id" to batch?.ifBlank { null },
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
