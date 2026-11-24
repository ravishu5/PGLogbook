package org.mdpeds.logbook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mdpeds.logbook.domain.logic.AccessPolicy
import org.mdpeds.logbook.domain.logic.CertificationPolicy
import org.mdpeds.logbook.domain.logic.EditorMode
import org.mdpeds.logbook.domain.logic.FormValidator
import org.mdpeds.logbook.domain.logic.ProgrammeTimeline
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.logic.Relation
import org.mdpeds.logbook.domain.logic.TargetEngine
import org.mdpeds.logbook.domain.model.AssignmentRole
import org.mdpeds.logbook.domain.model.CertificationStatus
import org.mdpeds.logbook.domain.model.Owner
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.SubmissionStatus
import java.io.File
import java.time.LocalDate

private val ref: ReferenceData by lazy {
    // Unit tests run with the module directory as working directory; reference data lives at the repo root.
    val dir = listOf(File("../../reference-data"), File("../reference-data"), File("reference-data")).first { it.isDirectory }
    ReferenceData.parse(
        File(dir, "templates.json").readText(),
        File(dir, "competencies.json").readText(),
        File(dir, "targets.json").readText(),
        File(dir, "documents.json").readText(),
    )
}

private fun sub(code: String, status: SubmissionStatus, data: Map<String, String> = emptyMap(), id: String = code + status, reviewedBy: String? = null, assessor: String? = null) =
    Submission(id = id, studentId = "s1", templateCode = code, status = status, studentData = data, reviewedBy = reviewedBy, assessorId = assessor)

class ProgrammeTimelineTest {
    private val start = LocalDate.of(2024, 1, 31)

    @Test fun monthsAreAnchoredToTheStartDateWithoutDrift() {
        assertEquals(LocalDate.of(2024, 2, 29), ProgrammeTimeline.monthStart(start, 2))
        assertEquals(LocalDate.of(2024, 3, 31), ProgrammeTimeline.monthStart(start, 3))
        assertEquals(LocalDate.of(2024, 3, 30), ProgrammeTimeline.monthEnd(start, 2))
    }

    @Test fun completionDateIsTheDayBeforeTheThirtySeventhMonth() {
        assertEquals(LocalDate.of(2027, 1, 30), ProgrammeTimeline.completionDate(start))
        assertEquals(36, ProgrammeTimeline.months(start).size)
        assertEquals(ProgrammeTimeline.completionDate(start), ProgrammeTimeline.months(start).last().end)
    }

    @Test fun programmeMonthAndPgYear() {
        assertEquals(0, ProgrammeTimeline.programmeMonth(start, start.minusDays(1)))
        assertEquals(1, ProgrammeTimeline.programmeMonth(start, start))
        assertEquals(1, ProgrammeTimeline.programmeMonth(start, LocalDate.of(2024, 2, 28)))
        assertEquals(2, ProgrammeTimeline.programmeMonth(start, LocalDate.of(2024, 2, 29)))
        assertEquals(13, ProgrammeTimeline.programmeMonth(start, LocalDate.of(2025, 1, 31)))
        assertEquals(36, ProgrammeTimeline.currentMonth(start, LocalDate.of(2030, 1, 1)))
        assertEquals(1, ProgrammeTimeline.pgYear(12))
        assertEquals(2, ProgrammeTimeline.pgYear(13))
        assertEquals(3, ProgrammeTimeline.pgYear(36))
    }

    @Test fun futureMonthsAreLockedUnlessAllowed() {
        val today = LocalDate.of(2024, 3, 15)
        assertTrue(ProgrammeTimeline.isMonthOpen(start, 2, today))
        assertFalse(ProgrammeTimeline.isMonthOpen(start, 3, today))
        assertTrue(ProgrammeTimeline.isMonthOpen(start, 3, today, allowFuture = true))
    }
}

class ReferenceDataTest {
    @Test fun bundledReferenceDataMatchesThePaperLogbook() {
        assertEquals(12, ref.templates.size)
        assertEquals(22, ref.competencies.size)
        assertEquals(34, ref.targets.size)
        assertEquals(36, ref.template("MONTHLY")?.periodCount)
        assertEquals(3, ref.template("ANNUAL_REVIEW")?.periodCount)
        assertEquals(6, ref.template("MINI_CEX")?.requiredInitial)
        assertEquals(6, ref.template("DOPS")?.requiredInitial)
        assertTrue(ref.monthlyPriorities.isNotEmpty())
    }
}

