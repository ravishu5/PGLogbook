# Verification against the paper logbook

The app digitises the institutional *MD Pediatrics – Detailed Competency-Based Logbook*.
* In the paper logbook, the resident fills each section by hand, and the guide, faculty and HOD rate, comment and sign.
* In the app, every section has a digital equivalent with the same fields. Signatures become authenticated actions that are timestamped and audited.

| # | Paper logbook section | App equivalent | Who fills / who signs |
|---|---|---|---|
| – | **Resident / programme details**: name, institution, university, batch, registration no., date of joining, guide/mentor, HOD | Profile and student overview. Set from the HOD invitation and *Student ▸ Programme start / Edit details / Assign faculty* | HOD sets these; the resident sees them read-only |
| – | **Rating scale**: O / A / PS / PI and B / M / E / R | O/A/PS/PI is the `role` field on every log. B/M/E is used by monthly and annual reviews. "R (repeat/remedial)" is the faculty `remedial_required` flag on Mini-CEX, DOPS and monthly assessments | – |
| 1 | Basis and use of this logbook | *Academic documents ▸ Logbook guide* | Read-only, HOD-maintained |
| 2 | Three-year rotation plan | *Academic documents ▸ Rotation plan*. The resident dashboard shows the current month's priorities | Read-only |
| 3 | Competency master index (PG-PED-01 … 22, domain, expected progression) | *Academic documents ▸ Competency index*, a competency picker on every form, and *Competency sign-off* (22 rows). The HOD can edit competencies | – |
| 4 | Suggested minimum clinical exposure targets (14) | *Progress & targets*: recorded, submitted and verified counts against the target. Logs count automatically, for example a ward clinical case counts towards *General pediatric admissions*. The HOD can adjust targets | Counts update automatically on faculty verification |
| 5 | Suggested procedure targets (20) | *Progress & targets ▸ Procedures*. Counted from the procedure log | As above |
| 6 | Clinical case log (ward / OPD / emergency) | `CLINICAL_CASE`: date, case ref, age/sex, setting, unit, diagnosis, long/short case, role, key learning, faculty, competencies | Resident writes; assigned faculty verifies with feedback or returns with a reason |
| 7 | Neonatal case log | `NEONATAL_CASE` | Same as 6 |
| 8 | PICU / critical care log | `PICU_CASE` | Same as 6 |
| 9 | Procedure log | `PROCEDURE`: procedure from the target list, role O/A/PS/PI, complication, reflection | Same as 6 |
| 10 | Mini-CEX, 6 forms | `MINI_CEX`, six numbered slots (extra forms allowed). Seven 1–9 ratings, strengths, areas to improve, action plan, remedial flag | Resident creates and picks the assessor; **only that assessor** (or the HOD) rates and signs |
| 11 | DOPS, 6 forms | `DOPS`, six numbered slots, same assessor rule | Same as 10 |
| 12 | Seminar / journal club / case discussion record (faculty feedback, signature) | `ACADEMIC_ACTIVITY` with activity type, topic, presenter, objectives | Faculty feedback recorded on verification |
| 13 | Research / dissertation tracker | `DISSERTATION_MILESTONE`, nine milestones with planned and completed dates and evidence files. Overdue milestones send reminders | **Guide** reviews |
| 14 | Teaching competency record | `TEACHING`: learner group, topic, method, observing faculty | Observing faculty gives feedback |
| 15 | Monthly assessment, 36 months (posting, attendance, cases, procedures, academic, research, teaching, professionalism, critical incident, self-assessment B/M/E, faculty assessment B/M/E, HOD/mentor review) | `MONTHLY`, 36 periods generated from the programme start date. All the paper fields are present; future months are locked | Resident, then faculty (B/M/E plus comments), then HOD or mentor review. The record is then completed |
| 16 | Annual comprehensive review | `ANNUAL_REVIEW`, three periods (PGY-1/2/3). Ten B/M/E domains with comments plus narrative feedback. Hidden from the resident until completed | Faculty and HOD only |
| 17 | Critical incident / reflection (faculty feedback) | `REFLECTION`: what happened, what I learned and what I'd do differently, each 50–200 words, as the logbook guidance requires | Faculty feedback recorded on verification |
| 18 | Final competency sign-off (22 competencies) | *Competency sign-off*: Observed, then Supervised, then Independent, then final approval, each dated with the signer's name. Returning a sign-off needs a reason | Faculty record progression and approve; the HOD can approve |
| 19 | Final certification | *Certificate*. Eligibility: all 22 sign-offs approved and, if configured, programme completed. The HOD issues it (or it auto-issues) with a unique number and a PDF certificate; it can be revoked with a reason | HOD |

## What the app adds beyond paper

* **Workflow states:** Draft, Submitted, Faculty review, HOD review, Verified/Completed, or Returned with a mandatory reason. Records lock after verification. Reopening or correcting a record needs a reason and is audited.
* **Access rules:**
  * Residents see only their own records.
  * Faculty see only the residents assigned to them.
  * Only the chosen assessor can rate a Mini-CEX or DOPS.
  * The HOD sees everything.
  * The database enforces these rules (RLS and RPCs), not just the UI.
* **Immutable audit trail** of every change, and an all-feedback history for the resident.
* **Offline drafts** with background sync and conflict detection.
* **Evidence attachments** (PDF and images).
* **Reminders** for monthly due dates, pending reviews, overdue milestones and annual reviews.
* **Reports:** PDF and CSV for progress, pending reviews, targets and sign-offs, plus a PDF certificate.

## How the mapping was verified

* The paper logbook's text was extracted, and each section and field was compared against `reference-data/templates.json`, `competencies.json` and `targets.json`:
  * 12 templates.
  * 22 competencies with the same codes and progressions.
  * 14 exposure and 20 procedure targets with the same suggested numbers.
* Automated checks:
  * `supabase/tests/run.mjs`, 31 tests. Covers the slot counts (36 monthly, 3 annual, 6 Mini-CEX, 6 DOPS, 9 milestones, 22 sign-offs), word limits, rating ranges, assessor rules and the monthly faculty-then-HOD chain.
  * `android/app/src/test/.../DomainTest.kt`, which runs the same rules on the device side.
