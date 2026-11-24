package org.mdpeds.logbook.ui.forms

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.data.RecordItem
import org.mdpeds.logbook.domain.logic.AccessPolicy
import org.mdpeds.logbook.domain.logic.EditorMode
import org.mdpeds.logbook.domain.logic.FormValidator
import org.mdpeds.logbook.domain.logic.ProgrammeTimeline
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.logic.Relation
import org.mdpeds.logbook.domain.model.Attachment
import org.mdpeds.logbook.domain.model.BackendException
import org.mdpeds.logbook.domain.model.FieldType
import org.mdpeds.logbook.domain.model.InstitutionSettings
import org.mdpeds.logbook.domain.model.Owner
import org.mdpeds.logbook.domain.model.ReviewAction
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.TemplateCategory
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.common.LoadViewModel
import org.mdpeds.logbook.ui.common.display
import org.mdpeds.logbook.ui.common.overview
import org.mdpeds.logbook.ui.common.staffList
import org.mdpeds.logbook.ui.common.userMessage
import org.mdpeds.logbook.util.Files
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

data class EditorArgs(val studentId: String, val code: String, val id: String?, val period: Int?, val slot: Int?)

data class EditorData(
    val session: UserSession,
    val reference: ReferenceData,
    val template: Template,
    val item: RecordItem?,
    val relation: Relation,
    val mode: EditorMode,
    val overview: StudentOverview?,
    val settings: InstitutionSettings,
    val ctx: FieldContext,
    val attachments: List<Attachment>,
    /** Non-null when a student opens a month that has not started yet. */
    val lockedMessage: String?,
    val period: Int?,
    val slot: Int?,
) {
    val submission: Submission? get() = item?.submission
    /** True when the record exists on the server (attachments and online actions are possible). */
    val onServer: Boolean get() = item != null && (!item.isLocalOnly || item.submission.version > 0)
    val canReopenOrCorrect: Boolean
        get() = submission?.let { AccessPolicy.canReopenOrCorrect(session.role, session.userId, relation, template, it) } == true
    val canDiscard: Boolean
        get() = mode == EditorMode.STUDENT_EDIT && submission?.let { AccessPolicy.canDiscardDraft(it) } == true
    val heading: String
        get() = when {
            template.category == TemplateCategory.MONTHLY -> "${template.title} · Month ${period ?: submission?.periodNo ?: ""}"
            template.category == TemplateCategory.ANNUAL -> "${template.title} · PG Year ${period ?: submission?.periodNo ?: ""}"
            slot != null || submission?.slotNo != null -> "${template.title} · ${slotLabel(template, slot ?: submission!!.slotNo!!)}"
            else -> template.title
        }
}

