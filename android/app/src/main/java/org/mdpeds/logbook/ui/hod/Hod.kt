package org.mdpeds.logbook.ui.hod

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.model.AcademicDocument
import org.mdpeds.logbook.domain.model.Batch
import org.mdpeds.logbook.domain.model.Competency
import org.mdpeds.logbook.domain.model.InstitutionSettings
import org.mdpeds.logbook.domain.model.Invitation
import org.mdpeds.logbook.domain.model.InvitationRequest
import org.mdpeds.logbook.domain.model.ReviewQueueItem
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.StaffMember
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.Target
import org.mdpeds.logbook.domain.model.TargetCategory
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.UiState
import org.mdpeds.logbook.ui.common.anyOffline
import org.mdpeds.logbook.ui.common.formatDate
import org.mdpeds.logbook.ui.common.formatDateTime
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
import org.mdpeds.logbook.ui.components.ScreenList
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StatTile
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.components.StatusChip
import org.mdpeds.logbook.ui.components.TextInput
import org.mdpeds.logbook.ui.components.Tone
import org.mdpeds.logbook.ui.faculty.ASSESSMENT_CODES
import org.mdpeds.logbook.ui.faculty.QueueCard
import org.mdpeds.logbook.ui.faculty.StaffHomeViewModel
import org.mdpeds.logbook.ui.faculty.StudentCard
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.Routes
import org.mdpeds.logbook.ui.nav.appNav
import org.mdpeds.logbook.ui.screens.FileEvent
import org.mdpeds.logbook.ui.screens.FileEventsHandler
import org.mdpeds.logbook.util.Files
import org.mdpeds.logbook.util.PdfRenderer
import java.time.LocalDate
import javax.inject.Inject

private val PrettyJson = Json { prettyPrint = true }

// ================================================================== HOD dashboard

@Composable
fun HodHomeScreen() {
    val nav = appNav
    val vm: StaffHomeViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("hod") }
    val state by vm.state.collectAsStateWithLifecycle()
    AppScreen(
        "Department dashboard", subtitle = nav.session.fullName, topLevel = true,
        actions = { IconButton(onClick = { nav.open(Routes.NOTIFICATIONS) }) { Icon(Icons.Filled.Notifications, "Notifications") } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val active = d.students.filter { it.active }
                val awaitingHod = d.queue.count { !it.isSignoff && SubmissionStatus.from(it.status).isAwaitingHod }
                val monthlyOpen = active.count { it.monthlyOverdueOrOpen > 1 }
                val certified = active.count { it.certificateNo != null }
                ScreenList(Modifier.testTag("hodHome")) {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile("Active PG students", "${active.size}", Modifier.weight(1f), onClick = { nav.open(Routes.STUDENTS) })
                            StatTile("Pending reviews", "${d.queue.size}", Modifier.weight(1f), tone = if (d.queue.isEmpty()) null else Tone.PENDING, onClick = { nav.open(Routes.QUEUE) })
                            StatTile("Awaiting HOD", "$awaitingHod", Modifier.weight(1f), tone = if (awaitingHod == 0) null else Tone.PENDING, onClick = { nav.open(Routes.QUEUE) })
                            StatTile("Monthly backlog", "$monthlyOpen", Modifier.weight(1f), tone = if (monthlyOpen == 0) null else Tone.RETURNED, onClick = { nav.open(Routes.MONITORING) })
                            StatTile("Certified", "$certified", Modifier.weight(1f), tone = Tone.APPROVED)
                        }
                    }
                    item {
                        InfoCard {
                            SectionTitle("Students by PG year")
                            (1..3).forEach { y -> KeyValue("PGY-$y", "${active.count { it.pgYear == y }}") }
                            KeyValue("Not started / completed", "${active.count { it.pgYear == null }}")
                        }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "Monitoring" to Routes.MONITORING, "Faculty" to Routes.FACULTY, "Invitations" to Routes.INVITATIONS,
                                "Administration" to Routes.ADMIN, "Reports" to Routes.REPORTS, "Audit log" to Routes.audit(),
                            ).forEach { (label, route) -> OutlinedButton(onClick = { nav.open(route) }) { Text(label) } }
                        }
                    }
                    val hodItems = d.queue.filter { !it.isSignoff && SubmissionStatus.from(it.status).isAwaitingHod }
                    if (hodItems.isNotEmpty()) {
                        item { SectionTitle("Awaiting your review") }
                        items(hodItems.take(6), key = { "h" + it.id }) { QueueCard(it) }
                    }
                }
            }
        }
    }
}

