package org.mdpeds.logbook.data.demo

import org.mdpeds.logbook.domain.model.AssignmentRole
import org.mdpeds.logbook.domain.model.ReviewAction
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.SaveRequest
import org.mdpeds.logbook.domain.model.SignoffAction
import org.mdpeds.logbook.domain.model.Submission
import java.time.LocalDate

/**
 * Builds realistic demo data by driving the backend's own workflow methods, so every seeded record
 * has passed the same validation and state transitions a real user would go through.
 *
 * Accounts (password Demo@1234): hod@demo.org, faculty1@demo.org, faculty2@demo.org,
 * student1@demo.org (PGY-2, active), student2@demo.org (month 2), student3@demo.org (completed & certified).
 */
internal class DemoSeed(private val b: DemoBackend) {

    private lateinit var today: LocalDate

    fun run() {
        today = b.todayForSeed()
        val hod = b.seedUser("hod@demo.org", "Dr. Meera Rao", Role.HOD, "Professor & Head", unit = "Unit I")
        val f1 = b.seedUser("faculty1@demo.org", "Dr. Arjun Menon", Role.FACULTY, "Associate Professor", unit = "Unit I", unitHead = true)
        val f2 = b.seedUser("faculty2@demo.org", "Dr. Sana Qureshi", Role.FACULTY, "Assistant Professor", unit = "Unit II")

        val s1Start = today.minusMonths(13).withDayOfMonth(1)
        val s2Start = today.minusMonths(1).withDayOfMonth(1)
        val s3Start = today.minusMonths(37).withDayOfMonth(1)
        val b1 = b.seedBatch("MD Pediatrics ${s3Start.year}", "${s3Start.year}-${(s3Start.year + 1) % 100}", s3Start)
        val b2 = b.seedBatch("MD Pediatrics ${s1Start.year}", "${s1Start.year}-${(s1Start.year + 1) % 100}", s1Start)
        val b3 = b.seedBatch("MD Pediatrics ${s2Start.year}", "${s2Start.year}-${(s2Start.year + 1) % 100}", s2Start)

        val s1 = b.seedStudent("student1@demo.org", "Dr. Priya Sharma", "PED-${s1Start.year}-001", b2, s1Start, f1, "PICU")
        b.seedAssign(s1, f2, AssignmentRole.UNIT_FACULTY)
        val s2 = b.seedStudent("student2@demo.org", "Dr. Rahul Verma", "PED-${s2Start.year}-014", b3, s2Start, f2, "General Pediatrics Ward")
        val s3 = b.seedStudent("student3@demo.org", "Dr. Ananya Iyer", "PED-${s3Start.year}-007", b1, s3Start, f1, null)

        seedActiveStudent(s1, f1, f2, hod)
        seedNewStudent(s2)
        seedCompletedStudent(s3, f1, hod)
    }

    // ---------------------------------------------------------------- helpers

    private fun save(student: String, code: String, data: Map<String, String>, period: Int? = null, slot: Int? = null, submit: Boolean = true): Submission =
        b.actingAs(student) {
            val existing = when {
                period != null -> periodRecordOrNull(student, code, period)
                slot != null -> slotRecord(student, code, slot)
                else -> null
            }
            var s = saveSubmission(SaveRequest(existing?.id, code, data, existing?.version, period, slot))
            if (submit) s = submitSubmission(s.id, s.version)
            s
        }

    private fun DemoBackend.periodRecordOrNull(student: String, code: String, period: Int) =
        runCatching { periodRecord(student, code, period) }.getOrNull()

    private fun verify(faculty: String, s: Submission, data: Map<String, String>): Submission =
        b.actingAs(faculty) { reviewSubmission(s.id, ReviewAction.VERIFY, data, null, s.version) }

    private fun returnFor(faculty: String, s: Submission, reason: String): Submission =
        b.actingAs(faculty) { reviewSubmission(s.id, ReviewAction.RETURN, emptyMap(), reason, s.version) }

    private fun day(daysAgo: Long) = today.minusDays(daysAgo).toString()

