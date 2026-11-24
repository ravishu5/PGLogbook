# Backend setup (Supabase)

The production backend is a single Supabase project. All business rules live in the database:
* Row-Level Security.
* `SECURITY DEFINER` RPCs that enforce the workflow, validation, optimistic locking and the audit trail.

The app therefore needs only the project URL and the **anon** key. The service-role key is never shipped in the app.

## 1. Create the project

1. Create a project at <https://supabase.com> and pick the region closest to the institution.
2. *Project Settings ▸ API*: copy the **Project URL** and the **anon public** key into `android/local.properties`:
   ```properties
   SUPABASE_URL=https://<project-ref>.supabase.co
   SUPABASE_ANON_KEY=<anon key>
   ```

## 2. Apply the migrations

With the Supabase CLI:

```bash
supabase link --project-ref <project-ref>
supabase db push            # applies supabase/migrations in order
```

Alternatively, paste each file from `supabase/migrations/` into the SQL editor, in file-name order.

| Migration | Contents |
|---|---|
| `…01_schema.sql` | Tables, constraints and indexes |
| `…02_helpers.sql` | Timeline functions, audit triggers, enrolment provisioning, the invitation-to-profile trigger |
| `…03_rls.sql` | Row-Level Security. Direct table writes are denied; all changes go through RPCs |
| `…04_rpc.sql` | Workflow RPCs (save, submit, review, HOD review, sign-offs, certification, administration, settings) and `run_daily_jobs` |
| `…05_storage.sql` | The private `evidence` and `documents` buckets, with their policies |
| `…06_reference_data.sql` | Generated from `reference-data/`. Re-generate it with `node supabase/scripts/generate-seed.mjs` |
| `…07_views.sql` | Overview and monitoring views used by the dashboards |

## 3. Auth configuration

*Authentication ▸ URL Configuration*:
* **Site URL:** `mdpeds://auth-callback`
* **Redirect URLs:** add `mdpeds://auth-callback`. Invitation and password-reset links open the app through this deep link.

*Authentication ▸ Providers ▸ Email*: keep Email enabled and **disable "Allow new users to sign up"**. Accounts are created only through invitations.

For real email delivery, configure custom SMTP under *Project Settings ▸ Auth ▸ SMTP*.

## 4. Deploy the invitation Edge Function

```bash
supabase functions deploy invite-user
```

The function:
* Checks that the caller is an active HOD.
* Loads the pending invitation that the `create_invitation` RPC created.
* Sends the Supabase invite email.

`SUPABASE_URL`, `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` are injected automatically.

## 5. Bootstrap the first HOD

1. Edit the institution details and the HOD's name and email in `supabase/scripts/bootstrap_hod.sql`, then run it in the SQL editor.
2. *Authentication ▸ Users ▸ Invite user* with the same email. Alternatively, use *Add user* with a password and auto-confirm.
3. When the account is created, the `on_auth_user_created` trigger turns the pending invitation into an HOD profile.

The HOD then signs in to the app and does the rest from there:
* Invites faculty and residents from **Invitations**. A resident needs a programme start date and optionally a guide.
* Adjusts **Programme settings**: certificate prefix, HOD review of monthly assessments, auto-issue of certificates, and so on.

## 6. Schedule the daily reminders (`pg_cron`)

Enable the `pg_cron` extension (*Database ▸ Extensions*), then run:

```sql
select cron.schedule('mdpeds-daily-jobs', '30 2 * * *', $$select public.run_daily_jobs()$$);
```

`run_daily_jobs()` creates de-duplicated notifications and refreshes certification status:
* Reminds residents that the current month's assessment closes within 5 days.
* Tells reviewers when an annual review is due.
* Flags dissertation milestones that are past their planned date.
* Sends the HOD summaries of pending monthly reviews and competency approvals, plus notice of residents nearing completion.

On the device, the app's periodic WorkManager sync (every 6 hours) turns new server notifications
into system notifications when reminders are enabled in the Profile.

## 7. Verify

Test the database locally before pushing:

```bash
cd supabase/tests
npm install
node run.mjs        # 31 passed, 0 failed
```

The suite runs every migration in PGlite and exercises the RLS, the RPC workflows and the reference data.

## Security notes

* The service-role key lives only in the Edge Function environment.
* Storage buckets are private. Residents can upload evidence only to their own editable records.
* The audit log is append-only: database triggers reject updates and deletes.
* Deactivated users lose access immediately, because every policy and RPC checks `profiles.active`.
