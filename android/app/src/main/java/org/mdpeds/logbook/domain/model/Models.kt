package org.mdpeds.logbook.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Role(val label: String) {
    STUDENT("PG Student"), FACULTY("Faculty"), HOD("Head of Department");

    companion object {
        fun from(value: String?): Role? = entries.firstOrNull { it.name == value }
    }
}

enum class SubmissionStatus(val label: String) {
    NOT_STARTED("Not started"),
    DRAFT("Draft"),
    SUBMITTED("Pending review"),
    RETURNED("Returned"),
    VERIFIED("Verified"),
    FACULTY_REVIEW_PENDING("Faculty review pending"),
    FACULTY_REVIEWED("Faculty reviewed"),
    HOD_REVIEW_PENDING("HOD review pending"),
    COMPLETED("Completed");

    val isStudentEditable get() = this == NOT_STARTED || this == DRAFT || this == RETURNED
    val isAwaitingFaculty get() = this == SUBMITTED || this == FACULTY_REVIEW_PENDING
    val isAwaitingHod get() = this == HOD_REVIEW_PENDING || this == FACULTY_REVIEWED
    val isReviewed get() = this == VERIFIED || this == COMPLETED || isAwaitingHod
    val isSubmitted get() = this != NOT_STARTED && this != DRAFT && this != RETURNED
    val isVerified get() = this == VERIFIED || this == COMPLETED

    companion object {
        fun from(value: String?): SubmissionStatus = entries.firstOrNull { it.name == value } ?: DRAFT
    }
}

enum class SignoffStatus(val label: String) {
    NOT_STARTED("Not started"),
    IN_PROGRESS("In progress"),
    AWAITING_APPROVAL("Awaiting approval"),
    APPROVED("Approved"),
    RETURNED("Returned for further training");

    companion object {
        fun from(value: String?): SignoffStatus = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

enum class SignoffAction(val label: String) {
    OBSERVED("Record observed"),
    SUPERVISED("Record supervised"),
    INDEPENDENT("Record independent"),
    APPROVE("Approve final sign-off"),
    RETURN("Return for further training"),
    REVOKE("Revoke approval"),
}

enum class ReviewAction { VERIFY, RETURN }

enum class CertificationStatus(val label: String) {
    NOT_ELIGIBLE("Not yet eligible"),
    AWAITING_FINAL_SIGNOFF("Awaiting final competency sign-off"),
    READY_FOR_CERTIFICATION("Ready for certification"),
    CERTIFICATE_ISSUED("Certificate issued");

    companion object {
        fun from(value: String?): CertificationStatus = entries.firstOrNull { it.name == value } ?: NOT_ELIGIBLE
    }
}

/** Reads any JSON object as a String map (non-string scalars become their textual form). */
object LooseStringMapSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val input = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val obj = input.decodeJsonElement() as? JsonObject ?: return emptyMap()
        return obj.toStringMap()
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) = delegate.serialize(encoder, value)
}

fun JsonObject.toStringMap(): Map<String, String> = buildMap {
    for ((k, v) in this@toStringMap) {
        when (v) {
            is JsonNull -> Unit
            is JsonPrimitive -> put(k, v.content)
            else -> put(k, v.toString())
        }
    }
}

// ------------------------------------------------------------------ identity

@Serializable
data class UserSession(
    val userId: String,
    val email: String,
    val fullName: String,
    val role: Role,
    val designation: String? = null,
)

// ------------------------------------------------------------------ templates (reference-data/templates.json)

@Serializable
data class Option(val value: String, val label: String)

@Serializable
data class TargetRule(
    val targetKey: String? = null,
    @SerialName("when") val whenMatch: Map<String, String>? = null,
    val targetFromField: String? = null,
)

@Serializable
data class AnnualDomain(val key: String, val label: String)

object FieldType {
    const val DATE = "DATE"
    const val TEXT = "TEXT"
    const val MULTILINE = "MULTILINE"
    const val SELECT = "SELECT"
    const val FACULTY = "FACULTY"
    const val COMPETENCIES = "COMPETENCIES"
    const val RATING_9 = "RATING_9"
    const val BOOLEAN = "BOOLEAN"
    const val ATTACHMENT = "ATTACHMENT"
    const val WORDS = "WORDS"
    const val BME = "BME"
}

object Owner {
    const val STUDENT = "STUDENT"
    const val FACULTY = "FACULTY"
    const val HOD = "HOD"
}

@Serializable
data class FieldDef(
    val key: String,
    val label: String,
    val type: String,
    val owner: String = Owner.STUDENT,
    val required: Boolean = false,
    val section: String? = null,
    val help: String? = null,
    val options: List<Option>? = null,
    val optionsSource: String? = null,
    val visibleWhen: Map<String, String>? = null,
    val readOnly: Boolean = false,
    val minWords: Int? = null,
    val maxWords: Int? = null,
    val group: String? = null,
    val richText: Boolean = false,
)

