package org.mdpeds.logbook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.data.local.OutboxEntity
import org.mdpeds.logbook.data.local.OutboxState
import org.mdpeds.logbook.data.local.ThemeMode
import org.mdpeds.logbook.data.local.UserPreferences
import org.mdpeds.logbook.domain.model.AppNotification
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.StaffMember
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.common.BaseViewModel
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.enumLabel
import org.mdpeds.logbook.ui.common.formatDate
import org.mdpeds.logbook.ui.common.formatDateTime
import org.mdpeds.logbook.ui.common.overview
import org.mdpeds.logbook.ui.common.staffList
import org.mdpeds.logbook.ui.common.userMessage
import org.mdpeds.logbook.ui.components.Banner
import org.mdpeds.logbook.ui.components.CollectMessages
import org.mdpeds.logbook.ui.components.EmptyState
import org.mdpeds.logbook.ui.components.InfoCard
import org.mdpeds.logbook.ui.components.KeyValue
import org.mdpeds.logbook.ui.components.ScreenList
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.components.StatusChip
import org.mdpeds.logbook.ui.components.TextInput
import org.mdpeds.logbook.ui.components.Tone
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.Routes
import org.mdpeds.logbook.ui.nav.appNav
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

// ================================================================== notifications

@HiltViewModel
class NotificationsViewModel @Inject constructor(repo: LogbookRepository) : LoadViewModel<String, List<AppNotification>>(repo) {
    private val _open = MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** Routes to open after resolving a notification target. */
    val open = _open.asSharedFlow()

    override suspend fun load(args: String): Loaded<List<AppNotification>> =
        fresh(repo.backend.notifications().sortedByDescending { it.createdAt })

    fun markAll() = action(success = "All notifications marked as read") { repo.online { markNotificationsRead(null) } }

    fun tap(n: AppNotification) {
        viewModelScope.launch {
            if (n.readAt == null) runCatching { repo.online { markNotificationsRead(listOf(n.id)) } }
            val sid = n.studentId
            val route = try {
                when (n.entityType) {
                    "submission" -> n.entityId?.let { id ->
                        val s = repo.record(id).submission
                        Routes.editor(s.studentId, s.templateCode, id = s.id, period = s.periodNo, slot = s.slotNo)
                    }
                    "competency_signoff" -> sid?.let(Routes::signoffs)
                    "certificate" -> sid?.let(Routes::certificate)
                    else -> if (sid != null && repo.requireSession().role != Role.STUDENT) Routes.student(sid) else null
                }
            } catch (e: Exception) {
                message(e.userMessage())
                null
            }
            route?.let { _open.tryEmit(it) }
        }
    }
}

