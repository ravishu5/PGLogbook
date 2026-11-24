-- =====================================================================
-- Helpers: authorization predicates, timeline arithmetic, template
-- validation, audit trail, notifications and student provisioning.
-- =====================================================================

-- ---------------------------------------------------------------- errors
-- PostgREST maps SQLSTATE PTxyz to HTTP status xyz.
create or replace function app.fail(p_code text, p_message text)
returns void language plpgsql as $$
begin
  raise exception using errcode = p_code, message = p_message;
end $$;

-- ---------------------------------------------------------------- identity
create or replace function app.user_role()
returns text language sql stable security definer set search_path = public as $$
  select p.role from public.profiles p where p.id = auth.uid() and p.active
$$;

create or replace function app.is_hod()
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce(app.user_role() = 'HOD', false)
$$;

create or replace function app.is_faculty()
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce(app.user_role() = 'FACULTY', false)
$$;

create or replace function app.is_staff()
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce(app.user_role() in ('FACULTY', 'HOD'), false)
$$;

-- Faculty assigned to the student (active assignment) or the student's guide.
create or replace function app.is_assigned(p_student uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select app.is_staff() and (
    exists (select 1 from public.faculty_assignments a
            where a.student_id = p_student and a.faculty_id = auth.uid() and a.active)
    or exists (select 1 from public.student_profiles s
               where s.user_id = p_student and s.guide_id = auth.uid())
  )
$$;

create or replace function app.is_guide(p_student uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select app.is_staff() and (
    exists (select 1 from public.student_profiles s where s.user_id = p_student and s.guide_id = auth.uid())
    or exists (select 1 from public.faculty_assignments a
               where a.student_id = p_student and a.faculty_id = auth.uid() and a.active
                 and a.assignment_role in ('GUIDE', 'MENTOR'))
  )
$$;

create or replace function app.can_view_student(p_student uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select auth.uid() is not null and app.user_role() is not null and (
    (auth.uid() = p_student and app.user_role() = 'STUDENT')
    or app.is_hod()
    or app.is_assigned(p_student)
  )
$$;

create or replace function app.can_review_student(p_student uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select app.is_hod() or app.is_assigned(p_student)
$$;

create or replace function app.require_role(variadic p_roles text[])
returns void language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then
    perform app.fail('PT401', 'Authentication required');
  end if;
  if app.user_role() is null or not (app.user_role() = any (p_roles)) then
    perform app.fail('PT403', 'Operation not permitted for your role');
  end if;
end $$;

create or replace function app.actor_name()
returns text language sql stable security definer set search_path = public as $$
  select full_name from public.profiles where id = auth.uid()
$$;

create or replace function app.actor_designation()
returns text language sql stable security definer set search_path = public as $$
  select designation from public.profiles where id = auth.uid()
$$;

-- ---------------------------------------------------------------- timeline
-- Month n always derives from the original start date (no drift):
-- 31 Jan + 1 month = 28/29 Feb, + 2 months = 31 Mar.
create or replace function app.month_start(p_start date, p_month int)
returns date language sql immutable as $$
  select (p_start + make_interval(months => p_month - 1))::date
$$;

create or replace function app.month_end(p_start date, p_month int)
returns date language sql immutable as $$
  select ((p_start + make_interval(months => p_month))::date - 1)
$$;

create or replace function app.completion_date(p_start date, p_duration int)
returns date language sql immutable as $$
  select ((p_start + make_interval(months => p_duration))::date - 1)
$$;

create or replace function app.programme_month(p_start date, p_on date)
returns int language sql immutable as $$
  -- Largest n with month_start(n) <= p_on, so month boundaries match month_start()
  -- exactly, including end-of-month starts (31 Jan -> month 2 starts 28/29 Feb).
  select case
    when p_on < p_start then 0
    else (select max(n) from generate_series(
            greatest(1, (extract(year from age(p_on, p_start)) * 12 + extract(month from age(p_on, p_start)))::int),
            (extract(year from age(p_on, p_start)) * 12 + extract(month from age(p_on, p_start)))::int + 2) n
          where (p_start + make_interval(months => n - 1))::date <= p_on)
  end
$$;

create or replace function app.pg_year(p_month int)
returns int language sql immutable as $$
  select case when p_month <= 0 then 0 else least(3, ((p_month - 1) / 12) + 1) end
$$;

-- ---------------------------------------------------------------- templates
create or replace function app.template_fields(p_template text, p_owner text)
returns setof jsonb language sql stable security definer set search_path = public as $$
  select f
  from public.form_templates t, jsonb_array_elements(t.schema -> 'fields') f
  where t.code = p_template and (p_owner is null or f ->> 'owner' = p_owner)
$$;

create or replace function app.word_count(p_text text)
returns int language sql immutable as $$
  select case when p_text is null or btrim(p_text) = '' then 0
              else coalesce(array_length(regexp_split_to_array(btrim(p_text), '\s+'), 1), 0) end
$$;

-- Rejects keys that do not belong to the given owner (field-level RBAC).
create or replace function app.assert_owned_keys(p_template text, p_owner text, p_data jsonb)
returns void language plpgsql stable security definer set search_path = public as $$
declare
  v_key text;
begin
  for v_key in select jsonb_object_keys(coalesce(p_data, '{}'::jsonb)) loop
    if not exists (select 1 from app.template_fields(p_template, p_owner) f where f ->> 'key' = v_key) then
      perform app.fail('PT403', format('Field "%s" cannot be written by %s', v_key, p_owner));
    end if;
  end loop;
end $$;

-- Returns a list of validation errors for the required fields of an owner.
create or replace function app.validate_fields(p_template text, p_owner text, p_data jsonb)
returns text[] language plpgsql stable security definer set search_path = public as $$
declare
  f jsonb;
  v text;
  n int;
  errs text[] := '{}';
  label text;
begin
  for f in select * from app.template_fields(p_template, p_owner) loop
    if coalesce((f ->> 'readOnly')::boolean, false) and p_owner = 'STUDENT' and f ->> 'type' = 'ATTACHMENT' then
      continue;
    end if;
    if f ->> 'type' = 'ATTACHMENT' then
      continue;
    end if;
    if f ? 'visibleWhen' and not (coalesce(p_data, '{}'::jsonb) @> (f -> 'visibleWhen')) then
      continue;
    end if;
    label := f ->> 'label';
    v := nullif(btrim(coalesce(p_data ->> (f ->> 'key'), '')), '');
    if v is null then
      if coalesce((f ->> 'required')::boolean, false) then
        errs := errs || format('%s is required', label);
      end if;
      continue;
    end if;
    case f ->> 'type'
      when 'RATING_9' then
        if v !~ '^[1-9]$' then errs := errs || format('%s must be between 1 and 9', label); end if;
      when 'BME' then
        if v not in ('B', 'M', 'E') then errs := errs || format('%s must be B, M or E', label); end if;
      when 'DATE' then
        begin
          perform v::date;
        exception when others then
          errs := errs || format('%s must be a valid date', label);
        end;
      when 'BOOLEAN' then
        if v not in ('true', 'false') then errs := errs || format('%s must be yes or no', label); end if;
      when 'WORDS' then
        n := app.word_count(v);
        if f ? 'minWords' and n < (f ->> 'minWords')::int then
          errs := errs || format('%s needs at least %s words (currently %s)', label, f ->> 'minWords', n);
        end if;
        if f ? 'maxWords' and n > (f ->> 'maxWords')::int then
          errs := errs || format('%s allows at most %s words (currently %s)', label, f ->> 'maxWords', n);
        end if;
      when 'SELECT' then
        if f ? 'options' and not exists (
          select 1 from jsonb_array_elements(f -> 'options') o where o ->> 'value' = v) then
          errs := errs || format('%s has an invalid value', label);
        end if;
      else
        null;
    end case;
  end loop;
  return errs;
end $$;

-- ---------------------------------------------------------------- audit
create or replace function app.set_audit(p_action text, p_reason text default null)
returns void language sql as $$
  select set_config('app.audit_action', coalesce(p_action, ''), true),
         set_config('app.audit_reason', coalesce(p_reason, ''), true);
$$;

create or replace function app.audit_trigger()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_old jsonb := case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) else null end;
  v_new jsonb := case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) else null end;
  v_changes jsonb;
  v_ref jsonb := coalesce(v_new, v_old);
  v_student uuid;
begin
  if tg_op = 'UPDATE' then
    select jsonb_object_agg(n.key, jsonb_build_object('old', v_old -> n.key, 'new', n.value))
      into v_changes
      from jsonb_each(v_new) n
     where (v_old -> n.key) is distinct from n.value
       and n.key not in ('updated_at', 'version');
    if v_changes is null then
      return new;
    end if;
  elsif tg_op = 'INSERT' then
    v_changes := v_new;
  else
    v_changes := v_old;
  end if;

  v_student := coalesce(
    (v_ref ->> 'student_id')::uuid,
    case when tg_table_name = 'student_profiles' then (v_ref ->> 'user_id')::uuid end);

  insert into public.audit_logs (actor_id, actor_role, action, entity_type, entity_id, student_id, changes, reason)
  values (
    auth.uid(),
    app.user_role(),
    coalesce(nullif(current_setting('app.audit_action', true), ''), tg_op),
    tg_table_name,
    coalesce(v_ref ->> 'id', v_ref ->> 'user_id', v_ref ->> 'key'),
    v_student,
    v_changes,
    nullif(current_setting('app.audit_reason', true), '')
  );
  return coalesce(new, old);
end $$;

create or replace function app.audit_immutable()
returns trigger language plpgsql as $$
begin
  raise exception using errcode = 'PT403', message = 'Audit records are immutable';
end $$;

create trigger audit_logs_immutable before update or delete on public.audit_logs
  for each row execute function app.audit_immutable();

create or replace function app.protect_submission_delete()
returns trigger language plpgsql as $$
begin
  if old.submitted_at is not null or old.status not in ('DRAFT', 'NOT_STARTED') then
    raise exception using errcode = 'PT403', message = 'Submitted or reviewed records cannot be deleted';
  end if;
  return old;
end $$;

create trigger submissions_protect_delete before delete on public.submissions
  for each row execute function app.protect_submission_delete();

do $$
declare
  t text;
begin
  foreach t in array array[
    'profiles', 'student_profiles', 'faculty_profiles', 'programme_enrolments', 'faculty_assignments',
    'invitations', 'institution_settings', 'academic_batches', 'competencies', 'targets',
    'form_templates', 'academic_documents', 'document_versions', 'submissions', 'attachments',
    'competency_signoffs', 'certificates']
  loop
    execute format('create trigger %I after insert or update or delete on public.%I
                    for each row execute function app.audit_trigger()', t || '_audit', t);
  end loop;
end $$;

-- ---------------------------------------------------------------- updated_at
create or replace function app.touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

do $$
declare
  t text;
begin
  foreach t in array array['profiles', 'student_profiles', 'faculty_profiles', 'programme_enrolments', 'submissions', 'competency_signoffs']
  loop
    execute format('create trigger %I before update on public.%I
                    for each row execute function app.touch_updated_at()', t || '_touch', t);
  end loop;
end $$;

-- ---------------------------------------------------------------- notifications
create or replace function app.notify(
  p_recipient uuid, p_title text, p_body text, p_category text,
  p_entity_type text default null, p_entity_id uuid default null,
  p_student uuid default null, p_dedupe text default null)
returns void language plpgsql security definer set search_path = public as $$
begin
  if p_recipient is null or p_recipient = auth.uid() then
    return;
  end if;
  insert into public.notifications (recipient_id, title, body, category, entity_type, entity_id, student_id, dedupe_key)
  values (p_recipient, p_title, p_body, p_category, p_entity_type, p_entity_id, p_student, p_dedupe)
  on conflict do nothing;
end $$;

create or replace function app.notify_reviewers(
  p_student uuid, p_title text, p_body text, p_category text,
  p_entity_type text default null, p_entity_id uuid default null)
returns void language plpgsql security definer set search_path = public as $$
declare
  r record;
begin
  for r in
    select distinct faculty_id as id from public.faculty_assignments where student_id = p_student and active
    union
    select guide_id from public.student_profiles where user_id = p_student and guide_id is not null
  loop
    perform app.notify(r.id, p_title, p_body, p_category, p_entity_type, p_entity_id, p_student);
  end loop;
end $$;

create or replace function app.notify_hods(
  p_title text, p_body text, p_category text, p_student uuid default null, p_dedupe text default null)
returns void language plpgsql security definer set search_path = public as $$
declare
  r record;
begin
  for r in select id from public.profiles where role = 'HOD' and active loop
    perform app.notify(r.id, p_title, p_body, p_category, null, null, p_student, p_dedupe);
  end loop;
end $$;

-- ---------------------------------------------------------------- provisioning
-- Creates the per-student record instances from the reusable templates:
-- Mini-CEX/DOPS initial slots, dissertation milestones, monthly assessments,
-- annual reviews and the competency sign-off sheet. Idempotent.
create or replace function app.provision_student(p_student uuid)
returns void language plpgsql security definer set search_path = public as $$
declare
  t record;
  i int;
  v_duration int;
  v_slot_name text;
begin
  select coalesce(e.duration_months, s.programme_duration_months)
    into v_duration
    from public.institution_settings s
    left join public.programme_enrolments e on e.student_id = p_student;
  v_duration := coalesce(v_duration, 36);

  for t in select * from public.form_templates where active loop
    if t.category = 'MONTHLY' then
      for i in 1 .. v_duration loop
        insert into public.submissions (student_id, template_code, template_version, period_no, status)
        values (p_student, t.code, t.version, i, 'NOT_STARTED')
        on conflict do nothing;
      end loop;
    elsif t.category = 'ANNUAL' then
      for i in 1 .. coalesce(t.period_count, 3) loop
        insert into public.submissions (student_id, template_code, template_version, period_no, status)
        values (p_student, t.code, t.version, i, 'NOT_STARTED')
        on conflict do nothing;
      end loop;
    elsif t.required_initial > 0 then
      for i in 1 .. t.required_initial loop
        v_slot_name := t.schema -> 'slots' ->> (i - 1);
        insert into public.submissions (student_id, template_code, template_version, slot_no, status, student_data)
        values (p_student, t.code, t.version, i, 'NOT_STARTED',
                case when v_slot_name is not null then jsonb_build_object('milestone', v_slot_name) else '{}'::jsonb end)
        on conflict do nothing;
      end loop;
    end if;
  end loop;

  insert into public.competency_signoffs (student_id, competency_code)
  select p_student, c.code from public.competencies c where c.active
  on conflict do nothing;
end $$;

create or replace function app.on_enrolment_change()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  perform app.provision_student(new.student_id);
  return new;
end $$;

create trigger programme_enrolments_provision after insert or update of duration_months on public.programme_enrolments
  for each row execute function app.on_enrolment_change();

-- ---------------------------------------------------------------- account linking
-- Roles come ONLY from HOD-created invitations, never from user metadata.
create or replace function app.handle_new_auth_user()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  inv public.invitations;
  v_settings public.institution_settings;
begin
  select * into inv from public.invitations
   where lower(email) = lower(new.email) and status = 'PENDING'
   order by created_at desc limit 1;
  if not found then
    return new;
  end if;

  select * into v_settings from public.institution_settings where id = 1;

  insert into public.profiles (id, role, full_name, email, designation)
  values (new.id, inv.role, inv.full_name, lower(new.email), inv.designation)
  on conflict (id) do nothing;

  if inv.role = 'STUDENT' then
    insert into public.student_profiles (user_id, university_reg_no, institution, university, batch_id, guide_id)
    values (new.id, inv.university_reg_no, v_settings.institution_name, v_settings.university_name, inv.batch_id, inv.guide_id)
    on conflict (user_id) do nothing;
    insert into public.programme_enrolments (student_id, programme_start_date, duration_months)
    values (new.id, inv.programme_start_date, coalesce(v_settings.programme_duration_months, 36))
    on conflict (student_id) do nothing;
    if inv.guide_id is not null then
      insert into public.faculty_assignments (student_id, faculty_id, assignment_role, assigned_by)
      values (new.id, inv.guide_id, 'GUIDE', inv.invited_by)
      on conflict do nothing;
    end if;
  else
    insert into public.faculty_profiles (user_id) values (new.id) on conflict do nothing;
  end if;

  update public.invitations set status = 'ACCEPTED', accepted_at = now() where id = inv.id;
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function app.handle_new_auth_user();