object TemplateCategory {
    const val LOG = "LOG"
    const val FORM = "FORM"
    const val REFLECTION = "REFLECTION"
    const val MONTHLY = "MONTHLY"
    const val ANNUAL = "ANNUAL"
}

@Serializable
data class Template(
    val code: String,
    val category: String,
    val title: String,
    val subtitle: String? = null,
    val sortOrder: Int = 0,
    val workflow: String = "VERIFY",
    val reviewer: String = "ASSIGNED",
    val signatures: List<String> = emptyList(),
    val dateField: String? = null,
    val titleField: String? = null,
    val subtitleFields: List<String> = emptyList(),
    val ratingHelp: String? = null,
    val notice: String? = null,
    val requiredInitial: Int = 0,
    val periodCount: Int? = null,
    val slots: List<String> = emptyList(),
    val domains: List<AnnualDomain> = emptyList(),
    val guidanceSource: String? = null,
    val targetRules: List<TargetRule> = emptyList(),
    val fields: List<FieldDef> = emptyList(),
    val version: Int = 1,
) {
    fun fieldsFor(owner: String) = fields.filter { it.owner == owner }
    val hasSlots get() = requiredInitial > 0
}

@Serializable
data class TemplatesFile(val roleOptions: List<Option> = emptyList(), val templates: List<Template>)

// ------------------------------------------------------------------ competencies & targets

@Serializable
data class Competency(
    val code: String,
    val title: String,
    val domain: String,
    val progression: String = "PS → PI",
    @SerialName("sort_order") val sortOrder: Int = 0,
    val active: Boolean = true,
)

@Serializable
data class CompetenciesFile(
    val note: String? = null,
    val expectedProgression: String? = null,
    val competencies: List<Competency>,
)

object TargetCategory {
    const val EXPOSURE = "EXPOSURE"
    const val PROCEDURE = "PROCEDURE"
}

@Serializable
data class Target(
    val key: String,
    val category: String,
    val label: String,
    @SerialName("target_value") val targetValue: Int,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val version: Int = 1,
    val notes: String? = null,
)

@Serializable
data class TargetsFile(
    val disclaimer: String? = null,
    val exposure: List<AssetTarget>,
    val procedures: List<AssetTarget>,
)

@Serializable
data class AssetTarget(
    val key: String,
    val label: String,
    val target: Int,
    val distribution: String? = null,
    val documentation: String? = null,
    val levelRemarks: String? = null,
)

@Serializable
data class TargetProgress(
    @SerialName("student_id") val studentId: String = "",
    @SerialName("target_key") val targetKey: String,
    val category: String,
    val label: String,
    @SerialName("target_value") val targetValue: Int,
    @SerialName("target_version") val targetVersion: Int = 1,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val recorded: Int = 0,
    val submitted: Int = 0,
    val verified: Int = 0,
    @SerialName("verified_percent") val verifiedPercent: Int = 0,
)

// ------------------------------------------------------------------ documents

@Serializable
data class DocumentsFile(val documents: List<AssetDocument>)

@Serializable
data class AssetDocument(
    val code: String,
    val title: String,
    val description: String? = null,
    val sortOrder: Int = 0,
    val content: JsonObject,
)

@Serializable
data class AcademicDocument(
    val code: String,
    val title: String,
    val description: String? = null,
    val sortOrder: Int = 0,
    val versionNo: Int = 1,
    val content: JsonObject? = null,
    val storagePath: String? = null,
    val fileName: String? = null,
    val publishedAt: String? = null,
    val notes: String? = null,
)

/** Cacheable snapshot of all reference data. */
@Serializable
data class ReferenceSnapshot(
    val templates: List<Template>,
    val roleOptions: List<Option>,
    val competencies: List<Competency>,
    val targets: List<Target>,
    val documents: List<AcademicDocument>,
)

// ------------------------------------------------------------------ people & programme

@Serializable
data class InstitutionSettings(
    @SerialName("institution_name") val institutionName: String = "Medical College & Hospital",
    @SerialName("department_name") val departmentName: String = "Department of Pediatrics",
    @SerialName("university_name") val universityName: String = "Health Sciences University",
    @SerialName("dean_name") val deanName: String? = null,
    @SerialName("dean_title") val deanTitle: String = "Dean / Principal",
    @SerialName("programme_duration_months") val programmeDurationMonths: Int = 36,
    @SerialName("allow_future_months") val allowFutureMonths: Boolean = false,
    @SerialName("require_programme_completion") val requireProgrammeCompletion: Boolean = true,
    @SerialName("monthly_hod_review_required") val monthlyHodReviewRequired: Boolean = true,
    @SerialName("auto_issue_certificate") val autoIssueCertificate: Boolean = true,
    @SerialName("certificate_prefix") val certificatePrefix: String = "MDPED",
)

