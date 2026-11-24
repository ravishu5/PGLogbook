-- =====================================================================
-- Workflow RPCs (the ONLY write path for API clients).
-- Every function authorizes the caller from auth.uid() + profiles and
-- stamps reviewer identity server-side.
-- =====================================================================

-- ---------------------------------------------------------------- internals
create or replace function app.student_ro_keys(p_template text)
returns text[] language sql stable security definer set search_path = public as $$
  select coalesce(array_agg(f ->> 'key'), '{}')
  from app.template_fields(p_template, 'STUDENT') f
  where coalesce((f ->> 'readOnly')::boolean, false)
$$;

create or replace function app.assert_faculty_refs(p_template text, p_student uuid, p_data jsonb)
returns void language plpgsql stable security definer set search_path = public as $$
declare
  f jsonb;
  v text;
  v_id uuid;
begin
  for f in select * from app.template_fields(p_template, 'STUDENT') x where x ->> 'type' = 'FACULTY' loop
    v := nullif(btrim(coalesce(p_data ->> (f ->> 'key'), '')), '');
    continue when v is null;
    begin
      v_id := v::uuid;
    exception when others then
      perform app.fail('PT422', format('%s must reference a faculty account', f ->> 'label'));
    end;
    if not exists (
      select 1 from public.faculty_assignments a
       where a.student_id = p_student and a.faculty_id = v_id and a.active
      union all
      select 1 from public.student_profiles s where s.user_id = p_student and s.guide_id = v_id
    ) then
      perform app.fail('PT422', format('%s must be a faculty member assigned to you', f ->> 'label'));
    end if;
  end loop;
end $$;

create or replace function app.assert_month_open(p_student uuid, p_month int)
returns void language plpgsql stable security definer set search_path = public as $$
declare
  e public.programme_enrolments;
  s public.institution_settings;
begin
  select * into e from public.programme_enrolments where student_id = p_student;
  select * into s from public.institution_settings where id = 1;
  if e.id is null then
    perform app.fail('PT409', 'Programme start date has not been configured');
  end if;
  if p_month is null or p_month < 1 or p_month > e.duration_months then
    perform app.fail('PT422', format('Programme month must be between 1 and %s', e.duration_months));
  end if;
  if not coalesce(s.allow_future_months, false) and current_date < app.month_start(e.programme_start_date, p_month) then
    perform app.fail('PT403', format('Month %s opens on %s', p_month, app.month_start(e.programme_start_date, p_month)));
  end if;
end $$;

create or replace function app.record_feedback(
  p_student uuid, p_entity_type text, p_entity_id uuid, p_action text, p_comment text)
returns void language sql security definer set search_path = public as $$
  insert into public.faculty_feedback (student_id, entity_type, entity_id, faculty_id, faculty_name, faculty_designation, action, comment)
  values (p_student, p_entity_type, p_entity_id, auth.uid(), app.actor_name(), app.actor_designation(), p_action, p_comment);
$$;

create or replace function app.template_title(p_code text)
returns text language sql stable security definer set search_path = public as $$
  select title from public.form_templates where code = p_code
$$;

-- ---------------------------------------------------------------- student: drafts & submission
create or replace function public.save_submission(
  p_id uuid,
  p_template text,
  p_student_data jsonb,
  p_expected_version int default null,
  p_period int default null,
  p_slot int default null)
returns public.submissions
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_tpl public.form_templates;
  v_row public.submissions;
  v_ro text[];
  v_data jsonb;
  v_codes text[] := '{}';
  v_date date;
  v_assessor uuid;
begin
  perform app.require_role('STUDENT');

  select * into v_tpl from public.form_templates where code = p_template and active;
  if v_tpl.code is null then
    perform app.fail('PT404', 'Unknown form template');
  end if;
  if v_tpl.category = 'ANNUAL' then
    perform app.fail('PT403', 'Annual comprehensive reviews are completed by faculty only');
  end if;

  v_ro := app.student_ro_keys(p_template);
  perform app.assert_owned_keys(p_template, 'STUDENT', coalesce(p_student_data, '{}'::jsonb));

  if p_id is not null then
    select * into v_row from public.submissions where id = p_id for update;
  end if;
  if v_row.id is null and p_period is not null then
    select * into v_row from public.submissions
     where student_id = v_uid and template_code = p_template and period_no = p_period for update;
  end if;
  if v_row.id is null and p_slot is not null then
    select * into v_row from public.submissions
     where student_id = v_uid and template_code = p_template and slot_no = p_slot and deleted_at is null for update;
  end if;

  if v_tpl.category = 'MONTHLY' then
    perform app.assert_month_open(v_uid, coalesce(v_row.period_no, p_period));
  end if;

  if v_row.id is not null then
    if v_row.student_id <> v_uid or v_row.template_code <> p_template then
      perform app.fail('PT403', 'You can only edit your own records');
    end if;
    if v_row.deleted_at is not null then
      perform app.fail('PT404', 'Record was deleted');
    end if;
    if v_row.status not in ('NOT_STARTED', 'DRAFT', 'RETURNED') then
      perform app.fail('PT409', format('Record is locked while %s', v_row.status));
    end if;
    if p_expected_version is not null and p_expected_version <> v_row.version then
      perform app.fail('PT409', format('CONFLICT: record changed on the server (server version %s)', v_row.version));
    end if;
  end if;

  -- read-only template fields (e.g. milestone names) are never client-writable
  v_data := (coalesce(p_student_data, '{}'::jsonb) - v_ro)
            || coalesce((select jsonb_object_agg(k, v) from jsonb_each(coalesce(v_row.student_data, '{}'::jsonb)) as e(k, v)
                         where k = any (v_ro)), '{}'::jsonb);

  perform app.assert_faculty_refs(p_template, v_uid, v_data);

  if nullif(v_data ->> 'competencies', '') is not null then
    select coalesce(array_agg(distinct btrim(c)), '{}') into v_codes
      from unnest(string_to_array(v_data ->> 'competencies', ',')) c where btrim(c) <> '';
    if exists (select 1 from unnest(v_codes) c where c not in (select code from public.competencies)) then
      perform app.fail('PT422', 'Unknown competency code');
    end if;
  end if;

  begin
    v_date := nullif(v_data ->> coalesce(v_tpl.schema ->> 'dateField', 'date'), '')::date;
  exception when others then
    v_date := null;
  end;

  if v_tpl.schema ->> 'reviewer' = 'ASSESSOR' and nullif(v_data ->> 'assessor', '') is not null then
    v_assessor := (v_data ->> 'assessor')::uuid;
  end if;

  perform app.set_audit('SAVE_DRAFT');
  if v_row.id is null then
    insert into public.submissions (id, student_id, template_code, template_version, period_no, slot_no, status,
                                    student_data, competency_codes, entry_date, assessor_id, created_by, updated_by)
    values (coalesce(p_id, gen_random_uuid()), v_uid, p_template, v_tpl.version,
            case when v_tpl.category = 'MONTHLY' then p_period end, null, 'DRAFT',
            v_data, v_codes, v_date, v_assessor, v_uid, v_uid)
    returning * into v_row;
  else
    update public.submissions
       set student_data = v_data,
           competency_codes = v_codes,
           entry_date = v_date,
           assessor_id = v_assessor,
           status = case when status = 'NOT_STARTED' then 'DRAFT' else status end,
           version = version + 1,
           updated_by = v_uid
     where id = v_row.id
     returning * into v_row;
  end if;
  perform app.set_audit(null);
  return v_row;
end $$;

create or replace function public.submit_submission(p_id uuid, p_expected_version int default null)
returns public.submissions
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_row public.submissions;
  v_tpl public.form_templates;
  v_errs text[];
  v_status text;
  v_has_faculty boolean;
