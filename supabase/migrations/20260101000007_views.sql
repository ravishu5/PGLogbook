-- =====================================================================
-- Read models. All views are security_invoker, so table RLS applies to
-- the calling user.
-- =====================================================================

-- Submission ↔ target mapping, interpreted from form_templates.schema.targetRules
-- (same rules as the Android TargetEngine). Each record counts at most once per target.
create or replace view public.v_submission_targets with (security_invoker = true) as
select distinct
  s.id as submission_id,
  s.student_id,
  s.status,
  s.entry_date,
  coalesce(r ->> 'targetKey', s.student_data ->> (r ->> 'targetFromField')) as target_key
from public.submissions s
join public.form_templates t on t.code = s.template_code
cross join lateral jsonb_array_elements(coalesce(t.schema -> 'targetRules', '[]'::jsonb)) r
where s.deleted_at is null
  and s.status <> 'NOT_STARTED'
  and (not (r ? 'when') or s.student_data @> (r -> 'when'))
  and coalesce(r ->> 'targetKey', s.student_data ->> (r ->> 'targetFromField')) is not null;

create or replace view public.v_target_progress with (security_invoker = true) as
select
  sp.user_id as student_id,
  tg.key as target_key,
  tg.category,
  tg.label,
  tg.target_value,
  tg.version as target_version,
  tg.sort_order,
  count(st.submission_id)::int as recorded,
  (count(st.submission_id) filter (where st.status not in ('DRAFT', 'RETURNED')))::int as submitted,
  (count(st.submission_id) filter (where st.status in ('VERIFIED', 'COMPLETED')))::int as verified,
  least(100, round(100.0 * count(st.submission_id) filter (where st.status in ('VERIFIED', 'COMPLETED')) / tg.target_value))::int as verified_percent
from public.student_profiles sp
cross join public.targets tg
left join public.v_submission_targets st on st.student_id = sp.user_id and st.target_key = tg.key
where tg.is_current
group by sp.user_id, tg.key, tg.category, tg.label, tg.target_value, tg.version, tg.sort_order;

create or replace view public.v_student_overview with (security_invoker = true) as
select
  p.id as student_id,
  p.full_name,
  p.email,
  p.active,
  sp.university_reg_no,
  sp.institution,
  sp.university,
  sp.current_posting,
  sp.batch_id,
  b.name as batch_name,
  b.academic_year,
  sp.guide_id,
  g.full_name as guide_name,
  e.programme_start_date,
  e.duration_months,
  e.status as enrolment_status,
  app.completion_date(e.programme_start_date, e.duration_months) as completion_date,
  least(app.programme_month(e.programme_start_date, current_date), e.duration_months) as current_month,
  app.pg_year(least(app.programme_month(e.programme_start_date, current_date), e.duration_months)) as pg_year,
  (select a.assignment_role from public.faculty_assignments a
    where a.student_id = p.id and a.faculty_id = auth.uid() and a.active limit 1) as my_assignment_role,
  (select count(*) from public.submissions s where s.student_id = p.id and s.template_code = 'MONTHLY'
     and s.status = 'COMPLETED')::int as monthly_completed,
  (select count(*) from public.submissions s where s.student_id = p.id and s.template_code = 'MONTHLY'
     and s.period_no <= app.programme_month(e.programme_start_date, current_date)
     and s.status in ('NOT_STARTED', 'DRAFT', 'RETURNED'))::int as monthly_overdue_or_open,
  (select count(*) from public.submissions s where s.student_id = p.id and s.deleted_at is null
     and s.status in ('SUBMITTED', 'FACULTY_REVIEW_PENDING', 'HOD_REVIEW_PENDING'))::int as pending_reviews,
  (select count(*) from public.submissions s join public.form_templates t on t.code = s.template_code
    where s.student_id = p.id and s.deleted_at is null and t.category = 'LOG' and s.status <> 'NOT_STARTED')::int as log_entries,
  (select count(*) from public.competency_signoffs c where c.student_id = p.id and c.status = 'APPROVED')::int as signoffs_approved,
  (select count(*) from public.competencies c where c.active)::int as signoffs_total,
  (select count(*) from public.submissions s where s.student_id = p.id and s.template_code = 'ANNUAL_REVIEW'
     and s.status = 'COMPLETED')::int as annual_reviews_completed,
  cert.certificate_no,
  cert.issued_at as certificate_issued_at
from public.profiles p
join public.student_profiles sp on sp.user_id = p.id
left join public.academic_batches b on b.id = sp.batch_id
left join public.profiles g on g.id = sp.guide_id
left join public.programme_enrolments e on e.student_id = p.id
left join public.certificates cert on cert.student_id = p.id and cert.status = 'ISSUED'
where p.role = 'STUDENT';

-- What the signed-in reviewer can act on now.
create or replace view public.v_review_queue with (security_invoker = true) as
select
  'SUBMISSION'::text as kind,
  s.id,
  s.student_id,
  p.full_name as student_name,
  s.template_code,
  t.title as template_title,
  s.period_no,
  s.slot_no,
  s.status,
  s.entry_date,
  s.submitted_at,
  s.version
