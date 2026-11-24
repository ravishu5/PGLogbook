package org.mdpeds.logbook.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.logic.AccessPolicy
import org.mdpeds.logbook.domain.model.Certificate
import org.mdpeds.logbook.domain.model.CertificationState
import org.mdpeds.logbook.domain.model.CertificationStatus
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.UiState
import org.mdpeds.logbook.ui.common.formatDate
import org.mdpeds.logbook.ui.common.formatDateTime
import org.mdpeds.logbook.ui.common.overview
import org.mdpeds.logbook.ui.components.Banner
import org.mdpeds.logbook.ui.components.CollectMessages
import org.mdpeds.logbook.ui.components.InfoCard
import org.mdpeds.logbook.ui.components.KeyValue
import org.mdpeds.logbook.ui.components.ProgressLine
import org.mdpeds.logbook.ui.components.ReasonDialog
import org.mdpeds.logbook.ui.components.ScreenList
import org.mdpeds.logbook.ui.components.SectionTitle
import org.mdpeds.logbook.ui.components.StateContent
import org.mdpeds.logbook.ui.components.StatusChip
import org.mdpeds.logbook.ui.components.Tone
import org.mdpeds.logbook.ui.components.tone
import org.mdpeds.logbook.ui.nav.AppScreen
import org.mdpeds.logbook.ui.nav.Routes
import org.mdpeds.logbook.ui.nav.appNav
import org.mdpeds.logbook.ui.nav.isTopLevel
import org.mdpeds.logbook.util.Files
import org.mdpeds.logbook.util.PdfRenderer
import java.io.File
import javax.inject.Inject

data class CertificateData(
    val session: UserSession,
    val overview: StudentOverview?,
    val state: CertificationState,
    val certificate: Certificate?,
) {
    val canIssue get() = AccessPolicy.canIssueCertificate(session.role)
}

/** File output requests for the screen: share, view or save via the system picker. */
sealed interface FileEvent {
    data class View(val file: File) : FileEvent
    data class Share(val file: File) : FileEvent
    data class Save(val file: File) : FileEvent
}

@HiltViewModel
class CertificateViewModel @Inject constructor(
    repo: LogbookRepository,
    @ApplicationContext private val context: Context,
) : LoadViewModel<String, CertificateData>(repo) {
    private val _files = MutableSharedFlow<FileEvent>(extraBufferCapacity = 2)
    val files = _files.asSharedFlow()

    override suspend fun load(args: String): Loaded<CertificateData> {
        val me = repo.requireSession()
        val ov = runCatching { repo.overview(args).value }.getOrNull()
        val st = repo.online { certificationStatus(args) }
        val cert = runCatching { repo.backend.certificate(args) }.getOrNull()
        return fresh(CertificateData(me, ov, st, cert))
    }

    fun issue() = action(success = "Certificate issued") { repo.online { issueCertificate(boundArgs) } }

    fun revoke(reason: String) = action(success = "Certificate revoked") { repo.online { revokeCertificate(boundArgs, reason) } }

    fun pdf(kind: String) {
        val cert = (state.value as? UiState.Ready)?.value?.certificate ?: return
        action {
            val file = withContext(Dispatchers.Default) {
                val bytes = PdfRenderer.certificate(cert)
                Files.write(context, "Certificate-${cert.certificateNo.replace('/', '-')}.pdf", bytes)
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

/** Handles [FileEvent]s: opens a viewer, the share sheet or the "save as" picker. */
@Composable
fun FileEventsHandler(events: kotlinx.coroutines.flow.Flow<FileEvent>) {
    val nav = appNav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val path = pending
        pending = null
        if (uri != null && path != null) {
            val ok = runCatching { Files.copyTo(context, File(path), uri) }.isSuccess
            scope.launch { nav.snackbar.showSnackbar(if (ok) "Saved" else "Could not save the file") }
        }
    }
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                is FileEvent.View -> if (!Files.view(context, e.file)) Files.share(context, e.file)
                is FileEvent.Share -> Files.share(context, e.file)
                is FileEvent.Save -> {
                    pending = e.file.absolutePath
                    saver.launch(e.file.name)
                }
            }
        }
    }
}