    private fun words(topic: String) =
        "During this $topic I worked closely with the team and documented each step carefully. " +
            "I reviewed the relevant guideline, discussed the plan with my senior and the parents, and monitored the child closely over the next hours. " +
            "The experience showed me how structured assessment and clear communication improve safety for sick children and their families."

    private fun monthly(n: Int, unit: String) = mapOf(
        "posting_unit" to unit,
        "attendance" to "Present on all working days; ${n % 3 + 4} night duties.",
        "admissions_opd_emergency" to "Admissions ${20 + n}, OPD ${60 + 2 * n}, emergency ${15 + n}.",
        "major_cases" to "Severe pneumonia, nephrotic syndrome, dengue with warning signs.",
        "procedures_completed" to "IV cannulation ×${8 + n % 5}, blood sampling ×10, lumbar puncture ×1.",
        "nicu_picu_exposure" to if (n % 4 == 0) "PICU rotation – ventilated patients ×3" else "Neonatal follow-up clinic",
        "academic_activities" to "Seminar on fluid management; journal club presentation.",
        "research_progress" to "Thesis work on schedule as per the dissertation plan.",
        "teaching_activity" to "Bedside teaching for MBBS students on growth charts.",
        "communication_professionalism" to "Counselled parents during admissions and discharge.",
        "critical_incident" to "None this month.",
        "self_assessment" to if (n < 6) "M" else "E",
        "strengths" to "Systematic history taking and documentation.",
        "areas_improvement" to "Speed during emergency triage.",
        "action_plan" to "Attend PALS refresher; lead two resuscitation drills.",
    )

    private val facultyMonthly = mapOf(
        "faculty_assessment" to "M",
        "faculty_comments" to "Good progress, consistent and reliable in the ward.",
        "remedial_required" to "false",
    )

    // ---------------------------------------------------------------- student 1: PGY-2, rich history