class FormValidatorTest {
    private val reflection get() = ref.template("REFLECTION")!!

    @Test fun reflectionEnforcesWordLimits() {
        val words = { n: Int -> List(n) { "word" }.joinToString(" ") }
        val base = reflection.fieldsFor(Owner.STUDENT).associate { it.key to "x" }.toMutableMap()
        base["what_happened"] = words(10)
        base["what_learned"] = words(60)
        base["do_differently"] = words(201)
        val errors = FormValidator.validate(reflection, Owner.STUDENT, base)
        assertTrue(errors["what_happened"]!!.contains("at least 50"))
        assertNull(errors["what_learned"])
        assertTrue(errors["do_differently"]!!.contains("at most 200"))
    }

    @Test fun requiredAndTypedFields() {
        val f = ref.template("MINI_CEX")!!.fields.first { it.type == "RATING_9" }
        assertEquals("${f.label} must be between 1 and 9", FormValidator.validateField(f, mapOf(f.key to "10")))
        assertNull(FormValidator.validateField(f, mapOf(f.key to "7")))
        assertEquals(0, FormValidator.wordCount("  "))
        assertEquals(3, FormValidator.wordCount(" a  b\nc "))
    }
}

class TargetEngineTest {
    @Test fun onlyVerifiedRecordsCountTowardsVerifiedProgress() {
        val subs = listOf(
            sub("CLINICAL_CASE", SubmissionStatus.VERIFIED, mapOf("setting" to "WARD"), id = "a"),
            sub("CLINICAL_CASE", SubmissionStatus.SUBMITTED, mapOf("setting" to "WARD"), id = "b"),
            sub("CLINICAL_CASE", SubmissionStatus.DRAFT, mapOf("setting" to "WARD"), id = "c"),
            sub("CLINICAL_CASE", SubmissionStatus.NOT_STARTED, mapOf("setting" to "WARD"), id = "d"),
            sub("PROCEDURE", SubmissionStatus.VERIFIED, mapOf("procedure" to "PROC_LUMBAR_PUNCTURE"), id = "e"),
        )
        val p = TargetEngine.progress("s1", ref.targets, ref.templates, subs).associateBy { it.targetKey }
        val ward = p.getValue("GENERAL_ADMISSIONS")
        assertEquals(3, ward.recorded)
        assertEquals(2, ward.submitted)
        assertEquals(1, ward.verified)
        assertEquals(1, p.getValue("PROC_LUMBAR_PUNCTURE").verified)
        assertEquals(0, p.getValue("OPD_ENCOUNTERS").recorded)
        assertEquals(100, TargetEngine.percent(50, 10))
        assertEquals(0, TargetEngine.percent(1, 0))
    }
}

class AccessPolicyTest {
    private val miniCex get() = ref.template("MINI_CEX")!!
    private val monthly get() = ref.template("MONTHLY")!!
    private val annual get() = ref.template("ANNUAL_REVIEW")!!

    @Test fun studentsEditOnlyTheirOwnUnsubmittedRecords() {
        val self = Relation.of(Role.STUDENT, "s1", "s1", "g1", null)
        val other = Relation.of(Role.STUDENT, "s2", "s1", "g1", null)
        assertEquals(EditorMode.STUDENT_EDIT, AccessPolicy.editorMode(Role.STUDENT, "s1", self, miniCex, null))
        assertEquals(EditorMode.READ_ONLY, AccessPolicy.editorMode(Role.STUDENT, "s1", self, miniCex, sub("MINI_CEX", SubmissionStatus.SUBMITTED)))
        assertEquals(EditorMode.STUDENT_EDIT, AccessPolicy.editorMode(Role.STUDENT, "s1", self, miniCex, sub("MINI_CEX", SubmissionStatus.RETURNED)))
        assertFalse(AccessPolicy.canView(Role.STUDENT, other))
        assertFalse(AccessPolicy.canEditAnnual(Role.STUDENT, self, annual))
    }

