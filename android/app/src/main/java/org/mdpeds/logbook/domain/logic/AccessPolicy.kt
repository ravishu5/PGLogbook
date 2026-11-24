package org.mdpeds.logbook.domain.logic

import org.mdpeds.logbook.domain.model.AssignmentRole
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.TemplateCategory

/** The caller's relationship to one student (mirrors app.is_assigned / app.is_guide). */
data class Relation(val isSelf: Boolean, val isAssigned: Boolean, val isGuide: Boolean) {
    companion object {
        fun of(
            role: Role,
            userId: String,
            studentId: String,
            guideId: String?,
            activeAssignmentRole: String?,
        ): Relation {
            val staff = role != Role.STUDENT
            val assigned = staff && (activeAssignmentRole != null || guideId == userId)
            val guide = staff && (guideId == userId || activeAssignmentRole == AssignmentRole.GUIDE || activeAssignmentRole == AssignmentRole.MENTOR)
            return Relation(isSelf = role == Role.STUDENT && userId == studentId, isAssigned = assigned, isGuide = guide)
        }
    }
}

enum class EditorMode {
    /** Student fills or corrects their own record. */
    STUDENT_EDIT,
    /** Faculty assessment / verification of a submitted record. */
    FACULTY_REVIEW,
    /** HOD (or mentor) completes the monthly assessment. */
    HOD_REVIEW,
    /** Faculty-only annual comprehensive review. */
    ANNUAL_EDIT,
    READ_ONLY,
}

/** Role-based access rules shared by the UI and the demo backend; the server enforces the same rules. */
object AccessPolicy {

    fun canView(role: Role, rel: Relation) = rel.isSelf || role == Role.HOD || rel.isAssigned

    fun canReviewStudent(role: Role, rel: Relation) = role == Role.HOD || rel.isAssigned

    fun canSeeSubmission(role: Role, rel: Relation, s: Submission): Boolean =
        canView(role, rel) && (role != Role.STUDENT || s.templateCode != "ANNUAL_REVIEW" || s.status == SubmissionStatus.COMPLETED)

    /** Reviewer rule from the template: ASSIGNED, ASSESSOR (selected assessor or HOD) or GUIDE (guide/mentor or HOD). */
    fun canFacultyReview(role: Role, userId: String, rel: Relation, t: Template, s: Submission): Boolean {
        if (role == Role.STUDENT || !canReviewStudent(role, rel)) return false
        if (t.category == TemplateCategory.ANNUAL || !s.status.isAwaitingFaculty) return false
        return when (t.reviewer) {
            "ASSESSOR" -> role == Role.HOD || s.assessorId == userId
            "GUIDE" -> role == Role.HOD || rel.isGuide
            else -> true
        }
    }

    fun canHodReview(role: Role, rel: Relation, t: Template, s: Submission): Boolean =
        role != Role.STUDENT && t.category == TemplateCategory.MONTHLY && s.status.isAwaitingHod &&
            (role == Role.HOD || rel.isGuide)

    /** Reopen (return for correction) or correct a reviewed record: the reviewer themself or the HOD. */
    fun canReopenOrCorrect(role: Role, userId: String, rel: Relation, t: Template, s: Submission): Boolean =
        role != Role.STUDENT && t.category != TemplateCategory.ANNUAL && canReviewStudent(role, rel) && s.status.isReviewed &&
            (role == Role.HOD || s.reviewedBy == userId)

    fun canStudentEdit(role: Role, rel: Relation, t: Template, s: Submission?): Boolean =
        role == Role.STUDENT && rel.isSelf && t.category != TemplateCategory.ANNUAL && (s == null || s.status.isStudentEditable)

    fun canEditAnnual(role: Role, rel: Relation, t: Template) =
        role != Role.STUDENT && t.category == TemplateCategory.ANNUAL && canReviewStudent(role, rel)

    fun editorMode(role: Role, userId: String, rel: Relation, t: Template, s: Submission?): EditorMode = when {
        canStudentEdit(role, rel, t, s) -> EditorMode.STUDENT_EDIT
        canEditAnnual(role, rel, t) -> EditorMode.ANNUAL_EDIT
        s != null && canFacultyReview(role, userId, rel, t, s) -> EditorMode.FACULTY_REVIEW
        s != null && canHodReview(role, rel, t, s) -> EditorMode.HOD_REVIEW
        else -> EditorMode.READ_ONLY
    }

    /** Only unsubmitted, additional (non-slot, non-period) drafts can be discarded. */
    fun canDiscardDraft(s: Submission) =
        s.status == SubmissionStatus.DRAFT && s.submittedAt == null && s.periodNo == null && s.slotNo == null

    // ----- administration
    fun canManageProgramme(role: Role) = role == Role.HOD
    fun canIssueCertificate(role: Role) = role == Role.HOD
    fun canRevokeSignoff(role: Role) = role == Role.HOD
    fun canSignoff(role: Role, rel: Relation) = role != Role.STUDENT && canReviewStudent(role, rel)
}
