package org.mdpeds.logbook.data

import kotlinx.serialization.json.JsonObject
import org.mdpeds.logbook.domain.model.AcademicDocument
import org.mdpeds.logbook.domain.model.AppNotification
import org.mdpeds.logbook.domain.model.Assignment
import org.mdpeds.logbook.domain.model.Attachment
import org.mdpeds.logbook.domain.model.AuditEntry
import org.mdpeds.logbook.domain.model.Batch
import org.mdpeds.logbook.domain.model.Certificate
import org.mdpeds.logbook.domain.model.CertificationState
import org.mdpeds.logbook.domain.model.Competency
import org.mdpeds.logbook.domain.model.FeedbackEntry
import org.mdpeds.logbook.domain.model.InstitutionSettings
import org.mdpeds.logbook.domain.model.Invitation
import org.mdpeds.logbook.domain.model.InvitationRequest
import org.mdpeds.logbook.domain.model.Option
import org.mdpeds.logbook.domain.model.ReviewAction
import org.mdpeds.logbook.domain.model.ReviewQueueItem
import org.mdpeds.logbook.domain.model.SaveRequest
import org.mdpeds.logbook.domain.model.Signoff
import org.mdpeds.logbook.domain.model.SignoffAction
import org.mdpeds.logbook.domain.model.StaffMember
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.Target
import org.mdpeds.logbook.domain.model.TargetProgress
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.UserSession

/**
 * The single backend contract used by the app. All authorization is enforced by the backend
 * (Supabase RLS + RPCs, or the demo backend's identical rules); failures throw
 * [org.mdpeds.logbook.domain.model.BackendException].
 */
interface LogbookBackend {
    val isDemo: Boolean

    // ---- authentication
    suspend fun signIn(email: String, password: String): UserSession
    suspend fun restoreSession(): UserSession?
    suspend fun signOut()
    suspend fun sendPasswordReset(email: String)
    /** Handles the invite / recovery deep link (mdpeds://auth-callback#access_token=...). */
    suspend fun completeAuthCallback(uri: String): UserSession
    suspend fun setPassword(newPassword: String)

    // ---- reference data
    suspend fun templates(): List<Template>
    suspend fun roleOptions(): List<Option>
    suspend fun competencies(): List<Competency>
    suspend fun targets(): List<Target>
    suspend fun documents(): List<AcademicDocument>
    suspend fun settings(): InstitutionSettings
    suspend fun batches(): List<Batch>

    // ---- people
    suspend fun studentOverviews(): List<StudentOverview>
    suspend fun studentOverview(studentId: String): StudentOverview
    suspend fun staff(): List<StaffMember>
    suspend fun assignments(studentId: String): List<Assignment>

    // ---- logbook records
    suspend fun submissions(studentId: String, templateCode: String? = null): List<Submission>
    suspend fun submission(id: String): Submission
    suspend fun saveSubmission(request: SaveRequest): Submission
    suspend fun submitSubmission(id: String, expectedVersion: Int?): Submission
    suspend fun deleteDraft(id: String)
    suspend fun reviewSubmission(id: String, action: ReviewAction, facultyData: Map<String, String>, comment: String?, expectedVersion: Int?): Submission
    suspend fun hodReviewSubmission(id: String, hodData: Map<String, String>, expectedVersion: Int?): Submission
    suspend fun reopenSubmission(id: String, reason: String): Submission
    suspend fun correctReview(id: String, facultyData: Map<String, String>, reason: String): Submission
    suspend fun saveAnnualReview(studentId: String, year: Int, data: Map<String, String>, submit: Boolean, reason: String?, expectedVersion: Int?): Submission
    suspend fun targetProgress(studentId: String): List<TargetProgress>

    // ---- evidence
    suspend fun attachments(submissionId: String): List<Attachment>
    suspend fun uploadAttachment(studentId: String, submissionId: String, fileName: String, mimeType: String, bytes: ByteArray): Attachment
    suspend fun downloadAttachment(storagePath: String): ByteArray

    // ---- competency sign-off & certification
    suspend fun signoffs(studentId: String): List<Signoff>
    suspend fun requestSignoff(code: String, note: String?): Signoff
    suspend fun facultySignoff(studentId: String, code: String, action: SignoffAction, comment: String?, expectedVersion: Int?): Signoff
    suspend fun certificationStatus(studentId: String): CertificationState
    suspend fun certificate(studentId: String): Certificate?
    suspend fun issueCertificate(studentId: String): Certificate
    suspend fun revokeCertificate(studentId: String, reason: String)

    // ---- review queue, notifications, history
    suspend fun reviewQueue(): List<ReviewQueueItem>
    suspend fun notifications(): List<AppNotification>
    suspend fun markNotificationsRead(ids: List<String>?)
    suspend fun feedback(studentId: String): List<FeedbackEntry>
    suspend fun auditLog(studentId: String?): List<AuditEntry>

    // ---- HOD administration
    suspend fun invitations(): List<Invitation>
    suspend fun createInvitation(request: InvitationRequest): Invitation
    suspend fun revokeInvitation(id: String)
    suspend fun assignFaculty(studentId: String, facultyId: String, role: String)
    suspend fun unassignFaculty(studentId: String, facultyId: String)
    suspend fun updateStudentDetails(studentId: String, data: Map<String, String?>)
    suspend fun setProgrammeStart(studentId: String, start: String, durationMonths: Int?, reason: String)
    suspend fun upsertBatch(id: String?, name: String, academicYear: String, intakeDate: String?): Batch
    suspend fun updateSettings(data: JsonObject): InstitutionSettings
    suspend fun setTarget(key: String, value: Int, notes: String?): Target
    suspend fun updateCompetency(code: String, title: String, domain: String, progression: String?): Competency
    suspend fun publishDocument(code: String, content: JsonObject?, pdf: ByteArray?, fileName: String?, notes: String?): AcademicDocument
    suspend fun downloadDocumentFile(storagePath: String): ByteArray
    suspend fun setUserActive(userId: String, active: Boolean)
    suspend fun updateFacultyProfile(facultyId: String, designation: String?, unit: String?, isUnitHead: Boolean)
    suspend fun updateMyProfile(data: Map<String, String>)
}
