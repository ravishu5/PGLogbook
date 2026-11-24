package org.mdpeds.logbook.ui.richinput

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a clean Markdown table from headers and row data.
 */
fun buildMarkdownTable(headers: List<String>, rows: List<List<String>>): String {
    val cleanHeaders = headers.map { it.trim().ifBlank { "Column" } }
    val headerLine = "| " + cleanHeaders.joinToString(" | ") + " |"
    val delimiterLine = "| " + cleanHeaders.map { "---" }.joinToString(" | ") + " |"

    val dataLines = rows.map { row ->
        val paddedRow = cleanHeaders.indices.map { idx ->
            row.getOrElse(idx) { "" }.trim()
        }
        "| " + paddedRow.joinToString(" | ") + " |"
    }

    return (listOf(headerLine, delimiterLine) + dataLines).joinToString("\n")
}

/**
 * Generates an empty grid table with [cols] columns and [rows] rows.
 */
fun generateCustomTable(cols: Int, rows: Int): String {
    val effectiveCols = cols.coerceIn(1, 8)
    val effectiveRows = rows.coerceIn(1, 20)
    val headers = (1..effectiveCols).map { "Column $it" }
    val rowData = (1..effectiveRows).map { r ->
        (1..effectiveCols).map { c -> "" }
    }
    return buildMarkdownTable(headers, rowData)
}

/**
 * Standard Clinical Template definitions.
 */
data class ClinicalTemplate(
    val title: String,
    val subtitle: String,
    val content: String,
)

object ClinicalTemplates {
    val allTemplates = listOf(
        ClinicalTemplate(
            title = "SOAP Clinical Note",
            subtitle = "Subjective, Objective, Assessment, Plan",
            content = """
                ### Subjective (S)
                - **Chief Complaints:** 
                - **History of Present Illness (HPI):** 
                - **Past Medical & Birth History:** 

                ### Objective (O)
                - **General Examination:** 
                - **Systemic Examination (CVS, RS, CNS, P/A):** 
                - **Current Vitals:** HR:       | RR:       | SpO2:       | Temp: 

                ### Assessment (A)
                - **Provisional Diagnosis:** 
                - **Differential Diagnoses:** 

                ### Plan (P)
                - **Diagnostic Workup:** 
                - **Management & Medications:** 
                - **Monitoring & Red Flags:** 
            """.trimIndent(),
        ),
        ClinicalTemplate(
            title = "SBAR Handover",
            subtitle = "Situation, Background, Assessment, Recommendation",
            content = """
                ### SBAR Clinical Handover
                - **Situation (S):** Dr. [Name] handing over Dr. [Name]. Patient: [Initials], Age: [Age/Sex], Bed: [Bed #]. Current issue: 
                - **Background (B):** Admitted for [Diagnosis] on [Date]. Key comorbidities: 
                - **Assessment (A):** Current clinical stability, recent ABG/labs, pending culture reports: 
                - **Recommendation (R):** Tasks for the incoming duty doctor (e.g. repeat electrolytes, check CXR, monitor urine output): 
            """.trimIndent(),
        ),
        ClinicalTemplate(
            title = "Clinical Case Examination",
            subtitle = "Full pediatric inpatient / outpatient case writeup",
            content = """
                ### Pediatric Case Presentation
                - **Informant & Reliability:** Mother / Father (Reliable)
                - **Presenting Complaints:** 
                  1. 
                  2. 
                - **HPI:** 
                - **Birth & Development History:** Term / Preterm, Birth Weight:     kg, Milestones: 
                - **Immunization & Dietary History:** Up to date as per UIP / IAP. Calorie:     %, Protein:     %
                - **General Physical Exam:** Vitals stable. Pallor / Icterus / Cyanosis / Clubbing / Lymphadenopathy / Edema: None.
                - **Systemic Exam:** 
                - **Summary & Diagnosis:** 
                - **Management Plan:** 
            """.trimIndent(),
        ),
        ClinicalTemplate(
            title = "Procedure / Intervention Log",
            subtitle = "Clinical procedure, steps, asepsis & outcome",
            content = """
                ### Procedure Record
                - **Procedure Name:** 
                - **Indication:** 
                - **Supervision Level:** Observed (O) / Assisted (A) / Performed Supervised (PS) / Performed Independent (PI)
                - **Informed Consent & Aseptic Precautions:** Obtained and documented. Standard universal sterile precautions maintained.
                - **Equipment & Gauge:** 
                - **Procedural Steps & Findings:** 
                - **Complications:** None encountered.
                - **Post-procedure Monitoring:** 
            """.trimIndent(),
        ),
        ClinicalTemplate(
            title = "Monthly Self-Reflection",
            subtitle = "Postgraduate clinical learning and dilemmas",
            content = """
                ### Monthly Reflective Learning
                - **Key Clinical Experiences this Month:** 
                - **Dilemmas / Challenging Encounters Faced:** 
                - **What went well & what could have been handled better:** 
                - **Guidance received from Faculty / Senior Residents:** 
                - **Action Plan & Competencies targeted for next month:** 
            """.trimIndent(),
        ),
        ClinicalTemplate(
            title = "Journal Club Presentation",
            subtitle = "Critical appraisal and academic presentation",
            content = """
                ### Journal Club Academic Record
                - **Article Title:** 
                - **Authors & Journal Citation:** 
                - **Study Hypothesis & Objectives:** 
                - **Study Design & Population:** 
                - **Primary & Secondary Outcomes:** 
                - **Critical Appraisal (Strengths & Limitations):** 
                - **Clinical Applicability in our setting:** 
            """.trimIndent(),
        ),
    )
}

