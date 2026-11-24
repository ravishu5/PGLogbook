package org.mdpeds.logbook.data.demo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.model.BackendException
import org.mdpeds.logbook.domain.model.CertificationStatus
import org.mdpeds.logbook.domain.model.ReviewAction
import org.mdpeds.logbook.domain.model.SaveRequest
import org.mdpeds.logbook.domain.model.SubmissionStatus
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DemoBackendTest {

    private val reference: ReferenceData by lazy {
        val dir = listOf(File("../../reference-data"), File("../reference-data"), File("reference-data")).first { it.isDirectory }
        ReferenceData.parse(
            File(dir, "templates.json").readText(),
            File(dir, "competencies.json").readText(),
            File(dir, "targets.json").readText(),
            File(dir, "documents.json").readText(),
        )
    }

    private fun backend() = DemoBackend(reference, Clock.fixed(Instant.parse("2025-06-15T06:00:00Z"), ZoneOffset.UTC))

    private val pw = DemoBackend.DEMO_PASSWORD

    private suspend fun expectError(code: Int, block: suspend () -> Unit): BackendException {
        try {
            block()
        } catch (e: BackendException) {
            assertEquals("unexpected error: ${e.message}", code, e.code)
            return e
        }
        fail("Expected BackendException $code")
        error("unreachable")
    }

    private fun clinicalCase(facultyId: String) = mapOf(
        "date" to "2025-06-10",
        "case_ref" to "IP-2231",
        "age_sex" to "4 y / M",
        "setting" to "WARD",
        "unit" to "Unit I",
        "diagnosis" to "Severe pneumonia",
        "role" to "PS",
        "key_learning" to "Early oxygen therapy and close monitoring.",
        "faculty" to facultyId,
    )

    @Test fun wrongPasswordIsRejected() = runTest {
        val b = backend()
        expectError(400) { b.signIn("student1@demo.org", "wrong") }
    }

    @Test fun logEntryWorkflowFromDraftToVerifiedUpdatesTargets() = runTest {
        val b = backend()
        val f1 = b.signIn("faculty1@demo.org", pw).userId
        val s1 = b.signIn("student1@demo.org", pw).userId
        val before = b.targetProgress(s1).first { it.targetKey == "GENERAL_ADMISSIONS" }.verified

        val draft = b.saveSubmission(SaveRequest(null, "CLINICAL_CASE", clinicalCase(f1), null, null, null))
        assertEquals(SubmissionStatus.DRAFT, draft.status)
        val submitted = b.submitSubmission(draft.id, draft.version)
        assertEquals(SubmissionStatus.SUBMITTED, submitted.status)
        expectError(409) { b.saveSubmission(SaveRequest(draft.id, "CLINICAL_CASE", clinicalCase(f1), submitted.version, null, null)) }

        b.signIn("faculty1@demo.org", pw)
        assertTrue(b.reviewQueue().any { it.id == draft.id })
        expectError(422) { b.reviewSubmission(draft.id, ReviewAction.RETURN, emptyMap(), " ", submitted.version) }
        expectError(409) { b.reviewSubmission(draft.id, ReviewAction.VERIFY, mapOf("faculty_feedback" to "Good"), null, submitted.version - 1) }
        val verified = b.reviewSubmission(draft.id, ReviewAction.VERIFY, mapOf("faculty_feedback" to "Well documented"), null, submitted.version)
        assertEquals(SubmissionStatus.VERIFIED, verified.status)
        assertEquals("Dr. Arjun Menon", verified.reviewedByName)

        b.signIn("student1@demo.org", pw)
        assertEquals(SubmissionStatus.VERIFIED, b.submission(draft.id).status)
        assertEquals(before + 1, b.targetProgress(s1).first { it.targetKey == "GENERAL_ADMISSIONS" }.verified)
        assertTrue(b.notifications().any { it.entityId == draft.id })
    }

    @Test fun incompleteRecordsCannotBeSubmitted() = runTest {
        val b = backend()
        val f1 = b.signIn("faculty1@demo.org", pw).userId
        b.signIn("student1@demo.org", pw)
        val draft = b.saveSubmission(SaveRequest(null, "CLINICAL_CASE", clinicalCase(f1) - "diagnosis", null, null, null))
        expectError(422) { b.submitSubmission(draft.id, draft.version) }
    }

    @Test fun accessIsLimitedToAssignedStudents() = runTest {
        val b = backend()
        b.signIn("hod@demo.org", pw)
        val all = b.studentOverviews()
        assertEquals(3, all.size)
        val s3 = all.first { it.email == "student3@demo.org" }.studentId

        b.signIn("faculty2@demo.org", pw)
        assertTrue(b.studentOverviews().none { it.studentId == s3 })
        expectError(404) { b.studentOverview(s3) }

        b.signIn("student1@demo.org", pw)
        expectError(403) { b.submissions(s3) }
        assertTrue(b.reviewQueue().isEmpty())
    }

    @Test fun certificateRequiresPrerequisitesAndIsIssuedOnce() = runTest {
        val b = backend()
        b.signIn("hod@demo.org", pw)
        val all = b.studentOverviews()
        val s2 = all.first { it.email == "student2@demo.org" }.studentId
        val s3 = all.first { it.email == "student3@demo.org" }.studentId

        val s2State = b.certificationStatus(s2)
        assertTrue(s2State.status in setOf(CertificationStatus.NOT_ELIGIBLE, CertificationStatus.AWAITING_FINAL_SIGNOFF))
        assertTrue(s2State.reasons.isNotEmpty())
        expectError(409) { b.issueCertificate(s2) }

        assertEquals(CertificationStatus.CERTIFICATE_ISSUED, b.certificationStatus(s3).status)
        expectError(409) { b.issueCertificate(s3) }

        b.signIn("faculty1@demo.org", pw)
        expectError(403) { b.revokeCertificate(s3, "test") }
    }
}
