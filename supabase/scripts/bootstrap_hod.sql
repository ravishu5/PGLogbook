-- Bootstraps the first Head of Department account on a fresh Supabase project.
-- Run once in the SQL editor (as postgres) after applying all migrations, then invite the same
-- email from Dashboard > Authentication > Users > "Invite user" (or create the user with a password).
-- The on_auth_user_created trigger turns the pending invitation into an HOD profile.
-- All further faculty and students are invited from the app (HOD > Invitations).

-- 1. Institution details shown on certificates and reports.
update public.institution_settings
   set institution_name = 'Your Medical College',
       university_name  = 'Your Health Sciences University',
       department_name  = 'Department of Pediatrics'
 where id = 1;

-- 2. Pending HOD invitation (edit the name and email).
insert into public.invitations (email, full_name, role, designation)
values (lower('hod@your-college.edu'), 'Dr. Head of Department', 'HOD', 'Professor & HOD')
on conflict do nothing;
