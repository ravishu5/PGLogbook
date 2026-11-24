package org.mdpeds.logbook.data.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mdpeds.logbook.data.LogbookBackend
import org.mdpeds.logbook.domain.logic.AccessPolicy
import org.mdpeds.logbook.domain.logic.CertificationPolicy
import org.mdpeds.logbook.domain.logic.FormValidator
import org.mdpeds.logbook.domain.logic.ProgrammeTimeline
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.logic.Relation
import org.mdpeds.logbook.domain.logic.TargetEngine
import org.mdpeds.logbook.domain.model.AcademicDocument
import org.mdpeds.logbook.domain.model.AppNotification
import org.mdpeds.logbook.domain.model.Assignment
import org.mdpeds.logbook.domain.model.AssignmentRole
import org.mdpeds.logbook.domain.model.Attachment
import org.mdpeds.logbook.domain.model.AuditEntry
import org.mdpeds.logbook.domain.model.BackendException
import org.mdpeds.logbook.domain.model.Batch
import org.mdpeds.logbook.domain.model.Certificate
import org.mdpeds.logbook.domain.model.CertificateSnapshot
import org.mdpeds.logbook.domain.model.CertificationState
import org.mdpeds.logbook.domain.model.CertificationStatus
import org.mdpeds.logbook.domain.model.Competency
import org.mdpeds.logbook.domain.model.FeedbackEntry
import org.mdpeds.logbook.domain.model.FieldType
import org.mdpeds.logbook.domain.model.InstitutionSettings
import org.mdpeds.logbook.domain.model.Invitation
import org.mdpeds.logbook.domain.model.InvitationRequest
import org.mdpeds.logbook.domain.model.Option
import org.mdpeds.logbook.domain.model.Owner
import org.mdpeds.logbook.domain.model.ReviewAction
import org.mdpeds.logbook.domain.model.ReviewQueueItem
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.SaveRequest
import org.mdpeds.logbook.domain.model.Signatory
import org.mdpeds.logbook.domain.model.Signoff
import org.mdpeds.logbook.domain.model.SignoffAction
import org.mdpeds.logbook.domain.model.SignoffStatus
import org.mdpeds.logbook.domain.model.StaffMember
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.Target
import org.mdpeds.logbook.domain.model.TargetProgress
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.TemplateCategory
import org.mdpeds.logbook.domain.model.UserSession
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * In-memory backend for the demo flavor and tests. It enforces the same role-based rules, workflow
 * transitions, validation, notifications and audit trail as the Supabase RPCs, so the app behaves
 * identically. Data is reset when the app process restarts.
 */