@Serializable
data class Batch(
    val id: String,
    val name: String,
    @SerialName("academic_year") val academicYear: String,
    @SerialName("intake_date") val intakeDate: String? = null,
)

@Serializable
data class StudentOverview(
    @SerialName("student_id") val studentId: String,
    @SerialName("full_name") val fullName: String,
    val email: String = "",
    val active: Boolean = true,
    @SerialName("university_reg_no") val universityRegNo: String? = null,
    val institution: String? = null,
    val university: String? = null,
    @SerialName("current_posting") val currentPosting: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("batch_name") val batchName: String? = null,
    @SerialName("academic_year") val academicYear: String? = null,
    @SerialName("guide_id") val guideId: String? = null,
    @SerialName("guide_name") val guideName: String? = null,
    @SerialName("programme_start_date") val programmeStartDate: String? = null,
    @SerialName("duration_months") val durationMonths: Int? = null,
    @SerialName("enrolment_status") val enrolmentStatus: String? = null,
    @SerialName("completion_date") val completionDate: String? = null,
    @SerialName("current_month") val currentMonth: Int? = null,
    @SerialName("pg_year") val pgYear: Int? = null,
    @SerialName("my_assignment_role") val myAssignmentRole: String? = null,
    @SerialName("monthly_completed") val monthlyCompleted: Int = 0,
    @SerialName("monthly_overdue_or_open") val monthlyOverdueOrOpen: Int = 0,
    @SerialName("pending_reviews") val pendingReviews: Int = 0,
    @SerialName("log_entries") val logEntries: Int = 0,
    @SerialName("signoffs_approved") val signoffsApproved: Int = 0,
    @SerialName("signoffs_total") val signoffsTotal: Int = 0,
    @SerialName("annual_reviews_completed") val annualReviewsCompleted: Int = 0,
    @SerialName("certificate_no") val certificateNo: String? = null,
    @SerialName("certificate_issued_at") val certificateIssuedAt: String? = null,
)

@Serializable
data class StaffMember(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val email: String = "",
    val role: Role,
    val designation: String? = null,
    val active: Boolean = true,
    val unit: String? = null,
    @SerialName("is_unit_head") val isUnitHead: Boolean? = false,
)

@Serializable
data class Assignment(
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("faculty_id") val facultyId: String,
    @SerialName("assignment_role") val assignmentRole: String,
    val active: Boolean = true,
    @SerialName("assigned_at") val assignedAt: String? = null,
)

object AssignmentRole {
    const val GUIDE = "GUIDE"
    const val UNIT_FACULTY = "UNIT_FACULTY"
    const val MENTOR = "MENTOR"
    val all = listOf(GUIDE, UNIT_FACULTY, MENTOR)
    fun label(role: String?) = when (role) {
        GUIDE -> "Guide"
        MENTOR -> "Mentor"
        UNIT_FACULTY -> "Unit faculty"
        else -> role ?: ""
    }
}

@Serializable
data class Invitation(
    val id: String,
    val email: String,
    @SerialName("full_name") val fullName: String,
    val role: Role,
    val designation: String? = null,
    @SerialName("university_reg_no") val universityRegNo: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("programme_start_date") val programmeStartDate: String? = null,
    @SerialName("guide_id") val guideId: String? = null,
    val status: String = "PENDING",
    @SerialName("created_at") val createdAt: String? = null,
)

data class InvitationRequest(
    val email: String,
    val fullName: String,
    val role: Role,
    val designation: String? = null,
    val universityRegNo: String? = null,
    val batchId: String? = null,
    val programmeStartDate: String? = null,
    val guideId: String? = null,
)

// ------------------------------------------------------------------ logbook records

@Serializable
data class Submission(
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("template_code") val templateCode: String,
    @SerialName("template_version") val templateVersion: Int = 1,
    @SerialName("period_no") val periodNo: Int? = null,
    @SerialName("slot_no") val slotNo: Int? = null,
    val status: SubmissionStatus = SubmissionStatus.DRAFT,
    @Serializable(with = LooseStringMapSerializer::class)
    @SerialName("student_data") val studentData: Map<String, String> = emptyMap(),
    @Serializable(with = LooseStringMapSerializer::class)
    @SerialName("faculty_data") val facultyData: Map<String, String> = emptyMap(),
    @Serializable(with = LooseStringMapSerializer::class)
    @SerialName("hod_data") val hodData: Map<String, String> = emptyMap(),
    @SerialName("competency_codes") val competencyCodes: List<String> = emptyList(),
    @SerialName("entry_date") val entryDate: String? = null,
    @SerialName("assessor_id") val assessorId: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("reviewed_by_name") val reviewedByName: String? = null,
    @SerialName("reviewed_by_designation") val reviewedByDesignation: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("hod_reviewed_by") val hodReviewedBy: String? = null,
    @SerialName("hod_reviewed_by_name") val hodReviewedByName: String? = null,
    @SerialName("hod_reviewed_at") val hodReviewedAt: String? = null,
    @SerialName("return_reason") val returnReason: String? = null,
    val version: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** Combined view of all three data owners, used for display and target rules. */
    val allData: Map<String, String> get() = studentData + facultyData + hodData
}