/** One-shot events for the editor screen. */
sealed interface EditorEvent {
    data object Close : EditorEvent
    data class Open(val file: File, val mime: String) : EditorEvent
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    repo: LogbookRepository,
    @ApplicationContext private val context: Context,
) : LoadViewModel<EditorArgs, EditorData>(repo) {

    private val _student = MutableStateFlow<Map<String, String>>(emptyMap())
    val student = _student.asStateFlow()
    private val _faculty = MutableStateFlow<Map<String, String>>(emptyMap())
    val faculty = _faculty.asStateFlow()
    private val _hod = MutableStateFlow<Map<String, String>>(emptyMap())
    val hod = _hod.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()
    private val _dirty = MutableStateFlow(false)
    val dirty = _dirty.asStateFlow()
    /** Faculty correction of an already reviewed record. */
    private val _correcting = MutableStateFlow(false)
    val correcting = _correcting.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** Id of the record once it exists (a new entry gets one on its first save). */
    private var recordId: String? = null
    private var loadedVersion: Int? = null
    private var data: EditorData? = null

    override suspend fun load(args: EditorArgs): Loaded<EditorData> {
        val me = repo.requireSession()
        val ref = repo.reference()
        val t = ref.template(args.code) ?: throw BackendException(404, "Unknown logbook section")
        val id = recordId ?: args.id
        var offline = false
        val item: RecordItem? = when {
            id != null -> repo.record(id)
            args.period != null || args.slot != null -> {
                val recs = repo.records(args.studentId, args.code)
                offline = recs.offline
                recs.value.firstOrNull { r ->
                    (args.period != null && r.submission.periodNo == args.period) || (args.slot != null && r.submission.slotNo == args.slot)
                }
            }
            else -> null
        }
        val ovLoad = runCatching { repo.overview(args.studentId) }.getOrNull()
        val ov = ovLoad?.value
        offline = offline || ovLoad?.offline == true
        val rel = Relation.of(me.role, me.userId, args.studentId, ov?.guideId, ov?.myAssignmentRole)
        var mode = AccessPolicy.editorMode(me.role, me.userId, rel, t, item?.submission)
        val settings = repo.settings()

        // Monthly forms open on the first day of the programme month.
        var locked: String? = null
        val period = args.period ?: item?.submission?.periodNo
        val start = ov?.programmeStartDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        if (mode == EditorMode.STUDENT_EDIT && t.category == TemplateCategory.MONTHLY && period != null) {
            if (start == null) {
                locked = "Your programme start date has not been set. Contact the HOD."
            } else if (!ProgrammeTimeline.isMonthOpen(start, period, LocalDate.now(), settings.allowFutureMonths)) {
                locked = "Month $period opens on ${ProgrammeTimeline.monthStart(start, period).display()}."
            }
            if (locked != null) mode = EditorMode.READ_ONLY
        }

        val staff = runCatching { repo.staffList().value }.getOrDefault(emptyList())
        val names = staff.associate { it.id to it.fullName }
        val facultyOptions = if (t.fields.any { it.type == FieldType.FACULTY } && mode == EditorMode.STUDENT_EDIT) {
            val assigned = runCatching { repo.backend.assignments(args.studentId).map { it.facultyId } }.getOrDefault(emptyList()) + listOfNotNull(ov?.guideId)
            staff.filter { it.id in assigned && it.active }.map { s -> s.id to (s.fullName + (s.designation?.let { " · $it" } ?: "")) }
        } else emptyList()

        val onServer = item != null && (!item.isLocalOnly || item.submission.version > 0)
        val attachments = if (onServer && t.fields.any { it.type == FieldType.ATTACHMENT }) {
            runCatching { repo.backend.attachments(item!!.submission.id) }.getOrDefault(emptyList())
        } else emptyList()

        return Loaded(
            EditorData(me, ref, t, item, rel, mode, ov, settings, FieldContext(ref, facultyOptions, names), attachments, locked, period, args.slot ?: item?.submission?.slotNo),
            offline,
        )
    }

    override fun onLoaded(value: EditorData) {
        data = value
        val s = value.submission
        if (s != null) recordId = s.id
        // Keep the user's unsaved edits when data reloads in the background.
        if (_dirty.value) return
        loadedVersion = s?.version
        val studentInit = (s?.studentData ?: emptyMap()).toMutableMap()
        if (value.template.slots.isNotEmpty() && value.slot != null && studentInit["milestone"].isNullOrBlank()) {
            studentInit["milestone"] = slotLabel(value.template, value.slot)
        }
        _student.value = studentInit
        _faculty.value = s?.facultyData ?: emptyMap()
        _hod.value = s?.hodData ?: emptyMap()
        _dirty.value = false
        _correcting.value = false
        _errors.value = emptyMap()
    }

    fun set(owner: String, key: String, value: String) {
        val target = when (owner) {
            Owner.FACULTY -> _faculty
            Owner.HOD -> _hod
            else -> _student
        }
        target.value = target.value + (key to value)
        _dirty.value = true
        if (key in _errors.value) _errors.value = _errors.value - key
    }

    fun startCorrection() {
        _correcting.value = true
    }

    fun cancelCorrection() {
        _correcting.value = false
        data?.let { _faculty.value = it.submission?.facultyData ?: emptyMap() }
    }

    private fun validate(owner: String, values: Map<String, String>, context: Map<String, String>): Boolean {
        val d = data ?: return false
        val errs = FormValidator.validate(d.template, owner, context + values)
            .filterKeys { k -> d.template.fields.any { it.key == k && it.owner == owner } }
        _errors.value = errs
        if (errs.isNotEmpty()) message("Please complete ${errs.size} highlighted field${if (errs.size == 1) "" else "s"}.")
        return errs.isEmpty()
    }

    // ------------------------------------------------------------ student

    fun saveStudent(submit: Boolean) {
        val d = data ?: return
        if (submit && !validate(Owner.STUDENT, _student.value, emptyMap())) return
        if (!submit) _errors.value = emptyMap()
        action {
            val item = d.item?.takeIf { it.submission.id == recordId || recordId == null }
                ?: recordId?.let { runCatching { repo.record(it) }.getOrNull() }
            val outcome = repo.saveDraft(d.template, item, d.period, d.slot, _student.value, submit)
            recordId = outcome.submission.id
            loadedVersion = null
            _dirty.value = false
            message(outcome.message ?: if (submit) "Submitted for review" else "Draft saved")
            if (submit && outcome.synced && outcome.submission.status.isSubmitted) _events.tryEmit(EditorEvent.Close)
            refresh(silent = true)
        }
    }

    fun discardDraft() {
        val item = data?.item ?: return
        action(success = "Draft deleted", onSuccess = { _events.tryEmit(EditorEvent.Close) }) { repo.deleteDraft(item) }
    }

    fun keepMine() {
        val id = recordId ?: return
        action {
            val r = repo.resolveKeepMine(id)
            message(if (r != null) "Your version was saved" else "Still offline — your version will be sent when you reconnect")
            loadedVersion = null
            _dirty.value = false
            refresh(silent = true)
        }
    }

    fun useServer() {
        val id = recordId ?: return
        action(success = "Local changes discarded") {
            repo.discardLocal(id)
            loadedVersion = null
            _dirty.value = false
            refresh(silent = true)
        }
    }

    fun retrySync() {
        val id = recordId ?: return
        action(success = "Retrying…") { repo.retry(id) }
    }

    // ------------------------------------------------------------ faculty / HOD

    private fun currentOrNull(): Submission? = data?.submission

    fun verify(comment: String?) {
        val s = currentOrNull() ?: return
        if (!validate(Owner.FACULTY, _faculty.value, s.studentData)) return
        action(success = "Verified", onSuccess = { _events.tryEmit(EditorEvent.Close) }) {
            repo.online { reviewSubmission(s.id, ReviewAction.VERIFY, _faculty.value, comment?.ifBlank { null }, s.version) }
            _dirty.value = false
        }
    }

    fun returnForCorrection(reason: String) {
        val s = currentOrNull() ?: return
        action(success = "Returned to the student", onSuccess = { _events.tryEmit(EditorEvent.Close) }) {
            repo.online { reviewSubmission(s.id, ReviewAction.RETURN, _faculty.value, reason, s.version) }
            _dirty.value = false
        }
    }

    fun hodComplete() {
        val s = currentOrNull() ?: return
        if (!validate(Owner.HOD, _hod.value, s.studentData + s.facultyData)) return
        action(success = "Monthly assessment completed", onSuccess = { _events.tryEmit(EditorEvent.Close) }) {
            repo.online { hodReviewSubmission(s.id, _hod.value, s.version) }
            _dirty.value = false
        }
    }

    fun reopen(reason: String) {
        val s = currentOrNull() ?: return
        action(success = "Reopened and returned to the student") {
            repo.online { reopenSubmission(s.id, reason) }
            loadedVersion = null
            _dirty.value = false
            refresh(silent = true)
        }
    }

    fun saveCorrection(reason: String) {
        val s = currentOrNull() ?: return
        if (!validate(Owner.FACULTY, _faculty.value, s.studentData)) return
        action(success = "Correction saved") {
            repo.online { correctReview(s.id, _faculty.value, reason) }
            loadedVersion = null
            _dirty.value = false
            _correcting.value = false
            refresh(silent = true)
        }
    }

    fun saveAnnual(submit: Boolean, reason: String?) {
        val d = data ?: return
        val year = d.period ?: return
        if (submit && !validate(Owner.FACULTY, _faculty.value, emptyMap())) return
        action(success = if (submit) "Annual review completed" else "Annual review saved") {
            val saved = repo.online {
                saveAnnualReview(boundArgs.studentId, year, _faculty.value, submit, reason, d.submission?.version)
            }
            recordId = saved.id
            loadedVersion = null
            _dirty.value = false
            if (submit) _events.tryEmit(EditorEvent.Close) else refresh(silent = true)
        }
    }

    // ------------------------------------------------------------ attachments

    fun upload(uri: Uri) {
        val d = data ?: return
        val s = d.submission ?: return
        action(success = "File attached") {
            val picked = withContext(Dispatchers.IO) {
                try {
                    Files.read(context, uri)
                } catch (e: IllegalArgumentException) {
                    throw BackendException(422, e.message ?: "File too large")
                }
            }
            repo.online { uploadAttachment(s.studentId, s.id, picked.name, picked.mime, picked.bytes) }
            refresh(silent = true)
        }
    }

    fun openAttachment(a: Attachment) {
        viewModelScope.launch {
            try {
                val bytes = repo.backend.downloadAttachment(a.storagePath)
                val file = withContext(Dispatchers.IO) { Files.write(context, a.fileName, bytes) }
                _events.tryEmit(EditorEvent.Open(file, a.mimeType ?: Files.mimeOf(a.fileName)))
            } catch (e: Exception) {
                message(e.userMessage())
            }
        }
    }

    val isStudentRole get() = data?.session?.role == Role.STUDENT
    val statusNow get() = data?.submission?.status ?: SubmissionStatus.NOT_STARTED
}