// ================================================================== monitoring

private enum class Watch(val label: String) { ALL("All"), MONTHLY("Monthly backlog"), REVIEWS("Pending reviews"), SIGNOFFS("Sign-offs incomplete"), CERTIFIED("Certified") }

@Composable
fun MonitoringScreen() {
    val vm: StaffHomeViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("monitoring") }
    val state by vm.state.collectAsStateWithLifecycle()
    var watch by rememberSaveable { mutableStateOf(Watch.ALL) }
    AppScreen("Monitoring", subtitle = "Logbook progress across the department", topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val students = d.students.filter { it.active }
                val shown = when (watch) {
                    Watch.ALL -> students
                    Watch.MONTHLY -> students.filter { it.monthlyOverdueOrOpen > 1 }
                    Watch.REVIEWS -> students.filter { it.pendingReviews > 0 }
                    Watch.SIGNOFFS -> students.filter { it.signoffsTotal > 0 && it.signoffsApproved < it.signoffsTotal }
                    Watch.CERTIFIED -> students.filter { it.certificateNo != null }
                }.sortedWith(compareByDescending<StudentOverview> { it.monthlyOverdueOrOpen }.thenByDescending { it.pendingReviews }.thenBy { it.fullName })
                ScreenList {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    item { FilterRow(Watch.entries.map { it to it.label }, watch) { watch = it } }
                    item {
                        Text(
                            "Monthly backlog counts months that are open or overdue beyond the current one. Pending reviews are entries waiting for faculty or HOD.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (shown.isEmpty()) item { EmptyState("No students in this view") }
                    items(shown, key = { it.studentId }) { StudentCard(it) }
                }
            }
        }
    }
}

// ================================================================== faculty management

data class FacultyData(val staff: List<StaffMember>, val students: List<StudentOverview>)

@HiltViewModel
class FacultyManagementViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, FacultyData>(repo) {
    override suspend fun load(args: String): Loaded<FacultyData> {
        val s = repo.staffList()
        val o = repo.overviews()
        return Loaded(FacultyData(s.value.filter { it.role != Role.STUDENT }.sortedWith(compareBy({ !it.active }, { it.role != Role.HOD }, { it.fullName })), o.value), anyOffline(s, o))
    }

    fun save(id: String, designation: String?, unit: String?, head: Boolean) =
        action(success = "Profile updated") { repo.online { updateFacultyProfile(id, designation, unit, head) } }

    fun setActive(id: String, active: Boolean) =
        action(success = if (active) "Account activated" else "Account deactivated") { repo.online { setUserActive(id, active) } }
}