data class SaveRequest(
    val id: String?,
    val templateCode: String,
    val studentData: Map<String, String>,
    val expectedVersion: Int?,
    val periodNo: Int?,
    val slotNo: Int?,
)

@Serializable
data class Signoff(
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("competency_code") val competencyCode: String,
    @SerialName("observed_at") val observedAt: String? = null,
    @SerialName("observed_by_name") val observedByName: String? = null,
    @SerialName("supervised_at") val supervisedAt: String? = null,
    @SerialName("supervised_by_name") val supervisedByName: String? = null,
    @SerialName("independent_at") val independentAt: String? = null,
    @SerialName("independent_by_name") val independentByName: String? = null,
    val status: SignoffStatus = SignoffStatus.NOT_STARTED,
    @SerialName("student_note") val studentNote: String? = null,
    val comment: String? = null,
    @SerialName("final_by") val finalBy: String? = null,
    @SerialName("final_by_name") val finalByName: String? = null,
    @SerialName("final_by_designation") val finalByDesignation: String? = null,
    @SerialName("final_at") val finalAt: String? = null,
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class CertificationState(
    val status: CertificationStatus,
    val approved: Int = 0,
    val total: Int = 0,
    @SerialName("programme_complete") val programmeComplete: Boolean = false,
    @SerialName("completion_date") val completionDate: String? = null,
    val reasons: List<String> = emptyList(),
    @SerialName("certificate_id") val certificateId: String? = null,
    @SerialName("certificate_no") val certificateNo: String? = null,
)

@Serializable
data class Signatory(val role: String, val name: String? = null, val verification: String? = null)

@Serializable
data class CertificateSnapshot(
    @SerialName("institution_name") val institutionName: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("university_name") val universityName: String? = null,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("university_reg_no") val universityRegNo: String? = null,
    val course: String? = null,
    val batch: String? = null,
    @SerialName("programme_start_date") val programmeStartDate: String? = null,
    @SerialName("programme_completion_date") val programmeCompletionDate: String? = null,
    @SerialName("issue_date") val issueDate: String? = null,
    @SerialName("certificate_no") val certificateNo: String? = null,
    @SerialName("competencies_approved") val competenciesApproved: Int? = null,
    @SerialName("guide_name") val guideName: String? = null,
    val statement: String? = null,
    val disclaimer: String? = null,
    val signatories: List<Signatory> = emptyList(),
)

@Serializable
data class Certificate(
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("certificate_no") val certificateNo: String,
    @SerialName("issued_at") val issuedAt: String? = null,
    @SerialName("issued_by_name") val issuedByName: String? = null,
    val snapshot: CertificateSnapshot,
    val status: String = "ISSUED",
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("revoked_reason") val revokedReason: String? = null,
)

@Serializable
data class ReviewQueueItem(
    val kind: String,
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("student_name") val studentName: String,
    @SerialName("template_code") val templateCode: String,
    @SerialName("template_title") val templateTitle: String,
    @SerialName("period_no") val periodNo: Int? = null,
    @SerialName("slot_no") val slotNo: Int? = null,
    val status: String,
    @SerialName("entry_date") val entryDate: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    val version: Int = 1,
) {
    val isSignoff get() = kind == "SIGNOFF"
}

@Serializable
data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val category: String = "GENERAL",
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class FeedbackEntry(
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("faculty_name") val facultyName: String,
    @SerialName("faculty_designation") val facultyDesignation: String? = null,
    val action: String,
    val comment: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class AuditEntry(
    val id: Long,
    val at: String,
    @SerialName("actor_role") val actorRole: String? = null,
    val action: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    val changes: JsonElement? = null,
    val reason: String? = null,
)

@Serializable
data class Attachment(
    val id: String,
    @SerialName("submission_id") val submissionId: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Error raised by any backend; [code] follows HTTP semantics (400, 401, 403, 404, 409, 422, 0 = network). */
class BackendException(val code: Int, override val message: String) : Exception(message) {
    val isConflict get() = code == 409 && message.startsWith("CONFLICT")
    val isNetwork get() = code == 0
}
