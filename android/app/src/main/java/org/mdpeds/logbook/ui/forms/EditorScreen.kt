package org.mdpeds.logbook.ui.forms

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mdpeds.logbook.data.local.OutboxState
import org.mdpeds.logbook.domain.logic.EditorMode
import org.mdpeds.logbook.domain.logic.FormValidator
import org.mdpeds.logbook.domain.model.FieldDef
import org.mdpeds.logbook.domain.model.FieldType
import org.mdpeds.logbook.domain.model.Owner
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.TemplateCategory
import org.mdpeds.logbook.ui.common.UiState
import org.mdpeds.logbook.ui.common.formatDateTime
import org.mdpeds.logbook.ui.components.Banner
import org.mdpeds.logbook.ui.components.ConfirmDialog
import org.mdpeds.logbook.ui.components.InfoCard
import org.mdpeds.logbook.ui.components.ReasonDialog
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.components.StatusChip
import org.mdpeds.logbook.ui.components.Tone
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.appNav
import org.mdpeds.logbook.util.Files

private enum class Ask { NONE, VERIFY, RETURN, REOPEN, CORRECT, ANNUAL_CORRECT, DISCARD, LEAVE }

@Composable
fun EditorScreen(studentId: String, code: String, id: String?, period: Int?, slot: Int?) {
    val nav = appNav
    val context = LocalContext.current
    val vm: EditorViewModel = hiltViewModel()
    LaunchedEffect(studentId, code, id, period, slot) { vm.bind(EditorArgs(studentId, code, id, period, slot)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val student by vm.student.collectAsStateWithLifecycle()
    val faculty by vm.faculty.collectAsStateWithLifecycle()
    val hod by vm.hod.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val correcting by vm.correcting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var ask by rememberSaveable { mutableStateOf(Ask.NONE) }

    LaunchedEffect(vm) { vm.messages.collect { nav.snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                EditorEvent.Close -> nav.back()
                is EditorEvent.Open -> if (!Files.view(context, e.file, e.mime)) nav.snackbar.showSnackbar("No app installed to open this file")
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::upload) }

    BackHandler(enabled = dirty) { ask = Ask.LEAVE }
    val data = (state as? UiState.Ready)?.value

    AppScreen(
        title = data?.heading ?: "Logbook entry",
        subtitle = data?.let { d -> if (d.session.userId == studentId) d.template.subtitle else d.overview?.fullName },
        onBack = { if (dirty) ask = Ask.LEAVE else nav.back() },
        actions = {
            if (data?.canDiscard == true) IconButton(onClick = { ask = Ask.DISCARD }) { Icon(Icons.Filled.Delete, "Delete draft") }
            if (data?.canReopenOrCorrect == true && !correcting) {
                IconButton(onClick = { ask = Ask.REOPEN }, modifier = Modifier.testTag("reopen")) { Icon(Icons.AutoMirrored.Filled.Undo, "Reopen for correction") }
                IconButton(onClick = vm::startCorrection, modifier = Modifier.testTag("correct")) { Icon(Icons.Filled.Edit, "Correct assessment") }
            }
        },
        bottomBar = {
            if (data != null) ActionBar(data, vm, correcting, busy) { ask = it }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Header(d)
                        Banners(d, vm)
                        val combined = student + faculty + hod
                        val editable = when (d.mode) {
                            EditorMode.STUDENT_EDIT -> Owner.STUDENT
                            EditorMode.FACULTY_REVIEW, EditorMode.ANNUAL_EDIT -> Owner.FACULTY
                            EditorMode.HOD_REVIEW -> Owner.HOD
                            EditorMode.READ_ONLY -> if (correcting) Owner.FACULTY else null
                        }
                        listOf(Owner.STUDENT, Owner.FACULTY, Owner.HOD).forEach { owner ->
                            val fields = d.template.fieldsFor(owner)
                            if (fields.isEmpty()) return@forEach
                            val values = when (owner) {
                                Owner.FACULTY -> faculty
                                Owner.HOD -> hod
                                else -> student
                            }
                            OwnerSection(
                                title = ownerTitle(d.template.category, owner),
                                fields = fields,
                                values = values,
                                visibilityData = combined,
                                editable = owner == editable || (correcting && owner == Owner.FACULTY),
                                errors = errors,
                                ctx = d.ctx,
                                showEmptyHint = d.template.category != TemplateCategory.ANNUAL,
                                onChange = { k, v -> vm.set(owner, k, v) },
                                attachmentSlot = { f -> AttachmentBlock(d, f, onAdd = { picker.launch(arrayOf("application/pdf", "image/*")) }, onOpen = vm::openAttachment) },
                            )
                        }
                        Signatures(d)
                    }
                }
            }
        }
    }

    when (ask) {
        Ask.NONE -> Unit
        Ask.VERIFY -> ReasonDialog(
            title = "Verify entry", label = "Comment for the student (optional)", confirm = "Verify", required = false,
            message = "Your name, designation and the time will be recorded as the authenticated verification.",
            onDismiss = { ask = Ask.NONE }, onConfirm = { ask = Ask.NONE; vm.verify(it) },
        )
        Ask.RETURN -> ReasonDialog(
            title = "Return for correction", label = "What needs to be corrected?", confirm = "Return",
            onDismiss = { ask = Ask.NONE }, onConfirm = { ask = Ask.NONE; vm.returnForCorrection(it) },
        )
        Ask.REOPEN -> ReasonDialog(
            title = "Reopen for correction", label = "Reason (recorded in the audit log)", confirm = "Reopen",
            message = "The student will be able to edit and resubmit this record.",
            onDismiss = { ask = Ask.NONE }, onConfirm = { ask = Ask.NONE; vm.reopen(it) },
        )
        Ask.CORRECT -> ReasonDialog(
            title = "Save correction", label = "Reason for the correction", confirm = "Save",
            onDismiss = { ask = Ask.NONE }, onConfirm = { ask = Ask.NONE; vm.saveCorrection(it) },
        )
        Ask.ANNUAL_CORRECT -> ReasonDialog(
            title = "Correct completed review", label = "Reason for the correction", confirm = "Save correction",
            onDismiss = { ask = Ask.NONE }, onConfirm = { ask = Ask.NONE; vm.saveAnnual(submit = true, reason = it) },
        )
        Ask.DISCARD -> ConfirmDialog(
            title = "Delete draft?", message = "This unsubmitted draft will be permanently deleted.", confirm = "Delete",
            onDismiss = { ask = Ask.NONE }, onConfirm = vm::discardDraft,
        )
        Ask.LEAVE -> AlertDialog(
            onDismissRequest = { ask = Ask.NONE },
            title = { Text("Unsaved changes") },
            text = { Text("You have changes that haven't been saved.") },
            confirmButton = {
                Button(onClick = {
                    ask = Ask.NONE
                    if (data?.mode == EditorMode.STUDENT_EDIT) vm.saveStudent(submit = false)
                }, enabled = data?.mode == EditorMode.STUDENT_EDIT) { Text("Save draft") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { ask = Ask.NONE; nav.back() }) { Text("Discard") }
                    TextButton(onClick = { ask = Ask.NONE }) { Text("Keep editing") }
                }
            },
        )
    }
}