/**
 * Standard Pediatric Clinical Reference Tables and Scoring Systems.
 */
data class ClinicalReferenceTable(
    val title: String,
    val description: String,
    val tableMarkdown: String,
)

object ClinicalReferenceTables {
    val allTables = listOf(
        ClinicalReferenceTable(
            title = "APGAR Score Table",
            description = "Neonatal assessment at 1 and 5 minutes",
            tableMarkdown = """
                | APGAR Criterion | 0 Points | 1 Point | 2 Points | 1 Min Score | 5 Min Score |
                | --- | --- | --- | --- | --- | --- |
                | Appearance (Color) | Blue / Pale | Acrocyanotic | Completely Pink | | |
                | Pulse (Heart Rate) | Absent | < 100 bpm | > 100 bpm | | |
                | Grimace (Reflex) | Floppy / None | Grimace / Whimper | Cough / Sneeze / Cry | | |
                | Activity (Muscle Tone) | Limp | Some Flexion | Active Motion | | |
                | Respiration | Absent | Slow / Irregular | Good Strong Cry | | |
                | **Total Score** | | | | **/10** | **/10** |
            """.trimIndent(),
        ),
        ClinicalReferenceTable(
            title = "Pediatric Vitals by Age",
            description = "Normal ranges for heart rate, respiration and BP",
            tableMarkdown = """
                | Age Group | Heart Rate (bpm) | Resp Rate (/min) | Systolic BP (mmHg) | Measured HR | Measured RR | Measured BP |
                | --- | --- | --- | --- | --- | --- | --- |
                | Neonate (< 28d) | 100 – 160 | 40 – 60 | 60 – 75 | | | |
                | Infant (1 – 12m) | 100 – 150 | 30 – 50 | 70 – 90 | | | |
                | Toddler (1 – 3y) | 90 – 140 | 24 – 40 | 80 – 100 | | | |
                | Preschool (3 – 5y) | 80 – 120 | 20 – 30 | 85 – 105 | | | |
                | School Age (6 – 12y) | 70 – 110 | 16 – 24 | 90 – 115 | | | |
                | Adolescent (> 12y) | 60 – 100 | 12 – 20 | 100 – 120 | | | |
            """.trimIndent(),
        ),
        ClinicalReferenceTable(
            title = "Pediatric Glasgow Coma Scale (pGCS)",
            description = "Modified neurological assessment for children",
            tableMarkdown = """
                | Component | Best Response (Child / Infant) | Max Points | Patient Score |
                | --- | --- | --- | --- |
                | Eye Opening (E) | Spontaneous (4), To Speech (3), To Pain (2), None (1) | 4 | |
                | Verbal Response (V) | Oriented / Coos (5), Confused / Irritable (4), Inappropriate / Cries to pain (3), Incomprehensible / Grunts (2), None (1) | 5 | |
                | Motor Response (M) | Obeys commands / Spontaneous (6), Localizes pain (5), Flexion withdrawal (4), Decorticate flexion (3), Decerebrate extension (2), None (1) | 6 | |
                | **Total GCS** | **Severe (3–8) · Moderate (9–12) · Mild (13–15)** | **15** | **/15** |
            """.trimIndent(),
        ),
        ClinicalReferenceTable(
            title = "Dehydration Assessment Table",
            description = "WHO guidelines for assessing dehydration severity",
            tableMarkdown = """
                | Sign / Feature | No Dehydration | Some Dehydration (Plan B) | Severe Dehydration (Plan C) | Patient Finding |
                | --- | --- | --- | --- | --- |
                | General Appearance | Well, alert | Restless, irritable | Lethargic or unconscious | |
                | Eyes | Normal | Sunken | Very sunken & dry | |
                | Tears | Present | Absent | Absent | |
                | Mouth & Tongue | Moist | Dry | Very dry | |
                | Thirst | Drinks normally | Thirsty, drinks eagerly | Drinks poorly or unable | |
                | Skin Pinch | Goes back quickly | Goes back slowly (≤2s) | Goes back very slowly (>2s) | |
                | **Classification** | **No Dehydration** | **Some Dehydration** | **Severe Dehydration** | |
            """.trimIndent(),
        ),
        ClinicalReferenceTable(
            title = "Anthropometry & Growth Percentiles",
            description = "Growth parameters and WHO Z-score tracking",
            tableMarkdown = """
                | Parameter | Measured Value | 50th Centile (Expected) | WHO Z-Score | Remarks |
                | --- | --- | --- | --- | --- |
                | Weight | kg | kg | SD | |
                | Length / Height | cm | cm | SD | |
                | Head Circumference | cm | cm | SD | |
                | Mid-Upper Arm (MUAC) | cm | > 12.5 cm | — | |
                | BMI / W-for-H | kg/m² | | SD | |
            """.trimIndent(),
        ),
        ClinicalReferenceTable(
            title = "Lab Investigations Table",
            description = "CBC, biochemistry, ABG, and culture records",
            tableMarkdown = """
                | Investigation | Result | Reference Units | Normal Range | Interpretation |
                | --- | --- | --- | --- | --- |
                | Hemoglobin (Hb) | | g/dL | 11.5 – 15.5 | |
                | Total Leuko Count (TLC) | | /cumm | 4,000 – 11,000 | |
                | Platelets | | /cumm | 1.5 – 4.5 Lakhs | |
                | Serum Sodium (Na+) | | mEq/L | 135 – 145 | |
                | Serum Potassium (K+) | | mEq/L | 3.5 – 5.0 | |
                | Serum Creatinine | | mg/dL | 0.3 – 0.7 | |
                | Blood Gas (pH / pCO2 / HCO3) | | | 7.35 – 7.45 / 35 – 45 / 22 – 26 | |
            """.trimIndent(),
        ),
        ClinicalReferenceTable(
            title = "Medication & Dosage Record",
            description = "Pediatric drug dosage calculation and schedule",
            tableMarkdown = """
                | Drug Name | Dose (mg/kg/day) | Total Dose (mg) | Route | Frequency | Duration |
                | --- | --- | --- | --- | --- | --- |
                | | | | IV / Oral | OD / BD / TDS | days |
                | | | | IV / Oral | OD / BD / TDS | days |
                | | | | IV / Oral | OD / BD / TDS | days |
            """.trimIndent(),
        ),
    )
}

/**
 * Returns an authenticated digital timestamp string for electronic logbooks.
 */
fun buildTimestampString(): String {
    val now = LocalDateTime.now()
    val fmt = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH)
    return "📅 [Recorded: ${now.format(fmt)} IST · Verified entry]"
}

/**
 * Returns a clinician signature stamp string.
 */
fun buildSignatureStamp(): String {
    val now = LocalDateTime.now()
    val fmt = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    return "✍️ [Electronically Verified: Dr. Resident / Faculty · Date: ${now.format(fmt)}]"
}