class DemoBackend(
    private val reference: ReferenceData,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val latencyMs: Long = 0,
    seed: Boolean = true,
) : LogbookBackend {

    override val isDemo = true

    private class DemoUser(
        val id: String,
        var email: String,
        var password: String,
        var fullName: String,
        val role: Role,
        var designation: String?,
        var active: Boolean = true,
        var unit: String? = null,
        var isUnitHead: Boolean = false,
        val createdAt: String,
    )

    private class StudentProfile(
        var regNo: String?,
        var institution: String?,
        var university: String?,
        var batchId: String?,
        var guideId: String?,
        var currentPosting: String?,
    )

    private class Enrolment(var start: LocalDate, var duration: Int, var status: String = "ACTIVE")

    private val mutex = Mutex()
    private val users = linkedMapOf<String, DemoUser>()
    private val students = linkedMapOf<String, StudentProfile>()
    private val enrolments = linkedMapOf<String, Enrolment>()
    private val assignments = mutableListOf<Assignment>()
    private val invitations = mutableListOf<Invitation>()
    private val batches = mutableListOf<Batch>()
    private var settings = InstitutionSettings(institutionName = "Demo Medical College & Hospital", deanName = "Dr. K. Iyer")
    private val competencies = reference.competencies.toMutableList()
    private val targets = reference.targets.toMutableList()
    private val documents = reference.documents.toMutableList()
    private val submissions = linkedMapOf<String, Submission>()
    private val deleted = mutableSetOf<String>()
    private val signoffs = linkedMapOf<String, Signoff>()
    private val certificates = mutableListOf<Certificate>()
    private val feedback = mutableListOf<FeedbackEntry>()
    private val audit = mutableListOf<AuditEntry>()
    private val notifications = mutableListOf<Pair<String, AppNotification>>()
    private val attachments = mutableListOf<Attachment>()
    private val files = hashMapOf<String, ByteArray>()
    private var certificateSeq = 0
    private var auditSeq = 0L
    private var current: DemoUser? = null

    init {
        if (seed) DemoSeed(this).run()
    }

    // ================================================================ plumbing

    private suspend fun <T> op(block: () -> T): T = mutex.withLock {
        if (latencyMs > 0) delay(latencyMs)
        block()
    }

    private fun fail(code: Int, message: String): Nothing = throw BackendException(code, message)
    private fun today(): LocalDate = LocalDate.now(clock)
    private fun now(): String = Instant.now(clock).toString()
    private fun uuid() = UUID.randomUUID().toString()

    private fun me(): DemoUser = current ?: fail(401, "Authentication required")

    private fun requireRole(vararg roles: Role): DemoUser {
        val u = me()
        if (u.role !in roles) fail(403, "Operation not permitted for your role")
        return u
    }

    private fun relation(studentId: String): Relation {
        val u = me()
        val a = assignments.firstOrNull { it.studentId == studentId && it.facultyId == u.id && it.active }
        return Relation.of(u.role, u.id, studentId, students[studentId]?.guideId, a?.assignmentRole)
    }

    private fun canView(studentId: String) = AccessPolicy.canView(me().role, relation(studentId))
    private fun canReview(studentId: String) = AccessPolicy.canReviewStudent(me().role, relation(studentId))
    private fun isGuide(studentId: String) = relation(studentId).isGuide

    private fun template(code: String): Template = reference.template(code) ?: fail(404, "Unknown form template")

    private fun audit(action: String, entityType: String, entityId: String?, studentId: String?, reason: String? = null, changes: JsonObject? = null) {
        val u = current
        audit += AuditEntry(++auditSeq, now(), u?.role?.name, action, entityType, entityId, studentId, changes, reason)
    }

    private fun notify(recipient: String?, title: String, body: String, category: String, entityType: String? = null, entityId: String? = null, studentId: String? = null) {
        if (recipient == null || recipient == current?.id) return
        notifications += recipient to AppNotification(uuid(), title, body, category, entityType, entityId, studentId, null, now())
    }

    private fun reviewersOf(studentId: String): Set<String> =
        assignments.filter { it.studentId == studentId && it.active }.map { it.facultyId }.toSet() + listOfNotNull(students[studentId]?.guideId)

    private fun notifyReviewers(studentId: String, title: String, body: String, category: String, entityType: String?, entityId: String?) {
        reviewersOf(studentId).forEach { notify(it, title, body, category, entityType, entityId, studentId) }
    }

    private fun notifyHods(title: String, body: String, category: String, studentId: String?) {
        users.values.filter { it.role == Role.HOD && it.active }.forEach { notify(it.id, title, body, category, null, null, studentId) }
    }

    private fun recordFeedback(studentId: String, entityType: String, entityId: String, action: String, comment: String?) {
        val u = me()
        feedback += FeedbackEntry(uuid(), studentId, entityType, entityId, u.fullName, u.designation, action, comment, now())
    }

    private fun sessionOf(u: DemoUser) = UserSession(u.id, u.email, u.fullName, u.role, u.designation)

    private fun assertOwnedKeys(t: Template, owner: String, data: Map<String, String>) {
        val keys = t.fieldsFor(owner).map { it.key }.toSet()
        data.keys.firstOrNull { it !in keys }?.let { fail(403, "Field \"$it\" cannot be written by $owner") }
    }

    private fun validateOrFail(t: Template, owner: String, data: Map<String, String>) {
        val errors = FormValidator.validate(t, owner, data)
        if (errors.isNotEmpty()) fail(422, errors.values.joinToString("; "))
    }

    private fun assertMonthOpen(studentId: String, month: Int?) {
        val e = enrolments[studentId] ?: fail(409, "Programme start date has not been configured")
        if (month == null || month < 1 || month > e.duration) fail(422, "Programme month must be between 1 and ${e.duration}")
        val opens = ProgrammeTimeline.monthStart(e.start, month)
        if (!settings.allowFutureMonths && today().isBefore(opens)) fail(403, "Month $month opens on $opens")
    }

    private fun assertFacultyRefs(t: Template, studentId: String, data: Map<String, String>) {
        for (f in t.fieldsFor(Owner.STUDENT).filter { it.type == FieldType.FACULTY }) {
            val v = data[f.key]?.trim().orEmpty()
            if (v.isEmpty()) continue
            if (v !in reviewersOf(studentId)) fail(422, "${f.label} must be a faculty member assigned to you")
        }
    }

    private fun liveSubmission(id: String): Submission = submissions[id]?.takeIf { it.id !in deleted } ?: fail(404, "Record not found")

    private fun checkVersion(expected: Int?, actual: Int, what: String = "record") {
        if (expected != null && expected != actual) fail(409, "CONFLICT: $what changed on the server (server version $actual)")
    }

    // ================================================================ provisioning (mirrors app.provision_student)

    internal fun provision(studentId: String) {
        val duration = enrolments[studentId]?.duration ?: settings.programmeDurationMonths
        fun exists(code: String, period: Int?, slot: Int?) = submissions.values.any {
            it.studentId == studentId && it.templateCode == code && it.periodNo == period && it.slotNo == slot && it.id !in deleted
        }
        for (t in reference.templates) {
            when {
                t.category == TemplateCategory.MONTHLY -> (1..duration).forEach { i ->
                    if (!exists(t.code, i, null)) put(Submission(uuid(), studentId, t.code, t.version, periodNo = i, status = SubmissionStatus.NOT_STARTED, createdAt = now()))
                }
                t.category == TemplateCategory.ANNUAL -> (1..(t.periodCount ?: 3)).forEach { i ->
                    if (!exists(t.code, i, null)) put(Submission(uuid(), studentId, t.code, t.version, periodNo = i, status = SubmissionStatus.NOT_STARTED, createdAt = now()))
                }
                t.requiredInitial > 0 -> (1..t.requiredInitial).forEach { i ->
                    if (!exists(t.code, null, i)) {
                        val name = t.slots.getOrNull(i - 1)
                        put(Submission(uuid(), studentId, t.code, t.version, slotNo = i, status = SubmissionStatus.NOT_STARTED,
                            studentData = if (name != null) mapOf("milestone" to name) else emptyMap(), createdAt = now()))
                    }
                }
            }
        }
        for (c in competencies.filter { it.active }) {
            if (signoffs.values.none { it.studentId == studentId && it.competencyCode == c.code }) {
                val id = uuid()
                signoffs[id] = Signoff(id, studentId, c.code, updatedAt = now())
            }
        }
    }

    private fun put(s: Submission): Submission {
        submissions[s.id] = s
        return s
    }

    // ================================================================ authentication

    override suspend fun signIn(email: String, password: String): UserSession = op {
        val e = email.trim().lowercase(Locale.ROOT)
        var u = users.values.firstOrNull { it.email == e }
        if (u == null) {
            // Demo stand-in for "accept invitation + set password": a pending invitation can sign in with the demo password.
            val inv = invitations.firstOrNull { it.email == e && it.status == "PENDING" }
            if (inv != null && password == DEMO_PASSWORD) u = acceptInvitation(inv)
        }
        if (u == null || u.password != password) fail(400, "Incorrect email or password.")
        if (!u.active) fail(403, "Your account has been deactivated. Contact the HOD.")
        current = u
        sessionOf(u)
    }

    private fun acceptInvitation(inv: Invitation): DemoUser {
        val u = DemoUser(uuid(), inv.email, DEMO_PASSWORD, inv.fullName, inv.role, inv.designation, createdAt = now())
        users[u.id] = u
        if (inv.role == Role.STUDENT) {
            students[u.id] = StudentProfile(inv.universityRegNo, settings.institutionName, settings.universityName, inv.batchId, inv.guideId, null)
            enrolments[u.id] = Enrolment(LocalDate.parse(inv.programmeStartDate!!), settings.programmeDurationMonths)
            inv.guideId?.let { assignments += Assignment(uuid(), u.id, it, AssignmentRole.GUIDE, true, now()) }
            provision(u.id)
        }
        invitations.replaceAll { if (it.id == inv.id) it.copy(status = "ACCEPTED") else it }
        return u
    }

    override suspend fun restoreSession(): UserSession? = op { current?.takeIf { it.active }?.let(::sessionOf) }

    override suspend fun signOut() = op { current = null }

    override suspend fun sendPasswordReset(email: String) = op { Unit }

    override suspend fun completeAuthCallback(uri: String): UserSession =
        fail(400, "Email links are not used in demo mode. Sign in with the demo password.")

    override suspend fun setPassword(newPassword: String) = op {
        if (newPassword.length < 8) fail(422, "Password must be at least 8 characters")
        me().password = newPassword
    }

    // ================================================================ reference data

    override suspend fun templates() = op { reference.templates }
    override suspend fun roleOptions(): List<Option> = op { reference.roleOptions }
    override suspend fun competencies() = op { me(); competencies.filter { it.active }.sortedBy { it.sortOrder } }
    override suspend fun targets() = op { me(); targets.sortedBy { it.sortOrder } }
    override suspend fun documents() = op { me(); documents.sortedBy { it.sortOrder } }
    override suspend fun settings() = op { me(); settings }
    override suspend fun batches() = op { me(); batches.sortedBy { it.name } }

    // ================================================================ people

    private fun overview(studentId: String): StudentOverview {
        val p = users.getValue(studentId)
        val sp = students.getValue(studentId)
        val e = enrolments[studentId]
        val b = batches.firstOrNull { it.id == sp.batchId }
        val progMonth = e?.let { ProgrammeTimeline.programmeMonth(it.start, today()) } ?: 0
        val month = e?.let { minOf(progMonth, it.duration) }
        val subs = submissions.values.filter { it.studentId == studentId && it.id !in deleted }
        val cert = certificates.firstOrNull { it.studentId == studentId && it.status == "ISSUED" }
        val mine = current?.let { u -> assignments.firstOrNull { it.studentId == studentId && it.facultyId == u.id && it.active }?.assignmentRole }
        val logCodes = reference.templates.filter { it.category == TemplateCategory.LOG }.map { it.code }.toSet()
        return StudentOverview(
            studentId = studentId,
            fullName = p.fullName,
            email = p.email,
            active = p.active,
            universityRegNo = sp.regNo,
            institution = sp.institution,
            university = sp.university,
            currentPosting = sp.currentPosting,
            batchId = sp.batchId,
            batchName = b?.name,
            academicYear = b?.academicYear,
            guideId = sp.guideId,
            guideName = sp.guideId?.let { users[it]?.fullName },
            programmeStartDate = e?.start?.toString(),
            durationMonths = e?.duration,
            enrolmentStatus = e?.status,
            completionDate = e?.let { ProgrammeTimeline.completionDate(it.start, it.duration).toString() },
            currentMonth = month,
            pgYear = month?.let { ProgrammeTimeline.pgYear(it) },
            myAssignmentRole = mine,
            monthlyCompleted = subs.count { it.templateCode == "MONTHLY" && it.status == SubmissionStatus.COMPLETED },
            monthlyOverdueOrOpen = subs.count {
                it.templateCode == "MONTHLY" && (it.periodNo ?: 0) <= progMonth && it.status.isStudentEditable
            },
            pendingReviews = subs.count {
                it.status == SubmissionStatus.SUBMITTED || it.status == SubmissionStatus.FACULTY_REVIEW_PENDING || it.status == SubmissionStatus.HOD_REVIEW_PENDING
            },
            logEntries = subs.count { it.templateCode in logCodes && it.status != SubmissionStatus.NOT_STARTED },
            signoffsApproved = signoffs.values.count { it.studentId == studentId && it.status == SignoffStatus.APPROVED },
            signoffsTotal = competencies.count { it.active },
            annualReviewsCompleted = subs.count { it.templateCode == "ANNUAL_REVIEW" && it.status == SubmissionStatus.COMPLETED },
            certificateNo = cert?.certificateNo,
            certificateIssuedAt = cert?.issuedAt,
        )
    }

    override suspend fun studentOverviews() = op {
        me()
        students.keys.filter { canView(it) }.map { overview(it) }.sortedBy { it.fullName }
    }

    override suspend fun studentOverview(studentId: String) = op {
        if (studentId !in students || !canView(studentId)) fail(404, "Student not found or not assigned to you")
        overview(studentId)
    }

    override suspend fun staff() = op {
        me()
        users.values.filter { it.role != Role.STUDENT }
            .map { StaffMember(it.id, it.fullName, it.email, it.role, it.designation, it.active, it.unit, it.isUnitHead) }
            .sortedBy { it.fullName }
    }

    override suspend fun assignments(studentId: String) = op {
        if (!canView(studentId)) fail(403, "Not permitted")
        assignments.filter { it.studentId == studentId && it.active }
    }

    // ================================================================ records

    override suspend fun submissions(studentId: String, templateCode: String?) = op {
        val u = me()
        if (!canView(studentId)) fail(403, "Not permitted")
        submissions.values.filter { s ->
            s.studentId == studentId && s.id !in deleted && (templateCode == null || s.templateCode == templateCode) &&
                (u.role != Role.STUDENT || s.templateCode != "ANNUAL_REVIEW" || s.status == SubmissionStatus.COMPLETED)
        }.sortedWith(compareBy<Submission>({ it.periodNo ?: Int.MAX_VALUE }, { it.slotNo ?: Int.MAX_VALUE }).thenByDescending { it.entryDate ?: "" }.thenByDescending { it.createdAt ?: "" })
    }

    override suspend fun submission(id: String) = op {
        val s = liveSubmission(id)
        if (!AccessPolicy.canSeeSubmission(me().role, relation(s.studentId), s)) fail(404, "Record not found")
        s
    }

    override suspend fun saveSubmission(request: SaveRequest): Submission = op {
        val u = requireRole(Role.STUDENT)
        val t = template(request.templateCode)
        if (t.category == TemplateCategory.ANNUAL) fail(403, "Annual comprehensive reviews are completed by faculty only")
        val readOnly = t.fieldsFor(Owner.STUDENT).filter { it.readOnly }.map { it.key }.toSet()
        assertOwnedKeys(t, Owner.STUDENT, request.studentData)

        var row = request.id?.let { submissions[it] }
        if (row == null && request.periodNo != null) {
            row = submissions.values.firstOrNull { it.studentId == u.id && it.templateCode == t.code && it.periodNo == request.periodNo }
        }
        if (row == null && request.slotNo != null) {
            row = submissions.values.firstOrNull { it.studentId == u.id && it.templateCode == t.code && it.slotNo == request.slotNo && it.id !in deleted }
        }
        if (t.category == TemplateCategory.MONTHLY) assertMonthOpen(u.id, row?.periodNo ?: request.periodNo)
        if (row != null) {
            if (row.studentId != u.id || row.templateCode != t.code) fail(403, "You can only edit your own records")
            if (row.id in deleted) fail(404, "Record was deleted")
            if (!row.status.isStudentEditable) fail(409, "Record is locked while ${row.status.name}")
            checkVersion(request.expectedVersion, row.version)
        }
        val data = request.studentData.filterKeys { it !in readOnly } + (row?.studentData?.filterKeys { it in readOnly } ?: emptyMap())
        assertFacultyRefs(t, u.id, data)
        val codes = data["competencies"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()
        if (codes.any { c -> competencies.none { it.code == c } }) fail(422, "Unknown competency code")
        val date = runCatching { LocalDate.parse(data[t.dateField ?: "date"]).toString() }.getOrNull()
        val assessor = if (t.reviewer == "ASSESSOR") data["assessor"]?.takeIf { it.isNotBlank() } else null
        val saved = if (row == null) {
            Submission(
                id = request.id ?: uuid(), studentId = u.id, templateCode = t.code, templateVersion = t.version,
                periodNo = if (t.category == TemplateCategory.MONTHLY) request.periodNo else null, slotNo = null,
                status = SubmissionStatus.DRAFT, studentData = data, competencyCodes = codes, entryDate = date,
                assessorId = assessor, version = 1, createdAt = now(), updatedAt = now(),
            )
        } else {
            row.copy(
                studentData = data, competencyCodes = codes, entryDate = date, assessorId = assessor,
                status = if (row.status == SubmissionStatus.NOT_STARTED) SubmissionStatus.DRAFT else row.status,
                version = row.version + 1, updatedAt = now(),
            )
        }
        put(saved)
        audit("SAVE_DRAFT", "submission", saved.id, u.id)
        saved
    }

    override suspend fun submitSubmission(id: String, expectedVersion: Int?): Submission = op {
        val u = requireRole(Role.STUDENT)
        val row = liveSubmission(id)
        if (row.studentId != u.id) fail(403, "You can only submit your own records")
        val t = template(row.templateCode)
        if (t.category == TemplateCategory.ANNUAL) fail(403, "Annual comprehensive reviews are completed by faculty only")
        if (row.status == SubmissionStatus.SUBMITTED || row.status == SubmissionStatus.FACULTY_REVIEW_PENDING) return@op row
        if (row.status != SubmissionStatus.DRAFT && row.status != SubmissionStatus.RETURNED) fail(409, "Record cannot be submitted while ${row.status.name}")
        checkVersion(expectedVersion, row.version)
        if (t.category == TemplateCategory.MONTHLY) assertMonthOpen(u.id, row.periodNo)
        validateOrFail(t, Owner.STUDENT, row.studentData)
        val hasFaculty = reviewersOf(u.id).isNotEmpty()
        val status = if (t.category == TemplateCategory.MONTHLY && hasFaculty) SubmissionStatus.FACULTY_REVIEW_PENDING else SubmissionStatus.SUBMITTED
        val saved = put(row.copy(status = status, submittedAt = now(), submittedByName = u.fullName, returnReason = null, version = row.version + 1, updatedAt = now()))
        audit(if (row.status == SubmissionStatus.RETURNED) "RESUBMIT" else "SUBMIT", "submission", id, u.id)
        if (saved.assessorId != null) {
            notify(saved.assessorId, "${t.title} awaiting assessment", "${u.fullName} submitted a ${t.title} for your assessment.", "REVIEW_REQUEST", "submission", id, u.id)
        } else {
            val period = saved.periodNo?.let { " (period $it)" }.orEmpty()
            notifyReviewers(u.id, "${t.title} submitted", "${u.fullName} submitted a ${t.title}$period for review.", "REVIEW_REQUEST", "submission", id)
        }
        saved
    }

    override suspend fun deleteDraft(id: String) = op {
        val u = requireRole(Role.STUDENT)
        val row = submissions[id]?.takeIf { it.studentId == u.id } ?: fail(404, "Record not found")
        if (!AccessPolicy.canDiscardDraft(row)) fail(403, "Only unsubmitted additional drafts can be discarded")
        deleted += id
        put(row.copy(version = row.version + 1, updatedAt = now()))
        audit("DISCARD_DRAFT", "submission", id, u.id)
    }

    override suspend fun reviewSubmission(id: String, action: ReviewAction, facultyData: Map<String, String>, comment: String?, expectedVersion: Int?): Submission = op {
        val u = requireRole(Role.FACULTY, Role.HOD)
        val row = liveSubmission(id)
        if (!canReview(row.studentId)) fail(403, "You are not assigned to this student")
        val t = template(row.templateCode)
        if (t.category == TemplateCategory.ANNUAL) fail(400, "Use save_annual_review for annual comprehensive reviews")
        if (t.reviewer == "ASSESSOR" && u.role != Role.HOD && row.assessorId != u.id) fail(403, "Only the selected assessor can assess this form")
        if (t.reviewer == "GUIDE" && u.role != Role.HOD && !isGuide(row.studentId)) fail(403, "Only the student's guide can review dissertation milestones")
        if (!row.status.isAwaitingFaculty) fail(409, "Record is not awaiting faculty review (${row.status.name})")
        checkVersion(expectedVersion, row.version)
        assertOwnedKeys(t, Owner.FACULTY, facultyData)
        val fdata = row.facultyData + facultyData
        val status = when (action) {
            ReviewAction.VERIFY -> {
                validateOrFail(t, Owner.FACULTY, fdata)
                when {
                    t.category == TemplateCategory.MONTHLY && settings.monthlyHodReviewRequired -> SubmissionStatus.HOD_REVIEW_PENDING
                    t.category == TemplateCategory.MONTHLY -> SubmissionStatus.COMPLETED
                    else -> SubmissionStatus.VERIFIED
                }
            }
            ReviewAction.RETURN -> {
                if (comment.isNullOrBlank()) fail(422, "A reason is required when returning a record")
                SubmissionStatus.RETURNED
            }
        }
        val saved = put(
            row.copy(
                facultyData = fdata, status = status, reviewedBy = u.id, reviewedByName = u.fullName,
                reviewedByDesignation = u.designation, reviewedAt = now(),
                returnReason = if (action == ReviewAction.RETURN) comment else null,
                version = row.version + 1, updatedAt = now(),
            ),
        )
        audit(action.name, "submission", id, row.studentId, comment)
        recordFeedback(row.studentId, "submission", id, action.name,
            comment ?: fdata["faculty_feedback"] ?: fdata["guide_feedback"] ?: fdata["feedback"] ?: fdata["faculty_comments"])
        if (action == ReviewAction.VERIFY) {
            notify(row.studentId, "${t.title} verified", "${u.fullName} verified your ${t.title}.", "VERIFIED", "submission", id, row.studentId)
            if (status == SubmissionStatus.HOD_REVIEW_PENDING) {
                notifyHods("Monthly assessment awaiting HOD review", "Month ${row.periodNo} assessment has been reviewed by faculty.", "REVIEW_REQUEST", row.studentId)
            }
        } else {
            notify(row.studentId, "${t.title} returned for correction", "${u.fullName}: $comment", "RETURNED", "submission", id, row.studentId)
        }
        saved
    }

    override suspend fun hodReviewSubmission(id: String, hodData: Map<String, String>, expectedVersion: Int?): Submission = op {
        val u = requireRole(Role.FACULTY, Role.HOD)
        val row = liveSubmission(id)
        val t = template(row.templateCode)
        if (t.category != TemplateCategory.MONTHLY) fail(400, "HOD/mentor review applies to monthly assessments only")
        if (!(u.role == Role.HOD || isGuide(row.studentId))) fail(403, "Only the HOD or the student's mentor can complete this review")
        if (!row.status.isAwaitingHod) fail(409, "Record is not awaiting HOD/mentor review (${row.status.name})")
        checkVersion(expectedVersion, row.version)
        assertOwnedKeys(t, Owner.HOD, hodData)
        val hdata = row.hodData + hodData
        validateOrFail(t, Owner.HOD, hdata)
        val saved = put(row.copy(hodData = hdata, status = SubmissionStatus.COMPLETED, hodReviewedBy = u.id, hodReviewedByName = u.fullName,
            hodReviewedAt = now(), version = row.version + 1, updatedAt = now()))
        audit("HOD_REVIEW", "submission", id, row.studentId)
        recordFeedback(row.studentId, "submission", id, "HOD_REVIEW", hdata["hod_review"])
        notify(row.studentId, "Monthly assessment completed", "Month ${row.periodNo} assessment was completed by ${u.fullName}.", "VERIFIED", "submission", id, row.studentId)
        saved
    }

    override suspend fun reopenSubmission(id: String, reason: String): Submission = op {
        val u = requireRole(Role.FACULTY, Role.HOD)
        if (reason.isBlank()) fail(422, "A reason is required")
        val row = liveSubmission(id)
        val t = template(row.templateCode)
        if (t.category == TemplateCategory.ANNUAL) fail(400, "Annual reviews are corrected through save_annual_review with a reason")
        if (!canReview(row.studentId)) fail(403, "You are not assigned to this student")
        if (!row.status.isReviewed) fail(409, "Only reviewed records can be reopened")
        if (u.role != Role.HOD && row.reviewedBy != u.id) fail(403, "Only the reviewing faculty member or the HOD can reopen this record")
        val saved = put(row.copy(status = SubmissionStatus.RETURNED, returnReason = reason, version = row.version + 1, updatedAt = now()))
        audit("REOPEN", "submission", id, row.studentId, reason)
        recordFeedback(row.studentId, "submission", id, "REOPEN", reason)
        notify(row.studentId, "${t.title} returned for correction", reason, "RETURNED", "submission", id, row.studentId)
        saved
    }

    override suspend fun correctReview(id: String, facultyData: Map<String, String>, reason: String): Submission = op {
        val u = requireRole(Role.FACULTY, Role.HOD)
        if (reason.isBlank()) fail(422, "A correction reason is required")
        val row = liveSubmission(id)
        if (row.templateCode == "ANNUAL_REVIEW") fail(400, "Use save_annual_review with a reason")
        if (!canReview(row.studentId)) fail(403, "You are not assigned to this student")
        if (!row.status.isReviewed) fail(409, "Only reviewed records can be corrected")
        if (u.role != Role.HOD && row.reviewedBy != u.id) fail(403, "Another faculty member's approved assessment can only be corrected by the HOD")
        val t = template(row.templateCode)
        assertOwnedKeys(t, Owner.FACULTY, facultyData)
        val fdata = row.facultyData + facultyData
        validateOrFail(t, Owner.FACULTY, fdata)
        val saved = put(row.copy(facultyData = fdata, version = row.version + 1, updatedAt = now()))
        audit("CORRECTION", "submission", id, row.studentId, reason)
        recordFeedback(row.studentId, "submission", id, "CORRECTION", reason)
        saved
    }

    override suspend fun saveAnnualReview(studentId: String, year: Int, data: Map<String, String>, submit: Boolean, reason: String?, expectedVersion: Int?): Submission = op {
        val u = requireRole(Role.FACULTY, Role.HOD)
        if (!canReview(studentId)) fail(403, "You are not assigned to this student")
        val t = template("ANNUAL_REVIEW")
        if (year < 1 || year > (t.periodCount ?: 3)) fail(422, "PG year must be 1, 2 or 3")
        assertOwnedKeys(t, Owner.FACULTY, data)
        val row = submissions.values.firstOrNull { it.studentId == studentId && it.templateCode == t.code && it.periodNo == year }
            ?: put(Submission(uuid(), studentId, t.code, t.version, periodNo = year, status = SubmissionStatus.NOT_STARTED, createdAt = now()))
        checkVersion(expectedVersion, row.version)
        val correction = row.status == SubmissionStatus.COMPLETED
        if (correction) {
            if (reason.isNullOrBlank()) fail(409, "Completed annual reviews can only be changed through a correction with a reason")
            if (u.role != Role.HOD && row.reviewedBy != u.id) fail(403, "Only the submitting faculty member or the HOD can correct this review")
        }
        val fd = row.facultyData.toMutableMap()
        for (d in t.domains) {
            val rk = "${d.key}_rating"
            val ck = "${d.key}_comments"
            val changed = (rk in data && data[rk] != fd[rk]) || (ck in data && data[ck] != fd[ck])
            if (!changed) continue
            val owner = fd["${d.key}_faculty_id"]
            if (owner != null && owner != u.id && u.role != Role.HOD && reason == null) {
                fail(403, "${d.label} was assessed by another faculty member; submit a correction with a reason")
            }
            data[rk]?.let { fd[rk] = it }
            data[ck]?.let { fd[ck] = it }
            fd["${d.key}_faculty_id"] = u.id
            fd["${d.key}_faculty_name"] = u.fullName
            fd["${d.key}_updated_at"] = now()
        }
        data["narrative_feedback"]?.let { fd["narrative_feedback"] = it }
        if (submit || correction) validateOrFail(t, Owner.FACULTY, fd)
        val action = when {
            correction -> "CORRECTION"
            submit -> "ANNUAL_SUBMIT"
            else -> "ANNUAL_SAVE"
        }
        val first = submit && !correction
        val saved = put(
            row.copy(
                facultyData = fd,
                status = if (submit || correction) SubmissionStatus.COMPLETED else SubmissionStatus.DRAFT,
                reviewedBy = if (first) u.id else row.reviewedBy,
                reviewedByName = if (first) u.fullName else row.reviewedByName,
                reviewedByDesignation = if (first) u.designation else row.reviewedByDesignation,
                reviewedAt = if (first) now() else row.reviewedAt,
                version = row.version + 1, updatedAt = now(),
            ),
        )
        audit(action, "submission", saved.id, studentId, reason)
        if (submit || correction) recordFeedback(studentId, "annual_review", saved.id, action, reason ?: fd["narrative_feedback"])
        if (first) notify(studentId, "PGY-$year annual review completed", "Your PGY-$year annual comprehensive review is now available.", "ANNUAL", "submission", saved.id, studentId)
        saved
    }

    override suspend fun targetProgress(studentId: String): List<TargetProgress> = op {
        if (!canView(studentId)) fail(403, "Not permitted")
        TargetEngine.progress(studentId, targets, reference.templates, submissions.values.filter { it.studentId == studentId && it.id !in deleted })
    }

    // ================================================================ evidence

    override suspend fun attachments(submissionId: String) = op {
        val s = liveSubmission(submissionId)
        if (!canView(s.studentId)) fail(403, "Not permitted")
        attachments.filter { it.submissionId == submissionId }
    }

    override suspend fun uploadAttachment(studentId: String, submissionId: String, fileName: String, mimeType: String, bytes: ByteArray): Attachment = op {
        val u = requireRole(Role.STUDENT)
        val s = submissions[submissionId]
        if (s == null || s.studentId != u.id || studentId != u.id || s.id in deleted || !s.status.isStudentEditable) {
            fail(403, "Evidence can only be attached to your own editable records")
        }
        if (bytes.size > 10 * 1024 * 1024) fail(422, "Files must be 10 MB or smaller")
        if (mimeType !in setOf("application/pdf", "image/jpeg", "image/png", "image/webp", "image/heic")) fail(422, "Only PDF and image files are allowed")
        val path = "$studentId/$submissionId/${uuid()}-$fileName"
        files[path] = bytes
        val a = Attachment(uuid(), submissionId, studentId, path, fileName, mimeType, bytes.size.toLong(), now())
        attachments += a
        audit("ATTACH", "attachment", a.id, studentId)
        a
    }

    override suspend fun downloadAttachment(storagePath: String): ByteArray = op {
        val a = attachments.firstOrNull { it.storagePath == storagePath } ?: fail(404, "File not found")
        if (!canView(a.studentId)) fail(403, "Not permitted")
        files[storagePath] ?: fail(404, "File not found")
    }

    // ================================================================ sign-off & certification

    override suspend fun signoffs(studentId: String) = op {
        if (!canView(studentId)) fail(403, "Not permitted")
        val order = competencies.associate { it.code to it.sortOrder }
        signoffs.values.filter { it.studentId == studentId }.sortedBy { order[it.competencyCode] ?: 999 }
    }

    override suspend fun requestSignoff(code: String, note: String?): Signoff = op {
        val u = requireRole(Role.STUDENT)
        val row = signoffs.values.firstOrNull { it.studentId == u.id && it.competencyCode == code } ?: fail(404, "Competency not found")
        if (row.status == SignoffStatus.APPROVED) fail(409, "Competency already approved")
        if (row.status == SignoffStatus.AWAITING_APPROVAL) return@op row
        val saved = row.copy(status = SignoffStatus.AWAITING_APPROVAL, studentNote = note, version = row.version + 1, updatedAt = now())
        signoffs[row.id] = saved
        audit("SIGNOFF_REQUEST", "competency_signoff", row.id, u.id)
        notifyReviewers(u.id, "Competency awaiting sign-off", "${u.fullName} requested final sign-off for $code.", "SIGNOFF", "competency_signoff", row.id)
        saved
    }

    override suspend fun facultySignoff(studentId: String, code: String, action: SignoffAction, comment: String?, expectedVersion: Int?): Signoff = op {
        val u = requireRole(Role.FACULTY, Role.HOD)
        if (!canReview(studentId)) fail(403, "You are not assigned to this student")
        val row = signoffs.values.firstOrNull { it.studentId == studentId && it.competencyCode == code } ?: fail(404, "Competency not found")
        checkVersion(expectedVersion, row.version, "sign-off")
        if (row.status == SignoffStatus.APPROVED && action != SignoffAction.REVOKE) {
            fail(409, "Competency already approved; the HOD must revoke it with a reason before changes")
        }
        val progress = if (row.status == SignoffStatus.NOT_STARTED || row.status == SignoffStatus.RETURNED) SignoffStatus.IN_PROGRESS else row.status
        val t = now()
        val saved = when (action) {
            SignoffAction.OBSERVED -> row.copy(observedAt = t, observedByName = u.fullName, status = progress)
            SignoffAction.SUPERVISED -> row.copy(supervisedAt = t, supervisedByName = u.fullName, status = progress)
            SignoffAction.INDEPENDENT -> row.copy(independentAt = t, independentByName = u.fullName, status = progress)
            SignoffAction.APPROVE -> {
                if (row.independentAt == null) fail(422, "Record the independent performance stage before final sign-off")
                row.copy(status = SignoffStatus.APPROVED, finalBy = u.id, finalByName = u.fullName, finalByDesignation = u.designation, finalAt = t, comment = comment)
            }
            SignoffAction.RETURN -> {
                if (comment.isNullOrBlank()) fail(422, "A comment is required when returning for further training")
                row.copy(status = SignoffStatus.RETURNED, comment = comment)
            }
            SignoffAction.REVOKE -> {
                if (u.role != Role.HOD) fail(403, "Only the HOD can revoke an approved sign-off")
                if (comment.isNullOrBlank()) fail(422, "A reason is required")
                if (certificates.any { it.studentId == studentId && it.status == "ISSUED" }) fail(409, "Revoke the issued certificate first")
                row.copy(status = SignoffStatus.RETURNED, comment = comment, finalBy = null, finalByName = null, finalByDesignation = null, finalAt = null)
            }
        }.copy(version = row.version + 1, updatedAt = t)
        signoffs[row.id] = saved
        audit("SIGNOFF_${action.name}", "competency_signoff", row.id, studentId, comment)
        recordFeedback(studentId, "competency_signoff", row.id, "SIGNOFF_${action.name}", comment)
        when (action) {
            SignoffAction.APPROVE -> {
                notify(studentId, "Competency approved", "$code final sign-off approved by ${u.fullName}.", "SIGNOFF", "competency_signoff", row.id, studentId)
                evaluateCertification(studentId)
            }
            SignoffAction.RETURN -> notify(studentId, "Competency returned for further training", "$code: $comment", "RETURNED", "competency_signoff", row.id, studentId)
            else -> Unit
        }
        saved
    }

    private fun certificationState(studentId: String): CertificationState {
        val e = enrolments[studentId]
        val activeCodes = competencies.filter { it.active }.map { it.code }.toSet()
        val approved = signoffs.values.count { it.studentId == studentId && it.status == SignoffStatus.APPROVED && it.competencyCode in activeCodes }
        val cert = certificates.firstOrNull { it.studentId == studentId && it.status == "ISSUED" }
        return CertificationPolicy.evaluate(
            approved = approved,
            total = activeCodes.size,
            completionDate = e?.let { ProgrammeTimeline.completionDate(it.start, it.duration) },
            today = today(),
            requireProgrammeCompletion = settings.requireProgrammeCompletion,
            issuedCertificateId = cert?.id,
            issuedCertificateNo = cert?.certificateNo,
        )
    }

    internal fun evaluateCertification(studentId: String): CertificationState {
        val state = certificationState(studentId)
        if (state.status == CertificationStatus.READY_FOR_CERTIFICATION && settings.autoIssueCertificate) {
            issueInternal(studentId, null)
            return certificationState(studentId)
        }
        return state
    }

    private fun issueInternal(studentId: String, issuer: DemoUser?): Certificate {
        val p = users.getValue(studentId)
        val sp = students.getValue(studentId)
        val e = enrolments.getValue(studentId)
        val b = batches.firstOrNull { it.id == sp.batchId }
        val guide = sp.guideId?.let { users[it] }
        val guideVerified = signoffs.values.any { it.studentId == studentId && it.finalBy == sp.guideId && it.status == SignoffStatus.APPROVED }
        val unitHead = assignments.filter { it.studentId == studentId && it.active }.sortedBy { it.assignedAt }
            .mapNotNull { users[it.facultyId] }.firstOrNull { it.isUnitHead }
        val hod = issuer ?: users.values.filter { it.role == Role.HOD && it.active }.minByOrNull { it.createdAt }
        val no = CertificationPolicy.certificateNumber(settings.certificatePrefix, today().year, ++certificateSeq)
        val issueDate = today()
        val snapshot = CertificateSnapshot(
            institutionName = settings.institutionName,
            departmentName = settings.departmentName,
            universityName = sp.university ?: settings.universityName,
            studentName = p.fullName,
            universityRegNo = sp.regNo,
            course = "MD Pediatrics",
            batch = b?.let { "${it.name} (${it.academicYear})" },
            programmeStartDate = e.start.toString(),
            programmeCompletionDate = ProgrammeTimeline.completionDate(e.start, e.duration).toString(),
            issueDate = issueDate.toString(),
            certificateNo = no,
            competenciesApproved = signoffs.values.count { it.studentId == studentId && it.status == SignoffStatus.APPROVED },
            guideName = guide?.fullName,
            statement = "Certified that the resident has maintained this logbook during postgraduate training and has undergone periodic assessment. Final certification is subject to fulfillment of the current NMC/university requirements and institutional assessment policy.",
            disclaimer = "This certificate records institutional logbook completion only and is not a degree or university qualification.",
            signatories = listOf(
                Signatory("Guide / Mentor", guide?.fullName, if (guideVerified) "Competency sign-offs approved electronically" else null),
                Signatory("Unit Head", unitHead?.fullName, null),
                Signatory("Head of Department", hod?.fullName,
                    if (issuer != null) "Issued electronically on ${issueDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))}" else null),
                Signatory(settings.deanTitle, settings.deanName, null),
            ),
        )
        val cert = Certificate(uuid(), studentId, no, now(), issuer?.fullName ?: "System (prerequisites verified)", snapshot)
        certificates += cert
        audit("CERTIFICATE_ISSUE", "certificate", cert.id, studentId)
        notify(studentId, "Certificate available", "Your MD Pediatrics logbook completion certificate $no has been issued.", "CERTIFICATE", "certificate", cert.id, studentId)
        notifyHods("Certificate issued", "${p.fullName} – $no", "CERTIFICATE", studentId)
        return cert
    }

    override suspend fun certificationStatus(studentId: String) = op {
        if (!canView(studentId)) fail(403, "Not permitted")
        evaluateCertification(studentId)
    }

    override suspend fun certificate(studentId: String) = op {
        if (!canView(studentId)) fail(403, "Not permitted")
        certificates.firstOrNull { it.studentId == studentId && it.status == "ISSUED" }
    }

    override suspend fun issueCertificate(studentId: String) = op {
        val u = requireRole(Role.HOD)
        val state = certificationState(studentId)
        if (state.status == CertificationStatus.CERTIFICATE_ISSUED) fail(409, "A certificate has already been issued")
        if (state.status != CertificationStatus.READY_FOR_CERTIFICATION) fail(409, "Certification prerequisites not met: " + state.reasons.joinToString("; "))
        issueInternal(studentId, u)
    }

    override suspend fun revokeCertificate(studentId: String, reason: String) = op {
        requireRole(Role.HOD)
        if (reason.isBlank()) fail(422, "A reason is required")
        certificates.replaceAll {
            if (it.studentId == studentId && it.status == "ISSUED") it.copy(status = "REVOKED", revokedAt = now(), revokedReason = reason) else it
        }
        audit("CERTIFICATE_REVOKE", "certificate", null, studentId, reason)
    }

    // ================================================================ queue, notifications, history

    override suspend fun reviewQueue(): List<ReviewQueueItem> = op {
        val u = me()
        if (u.role == Role.STUDENT) return@op emptyList()
        val subs = submissions.values.filter { it.id !in deleted }.mapNotNull { s ->
            val t = reference.template(s.templateCode) ?: return@mapNotNull null
            val rel = relation(s.studentId)
            val show = AccessPolicy.canFacultyReview(u.role, u.id, rel, t, s) || AccessPolicy.canHodReview(u.role, rel, t, s)
            if (!show) null else ReviewQueueItem("SUBMISSION", s.id, s.studentId, users[s.studentId]?.fullName.orEmpty(), s.templateCode, t.title,
                s.periodNo, s.slotNo, s.status.name, s.entryDate, s.submittedAt, s.version)
        }
        val titles = competencies.associate { it.code to it.title }
        val signs = signoffs.values.filter { it.status == SignoffStatus.AWAITING_APPROVAL && canReview(it.studentId) }.map {
            ReviewQueueItem("SIGNOFF", it.id, it.studentId, users[it.studentId]?.fullName.orEmpty(), it.competencyCode,
                titles[it.competencyCode] ?: it.competencyCode, null, null, it.status.name, null, it.updatedAt, it.version)
        }
        (subs + signs).sortedBy { it.submittedAt ?: "" }
    }

    override suspend fun notifications() = op {
        val u = me()
        notifications.filter { it.first == u.id }.map { it.second }.sortedByDescending { it.createdAt }
    }

    override suspend fun markNotificationsRead(ids: List<String>?) = op {
        val u = me()
        val t = now()
        notifications.replaceAll { (r, n) ->
            if (r == u.id && n.readAt == null && (ids == null || n.id in ids)) r to n.copy(readAt = t) else r to n
        }
    }

    override suspend fun feedback(studentId: String) = op {
        if (!canView(studentId)) fail(403, "Not permitted")
        feedback.filter { it.studentId == studentId }.sortedByDescending { it.createdAt }
    }

    override suspend fun auditLog(studentId: String?) = op {
        val u = me()
        when {
            u.role == Role.HOD -> audit.filter { studentId == null || it.studentId == studentId }
            studentId != null && canView(studentId) -> audit.filter { it.studentId == studentId }
            else -> fail(403, "Not permitted")
        }.sortedByDescending { it.id }.take(300)
    }

    // ================================================================ HOD administration

    override suspend fun invitations() = op {
        requireRole(Role.HOD)
        invitations.sortedByDescending { it.createdAt }
    }

    override suspend fun createInvitation(request: InvitationRequest): Invitation = op {
        val u = requireRole(Role.HOD)
        val email = request.email.trim().lowercase(Locale.ROOT)
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) fail(422, "A valid email address is required")
        if (request.fullName.isBlank()) fail(422, "Full name is required")
        if (request.role == Role.STUDENT && request.programmeStartDate.isNullOrBlank()) fail(422, "Programme start date is required for students")
        if (request.guideId != null && users[request.guideId]?.let { it.role != Role.STUDENT && it.active } != true) fail(422, "Guide must be an active faculty member")
        if (users.values.any { it.email == email }) fail(409, "An account with this email already exists")
        if (invitations.any { it.email == email && it.status == "PENDING" }) fail(409, "A pending invitation already exists for this email")
        val inv = Invitation(uuid(), email, request.fullName.trim(), request.role, request.designation, request.universityRegNo,
            request.batchId, request.programmeStartDate, request.guideId, "PENDING", now())
        invitations += inv
        audit("INVITE", "invitation", inv.id, null)
        inv
    }

    override suspend fun revokeInvitation(id: String) = op {
        requireRole(Role.HOD)
        invitations.replaceAll { if (it.id == id && it.status == "PENDING") it.copy(status = "REVOKED") else it }
        audit("INVITE_REVOKE", "invitation", id, null)
    }

    override suspend fun assignFaculty(studentId: String, facultyId: String, role: String) = op {
        requireRole(Role.HOD)
        if (role !in AssignmentRole.all) fail(422, "Invalid assignment role")
        if (studentId !in students) fail(404, "Student not found")
        if (users[facultyId]?.let { it.role != Role.STUDENT && it.active } != true) fail(422, "Faculty member not found or inactive")
        val i = assignments.indexOfFirst { it.studentId == studentId && it.facultyId == facultyId && it.active }
        if (i < 0) assignments += Assignment(uuid(), studentId, facultyId, role, true, now()) else assignments[i] = assignments[i].copy(assignmentRole = role)
        if (role == AssignmentRole.GUIDE) students.getValue(studentId).guideId = facultyId
        audit("ASSIGN_FACULTY", "faculty_assignment", facultyId, studentId)
        notify(facultyId, "New student assigned", "${users[studentId]?.fullName} has been assigned to you.", "ASSIGNMENT", null, null, studentId)
    }

    override suspend fun unassignFaculty(studentId: String, facultyId: String) = op {
        requireRole(Role.HOD)
        assignments.replaceAll { if (it.studentId == studentId && it.facultyId == facultyId && it.active) it.copy(active = false) else it }
        students[studentId]?.let { if (it.guideId == facultyId) it.guideId = null }
        audit("UNASSIGN_FACULTY", "faculty_assignment", facultyId, studentId)
    }

    override suspend fun updateStudentDetails(studentId: String, data: Map<String, String?>) = op {
        requireRole(Role.HOD)
        val allowed = setOf("full_name", "university_reg_no", "institution", "university", "batch_id", "current_posting")
        data.keys.firstOrNull { it !in allowed }?.let { fail(422, "Field \"$it\" cannot be updated here") }
        val sp = students[studentId] ?: fail(404, "Student not found")
        data["full_name"]?.let { users.getValue(studentId).fullName = it }
        if ("university_reg_no" in data) {
            val reg = data["university_reg_no"]?.takeIf { it.isNotBlank() }
            if (reg != null && students.any { (id, s) -> id != studentId && s.regNo == reg }) fail(409, "University registration number already in use")
            sp.regNo = reg
        }
        if ("institution" in data) sp.institution = data["institution"]
        if ("university" in data) sp.university = data["university"]
        if ("batch_id" in data) sp.batchId = data["batch_id"]?.takeIf { it.isNotBlank() }
        if ("current_posting" in data) sp.currentPosting = data["current_posting"]
        audit("UPDATE_STUDENT", "student_profile", studentId, studentId)
    }

    override suspend fun setProgrammeStart(studentId: String, start: String, durationMonths: Int?, reason: String) = op {
        requireRole(Role.HOD)
        if (reason.isBlank()) fail(422, "A reason is required for changing the programme start date")
        val date = runCatching { LocalDate.parse(start) }.getOrElse { fail(422, "Start date is required") }
        if (studentId !in students) fail(404, "Student not found")
        val e = enrolments[studentId]
        if (e == null) enrolments[studentId] = Enrolment(date, durationMonths ?: settings.programmeDurationMonths)
        else {
            e.start = date
            durationMonths?.let { e.duration = it }
        }
        provision(studentId)
        audit("PROGRAMME_START_CHANGE", "programme_enrolment", studentId, studentId, reason)
        notify(studentId, "Programme start date updated",
            "Your official programme start date is now ${date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))}.", "PROGRAMME", null, null, studentId)
    }

    override suspend fun upsertBatch(id: String?, name: String, academicYear: String, intakeDate: String?): Batch = op {
        requireRole(Role.HOD)
        if (name.isBlank() || academicYear.isBlank()) fail(422, "Batch name and academic year are required")
        if (batches.any { it.name == name.trim() && it.id != id }) fail(409, "A batch with this name already exists")
        val b = Batch(id ?: uuid(), name.trim(), academicYear.trim(), intakeDate?.takeIf { it.isNotBlank() })
        val i = batches.indexOfFirst { it.id == id }
        if (i >= 0) batches[i] = b else batches += b
        b
    }

    override suspend fun updateSettings(data: JsonObject): InstitutionSettings = op {
        requireRole(Role.HOD)
        val allowed = setOf("institution_name", "department_name", "university_name", "dean_name", "dean_title", "programme_duration_months",
            "allow_future_months", "require_programme_completion", "monthly_hod_review_required", "auto_issue_certificate", "certificate_prefix")
        data.keys.firstOrNull { it !in allowed }?.let { fail(422, "Unknown setting \"$it\"") }
        fun s(k: String) = (data[k] as? JsonPrimitive)?.contentOrNull
        fun b(k: String) = (data[k] as? JsonPrimitive)?.booleanOrNull
        val duration = (data["programme_duration_months"] as? JsonPrimitive)?.intOrNull
        if (duration != null && duration !in 12..72) fail(422, "Programme duration must be between 12 and 72 months")
        settings = settings.copy(
            institutionName = s("institution_name") ?: settings.institutionName,
            departmentName = s("department_name") ?: settings.departmentName,
            universityName = s("university_name") ?: settings.universityName,
            deanName = if ("dean_name" in data) s("dean_name") else settings.deanName,
            deanTitle = s("dean_title") ?: settings.deanTitle,
            programmeDurationMonths = duration ?: settings.programmeDurationMonths,
            allowFutureMonths = b("allow_future_months") ?: settings.allowFutureMonths,
            requireProgrammeCompletion = b("require_programme_completion") ?: settings.requireProgrammeCompletion,
            monthlyHodReviewRequired = b("monthly_hod_review_required") ?: settings.monthlyHodReviewRequired,
            autoIssueCertificate = b("auto_issue_certificate") ?: settings.autoIssueCertificate,
            certificatePrefix = s("certificate_prefix") ?: settings.certificatePrefix,
        )
        audit("UPDATE_SETTINGS", "institution_settings", "1", null)
        settings
    }

    override suspend fun setTarget(key: String, value: Int, notes: String?): Target = op {
        requireRole(Role.HOD)
        if (value <= 0) fail(422, "Target value must be a positive number")
        val i = targets.indexOfFirst { it.key == key }
        if (i < 0) fail(422, "Label and category are required for a new target")
        val t = targets[i].copy(targetValue = value, version = targets[i].version + 1, notes = notes ?: targets[i].notes)
        targets[i] = t
        audit("TARGET_CHANGE", "target", key, null, notes, buildJsonObject { put("target_value", value) })
        t
    }

    override suspend fun updateCompetency(code: String, title: String, domain: String, progression: String?): Competency = op {
        requireRole(Role.HOD)
        val i = competencies.indexOfFirst { it.code == code }
        if (i < 0) fail(404, "Competency not found")
        val c = competencies[i].copy(title = title.ifBlank { competencies[i].title }, domain = domain.ifBlank { competencies[i].domain },
            progression = progression?.takeIf { it.isNotBlank() } ?: competencies[i].progression)
        competencies[i] = c
        audit("COMPETENCY_UPDATE", "competency", code, null)
        c
    }

    override suspend fun publishDocument(code: String, content: JsonObject?, pdf: ByteArray?, fileName: String?, notes: String?): AcademicDocument = op {
        requireRole(Role.HOD)
        if (content == null && pdf == null) fail(422, "Provide document content or an uploaded file")
        val i = documents.indexOfFirst { it.code == code }
        if (i < 0) fail(422, "Title is required for a new document")
        val prev = documents[i]
        val path = pdf?.let { "$code/${prev.versionNo + 1}/${fileName ?: "$code.pdf"}".also { p -> files[p] = it } }
        val doc = prev.copy(versionNo = prev.versionNo + 1, content = content ?: prev.content, storagePath = path, fileName = if (pdf != null) fileName else null,
            publishedAt = now(), notes = notes)
        documents[i] = doc
        audit("DOCUMENT_PUBLISH", "document", code, null, notes)
        doc
    }

    override suspend fun downloadDocumentFile(storagePath: String): ByteArray = op { me(); files[storagePath] ?: fail(404, "File not found") }

    override suspend fun setUserActive(userId: String, active: Boolean) = op {
        val u = requireRole(Role.HOD)
        if (userId == u.id) fail(422, "You cannot deactivate your own account")
        users[userId]?.active = active
        audit(if (active) "ACTIVATE_USER" else "DEACTIVATE_USER", "profile", userId, userId.takeIf { it in students })
    }

    override suspend fun updateFacultyProfile(facultyId: String, designation: String?, unit: String?, isUnitHead: Boolean) = op {
        requireRole(Role.HOD)
        val f = users[facultyId]?.takeIf { it.role != Role.STUDENT } ?: fail(404, "Faculty member not found")
        f.designation = designation
        f.unit = unit
        f.isUnitHead = isUnitHead
        audit("UPDATE_FACULTY", "faculty_profile", facultyId, null)
    }

    override suspend fun updateMyProfile(data: Map<String, String>) = op {
        val u = me()
        for (k in data.keys) {
            if (u.role == Role.STUDENT && k != "current_posting") fail(403, "Students cannot change \"$k\"; contact the HOD")
            if (u.role != Role.STUDENT && k !in setOf("designation", "unit")) fail(403, "Field \"$k\" cannot be changed here")
        }
        if (u.role == Role.STUDENT) students[u.id]?.currentPosting = data["current_posting"]
        else {
            if ("designation" in data) u.designation = data["designation"]
            if ("unit" in data) u.unit = data["unit"]
        }
        audit("SELF_UPDATE", "profile", u.id, u.id.takeIf { it in students })
    }

    // ================================================================ seeding hooks (used by DemoSeed only)

    internal fun seedUser(email: String, name: String, role: Role, designation: String?, unit: String? = null, unitHead: Boolean = false): String {
        val id = uuid()
        users[id] = DemoUser(id, email, DEMO_PASSWORD, name, role, designation, unit = unit, isUnitHead = unitHead, createdAt = now())
        return id
    }

    internal fun seedStudent(email: String, name: String, regNo: String, batchId: String?, start: LocalDate, guideId: String?, posting: String?): String {
        val id = seedUser(email, name, Role.STUDENT, "PG Resident")
        students[id] = StudentProfile(regNo, settings.institutionName, settings.universityName, batchId, guideId, posting)
        enrolments[id] = Enrolment(start, settings.programmeDurationMonths)
        guideId?.let { assignments += Assignment(uuid(), id, it, AssignmentRole.GUIDE, true, now()) }
        provision(id)
        return id
    }

    internal fun seedAssign(studentId: String, facultyId: String, role: String) {
        assignments += Assignment(uuid(), studentId, facultyId, role, true, now())
    }

    internal fun seedBatch(name: String, year: String, intake: LocalDate): String {
        val b = Batch(uuid(), name, year, intake.toString())
        batches += b
        return b.id
    }

    internal fun <T> actingAs(userId: String, block: suspend DemoBackend.() -> T): T {
        val previous = current
        current = users.getValue(userId)
        return try {
            val backend = this
            kotlinx.coroutines.runBlocking { block(backend) }
        } finally {
            current = previous
        }
    }

    internal fun slotRecord(studentId: String, code: String, slot: Int): Submission =
        submissions.values.first { it.studentId == studentId && it.templateCode == code && it.slotNo == slot }

    internal fun periodRecord(studentId: String, code: String, period: Int): Submission =
        submissions.values.first { it.studentId == studentId && it.templateCode == code && it.periodNo == period }

    internal fun todayForSeed(): LocalDate = today()

    internal fun activeCompetencyCodes(): List<String> = competencies.filter { it.active }.sortedBy { it.sortOrder }.map { it.code }

    internal fun clearNotificationsFor(userId: String) {
        notifications.removeAll { it.first == userId && it.second.category == "GENERAL" }
    }

    companion object {
        const val DEMO_PASSWORD = "Demo@1234"
    }
}