    private fun seedActiveStudent(s1: String, f1: String, f2: String, hod: String) {
        val current = 14
        for (m in 1 until current) {
            val unit = if (m % 2 == 0) "Unit II – General Pediatrics" else "Unit I – NICU"
            val submitted = save(s1, "MONTHLY", monthly(m, unit), period = m)
            when (m) {
                current - 1 -> Unit // awaiting faculty review
                current - 2 -> returnFor(f2, submitted, "Please add the number of night duties and NICU exposure details.")
                current - 3 -> verify(f2, submitted, facultyMonthly) // awaiting HOD review
                else -> {
                    val v = verify(if (m % 2 == 0) f2 else f1, submitted, facultyMonthly)
                    b.actingAs(hod) { hodReviewSubmission(v.id, mapOf("hod_review" to "Reviewed. Satisfactory progress for month $m."), v.version) }
                }
            }
        }
        save(s1, "MONTHLY", monthly(current, "Unit I – PICU").filterKeys { it != "action_plan" }, period = current, submit = false)

        fun case(daysAgo: Long, dx: String, setting: String, presentation: String, role: String, comps: String) = mapOf(
            "date" to day(daysAgo), "case_ref" to "IP/${24000 + daysAgo}", "age_sex" to "${daysAgo % 11 + 1} y / ${if (daysAgo % 2 == 0L) "M" else "F"}",
            "setting" to setting, "unit" to "Unit I", "diagnosis" to dx, "presentation" to presentation, "role" to role,
            "key_learning" to "Structured approach to $dx and early recognition of complications.", "faculty" to f1, "competencies" to comps,
        )
        val c1 = save(s1, "CLINICAL_CASE", case(40, "Severe acute malnutrition with sepsis", "WARD", "LONG_CASE", "PS", "PG-PED-01,PG-PED-04"))
        verify(f1, c1, mapOf("faculty_feedback" to "Well-presented long case; good nutritional plan."))
        val c2 = save(s1, "CLINICAL_CASE", case(25, "Nephrotic syndrome – first episode", "WARD", "SHORT_CASE", "PI", "PG-PED-02"))
        verify(f1, c2, mapOf("faculty_feedback" to "Good examination findings."))
        val c3 = save(s1, "CLINICAL_CASE", case(12, "Acute severe asthma", "EMERGENCY", "NONE", "PS", "PG-PED-05"))
        returnFor(f1, c3, "Please document the PRAM score and the bronchodilator schedule.")
        save(s1, "CLINICAL_CASE", case(3, "Dengue with warning signs", "WARD", "LONG_CASE", "PS", ""))
        save(s1, "CLINICAL_CASE", case(1, "Febrile seizure", "EMERGENCY", "NONE", "A", ""), submit = false)

        save(s1, "NEONATAL_CASE", mapOf(
            "date" to day(60), "case_ref" to "NICU/1182", "ga_bw" to "32 wk / 1.6 kg", "diagnosis" to "RDS",
            "respiratory_support" to "CPAP", "major_intervention" to "Surfactant (LISA)", "outcome" to "DISCHARGED",
            "faculty" to f1, "competencies" to "PG-PED-07",
        )).let { verify(f1, it, mapOf("faculty_feedback" to "Good LISA technique.")) }

        save(s1, "PICU_CASE", mapOf(
            "date" to day(8), "case_ref" to "PICU/402", "diagnosis" to "Septic shock", "severity_pews" to "PEWS 7",
            "airway_ventilation" to "HFNC then intubation, SIMV", "circulation_drugs" to "Fluid boluses 40 ml/kg, adrenaline infusion",
            "outcome" to "ONGOING", "faculty" to f2,
        ))

        val procs = listOf("PROC_IV_CANNULATION", "PROC_IV_CANNULATION", "PROC_BLOOD_SAMPLING", "PROC_LUMBAR_PUNCTURE", "PROC_NEONATAL_RESUSCITATION", "PROC_BAG_MASK")
        procs.forEachIndexed { i, p ->
            val s = save(s1, "PROCEDURE", mapOf(
                "date" to day(50L - i * 6), "procedure" to p, "case_ref" to "IP/${23500 + i}", "role" to if (i < 2) "PS" else "PI",
                "complication" to "None", "learning_reflection" to "Confirmed landmarks and aseptic technique before the procedure.", "faculty" to f2,
            ))
            if (i < 5) verify(f2, s, mapOf("faculty_feedback" to "Competent."))
        }

        val cex = save(s1, "MINI_CEX", mapOf(
            "date" to day(30), "unit" to "Unit I", "patient_problem" to "Child with acute bronchiolitis", "assessor" to f1, "competencies" to "PG-PED-01",
        ), slot = 1)
        verify(f1, cex, mapOf(
            "history_taking" to "7", "examination" to "6", "clinical_reasoning" to "6", "communication" to "8", "management_plan" to "6",
            "professionalism" to "8", "overall" to "7", "strengths" to "Rapport with parents.", "areas_improvement" to "Prioritise the differential diagnosis.",
            "action_plan" to "Present two bronchiolitis cases at the unit meeting.", "remedial_required" to "false",
        ))
        save(s1, "MINI_CEX", mapOf("date" to day(4), "unit" to "Unit II", "patient_problem" to "Dehydration assessment", "assessor" to f2), slot = 2)
        save(s1, "DOPS", mapOf("procedure" to "Lumbar puncture", "date" to day(20), "unit" to "Unit I", "assessor" to f1), slot = 1)

        save(s1, "ACADEMIC_ACTIVITY", mapOf(
            "date" to day(15), "activity_type" to "SEMINAR", "topic" to "Approach to the wheezy child", "presenter" to "Dr. Priya Sharma",
            "learning_objectives" to "Differentiate asthma, bronchiolitis and foreign body.", "faculty" to f1,
        )).let { verify(f1, it, mapOf("faculty_feedback" to "Excellent seminar with good use of evidence.")) }
        save(s1, "ACADEMIC_ACTIVITY", mapOf(
            "date" to day(6), "activity_type" to "JOURNAL_CLUB", "topic" to "Balanced crystalloids in pediatric sepsis", "presenter" to "Dr. Priya Sharma",
            "learning_objectives" to "Critical appraisal of an RCT.", "faculty" to f2,
        ))

        val m1 = save(s1, "DISSERTATION_MILESTONE", mapOf(
            "planned_date" to day(300), "completed" to "true", "completed_date" to day(290), "evidence" to "Topic approved by the departmental research committee.",
        ), slot = 1)
        verify(f1, m1, mapOf("guide_feedback" to "Relevant, feasible topic."))
        val m2 = save(s1, "DISSERTATION_MILESTONE", mapOf(
            "planned_date" to day(200), "completed" to "true", "completed_date" to day(190), "evidence" to "Protocol submitted and approved by the guide.",
        ), slot = 2)
        verify(f1, m2, mapOf("guide_feedback" to "Protocol is well structured."))
        save(s1, "DISSERTATION_MILESTONE", mapOf(
            "planned_date" to day(30), "completed" to "true", "completed_date" to day(20), "evidence" to "Ethics committee approval letter received (IEC/2025/114).",
        ), slot = 3)

        save(s1, "TEACHING", mapOf(
            "date" to day(18), "learner" to "UG", "topic" to "Growth charts", "method" to "BEDSIDE", "observed_by" to f2,
        )).let { verify(f2, it, mapOf("feedback" to "Clear, interactive session.")) }

        save(s1, "REFLECTION", mapOf(
            "date" to day(9), "clinical_situation" to "Breaking bad news to parents of a child with leukaemia",
            "what_happened" to words("conversation"), "what_learned" to words("reflection"), "do_differently" to words("plan for next time"),
        ))

        b.actingAs(f1) {
            saveAnnualReview(s1, 1, buildMap {
                listOf("clinical_knowledge", "clinical_examination", "emergency_critical_care", "procedural_skills", "neonatal_care",
                    "communication_counselling", "professionalism_ethics", "research", "teaching", "overall_progression").forEach {
                    put("${it}_rating", if (it == "research") "B" else "M")
                    put("${it}_comments", "Progressing as expected for PGY-1.")
                }
                put("narrative_feedback", "A sincere and reliable resident. Focus on research timelines in PGY-2.")
            }, submit = true, reason = null, expectedVersion = null)
        }

        b.actingAs(f1) {
            facultySignoff(s1, "PG-PED-01", SignoffAction.OBSERVED, null, null)
            facultySignoff(s1, "PG-PED-01", SignoffAction.SUPERVISED, null, null)
            facultySignoff(s1, "PG-PED-01", SignoffAction.INDEPENDENT, null, null)
            facultySignoff(s1, "PG-PED-01", SignoffAction.APPROVE, "Consistently independent.", null)
            facultySignoff(s1, "PG-PED-02", SignoffAction.OBSERVED, null, null)
            facultySignoff(s1, "PG-PED-02", SignoffAction.SUPERVISED, null, null)
            facultySignoff(s1, "PG-PED-07", SignoffAction.OBSERVED, null, null)
        }
        b.actingAs(s1) { requestSignoff("PG-PED-02", "Performed three supervised cases; requesting review.") }
    }

    // ---------------------------------------------------------------- student 2: just started

    private fun seedNewStudent(s2: String) {
        save(s2, "MONTHLY", monthly(1, "General Pediatrics Ward"), period = 1)
        save(s2, "MONTHLY", mapOf("posting_unit" to "General Pediatrics Ward"), period = 2, submit = false)
    }

    // ---------------------------------------------------------------- student 3: completed & certified

    private fun seedCompletedStudent(s3: String, f1: String, hod: String) {
        val codes = b.activeCompetencyCodes()
        b.actingAs(f1) {
            codes.forEach { c ->
                facultySignoff(s3, c, SignoffAction.OBSERVED, null, null)
                facultySignoff(s3, c, SignoffAction.SUPERVISED, null, null)
                facultySignoff(s3, c, SignoffAction.INDEPENDENT, null, null)
                facultySignoff(s3, c, SignoffAction.APPROVE, "Independent performance verified.", null)
            }
        }
        // With auto-issue enabled the last approval issues the certificate; otherwise the HOD issues it.
        b.actingAs(hod) { if (certificate(s3) == null) issueCertificate(s3) }
    }
}