@Composable
fun NotificationsScreen() {
    val nav = appNav
    val vm: NotificationsViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("me") }
    LaunchedEffect(vm) { vm.open.collect { nav.open(it) } }
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    AppScreen(
        "Notifications", topLevel = true,
        actions = { IconButton(onClick = vm::markAll, modifier = Modifier.testTag("markAllRead")) { Icon(Icons.Filled.DoneAll, "Mark all as read") } },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { list ->
                ScreenList {
                    if (list.isEmpty()) item { EmptyState("No notifications", "Reviews, returns, sign-offs and reminders will appear here.") }
                    items(list, key = { it.id }) { n ->
                        InfoCard(onClick = { vm.tap(n) }, container = if (n.readAt == null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (n.readAt == null) Icon(Icons.Filled.Circle, "Unread", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(2.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(n.title, style = MaterialTheme.typography.titleSmall)
                                    Text(n.body, style = MaterialTheme.typography.bodyMedium)
                                    Text(formatDateTime(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== profile

data class ProfileData(val session: UserSession, val student: StudentOverview?, val staff: StaffMember?)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    repo: LogbookRepository,
    private val prefs: UserPreferences,
) : LoadViewModel<String, ProfileData>(repo) {
    val theme = prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val reminders = prefs.remindersEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val _pendingDrafts = MutableStateFlow(0)
    val pendingDrafts = _pendingDrafts.asStateFlow()

    override suspend fun load(args: String): Loaded<ProfileData> {
        val me = repo.requireSession()
        return if (me.role == Role.STUDENT) {
            val ov = repo.overview(me.userId)
            Loaded(ProfileData(me, ov.value, null), ov.offline)
        } else {
            val st = repo.staffList()
            Loaded(ProfileData(me, null, st.value.firstOrNull { it.id == me.userId }), st.offline)
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setReminders(on: Boolean) = viewModelScope.launch { prefs.setRemindersEnabled(on) }

    fun save(data: Map<String, String>) = action(success = "Profile updated") { repo.online { updateMyProfile(data) } }

    fun resetPassword() = action(success = "Password reset email sent") { repo.backend.sendPasswordReset(repo.requireSession().email) }

    fun checkPending() = viewModelScope.launch { _pendingDrafts.value = repo.pendingDraftCount() }

    fun signOut() = viewModelScope.launch {
        runCatching { repo.signOut() }.onFailure { message(it.userMessage()) }
    }
}

@Composable
fun ProfileScreen() {
    val nav = appNav
    val vm: ProfileViewModel = hiltViewModel()
    LaunchedEffect(Unit) { vm.bind("me") }
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val pending by vm.pendingDrafts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    AppScreen("Profile", topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                var posting by rememberSaveable(d.student?.currentPosting) { mutableStateOf(d.student?.currentPosting.orEmpty()) }
                var designation by rememberSaveable(d.staff?.designation) { mutableStateOf(d.staff?.designation.orEmpty()) }
                var unit by rememberSaveable(d.staff?.unit) { mutableStateOf(d.staff?.unit.orEmpty()) }
                ScreenList {
                    item {
                        InfoCard {
                            Text(d.session.fullName, style = MaterialTheme.typography.titleLarge)
                            Text(d.session.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            StatusChip(d.session.role.label, Tone.APPROVED)
                        }
                    }
                    d.student?.let { s ->
                        item {
                            InfoCard {
                                SectionTitle("Programme details")
                                KeyValue("University reg. no.", s.universityRegNo)
                                KeyValue("Institution", s.institution)
                                KeyValue("University", s.university)
                                KeyValue("Batch", listOfNotNull(s.batchName, s.academicYear).joinToString(" · ").ifBlank { null })
                                KeyValue("Guide", s.guideName)
                                KeyValue("Programme start", formatDate(s.programmeStartDate))
                                KeyValue("Expected completion", formatDate(s.completionDate))
                                KeyValue("Status", s.enrolmentStatus?.let(::enumLabel))
                                Text("Contact the HOD to correct registration or programme details.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextInput("Current posting / unit", posting, { posting = it })
                                Button(onClick = { vm.save(mapOf("current_posting" to posting.trim())) }, enabled = !busy && posting != s.currentPosting.orEmpty()) { Text("Save") }
                            }
                        }
                    }
                    if (d.session.role != Role.STUDENT) item {
                        InfoCard {
                            SectionTitle("Professional details")
                            TextInput("Designation", designation, { designation = it })
                            TextInput("Unit", unit, { unit = it })
                            if (d.staff?.isUnitHead == true) Text("Unit head", style = MaterialTheme.typography.labelMedium)
                            Button(
                                onClick = { vm.save(mapOf("designation" to designation.trim(), "unit" to unit.trim())) },
                                enabled = !busy && (designation != d.staff?.designation.orEmpty() || unit != d.staff?.unit.orEmpty()),
                            ) { Text("Save") }
                        }
                    }
                    item {
                        InfoCard {
                            SectionTitle("Appearance")
                            SingleChoiceSegmentedButtonRow {
                                ThemeMode.entries.forEachIndexed { i, m ->
                                    SegmentedButton(
                                        selected = theme == m, onClick = { vm.setTheme(m) },
                                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                    ) { Text(enumLabel(m.name)) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Reminder notifications", style = MaterialTheme.typography.bodyLarge)
                                    Text("Monthly assessments, pending reviews and returned entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = reminders, onCheckedChange = vm::setReminders)
                            }
                        }
                    }
                    item {
                        InfoCard {
                            SectionTitle("Account")
                            OutlinedButton(onClick = vm::resetPassword, enabled = !busy) { Text("Email me a password reset link") }
                            if (d.session.role == Role.STUDENT) OutlinedButton(onClick = { nav.open(Routes.SYNC) }) { Text("Offline drafts") }
                            Button(
                                onClick = { vm.checkPending(); confirmSignOut = true },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("signOut"),
                            ) { Text("Sign out") }
                            Text("Signing out removes all cached logbook data from this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pending > 0) {
                        Banner("$pending draft change${if (pending == 1) " has" else "s have"} not been synced and will be lost.", Tone.RETURNED, title = "Unsynced drafts")
                    }
                    Text("Cached data will be removed from this device.")
                }
            },
            confirmButton = {
                Button(onClick = { confirmSignOut = false; vm.signOut() }, modifier = Modifier.testTag("confirmSignOut")) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

// ================================================================== offline drafts / sync

@HiltViewModel
class SyncViewModel @Inject constructor(repo: LogbookRepository) : BaseViewModel(repo) {
    val outbox = repo.observeOutbox().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val templates = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        viewModelScope.launch { runCatching { templates.value = repo.reference().templates.associate { it.code to it.title } } }
    }

    fun syncNow() = action {
        val left = repo.syncAll()
        message(if (left == 0) "Everything is synced" else "$left change${if (left == 1) "" else "s"} still waiting")
    }

    fun retry(id: String) = action(success = "Retrying…") { repo.retry(id) }
    fun keepMine(id: String) = action {
        message(if (repo.resolveKeepMine(id) != null) "Your version was saved" else "Still offline — will retry")
    }
    fun discard(id: String) = action(success = "Local change discarded") { repo.discardLocal(id) }
}

@Composable
fun SyncScreen() {
    val nav = appNav
    val vm: SyncViewModel = hiltViewModel()
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    val list by vm.outbox.collectAsStateWithLifecycle()
    val titles by vm.templates.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var discarding by rememberSaveable { mutableStateOf<String?>(null) }
    AppScreen("Offline drafts", topLevel = true) { pad ->
        Box(Modifier.padding(pad)) {
            ScreenList {
                item {
                    InfoCard {
                        Text(
                            "Entries saved without a connection are stored securely on this device and sent automatically when you're back online.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = vm::syncNow, enabled = !busy && list.isNotEmpty(), modifier = Modifier.testTag("syncNow")) { Text("Sync now") }
                    }
                }
                if (list.isEmpty()) item { EmptyState("All changes are synced") }
                items(list, key = { it.id }) { e -> OutboxCard(e, titles[e.templateCode] ?: e.templateCode, busy, vm, onOpen = {
                    nav.open(Routes.editor(e.studentId, e.templateCode, id = e.id, period = e.periodNo, slot = e.slotNo))
                }, onDiscard = { discarding = e.id }) }
            }
        }
    }
    discarding?.let { id ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text("Discard local change?") },
            text = { Text("Your unsynced edits to this entry will be deleted from the device.") },
            confirmButton = { Button(onClick = { discarding = null; vm.discard(id) }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OutboxCard(e: OutboxEntity, title: String, busy: Boolean, vm: SyncViewModel, onOpen: () -> Unit, onDiscard: () -> Unit) {
    val (label, tone) = when (e.state) {
        OutboxState.CONFLICT -> "Conflict" to Tone.RETURNED
        OutboxState.FAILED -> "Failed" to Tone.RETURNED
        OutboxState.SYNCING -> "Syncing" to Tone.PENDING
        else -> (if (e.action == "SUBMIT") "Waiting to submit" else "Waiting to save") to Tone.PENDING
    }
    InfoCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title + (e.periodNo?.let { " · Month/Year $it" } ?: "") + (e.slotNo?.let { " · #$it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                Text("Edited " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.updatedAt)), style = MaterialTheme.typography.bodySmall)
            }
            StatusChip(label, tone)
        }
        e.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (e.state) {
                OutboxState.CONFLICT -> {
                    Button(onClick = { vm.keepMine(e.id) }, enabled = !busy) { Text("Keep mine") }
                    OutlinedButton(onClick = onDiscard, enabled = !busy) { Text("Use server") }
                }
                OutboxState.FAILED -> {
                    Button(onClick = { vm.retry(e.id) }, enabled = !busy) { Text("Retry") }
                    OutlinedButton(onClick = onDiscard, enabled = !busy) { Text("Discard") }
                }
                else -> OutlinedButton(onClick = onDiscard, enabled = !busy) { Text("Discard") }
            }
        }
    }
}
