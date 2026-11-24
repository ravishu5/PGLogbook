-- =====================================================================
-- Private storage buckets.
--   evidence/<student_id>/<submission_id>/<file>   – student evidence files
--   documents/<document_code>/<version>/<file>     – HOD-published PDFs
-- No public buckets; clients download via authenticated requests or
-- short-lived signed URLs.
-- =====================================================================

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('evidence', 'evidence', false, 10485760,
   array['application/pdf', 'image/jpeg', 'image/png', 'image/webp', 'image/heic']),
  ('documents', 'documents', false, 26214400, array['application/pdf'])
on conflict (id) do nothing;

create or replace function app.storage_student(p_name text)
returns uuid language plpgsql immutable as $$
begin
  return split_part(p_name, '/', 1)::uuid;
exception when others then
  return null;
end $$;

create or replace function app.storage_submission(p_name text)
returns uuid language plpgsql immutable as $$
begin
  return split_part(p_name, '/', 2)::uuid;
exception when others then
  return null;
end $$;

-- Students may upload only into their own, still-editable records.
create or replace function app.can_upload_evidence(p_name text)
returns boolean language sql stable security definer set search_path = public as $$
  select app.user_role() = 'STUDENT'
     and app.storage_student(p_name) = auth.uid()
     and exists (select 1 from public.submissions s
                  where s.id = app.storage_submission(p_name)
                    and s.student_id = auth.uid()
                    and s.deleted_at is null
                    and s.status in ('NOT_STARTED', 'DRAFT', 'RETURNED'))
$$;

create policy evidence_read on storage.objects for select to authenticated
  using (bucket_id = 'evidence' and app.can_view_student(app.storage_student(name)));

create policy evidence_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'evidence' and app.can_upload_evidence(name));

create policy documents_read on storage.objects for select to authenticated
  using (bucket_id = 'documents' and app.user_role() is not null);

create policy documents_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'documents' and app.is_hod());

-- Evidence metadata row (after a successful upload).
create or replace function public.register_attachment(
  p_submission uuid, p_path text, p_file_name text, p_mime text, p_size bigint)
returns public.attachments
language plpgsql security definer set search_path = public as $$
declare
  v_row public.attachments;
begin
  perform app.require_role('STUDENT');
  if not app.can_upload_evidence(p_path) or app.storage_submission(p_path) <> p_submission then
    perform app.fail('PT403', 'Evidence can only be attached to your own editable records');
  end if;
  if p_size is not null and p_size > 10485760 then
    perform app.fail('PT422', 'Files must be 10 MB or smaller');
  end if;
  perform app.set_audit('ATTACH');
  insert into public.attachments (submission_id, student_id, storage_path, file_name, mime_type, size_bytes, uploaded_by)
  values (p_submission, auth.uid(), p_path, p_file_name, p_mime, p_size, auth.uid())
  on conflict (storage_path) do update set file_name = excluded.file_name
  returning * into v_row;
  perform app.set_audit(null);
  return v_row;
end $$;

revoke execute on function public.register_attachment(uuid, text, text, text, bigint) from public, anon;
grant execute on function public.register_attachment(uuid, text, text, text, bigint) to authenticated;