from public.submissions s
join public.form_templates t on t.code = s.template_code
join public.profiles p on p.id = s.student_id
where s.deleted_at is null
  and app.is_staff()
  and (
    (s.status in ('SUBMITTED', 'FACULTY_REVIEW_PENDING')
      and app.can_review_student(s.student_id)
      and case t.schema ->> 'reviewer'
            when 'ASSESSOR' then app.is_hod() or s.assessor_id = auth.uid()
            when 'GUIDE' then app.is_hod() or app.is_guide(s.student_id)
            else true end)
    or (s.status in ('HOD_REVIEW_PENDING', 'FACULTY_REVIEWED') and (app.is_hod() or app.is_guide(s.student_id)))
  )
union all
select
  'SIGNOFF'::text,
  c.id,
  c.student_id,
  p.full_name,
  c.competency_code,
  k.title,
  null,
  null,
  c.status,
  null,
  c.updated_at,
  c.version
from public.competency_signoffs c
join public.competencies k on k.code = c.competency_code
join public.profiles p on p.id = c.student_id
where c.status = 'AWAITING_APPROVAL' and app.can_review_student(c.student_id);

create or replace view public.v_staff_directory with (security_invoker = true) as
select p.id, p.full_name, p.email, p.role, p.designation, p.active, fp.unit, fp.is_unit_head
from public.profiles p
left join public.faculty_profiles fp on fp.user_id = p.id
where p.role in ('FACULTY', 'HOD');

-- ---------------------------------------------------------------------
-- Typed entity views (one per template), generated from the template
-- field definitions so reports/BI tools get real columns. Re-run
-- app.refresh_entity_views() after changing templates.
-- ---------------------------------------------------------------------
create or replace function app.refresh_entity_views()
returns void language plpgsql as $$
declare
  m record;
  f jsonb;
  cols text;
  v_src text;
  v_type text;
  v_extra text;
begin
  for m in
    select * from (values
      ('CLINICAL_CASE', 'clinical_case_logs'),
      ('NEONATAL_CASE', 'neonatal_case_logs'),
      ('PICU_CASE', 'picu_logs'),
      ('PROCEDURE', 'procedure_logs'),
      ('MINI_CEX', 'mini_cex_submissions'),
      ('DOPS', 'dops_submissions'),
      ('ACADEMIC_ACTIVITY', 'academic_activity_records'),
      ('DISSERTATION_MILESTONE', 'dissertation_milestones'),
      ('TEACHING', 'teaching_competency_records'),
      ('REFLECTION', 'critical_incident_reflections'),
      ('MONTHLY', 'monthly_assessments'),
      ('ANNUAL_REVIEW', 'annual_comprehensive_reviews')) as x(code, view_name)
    where exists (select 1 from public.form_templates t where t.code = x.code)
  loop
    cols := '';
    for f in select * from app.template_fields(m.code, null) loop
      continue when f ->> 'type' = 'ATTACHMENT';
      v_src := case f ->> 'owner' when 'STUDENT' then 's.student_data' when 'FACULTY' then 's.faculty_data' else 's.hod_data' end;
      v_type := case f ->> 'type'
                  when 'DATE' then 'date'
                  when 'RATING_9' then 'int'
                  when 'BOOLEAN' then 'boolean'
                  else 'text' end;
      if v_type = 'text' then
        cols := cols || format(', %s ->> %L as %I', v_src, f ->> 'key', f ->> 'key');
      else
        cols := cols || format(', nullif(%s ->> %L, '''')::%s as %I', v_src, f ->> 'key', v_type, f ->> 'key');
      end if;
    end loop;

    v_extra := case m.code
      when 'MONTHLY' then
        ', app.month_start(e.programme_start_date, s.period_no) as month_start'
        ' , app.month_end(e.programme_start_date, s.period_no) as month_end'
        ' , app.pg_year(s.period_no) as pg_year'
      else '' end;

    execute format('drop view if exists public.%I', m.view_name);
    execute format($v$
      create view public.%I with (security_invoker = true) as
      select s.id, s.student_id, p.full_name as student_name, s.status, s.period_no, s.slot_no, s.entry_date,
             s.competency_codes, s.submitted_at, s.submitted_by_name, s.reviewed_by_name, s.reviewed_by_designation,
             s.reviewed_at, s.hod_reviewed_by_name, s.hod_reviewed_at, s.return_reason, s.version,
             s.created_at, s.updated_at %s %s
      from public.submissions s
      join public.profiles p on p.id = s.student_id
      left join public.programme_enrolments e on e.student_id = s.student_id
      where s.template_code = %L and s.deleted_at is null$v$, m.view_name, v_extra, cols, m.code);
    execute format('grant select on public.%I to authenticated', m.view_name);
    execute format('revoke all on public.%I from anon', m.view_name);
  end loop;
end $$;

revoke execute on function app.refresh_entity_views() from public, anon, authenticated;
select app.refresh_entity_views();

do $$
declare
  v text;
begin
  foreach v in array array['v_submission_targets', 'v_target_progress', 'v_student_overview', 'v_review_queue', 'v_staff_directory']
  loop
    execute format('revoke all on public.%I from anon', v);
    execute format('grant select on public.%I to authenticated', v);
  end loop;
end $$;