@Composable
fun CertificateScreen(studentId: String) {
    val nav = appNav
    val vm: CertificateViewModel = hiltViewModel()
    LaunchedEffect(studentId) { vm.bind(studentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    CollectMessages(vm.messages) { nav.snackbar.showSnackbar(it) }
    FileEventsHandler(vm.files)
    var revoking by rememberSaveable { mutableStateOf(false) }
    AppScreen("Certificate", subtitle = (state as? UiState.Ready)?.value?.overview?.fullName, topLevel = isTopLevel(Routes.certificate(studentId))) { pad ->
        Box(Modifier.padding(pad)) {
            StateContent(state, onRetry = { vm.refresh() }) { d ->
                ScreenList {
                    item {
                        InfoCard {
                            SectionTitle("Certification status")
                            StatusChip(d.state.status.label, d.state.status.tone(), Modifier.testTag("certStatus"))
                            ProgressLine("Competencies signed off", d.state.approved, d.state.total)
                            KeyValue("Programme complete", if (d.state.programmeComplete) "Yes" else "No")
                            KeyValue("Completion date", formatDate(d.state.completionDate))
                        }
                    }
                    if (d.state.reasons.isNotEmpty() && d.state.status != CertificationStatus.CERTIFICATE_ISSUED) item {
                        Banner(d.state.reasons.joinToString("\n") { "• $it" }, Tone.PENDING, title = "Outstanding requirements")
                    }
                    val cert = d.certificate?.takeIf { it.status == "ISSUED" }
                    if (cert != null) {
                        item {
                            InfoCard {
                                SectionTitle("Certificate ${cert.certificateNo}")
                                val s = cert.snapshot
                                KeyValue("Resident", s.studentName)
                                KeyValue("University reg. no.", s.universityRegNo)
                                KeyValue("Course", s.course)
                                KeyValue("Programme", "${formatDate(s.programmeStartDate)} – ${formatDate(s.programmeCompletionDate)}")
                                KeyValue("Issued", formatDateTime(cert.issuedAt) + (cert.issuedByName?.let { " by $it" } ?: ""))
                                s.signatories.forEach { sig -> KeyValue(sig.role, listOfNotNull(sig.name, sig.verification).joinToString(" · ")) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { vm.pdf("view") }, enabled = !busy, modifier = Modifier.testTag("viewPdf")) { Text("View PDF") }
                                    OutlinedButton(onClick = { vm.pdf("share") }, enabled = !busy) { Text("Share") }
                                    OutlinedButton(onClick = { vm.pdf("save") }, enabled = !busy) { Text("Save to device") }
                                }
                                Text(
                                    "Signatures are authenticated electronic verifications recorded in the audit trail; no handwritten signature is reproduced.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    d.certificate?.takeIf { it.status != "ISSUED" }?.let { old ->
                        item { Banner("Certificate ${old.certificateNo} was revoked ${formatDate(old.revokedAt)}: ${old.revokedReason.orEmpty()}", Tone.RETURNED) }
                    }
                    if (d.canIssue) item {
                        InfoCard {
                            SectionTitle("HOD actions")
                            if (cert == null) {
                                Button(
                                    onClick = vm::issue,
                                    enabled = !busy && d.state.status == CertificationStatus.READY_FOR_CERTIFICATION,
                                    modifier = Modifier.testTag("issueCertificate"),
                                ) { Text("Issue certificate") }
                                if (d.state.status != CertificationStatus.READY_FOR_CERTIFICATION) {
                                    Text("Available once every requirement above is met.", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                OutlinedButton(onClick = { revoking = true }, enabled = !busy) { Text("Revoke certificate") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (revoking) {
        ReasonDialog(
            title = "Revoke certificate", label = "Reason (recorded in the audit log)", confirm = "Revoke",
            message = "The certificate will no longer be valid. Sign-offs can then be revised.",
            onDismiss = { revoking = false }, onConfirm = { revoking = false; vm.revoke(it) },
        )
    }
}
