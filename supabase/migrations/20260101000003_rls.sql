-- =====================================================================
-- Row Level Security. Reads are governed by the policies below.
-- Direct INSERT/UPDATE/DELETE is revoked for API roles: every write goes
-- through an authorizing SECURITY DEFINER RPC (0004).
-- =====================================================================

grant usage on schema app to authenticated;
grant execute on all functions in schema app to authenticated;
revoke execute on function app.provision_student(uuid) from public, anon, authenticated;
revoke execute on function app.handle_new_auth_user() from public, anon, authenticated;
revoke execute on function app.notify(uuid, text, text, text, text, uuid, uuid, text) from public, anon, authenticated;
revoke execute on function app.notify_reviewers(uuid, text, text, text, text, uuid) from public, anon, authenticated;
revoke execute on function app.notify_hods(text, text, text, uuid, text) from public, anon, authenticated;
revoke execute on function app.audit_trigger() from public, anon, authenticated;
revoke execute on function app.on_enrolment_change() from public, anon, authenticated;

do $$
declare
  t text;
begin
  foreach t in array array[
    'user_roles', 'profiles', 'institution_settings', 'academic_batches', 'faculty_profiles',
    'student_profiles', 'programme_enrolments', 'faculty_assignments', 'invitations',
    'competencies', 'targets', 'form_templates', 'academic_documents', 'document_versions',
    'submissions', 'attachments', 'competency_signoffs', 'faculty_feedback', 'certificates',
    'audit_logs', 'notifications']
  loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from anon, authenticated', t);
    execute format('grant select on public.%I to authenticated', t);
  end loop;
end $$;

revoke all on sequence public.certificate_no_seq from anon, authenticated;

-- ---------------------------------------------------------------- reference data (all signed-in users)
create policy user_roles_read on public.user_roles for select to authenticated using (true);
create policy settings_read on public.institution_settings for select to authenticated using (app.user_role() is not null);
create policy batches_read on public.academic_batches for select to authenticated using (app.user_role() is not null);
create policy competencies_read on public.competencies for select to authenticated using (app.user_role() is not null);
create policy targets_read on public.targets for select to authenticated using (app.user_role() is not null);
create policy templates_read on public.form_templates for select to authenticated using (app.user_role() is not null);
create policy documents_read on public.academic_documents for select to authenticated using (app.user_role() is not null);
create policy document_versions_read on public.document_versions for select to authenticated
  using (app.user_role() is not null and (published or app.is_hod()));

-- ---------------------------------------------------------------- people
-- Self, HOD (all), staff directory (faculty/HOD visible to everyone signed in),
-- and students visible to their assigned faculty.
create policy profiles_read on public.profiles for select to authenticated
  using (
    id = auth.uid()
    or app.is_hod()
    or (app.user_role() is not null and role in ('FACULTY', 'HOD'))
    or (role = 'STUDENT' and app.is_assigned(id))
  );

create policy faculty_profiles_read on public.faculty_profiles for select to authenticated
  using (app.user_role() is not null);

create policy student_profiles_read on public.student_profiles for select to authenticated
  using (app.can_view_student(user_id));

create policy enrolments_read on public.programme_enrolments for select to authenticated
  using (app.can_view_student(student_id));

create policy assignments_read on public.faculty_assignments for select to authenticated
  using (app.can_view_student(student_id) or faculty_id = auth.uid());

create policy invitations_read on public.invitations for select to authenticated
  using (app.is_hod());

-- ---------------------------------------------------------------- logbook
-- Students never see annual reviews before they are completed by faculty.
create policy submissions_read on public.submissions for select to authenticated
  using (
    deleted_at is null
    and app.can_view_student(student_id)
    and (
      app.is_staff()
      or template_code <> 'ANNUAL_REVIEW'
      or status = 'COMPLETED'
    )
  );

create policy attachments_read on public.attachments for select to authenticated
  using (app.can_view_student(student_id));

create policy signoffs_read on public.competency_signoffs for select to authenticated
  using (app.can_view_student(student_id));

create policy feedback_read on public.faculty_feedback for select to authenticated
  using (app.can_view_student(student_id));

create policy certificates_read on public.certificates for select to authenticated
  using (app.can_view_student(student_id));

create policy audit_read on public.audit_logs for select to authenticated
  using (app.is_hod() or (student_id is not null and app.can_view_student(student_id)));

create policy notifications_read on public.notifications for select to authenticated
  using (recipient_id = auth.uid());