private fun ownerTitle(category: String, owner: String) = when (owner) {
    Owner.STUDENT -> "Resident entry"
    Owner.FACULTY -> if (category == TemplateCategory.ANNUAL) "Faculty assessment by domain" else "Faculty assessment & verification"
    else -> "HOD review"
}

@Composable
private fun Header(d: EditorData) {
    val s = d.submission
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(s?.status ?: SubmissionStatus.NOT_STARTED)
        if (s != null && s.version > 0) Text("Version ${s.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.mode == EditorMode.READ_ONLY) Text("Read only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Banners(d: EditorData, vm: EditorViewModel) {
    val s = d.submission
    d.lockedMessage?.let { Banner(it, Tone.NOT_STARTED, title = "Locked") }
    val sync = d.item?.sync
    if (sync != null) {
        when (sync.state) {
            OutboxState.CONFLICT -> Banner(
                "This record was changed on the server after you started editing offline. Choose which version to keep.",
                Tone.RETURNED, title = "Sync conflict",
            ) {
                Button(onClick = vm::keepMine) { Text("Keep mine") }
                OutlinedButton(onClick = vm::useServer) { Text("Use server version") }
            }
            OutboxState.FAILED -> Banner(sync.error ?: "The server rejected this change.", Tone.RETURNED, title = "Not saved to server") {
                Button(onClick = vm::retrySync) { Text("Retry") }
                OutlinedButton(onClick = vm::useServer) { Text("Discard local changes") }
            }
            else -> Banner(
                if (sync.pendingSubmit) "Saved on this device. It will be submitted automatically when you're back online."
                else "Saved on this device. It will sync automatically when you're back online.",
                Tone.PENDING, title = "Waiting to sync",
            )
        }
    }
    if (s?.status == SubmissionStatus.RETURNED && !s.returnReason.isNullOrBlank()) {
        Banner(s.returnReason, Tone.RETURNED, title = "Returned for correction")
    }
    if (d.template.category == TemplateCategory.MONTHLY && d.period != null) {
        val pr = d.reference.monthlyPriorities.filter { it.covers(d.period) }
        if (pr.isNotEmpty()) Banner(pr.joinToString("\n") { it.priority }, Tone.DRAFT, title = "Suggested priorities for this month")
    }
    d.template.notice?.let { Banner(it, Tone.DRAFT) }
    d.template.ratingHelp?.let { if (d.mode != EditorMode.READ_ONLY) Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun OwnerSection(
    title: String,
    fields: List<FieldDef>,
    values: Map<String, String>,
    visibilityData: Map<String, String>,
    editable: Boolean,
    errors: Map<String, String>,
    ctx: FieldContext,
    showEmptyHint: Boolean,
    onChange: (String, String) -> Unit,
    attachmentSlot: @Composable (FieldDef) -> Unit,
) {
    val visible = fields.filter { FormValidator.isVisible(it, visibilityData) }
    val hasValues = visible.any { !values[it.key].isNullOrBlank() }
    InfoCard(container = MaterialTheme.colorScheme.surfaceContainerLowest) {
        SectionTitle(title)
        if (!editable && !hasValues && visible.none { it.type == FieldType.ATTACHMENT }) {
            if (showEmptyHint) Text("Not completed yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@InfoCard
        }
        var lastSection: String? = null
        visible.forEach { f ->
            if (f.section != null && f.section != lastSection) {
                lastSection = f.section
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(f.section, style = MaterialTheme.typography.titleSmall)
            }
            when {
                f.type == FieldType.ATTACHMENT -> attachmentSlot(f)
                editable && !f.readOnly -> FieldInput(f, values[f.key].orEmpty(), { onChange(f.key, it) }, errors[f.key], ctx)
                else -> FieldValue(f, values[f.key], ctx)
            }
        }
    }
}

@Composable
private fun AttachmentBlock(d: EditorData, f: FieldDef, onAdd: () -> Unit, onOpen: (org.mdpeds.logbook.domain.model.Attachment) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.attachments.isEmpty()) Text("No files attached.", style = MaterialTheme.typography.bodyMedium)
        d.attachments.forEach { a ->
            TextButton(onClick = { onOpen(a) }) {
                Icon(Icons.Filled.InsertDriveFile, null)
                Text("  ${a.fileName}" + (a.sizeBytes?.let { "  (${it / 1024} KB)" } ?: ""))
            }
        }
        if (d.mode == EditorMode.STUDENT_EDIT) {
            if (d.onServer && d.item?.isLocalOnly != true) {
                OutlinedButton(onClick = onAdd, modifier = Modifier.testTag("attach")) { Icon(Icons.Filled.AttachFile, null); Text(" Attach PDF or image") }
                Text("Max 10 MB per file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Save the draft online first to attach evidence files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Authenticated electronic signatures replacing the paper logbook's signature boxes. */
@Composable
private fun Signatures(d: EditorData) {
    val s = d.submission ?: return
    if (!s.status.isSubmitted) return
    InfoCard {
        SectionTitle("Verification")
        d.template.signatures.forEach { role ->
            val (who, at) = when (role) {
                "RESIDENT" -> (s.submittedByName ?: d.overview?.fullName) to s.submittedAt
                "FACULTY" -> listOfNotNull(s.reviewedByName, s.reviewedByDesignation).joinToString(", ").ifBlank { null } to s.reviewedAt
                "HOD" -> s.hodReviewedByName to s.hodReviewedAt
                else -> null to null
            }
            val label = when (role) { "RESIDENT" -> "Resident"; "FACULTY" -> "Faculty"; "HOD" -> "HOD"; else -> role }
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (who != null && at != null) "$who · electronically verified ${formatDateTime(at)}" else "Pending",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ActionBar(d: EditorData, vm: EditorViewModel, correcting: Boolean, busy: Boolean, ask: (Ask) -> Unit) {
    val buttons: List<Triple<String, Boolean, () -> Unit>> = when {
        correcting -> listOf(
            Triple("Cancel", false) { vm.cancelCorrection() },
            Triple("Save correction", true) { ask(Ask.CORRECT) },
        )
        d.mode == EditorMode.STUDENT_EDIT -> listOf(
            Triple("Save draft", false) { vm.saveStudent(submit = false) },
            Triple(if (d.submission?.status == SubmissionStatus.RETURNED) "Resubmit" else "Submit", true) { vm.saveStudent(submit = true) },
        )
        d.mode == EditorMode.FACULTY_REVIEW -> listOf(
            Triple("Return", false) { ask(Ask.RETURN) },
            Triple("Verify", true) { ask(Ask.VERIFY) },
        )
        d.mode == EditorMode.HOD_REVIEW -> listOf(Triple("Complete review", true) { vm.hodComplete() })
        d.mode == EditorMode.ANNUAL_EDIT -> if (d.submission?.status == SubmissionStatus.COMPLETED) {
            listOf(Triple("Save correction", true) { ask(Ask.ANNUAL_CORRECT) })
        } else {
            listOf(Triple("Save", false) { vm.saveAnnual(submit = false, reason = null) }, Triple("Complete review", true) { vm.saveAnnual(submit = true, reason = null) })
        }
        else -> emptyList()
    }
    if (buttons.isEmpty()) return
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        FlowRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            buttons.forEach { (label, primary, onClick) ->
                if (primary) {
                    Button(onClick = onClick, enabled = !busy, modifier = Modifier.testTag("action:$label")) { Text(label) }
                } else {
                    OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.testTag("action:$label")) { Text(label) }
                }
            }
        }
    }
}