begin
  perform app.require_role('STUDENT');
  select * into v_row from public.submissions where id = p_id and deleted_at is null for update;
  if v_row.id is null then
    perform app.fail('PT404', 'Record not found');
  end if;
  if v_row.student_id <> v_uid then
    perform app.fail('PT403', 'You can only submit your own records');
  end if;
  select * into v_tpl from public.form_templates where code = v_row.template_code;
  if v_tpl.category = 'ANNUAL' then
    perform app.fail('PT403', 'Annual comprehensive reviews are completed by faculty only');
  end if;
  -- idempotent re-submit (e.g. retried offline sync)
  if v_row.status in ('SUBMITTED', 'FACULTY_REVIEW_PENDING') then
    return v_row;
  end if;
  if v_row.status not in ('DRAFT', 'RETURNED') then
    perform app.fail('PT409', format('Record cannot be submitted while %s', v_row.status));
  end if;
  if p_expected_version is not null and p_expected_version <> v_row.version then
    perform app.fail('PT409', format('CONFLICT: record changed on the server (server version %s)', v_row.version));
  end if;
  if v_tpl.category = 'MONTHLY' then
    perform app.assert_month_open(v_uid, v_row.period_no);
  end if;

  v_errs := app.validate_fields(v_row.template_code, 'STUDENT', v_row.student_data);
  if array_length(v_errs, 1) > 0 then
    perform app.fail('PT422', array_to_string(v_errs, '; '));
  end if;

  v_has_faculty := exists (select 1 from public.faculty_assignments where student_id = v_uid and active)
                   or exists (select 1 from public.student_profiles where user_id = v_uid and guide_id is not null);
  v_status := case when v_tpl.category = 'MONTHLY' and v_has_faculty then 'FACULTY_REVIEW_PENDING' else 'SUBMITTED' end;

  perform app.set_audit(case when v_row.status = 'RETURNED' then 'RESUBMIT' else 'SUBMIT' end);
  update public.submissions
     set status = v_status,
         submitted_at = now(),
         submitted_by_name = app.actor_name(),
         return_reason = null,
         version = version + 1,
         updated_by = v_uid
   where id = p_id
   returning * into v_row;
  perform app.set_audit(null);

  if v_row.assessor_id is not null then
    perform app.notify(v_row.assessor_id, v_tpl.title || ' awaiting assessment',
                       app.actor_name() || ' submitted a ' || v_tpl.title || ' for your assessment.',
                       'REVIEW_REQUEST', 'submission', v_row.id, v_uid);
  else
    perform app.notify_reviewers(v_uid, v_tpl.title || ' submitted',
                                 app.actor_name() || ' submitted a ' || v_tpl.title || case when v_row.period_no is not null then ' (period ' || v_row.period_no || ')' else '' end || ' for review.',
                                 'REVIEW_REQUEST', 'submission', v_row.id);
  end if;
  return v_row;
end $$;

