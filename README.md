# MD Pediatrics PG Logbook & Academic Management

A native Android app that replaces the paper **MD Pediatrics competency-based logbook**. Postgraduate
residents record cases, procedures, assessments, reflections and monthly reviews. Faculty verify, rate
and comment on them, and the HOD monitors progress, completes monthly and annual reviews, and issues the
final certificate.

| Layer | Technology |
|---|---|
| App | Kotlin, Jetpack Compose (Material 3), MVVM, Hilt, Navigation, Room (offline cache and draft outbox), DataStore, WorkManager (sync and reminders), Retrofit/OkHttp, Android `PdfDocument` |
| Backend | Supabase: PostgreSQL with Row-Level Security, Auth (invitation-only), Storage (private buckets), `SECURITY DEFINER` RPCs, an Edge Function, `pg_cron` |
| Reference data | [`reference-data/`](reference-data): templates, 22 competencies, exposure and procedure targets, academic documents. The app bundles it as assets, and the SQL seed is generated from it. |

## Repository layout

```
android/                 Android Studio project (module :app)
  app/src/main           shared app code (domain, data, sync, ui)
  app/src/demo           in-memory DemoBackend + seeded demo data   (flavor "demo")
  app/src/supabase       Supabase DI wiring                          (flavor "supabase")
  app/src/test           domain unit tests
  app/src/testDemo       DemoBackend workflow tests
  app/src/androidTestDemo Compose end-to-end journeys
reference-data/          canonical JSON shared by app and database
supabase/
  migrations/            schema, helpers/triggers, RLS, RPCs, storage, reference data, views
  functions/invite-user/ Edge Function that sends invitation emails
  scripts/               generate-seed.mjs, bootstrap_hod.sql
  tests/                 PGlite test-suite for the database (31 tests)
docs/                    backend setup and logbook verification
```

## Build and run

Requirements: Android Studio (latest stable) with JDK 17+ and Android SDK 37 installed.

1. Open the `android/` folder in Android Studio and let Gradle sync.
2. Choose the build variant (*Build ▸ Select Build Variant*):
   * **`demoDebug`** works offline straight away, with seeded data and the same role rules as production.
     Its data resets when the app process restarts.
   * **`supabaseDebug`** / **`supabaseRelease`** need a configured Supabase project
     (see [docs/BACKEND_SETUP.md](docs/BACKEND_SETUP.md)). Add the project's values to `android/local.properties`:
     ```properties
     SUPABASE_URL=https://<project-ref>.supabase.co
     SUPABASE_ANON_KEY=<anon public key>
     ```
3. Run on a device or emulator (API 26+).

Command line equivalents, run from `android/`:

```bash
./gradlew assembleDemoDebug                 # APK: app/build/outputs/apk/demo/debug
./gradlew testDemoDebugUnitTest             # domain + DemoBackend unit tests
./gradlew connectedDemoDebugAndroidTest     # Compose journeys (device/emulator required)
```

### Demo accounts (`demo` flavor)

All demo accounts use the password **`Demo@1234`**. The login screen also offers one-tap demo sign-in.

| Email | Role | Seeded situation |
|---|---|---|
| `hod@demo.org` | HOD | Sees all residents, monthly reviews awaiting the HOD, reports, settings |
| `faculty1@demo.org` | Faculty (unit head) | Guide of student 1 and student 3 |
| `faculty2@demo.org` | Faculty | Unit faculty for student 1, guide of student 2 |
| `student1@demo.org` | PG resident, month 14 | Month 13 awaiting faculty, month 12 returned, month 11 awaiting HOD |
| `student2@demo.org` | PG resident, month 2 | Just started |
| `student3@demo.org` | Completed resident | All 22 sign-offs approved, certificate issued |

## Features by role

**Resident (student)**
* Dashboard with the current programme month, PG year, the month's priorities from the rotation plan, and items that need action.
* Clinical, neonatal, PICU and procedure logs, using the O / A / PS / PI levels.
* Assessment forms:
  * 6 Mini-CEX and 6 DOPS forms, each assessed by the assessor the resident chooses.
  * Seminar, journal club and case discussion records.
  * A dissertation tracker with 9 milestones.
  * Teaching records.
* Reflections of 50–200 words per section, and 36 monthly assessments with B/M/E self-assessment. Future months stay locked.
* Progress against exposure and procedure targets, competency sign-off progression, faculty feedback history and certificate status.
* Offline drafts that sync automatically, PDF and image evidence, and reminder notifications.

**Faculty**
* Dashboard, assigned residents and the review queue.
* Verify records, or return them with a mandatory reason.
* Rate Mini-CEX and DOPS on the 1–9 scale.
* Record Observed, Supervised and Independent sign-offs, then approve the final sign-off.
* Reopen a record or correct an assessment, with the reason captured in the audit trail.

**HOD**
* Everything faculty can do, for all residents.
* Final monthly reviews and the annual comprehensive review.
* Assign guides and faculty, set programme start dates, and manage users through invitations and activation.
* Programme settings, batches, targets, competencies and academic documents.
* Monitoring, PDF and CSV reports, issuing and revoking certificates, and the audit log.

## Testing status

* **Database:** `cd supabase/tests && npm install && node run.mjs` runs 31 end-to-end tests covering RLS, RPC workflows, validation, audit immutability, scheduling and bootstrap. All pass.
* **Android:** there are unit tests (`src/test`, `src/testDemo`) and Compose UI tests (`src/androidTestDemo`). Run them from Android Studio or with the Gradle commands above.

## Further documentation

* [docs/BACKEND_SETUP.md](docs/BACKEND_SETUP.md) explains how to create and configure the Supabase project.
* [docs/LOGBOOK_VERIFICATION.md](docs/LOGBOOK_VERIFICATION.md) maps every section of the paper logbook to the app.