@Composable
fun FacultyManagementScreen() {
    val nav = appNav
    val vm: FacultyManagementViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("faculty") }
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<StaffMember?>(null) }
    var toggling by remember { mutableStateOf<StaffMember?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    AppScreen(
        "Faculty", topLevel = true,
        fab = { ExtendedFloatingActionButton(onClick = { nav.open(Routes.INVITATIONS) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Invite") }) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val shown = d.staff.filter { query.isBlank() || it.fullName.contains(query, true) || it.unit?.contains(query, true) == true }
                ScreenList {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    item { OutlinedTextField(query, { query = it }, label = { Text("Search by name or unit") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    if (shown.isEmpty()) item { EmptyState("No faculty found") }
                    items(shown, key = { it.id }) { f ->
                        val guided = d.students.count { it.guideId == f.id && it.active }
                        InfoCard(modifier = Modifier.testTag("staff:${f.id}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.fullName, style = MaterialTheme.typography.titleMedium)
                                    Text(listOfNotNull(f.designation, f.unit, if (f.isUnitHead == true) "Unit head" else null).joinToString(" · ").ifBlank { f.role.label }, style = MaterialTheme.typography.bodySmall)
                                    Text(f.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (f.role == Role.HOD) StatusChip("HOD", Tone.APPROVED)
                                if (!f.active) StatusChip("Inactive", Tone.NOT_STARTED)
                            }
                            if (guided > 0) Text("Guide for $guided student(s)", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editing = f }, enabled = !busy) { Text("Edit") }
                                if (f.id != nav.session.userId) TextButton(onClick = { toggling = f }, enabled = !busy) { Text(if (f.active) "Deactivate" else "Activate") }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { f -> FacultyDialog(f, onDismiss = { editing = null }) { des, unit, head -> editing = null; vm.save(f.id, des, unit, head) } }
    toggling?.let { f ->
        ConfirmDialog(
            title = if (f.active) "Deactivate ${f.fullName}?" else "Activate ${f.fullName}?",
            message = if (f.active) "They will no longer be able to sign in or review. Their past reviews remain in the logbooks." else "They will be able to sign in again.",
            confirm = if (f.active) "Deactivate" else "Activate",
            onDismiss = { toggling = null }, onConfirm = { toggling = null; vm.setActive(f.id, !f.active) },
        )
    }
}

@Composable
private fun FacultyDialog(f: StaffMember, onDismiss: () -> Unit, onConfirm: (String?, String?, Boolean) -> Unit) {
    var designation by rememberSaveable { mutableStateOf(f.designation.orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(f.unit.orEmpty()) }
    var head by rememberSaveable { mutableStateOf(f.isUnitHead == true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.fullName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Designation", designation, { designation = it })
                TextInput("Unit", unit, { unit = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unit head", Modifier.weight(1f))
                    Switch(checked = head, onCheckedChange = { head = it })
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(designation.trim().ifBlank { null }, unit.trim().ifBlank { null }, head) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================== invitations

data class InvitationsData(val invitations: List<Invitation>, val batches: List<Batch>, val staff: List<StaffMember>)

@HiltViewModel
class InvitationsViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, InvitationsData>(repo) {
    override suspend fun load(args: String): Loaded<InvitationsData> {
        val inv = repo.online { invitations() }
        val batches = runCatching { repo.backend.batches() }.getOrDefault(emptyList())
        val staff = runCatching { repo.staffList().value }.getOrDefault(emptyList())
        return fresh(InvitationsData(inv.sortedByDescending { it.createdAt }, batches, staff.filter { it.active && it.role != Role.STUDENT }))
    }

    fun create(r: InvitationRequest, onDone: () -> Unit) =
        action(success = "Invitation sent to ${r.email}", onSuccess = onDone) { repo.online { createInvitation(r) } }

    fun revoke(id: String) = action(success = "Invitation revoked") { repo.online { revokeInvitation(id) } }
}

@Composable
fun InvitationsScreen() {
    val nav = appNav
    val vm: InvitationsViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("invitations") }
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<Invitation?>(null) }
    AppScreen(
        "Invitations", topLevel = true,
        fab = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Invite user") }, modifier = Modifier.testTag("newInvite")) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                ScreenList {
                    item {
                        Text(
                            "Accounts are created only by invitation. The invitee receives an email link to set a password; students are enrolled with the batch, start date and guide given here.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (d.invitations.isEmpty()) item { EmptyState("No invitations yet") }
                    items(d.invitations, key = { it.id }) { i ->
                        InfoCard(modifier = Modifier.testTag("invite:${i.email}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(i.fullName, style = MaterialTheme.typography.titleMedium)
                                    Text("${i.email} · ${i.role.label}", style = MaterialTheme.typography.bodySmall)
                                    i.createdAt?.let { Text("Sent ${formatDateTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                StatusChip(
                                    i.status.lowercase().replaceFirstChar { it.uppercase() },
                                    when (i.status) { "ACCEPTED" -> Tone.APPROVED; "PENDING" -> Tone.PENDING; else -> Tone.NOT_STARTED },
                                )
                            }
                            if (i.role == Role.STUDENT) {
                                KeyValue("Batch", d.batches.firstOrNull { it.id == i.batchId }?.name)
                                KeyValue("Programme start", formatDate(i.programmeStartDate))
                                KeyValue("Guide", d.staff.firstOrNull { it.id == i.guideId }?.fullName)
                            }
                            if (i.status == "PENDING") TextButton(onClick = { revoking = i }, enabled = !busy) { Text("Revoke") }
                        }
                    }
                }
            }
        }
    }
    val d = (state as? UiState.Ready)?.value
    if (creating && d != null) InviteDialog(d, busy, onDismiss = { creating = false }) { vm.create(it) { creating = false } }
    revoking?.let { i ->
        ConfirmDialog(
            "Revoke invitation?", "The link sent to ${i.email} will stop working.", "Revoke",
            onDismiss = { revoking = null }, onConfirm = { revoking = null; vm.revoke(i.id) },
        )
    }
}

@Composable
private fun InviteDialog(d: InvitationsData, busy: Boolean, onDismiss: () -> Unit, onConfirm: (InvitationRequest) -> Unit) {
    var role by rememberSaveable { mutableStateOf(Role.STUDENT.name) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var designation by rememberSaveable { mutableStateOf("") }
    var reg by rememberSaveable { mutableStateOf("") }
    var batch by rememberSaveable { mutableStateOf<String?>(d.batches.maxByOrNull { it.intakeDate.orEmpty() }?.id) }
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    var guide by rememberSaveable { mutableStateOf<String?>(null) }
    val student = role == Role.STUDENT.name
    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite user") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Role", role, Role.entries.map { it.name to it.label }, { role = it })
                TextInput("Full name", name, { name = it })
                TextInput("Email", email, { email = it }, keyboardType = KeyboardType.Email, error = if (email.isNotBlank() && !emailOk) "Enter a valid email" else null)
                if (student) {
                    TextInput("University registration no.", reg, { reg = it })
                    DropdownField("Batch", batch, d.batches.map { it.id to "${it.name} (${it.academicYear})" }, { batch = it.ifBlank { null } }, allowClear = true)
                    DateField("Programme start date", start, { start = it }, supporting = "Official start; drives the 36-month timeline")
                    DropdownField("Guide", guide, d.staff.map { it.id to it.fullName }, { guide = it.ifBlank { null } }, allowClear = true)
                } else {
                    TextInput("Designation", designation, { designation = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        InvitationRequest(
                            email = email.trim().lowercase(), fullName = name.trim(), role = Role.valueOf(role),
                            designation = designation.trim().ifBlank { null }.takeIf { !student },
                            universityRegNo = reg.trim().ifBlank { null }.takeIf { student },
                            batchId = batch.takeIf { student }, programmeStartDate = start.takeIf { student }, guideId = guide.takeIf { student },
                        ),
                    )
                },
                enabled = !busy && emailOk && name.isNotBlank(),
                modifier = Modifier.testTag("sendInvite"),
            ) { Text("Send invitation") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================== administration

data class AdminData(
    val settings: InstitutionSettings,
    val batches: List<Batch>,
    val targets: List<Target>,
    val competencies: List<Competency>,
    val documents: List<AcademicDocument>,
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    repo: LogbookRepository,
    @ApplicationContext private val context: Context,
) : LoadViewModel<String, AdminData>(repo) {
    override suspend fun load(args: String): Loaded<AdminData> {
        val settings = repo.online { settings() }
        val batches = repo.backend.batches()
        val ref = repo.refreshReference()
        return fresh(AdminData(settings, batches.sortedByDescending { it.intakeDate.orEmpty() }, ref.targets.sortedBy { it.sortOrder }, ref.competencies.sortedBy { it.sortOrder }, ref.documents.sortedBy { it.sortOrder }))
    }

    fun saveSettings(data: JsonObject) = action(success = "Settings saved") { repo.online { updateSettings(data) } }

    fun saveBatch(id: String?, name: String, year: String, intake: String?, onDone: () -> Unit) =
        action(success = "Batch saved", onSuccess = onDone) { repo.online { upsertBatch(id, name, year, intake) } }

    fun saveTarget(key: String, value: Int, notes: String?) = action(success = "Target updated (new version)") {
        repo.online { setTarget(key, value, notes) }
        repo.refreshReference()
    }

    fun saveCompetency(code: String, title: String, domain: String, progression: String?) = action(success = "Competency updated") {
        repo.online { updateCompetency(code, title, domain, progression) }
        repo.refreshReference()
    }

    fun publishJson(code: String, text: String, notes: String?, onDone: () -> Unit) = action(success = "New version published", onSuccess = onDone) {
        val content = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("The content is not a valid JSON object.")
        }
        repo.online { publishDocument(code, content, null, null, notes) }
        repo.refreshReference()
    }

    fun publishPdf(code: String, uri: Uri, notes: String?, onDone: () -> Unit) = action(success = "PDF version published", onSuccess = onDone) {
        val picked = withContext(Dispatchers.IO) { Files.read(context, uri) }
        require(picked.mime == "application/pdf" || picked.name.endsWith(".pdf", true)) { "Please choose a PDF file." }
        repo.online { publishDocument(code, null, picked.bytes, picked.name, notes) }
        repo.refreshReference()
    }
}

private enum class AdminTab(val label: String) { SETTINGS("Institution"), BATCHES("Batches"), TARGETS("Targets"), COMPETENCIES("Competencies"), DOCUMENTS("Documents") }

@Composable
fun AdminScreen() {
    val nav = appNav
    val vm: AdminViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("admin") }
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AdminTab.SETTINGS) }
    var batchEdit by remember { mutableStateOf<Batch?>(null) }
    var newBatch by rememberSaveable { mutableStateOf(false) }
    var targetEdit by remember { mutableStateOf<Target?>(null) }
    var compEdit by remember { mutableStateOf<Competency?>(null) }
    var docEdit by remember { mutableStateOf<AcademicDocument?>(null) }
    AppScreen("Administration", topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                ScreenList {
                    item { FilterRow(AdminTab.entries.map { it to it.label }, tab) { tab = it } }
                    when (tab) {
                        AdminTab.SETTINGS -> item { SettingsForm(d.settings, busy) { vm.saveSettings(it) } }
                        AdminTab.BATCHES -> {
                            item { Button(onClick = { newBatch = true }, enabled = !busy) { Text("Add batch") } }
                            if (d.batches.isEmpty()) item { EmptyState("No batches yet") }
                            items(d.batches, key = { it.id }) { b ->
                                InfoCard(onClick = { batchEdit = b }) {
                                    Text(b.name, style = MaterialTheme.typography.titleMedium)
                                    Text("Academic year ${b.academicYear} · intake ${formatDate(b.intakeDate)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        AdminTab.TARGETS -> {
                            item { Banner("Changing a target creates a new version. Progress screens use the latest version.", Tone.PENDING) }
                            listOf(TargetCategory.EXPOSURE to "Clinical exposure", TargetCategory.PROCEDURE to "Procedures").forEach { (cat, label) ->
                                item { SectionTitle(label) }
                                items(d.targets.filter { it.category == cat }, key = { it.key }) { t ->
                                    InfoCard(onClick = { targetEdit = t }) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                            Text("${t.targetValue}", style = MaterialTheme.typography.titleMedium)
                                        }
                                        Text("Version ${t.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        AdminTab.COMPETENCIES -> {
                            d.competencies.groupBy { it.domain }.forEach { (domain, list) ->
                                item { SectionTitle(domain) }
                                items(list, key = { it.code }) { c ->
                                    InfoCard(onClick = { compEdit = c }) {
                                        Text("${c.code} · ${c.title}", style = MaterialTheme.typography.bodyLarge)
                                        Text(c.progression, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        AdminTab.DOCUMENTS -> {
                            item { Text("Publishing creates a new version. Earlier versions are kept for the audit trail.", style = MaterialTheme.typography.bodySmall) }
                            items(d.documents, key = { it.code }) { doc ->
                                InfoCard(onClick = { docEdit = doc }) {
                                    Text(doc.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOfNotNull("Version ${doc.versionNo}", doc.publishedAt?.let { "published ${formatDate(it)}" }, doc.fileName).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (newBatch || batchEdit != null) {
        BatchDialog(batchEdit, onDismiss = { newBatch = false; batchEdit = null }) { name, year, intake ->
            vm.saveBatch(batchEdit?.id, name, year, intake) { newBatch = false; batchEdit = null }
        }
    }
    targetEdit?.let { t -> TargetDialog(t, onDismiss = { targetEdit = null }) { v, n -> targetEdit = null; vm.saveTarget(t.key, v, n) } }
    compEdit?.let { c -> CompetencyDialog(c, onDismiss = { compEdit = null }) { ti, dom, pr -> compEdit = null; vm.saveCompetency(c.code, ti, dom, pr) } }
    docEdit?.let { doc ->
        DocumentDialog(
            doc, busy, onDismiss = { docEdit = null },
            onJson = { text, notes -> vm.publishJson(doc.code, text, notes) { docEdit = null } },
            onPdf = { uri, notes -> vm.publishPdf(doc.code, uri, notes) { docEdit = null } },
        )
    }
}

@Composable
private fun SettingsForm(s: InstitutionSettings, busy: Boolean, onSave: (JsonObject) -> Unit) {
    var institution by rememberSaveable(s) { mutableStateOf(s.institutionName) }
    var department by rememberSaveable(s) { mutableStateOf(s.departmentName) }
    var university by rememberSaveable(s) { mutableStateOf(s.universityName) }
    var dean by rememberSaveable(s) { mutableStateOf(s.deanName.orEmpty()) }
    var deanTitle by rememberSaveable(s) { mutableStateOf(s.deanTitle) }
    var duration by rememberSaveable(s) { mutableStateOf(s.programmeDurationMonths.toString()) }
    var prefix by rememberSaveable(s) { mutableStateOf(s.certificatePrefix) }
    var future by rememberSaveable(s) { mutableStateOf(s.allowFutureMonths) }
    var completion by rememberSaveable(s) { mutableStateOf(s.requireProgrammeCompletion) }
    var monthlyHod by rememberSaveable(s) { mutableStateOf(s.monthlyHodReviewRequired) }
    var autoIssue by rememberSaveable(s) { mutableStateOf(s.autoIssueCertificate) }
    val months = duration.toIntOrNull()
    val monthsError = if (months == null || months !in 12..72) "Between 12 and 72 months" else null
    InfoCard {
        TextInput("Institution name", institution, { institution = it })
        TextInput("Department", department, { department = it })
        TextInput("University", university, { university = it })
        TextInput("Dean / Principal name", dean, { dean = it })
        TextInput("Dean title", deanTitle, { deanTitle = it })
        TextInput("Programme duration (months)", duration, { duration = it.filter(Char::isDigit).take(2) }, keyboardType = KeyboardType.Number, error = monthsError)
        TextInput("Certificate number prefix", prefix, { prefix = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(12) })
        SwitchRow("Allow entries for future months", future) { future = it }
        SwitchRow("Certificate requires programme completion", completion) { completion = it }
        SwitchRow("Monthly assessments need HOD review", monthlyHod) { monthlyHod = it }
        SwitchRow("Issue certificate automatically when eligible", autoIssue) { autoIssue = it }
        Button(
            onClick = {
                onSave(
                    buildJsonObject {
                        put("institution_name", institution.trim())
                        put("department_name", department.trim())
                        put("university_name", university.trim())
                        put("dean_name", JsonPrimitive(dean.trim().ifBlank { null }))
                        put("dean_title", deanTitle.trim())
                        put("programme_duration_months", months ?: 36)
                        put("allow_future_months", future)
                        put("require_programme_completion", completion)
                        put("monthly_hod_review_required", monthlyHod)
                        put("auto_issue_certificate", autoIssue)
                        put("certificate_prefix", prefix.trim())
                    },
                )
            },
            enabled = !busy && monthsError == null && institution.isNotBlank() && prefix.isNotBlank(),
            modifier = Modifier.testTag("saveSettings"),
        ) { Text("Save settings") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BatchDialog(b: Batch?, onDismiss: () -> Unit, onConfirm: (String, String, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(b?.name.orEmpty()) }
    var year by rememberSaveable { mutableStateOf(b?.academicYear ?: LocalDate.now().year.let { "$it-${(it + 1) % 100}" }) }
    var intake by rememberSaveable { mutableStateOf(b?.intakeDate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (b == null) "Add batch" else "Edit batch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Batch name", name, { name = it })
                TextInput("Academic year", year, { year = it })
                DateField("Intake date", intake, { intake = it })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name.trim(), year.trim(), intake) }, enabled = name.isNotBlank() && year.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TargetDialog(t: Target, onDismiss: () -> Unit, onConfirm: (Int, String?) -> Unit) {
    var value by rememberSaveable { mutableStateOf(t.targetValue.toString()) }
    var notes by rememberSaveable { mutableStateOf(t.notes.orEmpty()) }
    val v = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Target (minimum number)", value, { value = it.filter(Char::isDigit).take(4) }, keyboardType = KeyboardType.Number, error = if (v == null || v < 0) "Enter a number" else null)
                TextInput("Notes / distribution", notes, { notes = it }, singleLine = false, minLines = 3)
            }
        },
        confirmButton = { Button(onClick = { v?.let { onConfirm(it, notes.trim().ifBlank { null }) } }, enabled = v != null) { Text("Save new version") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CompetencyDialog(c: Competency, onDismiss: () -> Unit, onConfirm: (String, String, String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf(c.title) }
    var domain by rememberSaveable { mutableStateOf(c.domain) }
    var progression by rememberSaveable { mutableStateOf(c.progression) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.code) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Title", title, { title = it }, singleLine = false)
                TextInput("Domain", domain, { domain = it })
                TextInput("Expected progression", progression, { progression = it })
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(title.trim(), domain.trim(), progression.trim().ifBlank { null }) }, enabled = title.isNotBlank() && domain.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DocumentDialog(
    doc: AcademicDocument,
    busy: Boolean,
    onDismiss: () -> Unit,
    onJson: (String, String?) -> Unit,
    onPdf: (Uri, String?) -> Unit,
) {
    val initial = remember(doc) { doc.content?.let { PrettyJson.encodeToString(JsonObject.serializer(), it) }.orEmpty() }
    var text by rememberSaveable { mutableStateOf(initial) }
    var notes by rememberSaveable { mutableStateOf("") }
    var pdfMode by rememberSaveable { mutableStateOf(doc.content == null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPdf(uri, notes.trim().ifBlank { null }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish: ${doc.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current version ${doc.versionNo}. Students and faculty see the new version after their next refresh.", style = MaterialTheme.typography.bodySmall)
                FilterRow(listOf(false to "Structured content", true to "Upload PDF"), pdfMode) { pdfMode = it }
                TextInput("Version notes", notes, { notes = it })
                if (!pdfMode) {
                    OutlinedTextField(
                        text, { text = it }, label = { Text("Content (JSON)") }, minLines = 8, maxLines = 16,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Choose a PDF (max 10 MB). It replaces the structured view for this document.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (pdfMode) {
                Button(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !busy) { Text("Choose PDF") }
            } else {
                Button(onClick = { onJson(text, notes.trim().ifBlank { null }) }, enabled = !busy && text.isNotBlank()) { Text("Publish") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================== reports

internal enum class Report(val label: String, val needsStudent: Boolean) {
    PROGRESS("Department progress summary", false),
    PENDING("Pending reviews", false),
    TARGETS("Target progress (one student)", true),
    SIGNOFFS("Competency sign-offs (one student)", true),
}

data class ReportsData(val students: List<StudentOverview>, val queue: List<ReviewQueueItem>)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    repo: LogbookRepository,
    @ApplicationContext private val context: Context,
) : LoadViewModel<String, ReportsData>(repo) {
    private val _files = MutableSharedFlow<FileEvent>(extraBufferCapacity = 2)
    val files = _files.asSharedFlow()

    override suspend fun load(args: String): Loaded<ReportsData> {
        val st = repo.overviews()
        val q = repo.cached("queue", ListSerializer(ReviewQueueItem.serializer())) { repo.backend.reviewQueue() }
        return Loaded(ReportsData(st.value.sortedBy { it.fullName }, q.value), anyOffline(st, q))
    }

    private data class Table(val title: String, val subtitle: String, val headers: List<String>, val rows: List<List<String>>)

    private suspend fun build(r: Report, studentId: String?, d: ReportsData): Table {
        val settings = repo.settings()
        val now = "${settings.departmentName}, ${settings.institutionName} · generated ${LocalDate.now()}"
        return when (r) {
            Report.PROGRESS -> Table(
                r.label, now,
                listOf("Student", "Batch", "PGY", "Month", "Monthly done", "Monthly open", "Pending", "Sign-offs", "Certificate"),
                d.students.filter { it.active }.map {
                    listOf(
                        it.fullName, it.batchName.orEmpty(), it.pgYear?.toString().orEmpty(), it.currentMonth?.toString().orEmpty(),
                        "${it.monthlyCompleted}", "${it.monthlyOverdueOrOpen}", "${it.pendingReviews}", "${it.signoffsApproved}/${it.signoffsTotal}", it.certificateNo.orEmpty(),
                    )
                },
            )
            Report.PENDING -> Table(
                r.label, now, listOf("Student", "Item", "Period", "Status", "Submitted"),
                d.queue.sortedBy { it.submittedAt }.map {
                    listOf(
                        it.studentName, if (it.isSignoff) "Sign-off: ${it.templateTitle}" else it.templateTitle,
                        listOfNotNull(it.periodNo?.let { p -> "M$p" }, it.slotNo?.let { s -> "#$s" }).joinToString(" "),
                        if (it.isSignoff) it.status else SubmissionStatus.from(it.status).label, formatDateTime(it.submittedAt),
                    )
                },
            )
            Report.TARGETS -> {
                val id = requireNotNull(studentId) { "Choose a student" }
                val name = d.students.firstOrNull { it.studentId == id }?.fullName.orEmpty()
                val rows = repo.online { targetProgress(id) }.sortedBy { it.sortOrder }
                Table(
                    "Target progress — $name", now, listOf("Item", "Category", "Target", "Recorded", "Submitted", "Verified", "% verified"),
                    rows.map { listOf(it.label, it.category.lowercase(), "${it.targetValue}", "${it.recorded}", "${it.submitted}", "${it.verified}", "${it.verifiedPercent}%") },
                )
            }
            Report.SIGNOFFS -> {
                val id = requireNotNull(studentId) { "Choose a student" }
                val name = d.students.firstOrNull { it.studentId == id }?.fullName.orEmpty()
                val comps = repo.reference().competencies.associateBy { it.code }
                val rows = repo.online { signoffs(id) }.sortedBy { comps[it.competencyCode]?.sortOrder ?: 0 }
                Table(
                    "Competency sign-offs — $name", now, listOf("Code", "Competency", "Observed", "Supervised", "Independent", "Status", "Signed off by"),
                    rows.map {
                        listOf(
                            it.competencyCode, comps[it.competencyCode]?.title.orEmpty(), formatDate(it.observedAt), formatDate(it.supervisedAt),
                            formatDate(it.independentAt), it.status.label, listOfNotNull(it.finalByName, it.finalAt?.let(::formatDate)).joinToString(", "),
                        )
                    },
                )
            }
        }
    }

    fun export(report: String, studentId: String?, format: String, kind: String) {
        val d = (state.value as? UiState.Ready)?.value ?: return
        val r = Report.valueOf(report)
        action {
            val t = build(r, studentId, d)
            val base = t.title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') + "-" + LocalDate.now()
            val file = withContext(Dispatchers.Default) {
                if (format == "pdf") {
                    Files.write(context, "$base.pdf", PdfRenderer.table(t.title, t.subtitle, t.headers, t.rows))
                } else {
                    val csv = (listOf(t.headers) + t.rows).joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } }
                    Files.write(context, "$base.csv", ("\uFEFF" + csv).toByteArray(Charsets.UTF_8))
                }
            }
            _files.tryEmit(
                when (kind) {
                    "share" -> FileEvent.Share(file)
                    "save" -> FileEvent.Save(file)
                    else -> FileEvent.View(file)
                },
            )
        }
    }
}

/** RFC 4180 quoting; also neutralises spreadsheet formula injection. */
internal fun csvCell(raw: String): String {
    val v = if (raw.isNotEmpty() && raw[0] in "=+-@") "'$raw" else raw
    return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

@Composable
fun ReportsScreen() {
    val nav = appNav
    val vm: ReportsViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("reports") }
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    FileEventsHandler(vm.files)
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var report by rememberSaveable { mutableStateOf(Report.PROGRESS.name) }
    var student by rememberSaveable { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf("pdf") }
    AppScreen("Reports", topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                val r = Report.valueOf(report)
                ScreenList {
                    if ((state as? UiState.Ready)?.offline == true) item { OfflineBanner() }
                    item {
                        InfoCard {
                            SectionTitle("Export")
                            DropdownField("Report", report, Report.entries.map { it.name to it.label }, { report = it })
                            if (r.needsStudent) {
                                DropdownField("Student", student, d.students.map { it.studentId to it.fullName }, { student = it })
                            }
                            FilterRow(listOf("pdf" to "PDF", "csv" to "CSV (Excel)"), format) { format = it }
                            val ready = !busy && (!r.needsStudent || student != null)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.export(report, student, format, "view") }, enabled = ready, modifier = Modifier.testTag("exportView")) { Text("View") }
                                OutlinedButton(onClick = { vm.export(report, student, format, "share") }, enabled = ready) { Text("Share") }
                                OutlinedButton(onClick = { vm.export(report, student, format, "save") }, enabled = ready) { Text("Save") }
                            }
                        }
                    }
                    item {
                        InfoCard {
                            SectionTitle("At a glance")
                            KeyValue("Active students", "${d.students.count { it.active }}")
                            KeyValue("Items pending review", "${d.queue.size}")
                            KeyValue("Assessments pending", "${d.queue.count { it.templateCode in ASSESSMENT_CODES }}")
                            KeyValue("Certificates issued", "${d.students.count { it.certificateNo != null }}")
                        }
                    }
                }
            }
        }
    }
}
