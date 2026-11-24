-- =====================================================================
-- MD Pediatrics PG Logbook – core schema
-- Target: Supabase (PostgreSQL 15+). All writes go through SECURITY
-- DEFINER RPCs (see 0004); tables are read through RLS (see 0003).
-- =====================================================================

create extension if not exists pgcrypto;

create schema if not exists app;
comment on schema app is 'Private helper functions. Not exposed through PostgREST.';

-- ---------------------------------------------------------------------
-- Users & roles
-- ---------------------------------------------------------------------
create table public.user_roles (
  code  text primary key,
  label text not null
);
insert into public.user_roles (code, label) values
  ('STUDENT', 'PG Student'),
  ('FACULTY', 'Faculty'),
  ('HOD',     'Head of Department');

create table public.profiles (
  id          uuid primary key references auth.users (id) on delete cascade,
  role        text not null references public.user_roles (code),
  full_name   text not null check (length(btrim(full_name)) > 0),
  email       text not null,
  designation text,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create unique index profiles_email_uq on public.profiles (lower(email));
create index profiles_role_idx on public.profiles (role);

create table public.institution_settings (
  id                               int primary key default 1 check (id = 1),
  institution_name                 text not null default 'Medical College & Hospital',
  department_name                  text not null default 'Department of Pediatrics',
  university_name                  text not null default 'Health Sciences University',
  dean_name                        text,
  dean_title                       text not null default 'Dean / Principal',
  programme_duration_months        int  not null default 36 check (programme_duration_months between 12 and 72),
  allow_future_months              boolean not null default false,
  require_programme_completion     boolean not null default true,
  monthly_hod_review_required      boolean not null default true,
  auto_issue_certificate           boolean not null default true,
  certificate_prefix               text not null default 'MDPED',
  updated_at                       timestamptz not null default now(),
  updated_by                       uuid references public.profiles (id)
);

create table public.academic_batches (
  id            uuid primary key default gen_random_uuid(),
  name          text not null unique,
  academic_year text not null,
  intake_date   date,
  created_at    timestamptz not null default now(),
  created_by    uuid references public.profiles (id)
);

create table public.faculty_profiles (
  user_id       uuid primary key references public.profiles (id) on delete cascade,
  unit          text,
  employee_code text,
  is_unit_head  boolean not null default false,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create table public.student_profiles (
  user_id            uuid primary key references public.profiles (id) on delete cascade,
  university_reg_no  text,
  institution        text,
  university         text,
  batch_id           uuid references public.academic_batches (id),
  guide_id           uuid references public.profiles (id),
  current_posting    text,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create unique index student_profiles_reg_uq on public.student_profiles (university_reg_no) where university_reg_no is not null;
create index student_profiles_batch_idx on public.student_profiles (batch_id);

-- One authoritative programme start date per student.
create table public.programme_enrolments (
  id                    uuid primary key default gen_random_uuid(),
  student_id            uuid not null unique references public.student_profiles (user_id) on delete cascade,
  programme_start_date  date not null,
  duration_months       int  not null default 36 check (duration_months between 12 and 72),
  status                text not null default 'ACTIVE' check (status in ('ACTIVE', 'COMPLETED', 'WITHDRAWN')),
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);

create table public.faculty_assignments (
  id             uuid primary key default gen_random_uuid(),
  student_id     uuid not null references public.student_profiles (user_id) on delete cascade,
  faculty_id     uuid not null references public.profiles (id) on delete cascade,
  assignment_role text not null default 'UNIT_FACULTY' check (assignment_role in ('GUIDE', 'UNIT_FACULTY', 'MENTOR')),
  active         boolean not null default true,
  assigned_at    timestamptz not null default now(),
  assigned_by    uuid references public.profiles (id),
  unassigned_at  timestamptz
);
create unique index faculty_assignments_active_uq on public.faculty_assignments (student_id, faculty_id) where active;
create index faculty_assignments_faculty_idx on public.faculty_assignments (faculty_id) where active;

create table public.invitations (
  id                    uuid primary key default gen_random_uuid(),
  email                 text not null,
  full_name             text not null,
  role                  text not null references public.user_roles (code),
  designation           text,
  university_reg_no     text,
  batch_id              uuid references public.academic_batches (id),
  programme_start_date  date,
  guide_id              uuid references public.profiles (id),
  status                text not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'REVOKED')),
  invited_by            uuid references public.profiles (id),
  created_at            timestamptz not null default now(),
  accepted_at           timestamptz,
  constraint invitations_student_start check (role <> 'STUDENT' or programme_start_date is not null)
);
create unique index invitations_pending_email_uq on public.invitations (lower(email)) where status = 'PENDING';

-- ---------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------
create table public.competencies (
  code        text primary key,
  title       text not null,
  domain      text not null,
  progression text not null default 'PS → PI',
  sort_order  int  not null,
  active      boolean not null default true
);

-- Versioned, editable targets (exposure + procedures).
create table public.targets (
  id             uuid primary key default gen_random_uuid(),
  key            text not null,
  category       text not null check (category in ('EXPOSURE', 'PROCEDURE')),
  label          text not null,
  target_value   int  not null check (target_value > 0),
  sort_order     int  not null default 0,
  version        int  not null default 1,
  is_current     boolean not null default true,
  notes          text,
  effective_from timestamptz not null default now(),
  created_by     uuid references public.profiles (id),
  unique (key, version)
);
create unique index targets_current_uq on public.targets (key) where is_current;

create table public.form_templates (
  code             text primary key,
  category         text not null check (category in ('LOG', 'FORM', 'REFLECTION', 'MONTHLY', 'ANNUAL')),
  title            text not null,
  subtitle         text,
  schema           jsonb not null,
  required_initial int  not null default 0 check (required_initial >= 0),
  period_count     int,
  version          int  not null default 1,
  sort_order       int  not null default 0,
  active           boolean not null default true
);

create table public.academic_documents (
  id          uuid primary key default gen_random_uuid(),
  code        text not null unique,
  title       text not null,
  description text,
  sort_order  int  not null default 0,
  created_at  timestamptz not null default now()
);

create table public.document_versions (
  id            uuid primary key default gen_random_uuid(),
  document_id   uuid not null references public.academic_documents (id) on delete cascade,
  version_no    int  not null,
  content       jsonb,
  storage_path  text,
  file_name     text,
  notes         text,
  published     boolean not null default false,
  published_at  timestamptz,
  published_by  uuid references public.profiles (id),
  created_at    timestamptz not null default now(),
  unique (document_id, version_no),
  check (content is not null or storage_path is not null)
);
create unique index document_versions_published_uq on public.document_versions (document_id) where published;

-- ---------------------------------------------------------------------
-- Logbook records: ONE reusable table for every template instance
-- (logs, Mini-CEX, DOPS, activities, milestones, teaching, reflections,
--  36 monthly assessments and 3 annual reviews).
-- ---------------------------------------------------------------------
create table public.submissions (
  id                      uuid primary key default gen_random_uuid(),
  student_id              uuid not null references public.student_profiles (user_id) on delete restrict,
  template_code           text not null references public.form_templates (code),
  template_version        int  not null default 1,
  period_no               int  check (period_no is null or period_no > 0),
  slot_no                 int  check (slot_no is null or slot_no > 0),
  status                  text not null default 'DRAFT' check (status in (
                            'NOT_STARTED', 'DRAFT', 'SUBMITTED', 'RETURNED', 'VERIFIED',
                            'FACULTY_REVIEW_PENDING', 'FACULTY_REVIEWED', 'HOD_REVIEW_PENDING', 'COMPLETED')),
  student_data            jsonb not null default '{}'::jsonb,
  faculty_data            jsonb not null default '{}'::jsonb,
  hod_data                jsonb not null default '{}'::jsonb,
  competency_codes        text[] not null default '{}',
  entry_date              date,
  assessor_id             uuid references public.profiles (id),
  submitted_at            timestamptz,
  submitted_by_name       text,
  reviewed_by             uuid references public.profiles (id),
  reviewed_by_name        text,
  reviewed_by_designation text,
  reviewed_at             timestamptz,
  hod_reviewed_by         uuid references public.profiles (id),
  hod_reviewed_by_name    text,
  hod_reviewed_at         timestamptz,
  return_reason           text,
  version                 int  not null default 1,
  created_by              uuid references public.profiles (id),
  created_at              timestamptz not null default now(),
  updated_by              uuid references public.profiles (id),
  updated_at              timestamptz not null default now(),
  deleted_at              timestamptz
);
-- one monthly assessment per programme month, one annual review per PG year
create unique index submissions_period_uq on public.submissions (student_id, template_code, period_no) where period_no is not null;
create unique index submissions_slot_uq   on public.submissions (student_id, template_code, slot_no)   where slot_no is not null and deleted_at is null;
create index submissions_student_tpl_idx  on public.submissions (student_id, template_code) where deleted_at is null;
create index submissions_status_idx       on public.submissions (status) where deleted_at is null;
create index submissions_assessor_idx     on public.submissions (assessor_id) where assessor_id is not null;
create index submissions_updated_idx      on public.submissions (updated_at);

create table public.attachments (
  id            uuid primary key default gen_random_uuid(),
  submission_id uuid not null references public.submissions (id) on delete restrict,
  student_id    uuid not null references public.student_profiles (user_id),
  storage_path  text not null unique,
  file_name     text not null,
  mime_type     text,
  size_bytes    bigint,
  uploaded_by   uuid references public.profiles (id),
  created_at    timestamptz not null default now()
);
create index attachments_submission_idx on public.attachments (submission_id);

-- 22 final competency sign-offs per student
create table public.competency_signoffs (
  id                      uuid primary key default gen_random_uuid(),
  student_id              uuid not null references public.student_profiles (user_id) on delete cascade,
  competency_code         text not null references public.competencies (code),
  observed_at             timestamptz,
  observed_by             uuid references public.profiles (id),
  observed_by_name        text,
  supervised_at           timestamptz,
  supervised_by           uuid references public.profiles (id),
  supervised_by_name      text,
  independent_at          timestamptz,
  independent_by          uuid references public.profiles (id),
  independent_by_name     text,
  status                  text not null default 'NOT_STARTED' check (status in (
                            'NOT_STARTED', 'IN_PROGRESS', 'AWAITING_APPROVAL', 'APPROVED', 'RETURNED')),
  student_note            text,
  comment                 text,
  final_by                uuid references public.profiles (id),
  final_by_name           text,
  final_by_designation    text,
  final_at                timestamptz,
  version                 int not null default 1,
  updated_at              timestamptz not null default now(),
  unique (student_id, competency_code)
);
create index competency_signoffs_status_idx on public.competency_signoffs (status);

-- Attributable faculty feedback history (every review / correction)
create table public.faculty_feedback (
  id                  uuid primary key default gen_random_uuid(),
  student_id          uuid not null references public.student_profiles (user_id) on delete cascade,
  entity_type         text not null,
  entity_id           uuid not null,
  faculty_id          uuid not null references public.profiles (id),
  faculty_name        text not null,
  faculty_designation text,
  action              text not null,
  comment             text,
  created_at          timestamptz not null default now()
);
create index faculty_feedback_entity_idx on public.faculty_feedback (entity_id);

create sequence public.certificate_no_seq;

create table public.certificates (
  id              uuid primary key default gen_random_uuid(),
  student_id      uuid not null references public.student_profiles (user_id) on delete restrict,
  certificate_no  text not null unique,
  issued_at       timestamptz not null default now(),
  issued_by       uuid references public.profiles (id),
  issued_by_name  text,
  snapshot        jsonb not null,
  status          text not null default 'ISSUED' check (status in ('ISSUED', 'REVOKED')),
  revoked_at      timestamptz,
  revoked_reason  text
);
create unique index certificates_active_uq on public.certificates (student_id) where status = 'ISSUED';

create table public.audit_logs (
  id          bigint generated always as identity primary key,
  at          timestamptz not null default now(),
  actor_id    uuid,
  actor_role  text,
  action      text not null,
  entity_type text not null,
  entity_id   text,
  student_id  uuid,
  changes     jsonb,
  reason      text
);
create index audit_logs_entity_idx  on public.audit_logs (entity_id);
create index audit_logs_student_idx on public.audit_logs (student_id);
create index audit_logs_at_idx      on public.audit_logs (at desc);

create table public.notifications (
  id           uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references public.profiles (id) on delete cascade,
  title        text not null,
  body         text not null,
  category     text not null default 'GENERAL',
  entity_type  text,
  entity_id    uuid,
  student_id   uuid,
  dedupe_key   text,
  read_at      timestamptz,
  created_at   timestamptz not null default now()
);
create index notifications_recipient_idx on public.notifications (recipient_id, created_at desc);
create unique index notifications_dedupe_uq on public.notifications (recipient_id, dedupe_key) where dedupe_key is not null;