    @Test fun assessorTemplatesAreReviewedByTheSelectedAssessorOrHod() {
        val rel = Relation.of(Role.FACULTY, "f1", "s1", "g1", AssignmentRole.UNIT_FACULTY)
        val s = sub("MINI_CEX", SubmissionStatus.SUBMITTED, assessor = "f2")
        assertFalse(AccessPolicy.canFacultyReview(Role.FACULTY, "f1", rel, miniCex, s))
        assertTrue(AccessPolicy.canFacultyReview(Role.FACULTY, "f2", rel, miniCex, s))
        assertTrue(AccessPolicy.canFacultyReview(Role.HOD, "h1", Relation.of(Role.HOD, "h1", "s1", "g1", null), miniCex, s))
        val unassigned = Relation.of(Role.FACULTY, "f2", "s1", "g1", null)
        assertFalse(AccessPolicy.canFacultyReview(Role.FACULTY, "f2", unassigned, miniCex, s))
    }

    @Test fun monthlyHodStageNeedsGuideOrHod() {
        val s = sub("MONTHLY", SubmissionStatus.HOD_REVIEW_PENDING)
        val guide = Relation.of(Role.FACULTY, "g1", "s1", "g1", null)
        val unit = Relation.of(Role.FACULTY, "f1", "s1", "g1", AssignmentRole.UNIT_FACULTY)
        assertEquals(EditorMode.HOD_REVIEW, AccessPolicy.editorMode(Role.FACULTY, "g1", guide, monthly, s))
        assertEquals(EditorMode.READ_ONLY, AccessPolicy.editorMode(Role.FACULTY, "f1", unit, monthly, s))
    }

    @Test fun onlyTheReviewerOrHodMayReopen() {
        val rel = Relation.of(Role.FACULTY, "f1", "s1", "g1", AssignmentRole.UNIT_FACULTY)
        val s = sub("MINI_CEX", SubmissionStatus.VERIFIED, reviewedBy = "f2")
        assertFalse(AccessPolicy.canReopenOrCorrect(Role.FACULTY, "f1", rel, miniCex, s))
        assertTrue(AccessPolicy.canReopenOrCorrect(Role.FACULTY, "f2", rel, miniCex, s))
        assertTrue(AccessPolicy.canReopenOrCorrect(Role.HOD, "h1", Relation.of(Role.HOD, "h1", "s1", "g1", null), miniCex, s))
    }

    @Test fun annualReviewIsHiddenFromStudentUntilCompleted() {
        val self = Relation.of(Role.STUDENT, "s1", "s1", "g1", null)
        assertFalse(AccessPolicy.canSeeSubmission(Role.STUDENT, self, sub("ANNUAL_REVIEW", SubmissionStatus.DRAFT)))
        assertTrue(AccessPolicy.canSeeSubmission(Role.STUDENT, self, sub("ANNUAL_REVIEW", SubmissionStatus.COMPLETED)))
    }
}

class CertificationPolicyTest {
    private val completion = LocalDate.of(2027, 1, 30)

    @Test fun certificateUnlocksOnlyWhenEveryPrerequisiteIsMet() {
        assertEquals(CertificationStatus.NOT_ELIGIBLE, CertificationPolicy.evaluate(22, 22, completion, completion.minusDays(1), true).status)
        assertEquals(CertificationStatus.AWAITING_FINAL_SIGNOFF, CertificationPolicy.evaluate(21, 22, completion, completion, true).status)
        assertEquals(CertificationStatus.READY_FOR_CERTIFICATION, CertificationPolicy.evaluate(22, 22, completion, completion, true).status)
        assertEquals(CertificationStatus.READY_FOR_CERTIFICATION, CertificationPolicy.evaluate(22, 22, completion, completion.minusYears(1), false).status)
        assertEquals(CertificationStatus.AWAITING_FINAL_SIGNOFF, CertificationPolicy.evaluate(0, 0, completion, completion, true).status)
        assertEquals(CertificationStatus.CERTIFICATE_ISSUED, CertificationPolicy.evaluate(22, 22, completion, completion, true, "c1", "X").status)
    }

    @Test fun reasonsAndNumbering() {
        val st = CertificationPolicy.evaluate(20, 22, completion, completion.minusDays(10), true)
        assertEquals(2, st.reasons.size)
        assertEquals("MDPED/2027/00042", CertificationPolicy.certificateNumber("MDPED", 2027, 42))
    }
}