create or replace function public.delete_draft(p_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_row public.submissions;
begin
  perform app.require_role('STUDENT');
  select * into v_row from public.submissions where id = p_id for update;
  if v_row.id is null or v_row.student_id <> auth.uid() then
    perform app.fail('PT404', 'Record not found');
  end if;
  if v_row.status <> 'DRAFT' or v_row.submitted_at is not null or v_row.period_no is not null or v_row.slot_no is not null then
    perform app.fail('PT403', 'Only unsubmitted additional drafts can be discarded');
  end if;
  perform app.set_audit('DISCARD_DRAFT');
  update public.submissions set deleted_at = now(), version = version + 1, updated_by = auth.uid() where id = p_id;
  perform app.set_audit(null);
end $$;

-- ---------------------------------------------------------------- faculty review
create or replace function public.review_submission(
  p_id uuid,
  p_action text,
  p_faculty_data jsonb default '{}'::jsonb,
  p_comment text default null,
  p_expected_version int default null)
returns public.submissions
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_row public.submissions;
  v_tpl public.form_templates;
  v_settings public.institution_settings;
  v_fdata jsonb;
  v_errs text[];
  v_status text;
begin
  perform app.require_role('FACULTY', 'HOD');
  select * into v_row from public.submissions where id = p_id and deleted_at is null for update;
  if v_row.id is null then
    perform app.fail('PT404', 'Record not found');
  end if;
  if not app.can_review_student(v_row.student_id) then
    perform app.fail('PT403', 'You are not assigned to this student');
  end if;
  select * into v_tpl from public.form_templates where code = v_row.template_code;
  select * into v_settings from public.institution_settings where id = 1;
  if v_tpl.category = 'ANNUAL' then
    perform app.fail('PT400', 'Use save_annual_review for annual comprehensive reviews');
  end if;
  if v_tpl.schema ->> 'reviewer' = 'ASSESSOR' and not app.is_hod() and v_row.assessor_id is distinct from v_uid then
    perform app.fail('PT403', 'Only the selected assessor can assess this form');
  end if;
  if v_tpl.schema ->> 'reviewer' = 'GUIDE' and not app.is_hod() and not app.is_guide(v_row.student_id) then
    perform app.fail('PT403', 'Only the student''s guide can review dissertation milestones');
  end if;
  if v_row.status not in ('SUBMITTED', 'FACULTY_REVIEW_PENDING') then
    perform app.fail('PT409', format('Record is not awaiting faculty review (%s)', v_row.status));
  end if;
  if p_expected_version is not null and p_expected_version <> v_row.version then
    perform app.fail('PT409', format('CONFLICT: record changed on the server (server version %s)', v_row.version));
  end if;

  perform app.assert_owned_keys(v_row.template_code, 'FACULTY', coalesce(p_faculty_data, '{}'::jsonb));
  v_fdata := v_row.faculty_data || coalesce(p_faculty_data, '{}'::jsonb);

  if p_action = 'VERIFY' then
    v_errs := app.validate_fields(v_row.template_code, 'FACULTY', v_fdata);
    if array_length(v_errs, 1) > 0 then
      perform app.fail('PT422', array_to_string(v_errs, '; '));
    end if;
    v_status := case
      when v_tpl.category = 'MONTHLY' and v_settings.monthly_hod_review_required then 'HOD_REVIEW_PENDING'
      when v_tpl.category = 'MONTHLY' then 'COMPLETED'
      else 'VERIFIED' end;
  elsif p_action = 'RETURN' then
    if nullif(btrim(coalesce(p_comment, '')), '') is null then
      perform app.fail('PT422', 'A reason is required when returning a record');
    end if;
    v_status := 'RETURNED';
  else
    perform app.fail('PT400', 'Action must be VERIFY or RETURN');
  end if;

  perform app.set_audit(p_action, p_comment);
  update public.submissions
     set faculty_data = v_fdata,
         status = v_status,
         reviewed_by = v_uid,
         reviewed_by_name = app.actor_name(),
         reviewed_by_designation = app.actor_designation(),
         reviewed_at = now(),
         return_reason = case when p_action = 'RETURN' then p_comment else null end,
         version = version + 1,
         updated_by = v_uid
   where id = p_id
   returning * into v_row;
  perform app.set_audit(null);

  perform app.record_feedback(v_row.student_id, 'submission', v_row.id, p_action,
    coalesce(p_comment, v_fdata ->> 'faculty_feedback', v_fdata ->> 'guide_feedback', v_fdata ->> 'feedback', v_fdata ->> 'faculty_comments'));

  if p_action = 'VERIFY' then
    perform app.notify(v_row.student_id, v_tpl.title || ' verified',
                       app.actor_name() || ' verified your ' || v_tpl.title || '.', 'VERIFIED', 'submission', v_row.id, v_row.student_id);
    if v_status = 'HOD_REVIEW_PENDING' then
      perform app.notify_hods('Monthly assessment awaiting HOD review',
                              'Month ' || v_row.period_no || ' assessment has been reviewed by faculty.', 'REVIEW_REQUEST', v_row.student_id);
    end if;
  else
    perform app.notify(v_row.student_id, v_tpl.title || ' returned for correction',
                       app.actor_name() || ': ' || p_comment, 'RETURNED', 'submission', v_row.id, v_row.student_id);
  end if;
  return v_row;
end $$;

create or replace function public.hod_review_submission(p_id uuid, p_hod_data jsonb, p_expected_version int default null)
returns public.submissions
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_row public.submissions;
  v_tpl public.form_templates;
  v_hdata jsonb;
  v_errs text[];
begin
  perform app.require_role('FACULTY', 'HOD');
  select * into v_row from public.submissions where id = p_id and deleted_at is null for update;
  if v_row.id is null then
    perform app.fail('PT404', 'Record not found');
  end if;
  select * into v_tpl from public.form_templates where code = v_row.template_code;
  if v_tpl.category <> 'MONTHLY' then
    perform app.fail('PT400', 'HOD/mentor review applies to monthly assessments only');
  end if;
  if not (app.is_hod() or app.is_guide(v_row.student_id)) then
    perform app.fail('PT403', 'Only the HOD or the student''s mentor can complete this review');
  end if;
  if v_row.status not in ('HOD_REVIEW_PENDING', 'FACULTY_REVIEWED') then
    perform app.fail('PT409', format('Record is not awaiting HOD/mentor review (%s)', v_row.status));
  end if;
  if p_expected_version is not null and p_expected_version <> v_row.version then
    perform app.fail('PT409', format('CONFLICT: record changed on the server (server version %s)', v_row.version));
  end if;
  perform app.assert_owned_keys(v_row.template_code, 'HOD', coalesce(p_hod_data, '{}'::jsonb));
  v_hdata := v_row.hod_data || coalesce(p_hod_data, '{}'::jsonb);
  v_errs := app.validate_fields(v_row.template_code, 'HOD', v_hdata);
  if array_length(v_errs, 1) > 0 then
    perform app.fail('PT422', array_to_string(v_errs, '; '));
  end if;

  perform app.set_audit('HOD_REVIEW');
  update public.submissions
     set hod_data = v_hdata,
         status = 'COMPLETED',
         hod_reviewed_by = v_uid,
         hod_reviewed_by_name = app.actor_name(),
         hod_reviewed_at = now(),
         version = version + 1,
         updated_by = v_uid
   where id = p_id
   returning * into v_row;
  perform app.set_audit(null);
  perform app.record_feedback(v_row.student_id, 'submission', v_row.id, 'HOD_REVIEW', v_hdata ->> 'hod_review');
  perform app.notify(v_row.student_id, 'Monthly assessment completed',
                     'Month ' || v_row.period_no || ' assessment was completed by ' || app.actor_name() || '.',
                     'VERIFIED', 'submission', v_row.id, v_row.student_id);
  return v_row;
end $$;

-- Return an approved/verified record to the student for correction (audited).
create or replace function public.reopen_submission(p_id uuid, p_reason text)
returns public.submissions
language plpgsql security definer set search_path = public as $$
declare
  v_row public.submissions;
  v_tpl public.form_templates;
begin
  perform app.require_role('FACULTY', 'HOD');
  if nullif(btrim(coalesce(p_reason, '')), '') is null then
    perform app.fail('PT422', 'A reason is required');
  end if;
  select * into v_row from public.submissions where id = p_id and deleted_at is null for update;
  if v_row.id is null then
    perform app.fail('PT404', 'Record not found');
  end if;
  select * into v_tpl from public.form_templates where code = v_row.template_code;
  if v_tpl.category = 'ANNUAL' then
    perform app.fail('PT400', 'Annual reviews are corrected through save_annual_review with a reason');
  end if;
  if not app.can_review_student(v_row.student_id) then
    perform app.fail('PT403', 'You are not assigned to this student');
  end if;
  if v_row.status not in ('VERIFIED', 'COMPLETED', 'HOD_REVIEW_PENDING', 'FACULTY_REVIEWED') then
    perform app.fail('PT409', 'Only reviewed records can be reopened');
  end if;
  if not app.is_hod() and v_row.reviewed_by is distinct from auth.uid() then
    perform app.fail('PT403', 'Only the reviewing faculty member or the HOD can reopen this record');
  end if;
  perform app.set_audit('REOPEN', p_reason);
  update public.submissions
     set status = 'RETURNED', return_reason = p_reason, version = version + 1, updated_by = auth.uid()
   where id = p_id returning * into v_row;
  perform app.set_audit(null);
  perform app.record_feedback(v_row.student_id, 'submission', v_row.id, 'REOPEN', p_reason);
  perform app.notify(v_row.student_id, v_tpl.title || ' returned for correction', p_reason, 'RETURNED', 'submission', v_row.id, v_row.student_id);
  return v_row;
end $$;

-- Auditable correction of a reviewer's own (or, for HOD, any) completed assessment.
create or replace function public.correct_review(p_id uuid, p_faculty_data jsonb, p_reason text)
returns public.submissions
language plpgsql security definer set search_path = public as $$
declare
  v_row public.submissions;
  v_fdata jsonb;
  v_errs text[];
begin
  perform app.require_role('FACULTY', 'HOD');
  if nullif(btrim(coalesce(p_reason, '')), '') is null then
    perform app.fail('PT422', 'A correction reason is required');
  end if;
  select * into v_row from public.submissions where id = p_id and deleted_at is null for update;
  if v_row.id is null then
    perform app.fail('PT404', 'Record not found');
  end if;
  if v_row.template_code = 'ANNUAL_REVIEW' then
    perform app.fail('PT400', 'Use save_annual_review with a reason');
  end if;
  if not app.can_review_student(v_row.student_id) then
    perform app.fail('PT403', 'You are not assigned to this student');
  end if;
  if v_row.status not in ('VERIFIED', 'COMPLETED', 'HOD_REVIEW_PENDING', 'FACULTY_REVIEWED') then
    perform app.fail('PT409', 'Only reviewed records can be corrected');
  end if;
  if not app.is_hod() and v_row.reviewed_by is distinct from auth.uid() then
    perform app.fail('PT403', 'Another faculty member''s approved assessment can only be corrected by the HOD');
  end if;
  perform app.assert_owned_keys(v_row.template_code, 'FACULTY', coalesce(p_faculty_data, '{}'::jsonb));
  v_fdata := v_row.faculty_data || coalesce(p_faculty_data, '{}'::jsonb);
  v_errs := app.validate_fields(v_row.template_code, 'FACULTY', v_fdata);
  if array_length(v_errs, 1) > 0 then
    perform app.fail('PT422', array_to_string(v_errs, '; '));
  end if;
  perform app.set_audit('CORRECTION', p_reason);
  update public.submissions set faculty_data = v_fdata, version = version + 1, updated_by = auth.uid()
   where id = p_id returning * into v_row;
  perform app.set_audit(null);
  perform app.record_feedback(v_row.student_id, 'submission', v_row.id, 'CORRECTION', p_reason);
  return v_row;
end $$;

-- ---------------------------------------------------------------- annual review (faculty only)
create or replace function public.save_annual_review(
  p_student uuid,
  p_year int,
  p_data jsonb,
  p_submit boolean default false,
  p_reason text default null,
  p_expected_version int default null)
returns public.submissions
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_tpl public.form_templates;
  v_row public.submissions;
  v_fd jsonb;
  d jsonb;
  v_dkey text;
  v_changed boolean;
  v_owner text;
  v_errs text[];
  v_action text;
  v_correction boolean;
begin
  perform app.require_role('FACULTY', 'HOD');
  if not app.can_review_student(p_student) then
    perform app.fail('PT403', 'You are not assigned to this student');
  end if;
  select * into v_tpl from public.form_templates where code = 'ANNUAL_REVIEW';
  if p_year is null or p_year < 1 or p_year > coalesce(v_tpl.period_count, 3) then
    perform app.fail('PT422', 'PG year must be 1, 2 or 3');
  end if;
  perform app.assert_owned_keys('ANNUAL_REVIEW', 'FACULTY', coalesce(p_data, '{}'::jsonb));

  select * into v_row from public.submissions
   where student_id = p_student and template_code = 'ANNUAL_REVIEW' and period_no = p_year for update;
  if v_row.id is null then
    insert into public.submissions (student_id, template_code, template_version, period_no, status, created_by, updated_by)
    values (p_student, 'ANNUAL_REVIEW', v_tpl.version, p_year, 'NOT_STARTED', v_uid, v_uid)
    returning * into v_row;
  end if;
  if p_expected_version is not null and p_expected_version <> v_row.version then
    perform app.fail('PT409', format('CONFLICT: record changed on the server (server version %s)', v_row.version));
  end if;

  v_correction := v_row.status = 'COMPLETED';
  if v_correction then
    if nullif(btrim(coalesce(p_reason, '')), '') is null then
      perform app.fail('PT409', 'Completed annual reviews can only be changed through a correction with a reason');
    end if;
    if not app.is_hod() and v_row.reviewed_by is distinct from v_uid then
      perform app.fail('PT403', 'Only the submitting faculty member or the HOD can correct this review');
    end if;
  end if;

  v_fd := v_row.faculty_data;
  for d in select * from jsonb_array_elements(v_tpl.schema -> 'domains') loop
    v_dkey := d ->> 'key';
    v_changed := (p_data ? (v_dkey || '_rating') and (p_data ->> (v_dkey || '_rating')) is distinct from (v_fd ->> (v_dkey || '_rating')))
              or (p_data ? (v_dkey || '_comments') and (p_data ->> (v_dkey || '_comments')) is distinct from (v_fd ->> (v_dkey || '_comments')));
    continue when not v_changed;
    v_owner := v_fd ->> (v_dkey || '_faculty_id');
    if v_owner is not null and v_owner <> v_uid::text and not app.is_hod() and p_reason is null then
      perform app.fail('PT403', format('%s was assessed by another faculty member; submit a correction with a reason', d ->> 'label'));
    end if;
    v_fd := v_fd
      || jsonb_strip_nulls(jsonb_build_object(
           v_dkey || '_rating', p_data -> (v_dkey || '_rating'),
           v_dkey || '_comments', p_data -> (v_dkey || '_comments')))
      || jsonb_build_object(
           v_dkey || '_faculty_id', v_uid,
           v_dkey || '_faculty_name', app.actor_name(),
           v_dkey || '_updated_at', now());
  end loop;
  if p_data ? 'narrative_feedback' then
    v_fd := v_fd || jsonb_build_object('narrative_feedback', p_data -> 'narrative_feedback');
  end if;

  if p_submit or v_correction then
    v_errs := app.validate_fields('ANNUAL_REVIEW', 'FACULTY', v_fd);
    if array_length(v_errs, 1) > 0 then
      perform app.fail('PT422', array_to_string(v_errs, '; '));
    end if;
  end if;

  v_action := case when v_correction then 'CORRECTION' when p_submit then 'ANNUAL_SUBMIT' else 'ANNUAL_SAVE' end;
  perform app.set_audit(v_action, p_reason);
  update public.submissions
     set faculty_data = v_fd,
         status = case when p_submit or v_correction then 'COMPLETED' else 'DRAFT' end,
         reviewed_by = case when p_submit and not v_correction then v_uid else reviewed_by end,
         reviewed_by_name = case when p_submit and not v_correction then app.actor_name() else reviewed_by_name end,
         reviewed_by_designation = case when p_submit and not v_correction then app.actor_designation() else reviewed_by_designation end,
         reviewed_at = case when p_submit and not v_correction then now() else reviewed_at end,
         version = version + 1,
         updated_by = v_uid
   where id = v_row.id
   returning * into v_row;
  perform app.set_audit(null);

  if p_submit or v_correction then
    perform app.record_feedback(p_student, 'annual_review', v_row.id, v_action, coalesce(p_reason, v_fd ->> 'narrative_feedback'));
  end if;
  if p_submit and not v_correction then
    perform app.notify(p_student, 'PGY-' || p_year || ' annual review completed',
                       'Your PGY-' || p_year || ' annual comprehensive review is now available.', 'ANNUAL', 'submission', v_row.id, p_student);
  end if;
  return v_row;
end $$;

-- ---------------------------------------------------------------- certification
create or replace function app.certification_state(p_student uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  e public.programme_enrolments;
  s public.institution_settings;
  v_approved int;
  v_total int;
  v_completion date;
  v_complete boolean;
  v_cert public.certificates;
  v_status text;
  v_reasons text[] := '{}';
begin
  select * into e from public.programme_enrolments where student_id = p_student;
  select * into s from public.institution_settings where id = 1;
  select count(*) filter (where cs.status = 'APPROVED') into v_approved
    from public.competency_signoffs cs join public.competencies c on c.code = cs.competency_code and c.active
   where cs.student_id = p_student;
  select count(*) into v_total from public.competencies where active;
  select * into v_cert from public.certificates where student_id = p_student and status = 'ISSUED';
  v_completion := case when e.id is not null then app.completion_date(e.programme_start_date, e.duration_months) end;
  v_complete := v_completion is not null and current_date >= v_completion;

  if v_approved < v_total then
    v_reasons := v_reasons || format('%s of %s final competency sign-offs pending', v_total - v_approved, v_total);
  end if;
  if s.require_programme_completion and not v_complete then
    v_reasons := v_reasons || format('Programme completes on %s', coalesce(v_completion::text, 'an unconfigured date'));
  end if;

  v_status := case
    when v_cert.id is not null then 'CERTIFICATE_ISSUED'
    when s.require_programme_completion and not v_complete then 'NOT_ELIGIBLE'
    when v_approved < v_total or v_total = 0 then 'AWAITING_FINAL_SIGNOFF'
    else 'READY_FOR_CERTIFICATION' end;

  return jsonb_build_object(
    'status', v_status,
    'approved', v_approved,
    'total', v_total,
    'programme_complete', v_complete,
    'completion_date', v_completion,
    'reasons', to_jsonb(v_reasons),
    'certificate_id', v_cert.id,
    'certificate_no', v_cert.certificate_no);
end $$;

create or replace function app.issue_certificate_internal(p_student uuid, p_issuer uuid)
returns public.certificates language plpgsql security definer set search_path = public as $$
declare
  s public.institution_settings;
  p public.profiles;
  sp public.student_profiles;
  e public.programme_enrolments;
  b public.academic_batches;
  v_guide text;
  v_guide_verified boolean;
  v_unit_head text;
  v_hod text;
  v_issuer_name text;
  v_cert public.certificates;
  v_no text;
begin
  select * into s from public.institution_settings where id = 1;
  select * into p from public.profiles where id = p_student;
  select * into sp from public.student_profiles where user_id = p_student;
  select * into e from public.programme_enrolments where student_id = p_student;
  select * into b from public.academic_batches where id = sp.batch_id;
  select full_name into v_guide from public.profiles where id = sp.guide_id;
  v_guide_verified := exists (select 1 from public.competency_signoffs
                               where student_id = p_student and final_by = sp.guide_id and status = 'APPROVED');
  select pr.full_name into v_unit_head
    from public.faculty_assignments a
    join public.faculty_profiles fp on fp.user_id = a.faculty_id and fp.is_unit_head
    join public.profiles pr on pr.id = a.faculty_id
   where a.student_id = p_student and a.active
   order by a.assigned_at limit 1;
  select full_name into v_issuer_name from public.profiles where id = p_issuer;
  select full_name into v_hod from public.profiles
   where role = 'HOD' and active and (p_issuer is null or id = p_issuer)
   order by created_at limit 1;

  v_no := s.certificate_prefix || '/' || to_char(current_date, 'YYYY') || '/' || lpad(nextval('public.certificate_no_seq')::text, 5, '0');

  perform app.set_audit('CERTIFICATE_ISSUE');
  insert into public.certificates (student_id, certificate_no, issued_by, issued_by_name, snapshot)
  values (p_student, v_no, p_issuer, coalesce(v_issuer_name, 'System (prerequisites verified)'),
    jsonb_build_object(
      'institution_name', s.institution_name,
      'department_name', s.department_name,
      'university_name', coalesce(sp.university, s.university_name),
      'student_name', p.full_name,
      'university_reg_no', sp.university_reg_no,
      'course', 'MD Pediatrics',
      'batch', case when b.id is not null then b.name || ' (' || b.academic_year || ')' end,
      'programme_start_date', e.programme_start_date,
      'programme_completion_date', app.completion_date(e.programme_start_date, e.duration_months),
      'issue_date', current_date,
      'certificate_no', v_no,
      'competencies_approved', (select count(*) from public.competency_signoffs where student_id = p_student and status = 'APPROVED'),
      'guide_name', v_guide,
      'statement', 'Certified that the resident has maintained this logbook during postgraduate training and has undergone periodic assessment. Final certification is subject to fulfillment of the current NMC/university requirements and institutional assessment policy.',
      'disclaimer', 'This certificate records institutional logbook completion only and is not a degree or university qualification.',
      'signatories', jsonb_build_array(
        jsonb_build_object('role', 'Guide / Mentor', 'name', v_guide,
          'verification', case when v_guide_verified then 'Competency sign-offs approved electronically' end),
        jsonb_build_object('role', 'Unit Head', 'name', v_unit_head, 'verification', null),
        jsonb_build_object('role', 'Head of Department', 'name', v_hod,
          'verification', case when p_issuer is not null then 'Issued electronically on ' || to_char(current_date, 'DD Mon YYYY') end),
        jsonb_build_object('role', s.dean_title, 'name', s.dean_name, 'verification', null))))
  returning * into v_cert;
  perform app.set_audit(null);

  perform app.notify(p_student, 'Certificate available',
                     'Your MD Pediatrics logbook completion certificate ' || v_no || ' has been issued.', 'CERTIFICATE',
                     'certificate', v_cert.id, p_student);
  perform app.notify_hods('Certificate issued', p.full_name || ' – ' || v_no, 'CERTIFICATE', p_student);
  return v_cert;
end $$;

create or replace function app.evaluate_certification(p_student uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_state jsonb := app.certification_state(p_student);
  s public.institution_settings;
begin
  select * into s from public.institution_settings where id = 1;
  if v_state ->> 'status' = 'READY_FOR_CERTIFICATION' and s.auto_issue_certificate then
    perform app.issue_certificate_internal(p_student, null);
    v_state := app.certification_state(p_student);
  end if;
  return v_state;
end $$;

create or replace function public.certification_status(p_student uuid default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_student uuid := coalesce(p_student, auth.uid());
begin
  if not app.can_view_student(v_student) then
    perform app.fail('PT403', 'Not permitted');
  end if;
  return app.evaluate_certification(v_student);
end $$;

create or replace function public.issue_certificate(p_student uuid)
returns public.certificates language plpgsql security definer set search_path = public as $$
declare
  v_state jsonb;
begin
  perform app.require_role('HOD');
  v_state := app.certification_state(p_student);
  if v_state ->> 'status' = 'CERTIFICATE_ISSUED' then
    perform app.fail('PT409', 'A certificate has already been issued');
  end if;
  if v_state ->> 'status' <> 'READY_FOR_CERTIFICATION' then
    perform app.fail('PT409', 'Certification prerequisites not met: ' ||
      (select string_agg(r, '; ') from jsonb_array_elements_text(v_state -> 'reasons') r));
  end if;
  return app.issue_certificate_internal(p_student, auth.uid());
end $$;

create or replace function public.revoke_certificate(p_student uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform app.require_role('HOD');
  if nullif(btrim(coalesce(p_reason, '')), '') is null then
    perform app.fail('PT422', 'A reason is required');
  end if;
  perform app.set_audit('CERTIFICATE_REVOKE', p_reason);
  update public.certificates set status = 'REVOKED', revoked_at = now(), revoked_reason = p_reason
   where student_id = p_student and status = 'ISSUED';
  perform app.set_audit(null);
end $$;

-- ---------------------------------------------------------------- competency sign-off
create or replace function public.request_signoff(p_code text, p_note text default null)
returns public.competency_signoffs
language plpgsql security definer set search_path = public as $$
declare
  v_row public.competency_signoffs;
begin
  perform app.require_role('STUDENT');
  select * into v_row from public.competency_signoffs
   where student_id = auth.uid() and competency_code = p_code for update;
  if v_row.id is null then
    perform app.fail('PT404', 'Competency not found');
  end if;
  if v_row.status = 'APPROVED' then
    perform app.fail('PT409', 'Competency already approved');
  end if;
  if v_row.status = 'AWAITING_APPROVAL' then
    return v_row;
  end if;
  perform app.set_audit('SIGNOFF_REQUEST');
  update public.competency_signoffs
     set status = 'AWAITING_APPROVAL', student_note = p_note, version = version + 1
   where id = v_row.id returning * into v_row;
  perform app.set_audit(null);
  perform app.notify_reviewers(auth.uid(), 'Competency awaiting sign-off',
                               app.actor_name() || ' requested final sign-off for ' || p_code || '.', 'SIGNOFF', 'competency_signoff', v_row.id);
  return v_row;
end $$;

create or replace function public.faculty_signoff(
  p_student uuid, p_code text, p_action text, p_comment text default null, p_expected_version int default null)
returns public.competency_signoffs
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_name text := app.actor_name();
  v_row public.competency_signoffs;
begin
  perform app.require_role('FACULTY', 'HOD');
  if not app.can_review_student(p_student) then
    perform app.fail('PT403', 'You are not assigned to this student');
  end if;
  select * into v_row from public.competency_signoffs
   where student_id = p_student and competency_code = p_code for update;
  if v_row.id is null then
    perform app.fail('PT404', 'Competency not found');
  end if;
  if p_expected_version is not null and p_expected_version <> v_row.version then
    perform app.fail('PT409', format('CONFLICT: sign-off changed on the server (server version %s)', v_row.version));
  end if;
  if v_row.status = 'APPROVED' and p_action <> 'REVOKE' then
    perform app.fail('PT409', 'Competency already approved; the HOD must revoke it with a reason before changes');
  end if;

  perform app.set_audit('SIGNOFF_' || p_action, p_comment);
  case p_action
    when 'OBSERVED' then
      update public.competency_signoffs set observed_at = now(), observed_by = v_uid, observed_by_name = v_name,
             status = case when status in ('NOT_STARTED', 'RETURNED') then 'IN_PROGRESS' else status end, version = version + 1
       where id = v_row.id returning * into v_row;
    when 'SUPERVISED' then
      update public.competency_signoffs set supervised_at = now(), supervised_by = v_uid, supervised_by_name = v_name,
             status = case when status in ('NOT_STARTED', 'RETURNED') then 'IN_PROGRESS' else status end, version = version + 1
       where id = v_row.id returning * into v_row;
    when 'INDEPENDENT' then
      update public.competency_signoffs set independent_at = now(), independent_by = v_uid, independent_by_name = v_name,
             status = case when status in ('NOT_STARTED', 'RETURNED') then 'IN_PROGRESS' else status end, version = version + 1
       where id = v_row.id returning * into v_row;
    when 'APPROVE' then
      if v_row.independent_at is null then
        perform app.fail('PT422', 'Record the independent performance stage before final sign-off');
      end if;
      update public.competency_signoffs
         set status = 'APPROVED', final_by = v_uid, final_by_name = v_name, final_by_designation = app.actor_designation(),
             final_at = now(), comment = p_comment, version = version + 1
       where id = v_row.id returning * into v_row;
    when 'RETURN' then
      if nullif(btrim(coalesce(p_comment, '')), '') is null then
        perform app.fail('PT422', 'A comment is required when returning for further training');
      end if;
      update public.competency_signoffs set status = 'RETURNED', comment = p_comment, version = version + 1
       where id = v_row.id returning * into v_row;
    when 'REVOKE' then
      if not app.is_hod() then
        perform app.fail('PT403', 'Only the HOD can revoke an approved sign-off');
      end if;
      if nullif(btrim(coalesce(p_comment, '')), '') is null then
        perform app.fail('PT422', 'A reason is required');
      end if;
      if exists (select 1 from public.certificates where student_id = p_student and status = 'ISSUED') then
        perform app.fail('PT409', 'Revoke the issued certificate first');
      end if;
      update public.competency_signoffs
         set status = 'RETURNED', comment = p_comment, final_by = null, final_by_name = null,
             final_by_designation = null, final_at = null, version = version + 1
       where id = v_row.id returning * into v_row;
    else
      perform app.fail('PT400', 'Unknown sign-off action');
  end case;
  perform app.set_audit(null);

  perform app.record_feedback(p_student, 'competency_signoff', v_row.id, 'SIGNOFF_' || p_action, p_comment);
  if p_action = 'APPROVE' then
    perform app.notify(p_student, 'Competency approved', p_code || ' final sign-off approved by ' || v_name || '.',
                       'SIGNOFF', 'competency_signoff', v_row.id, p_student);
    perform app.evaluate_certification(p_student);
  elsif p_action = 'RETURN' then
    perform app.notify(p_student, 'Competency returned for further training', p_code || ': ' || p_comment,
                       'RETURNED', 'competency_signoff', v_row.id, p_student);
  end if;
  return v_row;
end $$;

-- ---------------------------------------------------------------- HOD administration
create or replace function public.create_invitation(
  p_email text, p_full_name text, p_role text, p_designation text default null,
  p_reg_no text default null, p_batch uuid default null, p_start date default null, p_guide uuid default null)
returns public.invitations
language plpgsql security definer set search_path = public as $$
declare
  v_inv public.invitations;
begin
  perform app.require_role('HOD');
  if p_role not in ('STUDENT', 'FACULTY', 'HOD') then
    perform app.fail('PT422', 'Invalid role');
  end if;
  if p_email is null or p_email !~* '^[^@\s]+@[^@\s]+\.[^@\s]+$' then
    perform app.fail('PT422', 'A valid email address is required');
  end if;
  if nullif(btrim(coalesce(p_full_name, '')), '') is null then
    perform app.fail('PT422', 'Full name is required');
  end if;
  if p_role = 'STUDENT' and p_start is null then
    perform app.fail('PT422', 'Programme start date is required for students');
  end if;
  if p_guide is not null and not exists (select 1 from public.profiles where id = p_guide and role in ('FACULTY', 'HOD') and active) then
    perform app.fail('PT422', 'Guide must be an active faculty member');
  end if;
  if exists (select 1 from public.profiles where lower(email) = lower(p_email)) then
    perform app.fail('PT409', 'An account with this email already exists');
  end if;
  if exists (select 1 from public.invitations where lower(email) = lower(p_email) and status = 'PENDING') then
    perform app.fail('PT409', 'A pending invitation already exists for this email');
  end if;
  perform app.set_audit('INVITE');
  insert into public.invitations (email, full_name, role, designation, university_reg_no, batch_id, programme_start_date, guide_id, invited_by)
  values (lower(btrim(p_email)), btrim(p_full_name), p_role, p_designation, p_reg_no, p_batch, p_start, p_guide, auth.uid())
  returning * into v_inv;
  perform app.set_audit(null);
  return v_inv;
end $$;

create or replace function public.revoke_invitation(p_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform app.require_role('HOD');
  perform app.set_audit('INVITE_REVOKE');
  update public.invitations set status = 'REVOKED' where id = p_id and status = 'PENDING';
  perform app.set_audit(null);
end $$;

create or replace function public.assign_faculty(p_student uuid, p_faculty uuid, p_role text default 'UNIT_FACULTY')
returns public.faculty_assignments
language plpgsql security definer set search_path = public as $$
declare
  v_row public.faculty_assignments;
begin
  perform app.require_role('HOD');
  if p_role not in ('GUIDE', 'UNIT_FACULTY', 'MENTOR') then
    perform app.fail('PT422', 'Invalid assignment role');
  end if;
  if not exists (select 1 from public.student_profiles where user_id = p_student) then
    perform app.fail('PT404', 'Student not found');
  end if;
  if not exists (select 1 from public.profiles where id = p_faculty and role in ('FACULTY', 'HOD') and active) then
    perform app.fail('PT422', 'Faculty member not found or inactive');
  end if;
  perform app.set_audit('ASSIGN_FACULTY');
  select * into v_row from public.faculty_assignments where student_id = p_student and faculty_id = p_faculty and active for update;
  if v_row.id is null then
    insert into public.faculty_assignments (student_id, faculty_id, assignment_role, assigned_by)
    values (p_student, p_faculty, p_role, auth.uid()) returning * into v_row;
  else
    update public.faculty_assignments set assignment_role = p_role where id = v_row.id returning * into v_row;
  end if;
  if p_role = 'GUIDE' then
    update public.student_profiles set guide_id = p_faculty where user_id = p_student;
  end if;
  perform app.set_audit(null);
  perform app.notify(p_faculty, 'New student assigned',
                     (select full_name from public.profiles where id = p_student) || ' has been assigned to you.', 'ASSIGNMENT',
                     null, null, p_student);
  return v_row;
end $$;

create or replace function public.unassign_faculty(p_student uuid, p_faculty uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform app.require_role('HOD');
  perform app.set_audit('UNASSIGN_FACULTY');
  update public.faculty_assignments set active = false, unassigned_at = now()
   where student_id = p_student and faculty_id = p_faculty and active;
  update public.student_profiles set guide_id = null where user_id = p_student and guide_id = p_faculty;
  perform app.set_audit(null);
end $$;

create or replace function public.update_student_details(p_student uuid, p_data jsonb)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_key text;
begin
  perform app.require_role('HOD');
  for v_key in select jsonb_object_keys(p_data) loop
    if v_key not in ('full_name', 'university_reg_no', 'institution', 'university', 'batch_id', 'current_posting') then
      perform app.fail('PT422', format('Field "%s" cannot be updated here', v_key));
    end if;
  end loop;
  perform app.set_audit('UPDATE_STUDENT');
  if p_data ? 'full_name' then
    update public.profiles set full_name = p_data ->> 'full_name' where id = p_student and role = 'STUDENT';
  end if;
  update public.student_profiles set
    university_reg_no = case when p_data ? 'university_reg_no' then nullif(p_data ->> 'university_reg_no', '') else university_reg_no end,
    institution = case when p_data ? 'institution' then p_data ->> 'institution' else institution end,
    university = case when p_data ? 'university' then p_data ->> 'university' else university end,
    batch_id = case when p_data ? 'batch_id' then nullif(p_data ->> 'batch_id', '')::uuid else batch_id end,
    current_posting = case when p_data ? 'current_posting' then p_data ->> 'current_posting' else current_posting end
  where user_id = p_student;
  perform app.set_audit(null);
end $$;

-- The programme start date is authoritative for the 36-month timeline; HOD-only and audited.
create or replace function public.set_programme_start(p_student uuid, p_start date, p_duration int default null, p_reason text default null)
returns public.programme_enrolments
language plpgsql security definer set search_path = public as $$
declare
  v_row public.programme_enrolments;
begin
  perform app.require_role('HOD');
  if p_start is null then
    perform app.fail('PT422', 'Start date is required');
  end if;
  if nullif(btrim(coalesce(p_reason, '')), '') is null then
    perform app.fail('PT422', 'A reason is required for changing the programme start date');
  end if;
  perform app.set_audit('PROGRAMME_START_CHANGE', p_reason);
  insert into public.programme_enrolments (student_id, programme_start_date, duration_months)
  values (p_student, p_start, coalesce(p_duration, (select programme_duration_months from public.institution_settings where id = 1)))
  on conflict (student_id) do update
     set programme_start_date = excluded.programme_start_date,
         duration_months = coalesce(p_duration, public.programme_enrolments.duration_months)
  returning * into v_row;
  perform app.set_audit(null);
  perform app.notify(p_student, 'Programme start date updated',
                     'Your official programme start date is now ' || to_char(p_start, 'DD Mon YYYY') || '.', 'PROGRAMME',
                     null, null, p_student);
  return v_row;
end $$;

create or replace function public.upsert_batch(p_id uuid, p_name text, p_academic_year text, p_intake date default null)
returns public.academic_batches
language plpgsql security definer set search_path = public as $$
declare
  v_row public.academic_batches;
begin
  perform app.require_role('HOD');
  if nullif(btrim(coalesce(p_name, '')), '') is null or nullif(btrim(coalesce(p_academic_year, '')), '') is null then
    perform app.fail('PT422', 'Batch name and academic year are required');
  end if;
  if p_id is null then
    insert into public.academic_batches (name, academic_year, intake_date, created_by)
    values (btrim(p_name), btrim(p_academic_year), p_intake, auth.uid()) returning * into v_row;
  else
    update public.academic_batches set name = btrim(p_name), academic_year = btrim(p_academic_year), intake_date = p_intake
     where id = p_id returning * into v_row;
  end if;
  return v_row;
end $$;

create or replace function public.update_institution_settings(p_data jsonb)
returns public.institution_settings
language plpgsql security definer set search_path = public as $$
declare
  v_row public.institution_settings;
  v_key text;
begin
  perform app.require_role('HOD');
  for v_key in select jsonb_object_keys(p_data) loop
    if v_key not in ('institution_name', 'department_name', 'university_name', 'dean_name', 'dean_title',
                     'programme_duration_months', 'allow_future_months', 'require_programme_completion',
                     'monthly_hod_review_required', 'auto_issue_certificate', 'certificate_prefix') then
      perform app.fail('PT422', format('Unknown setting "%s"', v_key));
    end if;
  end loop;
  perform app.set_audit('UPDATE_SETTINGS');
  update public.institution_settings set
    institution_name = coalesce(p_data ->> 'institution_name', institution_name),
    department_name = coalesce(p_data ->> 'department_name', department_name),
    university_name = coalesce(p_data ->> 'university_name', university_name),
    dean_name = case when p_data ? 'dean_name' then p_data ->> 'dean_name' else dean_name end,
    dean_title = coalesce(p_data ->> 'dean_title', dean_title),
    programme_duration_months = coalesce((p_data ->> 'programme_duration_months')::int, programme_duration_months),
    allow_future_months = coalesce((p_data ->> 'allow_future_months')::boolean, allow_future_months),
    require_programme_completion = coalesce((p_data ->> 'require_programme_completion')::boolean, require_programme_completion),
    monthly_hod_review_required = coalesce((p_data ->> 'monthly_hod_review_required')::boolean, monthly_hod_review_required),
    auto_issue_certificate = coalesce((p_data ->> 'auto_issue_certificate')::boolean, auto_issue_certificate),
    certificate_prefix = coalesce(p_data ->> 'certificate_prefix', certificate_prefix),
    updated_at = now(),
    updated_by = auth.uid()
  where id = 1 returning * into v_row;
  perform app.set_audit(null);
  return v_row;
end $$;

-- Targets are versioned: a change creates a new current version.
create or replace function public.set_target(
  p_key text, p_value int, p_label text default null, p_category text default null, p_notes text default null)
returns public.targets
language plpgsql security definer set search_path = public as $$
declare
  v_cur public.targets;
  v_row public.targets;
begin
  perform app.require_role('HOD');
  if p_value is null or p_value <= 0 then
    perform app.fail('PT422', 'Target value must be a positive number');
  end if;
  select * into v_cur from public.targets where key = p_key and is_current for update;
  if v_cur.id is null and (p_label is null or p_category is null) then
    perform app.fail('PT422', 'Label and category are required for a new target');
  end if;
  perform app.set_audit('TARGET_CHANGE', p_notes);
  if v_cur.id is not null then
    update public.targets set is_current = false where id = v_cur.id;
  end if;
  insert into public.targets (key, category, label, target_value, sort_order, version, is_current, notes, created_by)
  values (p_key, coalesce(p_category, v_cur.category), coalesce(p_label, v_cur.label), p_value,
          coalesce(v_cur.sort_order, 999), coalesce(v_cur.version, 0) + 1, true, p_notes, auth.uid())
  returning * into v_row;
  perform app.set_audit(null);
  return v_row;
end $$;

create or replace function public.update_competency(p_code text, p_title text, p_domain text, p_progression text default null)
returns public.competencies
language plpgsql security definer set search_path = public as $$
declare
  v_row public.competencies;
begin
  perform app.require_role('HOD');
  perform app.set_audit('COMPETENCY_UPDATE');
  update public.competencies set title = coalesce(p_title, title), domain = coalesce(p_domain, domain),
         progression = coalesce(p_progression, progression)
   where code = p_code returning * into v_row;
  perform app.set_audit(null);
  if v_row.code is null then
    perform app.fail('PT404', 'Competency not found');
  end if;
  return v_row;
end $$;

create or replace function public.publish_document_version(
  p_code text, p_content jsonb, p_storage_path text default null, p_file_name text default null,
  p_notes text default null, p_title text default null, p_description text default null)
returns public.document_versions
language plpgsql security definer set search_path = public as $$
declare
  v_doc public.academic_documents;
  v_row public.document_versions;
begin
  perform app.require_role('HOD');
  if p_content is null and p_storage_path is null then
    perform app.fail('PT422', 'Provide document content or an uploaded file');
  end if;
  select * into v_doc from public.academic_documents where code = p_code;
  if v_doc.id is null then
    if p_title is null then
      perform app.fail('PT422', 'Title is required for a new document');
    end if;
    insert into public.academic_documents (code, title, description, sort_order)
    values (p_code, p_title, p_description, (select coalesce(max(sort_order), 0) + 1 from public.academic_documents))
    returning * into v_doc;
  elsif p_title is not null or p_description is not null then
    update public.academic_documents set title = coalesce(p_title, title), description = coalesce(p_description, description)
     where id = v_doc.id;
  end if;
  perform app.set_audit('DOCUMENT_PUBLISH', p_notes);
  update public.document_versions set published = false where document_id = v_doc.id and published;
  insert into public.document_versions (document_id, version_no, content, storage_path, file_name, notes, published, published_at, published_by)
  values (v_doc.id,
          (select coalesce(max(version_no), 0) + 1 from public.document_versions where document_id = v_doc.id),
          coalesce(p_content, (select content from public.document_versions where document_id = v_doc.id order by version_no desc limit 1)),
          p_storage_path, p_file_name, p_notes, true, now(), auth.uid())
  returning * into v_row;
  perform app.set_audit(null);
  return v_row;
end $$;

create or replace function public.set_user_active(p_user uuid, p_active boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform app.require_role('HOD');
  if p_user = auth.uid() then
    perform app.fail('PT422', 'You cannot deactivate your own account');
  end if;
  perform app.set_audit(case when p_active then 'ACTIVATE_USER' else 'DEACTIVATE_USER' end);
  update public.profiles set active = p_active where id = p_user;
  perform app.set_audit(null);
end $$;

create or replace function public.update_faculty_profile(p_faculty uuid, p_designation text, p_unit text, p_is_unit_head boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform app.require_role('HOD');
  perform app.set_audit('UPDATE_FACULTY');
  update public.profiles set designation = p_designation where id = p_faculty and role in ('FACULTY', 'HOD');
  insert into public.faculty_profiles (user_id, unit, is_unit_head) values (p_faculty, p_unit, coalesce(p_is_unit_head, false))
  on conflict (user_id) do update set unit = excluded.unit, is_unit_head = excluded.is_unit_head, updated_at = now();
  perform app.set_audit(null);
end $$;

-- ---------------------------------------------------------------- self-service
create or replace function public.update_my_profile(p_data jsonb)
returns void language plpgsql security definer set search_path = public as $$
declare
  v_role text := app.user_role();
  v_key text;
begin
  perform app.require_role('STUDENT', 'FACULTY', 'HOD');
  for v_key in select jsonb_object_keys(p_data) loop
    if v_role = 'STUDENT' and v_key not in ('current_posting') then
      perform app.fail('PT403', format('Students cannot change "%s"; contact the HOD', v_key));
    end if;
    if v_role in ('FACULTY', 'HOD') and v_key not in ('designation', 'unit') then
      perform app.fail('PT403', format('Field "%s" cannot be changed here', v_key));
    end if;
  end loop;
  perform app.set_audit('SELF_UPDATE');
  if v_role = 'STUDENT' then
    update public.student_profiles set current_posting = p_data ->> 'current_posting' where user_id = auth.uid();
  else
    if p_data ? 'designation' then
      update public.profiles set designation = p_data ->> 'designation' where id = auth.uid();
    end if;
    if p_data ? 'unit' then
      insert into public.faculty_profiles (user_id, unit) values (auth.uid(), p_data ->> 'unit')
      on conflict (user_id) do update set unit = excluded.unit, updated_at = now();
    end if;
  end if;
  perform app.set_audit(null);
end $$;

create or replace function public.mark_notifications_read(p_ids uuid[] default null)
returns void language sql security definer set search_path = public as $$
  update public.notifications set read_at = now()
   where recipient_id = auth.uid() and read_at is null and (p_ids is null or id = any (p_ids));
$$;

-- ---------------------------------------------------------------- scheduled reminders (pg_cron)
create or replace function public.run_daily_jobs()
returns void language plpgsql security definer set search_path = public as $$
declare
  r record;
  v_month int;
  v_pending int;
begin
  for r in
    select e.student_id, e.programme_start_date, e.duration_months, p.full_name
      from public.programme_enrolments e join public.profiles p on p.id = e.student_id
     where e.status = 'ACTIVE' and p.active
  loop
    v_month := app.programme_month(r.programme_start_date, current_date);
    if v_month between 1 and r.duration_months then
      if exists (select 1 from public.submissions
                  where student_id = r.student_id and template_code = 'MONTHLY' and period_no = v_month
                    and status in ('NOT_STARTED', 'DRAFT', 'RETURNED'))
         and app.month_end(r.programme_start_date, v_month) - current_date <= 5 then
        perform app.notify(r.student_id, 'Monthly assessment due',
                           'Month ' || v_month || ' assessment closes on ' || to_char(app.month_end(r.programme_start_date, v_month), 'DD Mon YYYY') || '.',
                           'REMINDER', null, null, r.student_id, 'monthly-due-' || v_month);
      end if;
      if v_month > 12 and exists (
           select 1 from public.submissions s
            where s.student_id = r.student_id and s.template_code = 'ANNUAL_REVIEW'
              and s.period_no = least(3, (v_month - 1) / 12) and s.status <> 'COMPLETED') then
        perform app.notify_reviewers(r.student_id, 'Annual review due',
                                     'PGY-' || least(3, (v_month - 1) / 12) || ' annual review for ' || r.full_name || ' is pending.',
                                     'ANNUAL', null, null);
      end if;
    end if;
    if app.completion_date(r.programme_start_date, r.duration_months) - current_date between 0 and 60 then
      perform app.notify_hods('Student nearing programme completion',
                              r.full_name || ' completes the programme on ' ||
                              to_char(app.completion_date(r.programme_start_date, r.duration_months), 'DD Mon YYYY') || '.',
                              'PROGRAMME', r.student_id, 'nearing-' || r.student_id);
    end if;
    perform app.evaluate_certification(r.student_id);
  end loop;

  for r in
    select s.id, s.student_id, s.student_data ->> 'milestone' as milestone
      from public.submissions s
     where s.template_code = 'DISSERTATION_MILESTONE' and s.deleted_at is null
       and coalesce(s.student_data ->> 'completed', 'false') <> 'true'
       and nullif(s.student_data ->> 'planned_date', '') is not null
       and (s.student_data ->> 'planned_date')::date < current_date
  loop
    perform app.notify(r.student_id, 'Dissertation milestone pending',
                       coalesce(r.milestone, 'A milestone') || ' is past its planned date.', 'REMINDER',
                       'submission', r.id, r.student_id, 'milestone-' || r.id || '-' || to_char(current_date, 'IYYY-IW'));
  end loop;

  select count(*) into v_pending from public.submissions
   where template_code = 'MONTHLY' and status in ('SUBMITTED', 'FACULTY_REVIEW_PENDING', 'HOD_REVIEW_PENDING');
  if v_pending > 0 then
    perform app.notify_hods('Monthly assessments pending', v_pending || ' monthly assessments are awaiting review.',
                            'REMINDER', null, 'hod-monthly-' || to_char(current_date, 'IYYY-IW'));
  end if;
  select count(*) into v_pending from public.competency_signoffs where status = 'AWAITING_APPROVAL';
  if v_pending > 0 then
    perform app.notify_hods('Final competency approvals pending', v_pending || ' competency sign-offs are awaiting faculty approval.',
                            'REMINDER', null, 'hod-signoff-' || to_char(current_date, 'IYYY-IW'));
  end if;
end $$;

-- ---------------------------------------------------------------- grants
revoke execute on all functions in schema public from public, anon, authenticated;
revoke execute on function app.issue_certificate_internal(uuid, uuid) from public, authenticated;
revoke execute on function app.evaluate_certification(uuid) from public, authenticated;
revoke execute on function app.record_feedback(uuid, text, uuid, text, text) from public, authenticated;

grant execute on function
  public.save_submission(uuid, text, jsonb, int, int, int),
  public.submit_submission(uuid, int),
  public.delete_draft(uuid),
  public.review_submission(uuid, text, jsonb, text, int),
  public.hod_review_submission(uuid, jsonb, int),
  public.reopen_submission(uuid, text),
  public.correct_review(uuid, jsonb, text),
  public.save_annual_review(uuid, int, jsonb, boolean, text, int),
  public.certification_status(uuid),
  public.issue_certificate(uuid),
  public.revoke_certificate(uuid, text),
  public.request_signoff(text, text),
  public.faculty_signoff(uuid, text, text, text, int),
  public.create_invitation(text, text, text, text, text, uuid, date, uuid),
  public.revoke_invitation(uuid),
  public.assign_faculty(uuid, uuid, text),
  public.unassign_faculty(uuid, uuid),
  public.update_student_details(uuid, jsonb),
  public.set_programme_start(uuid, date, int, text),
  public.upsert_batch(uuid, text, text, date),
  public.update_institution_settings(jsonb),
  public.set_target(text, int, text, text, text),
  public.update_competency(text, text, text, text),
  public.publish_document_version(text, jsonb, text, text, text, text, text),
  public.set_user_active(uuid, boolean),
  public.update_faculty_profile(uuid, text, text, boolean),
  public.update_my_profile(jsonb),
  public.mark_notifications_read(uuid[])
to authenticated;
