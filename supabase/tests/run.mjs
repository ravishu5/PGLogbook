// Backend test-suite: applies the real Supabase migrations to an in-process
// PostgreSQL (PGlite) with a Supabase stub, then exercises RBAC, RLS,
// workflows, timeline, targets, audit and certification as real API roles.
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const here = dirname(fileURLToPath(import.meta.url));
const migrationsDir = join(here, '..', 'migrations');

const db = await PGlite.create({ extensions: { pgcrypto } });
await db.exec(readFileSync(join(here, 'supabase-stub.sql'), 'utf8'));
for (const f of readdirSync(migrationsDir).filter((x) => x.endsWith('.sql')).sort()) {
  try {
    await db.exec(readFileSync(join(migrationsDir, f), 'utf8'));
  } catch (e) {
    console.error(`Migration ${f} failed: ${e.message}`);
    process.exit(1);
  }
}

// ------------------------------------------------------------------ helpers
async function as(uid, sql, params = []) {
  return db.transaction(async (tx) => {
    if (uid) {
      await tx.query(`select set_config('request.jwt.claims', $1, true)`, [JSON.stringify({ sub: uid, role: 'authenticated' })]);
      await tx.exec('set local role authenticated');
    } else {
      await tx.exec('set local role anon');
    }
    return (await tx.query(sql, params)).rows;
  });
}
const one = async (uid, sql, params) => (await as(uid, sql, params))[0];
async function rejects(promise, pattern) {
  try {
    await promise;
  } catch (e) {
    const text = `${e.code ?? ''} ${e.message}`;
    if (pattern && !pattern.test(text)) throw new Error(`Expected ${pattern} but got: ${text}`);
    return text;
  }
  throw new Error(`Expected failure ${pattern ?? ''} but call succeeded`);
}
async function signUp(email) {
  const r = await db.query('insert into auth.users (email) values ($1) returning id', [email]);
  return r.rows[0].id;
}
const sqlDate = async (expr) => (await db.query(`select (${expr})::date::text as d`)).rows[0].d;

let passed = 0;
const failures = [];
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  ✓ ${name}`);
  } catch (e) {
    failures.push(name);
    console.log(`  ✗ ${name}\n      ${(e.stack + (e.actual !== undefined ? '\n actual=' + JSON.stringify(e.actual) + ' expected=' + JSON.stringify(e.expected) : '')).split('\n').filter((l) => !l.includes('node_modules')).slice(0, 8).join('\n      ')}`);
  }
}

// ------------------------------------------------------------------ fixtures
await db.query(`insert into public.invitations (email, full_name, role, designation) values ('hod@college.edu', 'Dr Hema HOD', 'HOD', 'Professor & Head')`);
const HOD = await signUp('hod@college.edu');

const ids = {};
const startA = await sqlDate(`current_date - interval '13 months'`);
const startB = await sqlDate(`current_date - interval '2 months'`);
const startC = await sqlDate(`current_date - interval '37 months'`);

console.log('Fixtures');
await test('HOD invites faculty and students (RPC), accounts link on sign-up', async () => {
  const batch = await one(HOD, `select * from upsert_batch(null, 'Batch 2024', '2024-25')`);
  ids.batch = batch.id;
  await as(HOD, `select create_invitation('f1@college.edu', 'Dr Fiona One', 'FACULTY', 'Associate Professor')`);
  await as(HOD, `select create_invitation('f2@college.edu', 'Dr Farid Two', 'FACULTY', 'Assistant Professor')`);
  await as(HOD, `select create_invitation('f3@college.edu', 'Dr Faye Three', 'FACULTY', 'Professor')`);
  ids.f1 = await signUp('f1@college.edu');
  ids.f2 = await signUp('f2@college.edu');
  ids.f3 = await signUp('f3@college.edu');
  await as(HOD, `select create_invitation('a@college.edu', 'Asha Student', 'STUDENT', null, 'REG-A', $1, $2, $3)`, [ids.batch, startA, ids.f1]);
  await as(HOD, `select create_invitation('b@college.edu', 'Bala Student', 'STUDENT', null, 'REG-B', $1, $2, null)`, [ids.batch, startB]);
  await as(HOD, `select create_invitation('c@college.edu', 'Chitra Student', 'STUDENT', null, 'REG-C', $1, $2, $3)`, [ids.batch, startC, ids.f3]);
  ids.a = await signUp('a@college.edu');
  ids.b = await signUp('b@college.edu');
  ids.c = await signUp('c@college.edu');
  await as(HOD, `select assign_faculty($1, $2, 'UNIT_FACULTY')`, [ids.a, ids.f2]);
  await as(HOD, `select assign_faculty($1, $2, 'UNIT_FACULTY')`, [ids.b, ids.f3]);
  const roles = (await db.query(`select email, role from profiles order by email`)).rows;
  assert.equal(roles.length, 7);
  assert.equal(roles.find((r) => r.email === 'a@college.edu').role, 'STUDENT');
});

await test('Sign-up without an invitation gets no profile/role', async () => {
  const x = await signUp('stranger@example.com');
  const r = await db.query('select 1 from profiles where id = $1', [x]);
  assert.equal(r.rows.length, 0);
  assert.equal((await as(x, 'select * from submissions')).length, 0);
  await rejects(as(x, `select * from save_submission(null, 'CLINICAL_CASE', '{}'::jsonb)`), /PT403|PT401/);
});

await test('Students cannot invite users or self-assign roles', async () => {
  await rejects(as(ids.a, `select create_invitation('z@x.edu', 'Z', 'HOD')`), /PT403/);
});

console.log('Provisioning & timeline');
await test('Each student gets 36 monthly, 3 annual, 6 Mini-CEX, 6 DOPS, 9 milestones, 22 sign-offs', async () => {
  const r = (await db.query(
    `select template_code, count(*)::int n from submissions where student_id = $1 group by 1`, [ids.a])).rows;
  const m = Object.fromEntries(r.map((x) => [x.template_code, x.n]));
  assert.deepEqual(m, { MONTHLY: 36, ANNUAL_REVIEW: 3, MINI_CEX: 6, DOPS: 6, DISSERTATION_MILESTONE: 9 });
  const s = (await db.query(`select count(*)::int n from competency_signoffs where student_id = $1`, [ids.a])).rows[0].n;
  assert.equal(s, 22);
  const ms = (await db.query(`select student_data->>'milestone' m from submissions where student_id=$1 and template_code='DISSERTATION_MILESTONE' order by slot_no`, [ids.a])).rows;
  assert.ok(ms.every((x) => x.m && x.m.length > 3));
});

await test('Timeline: month boundaries, completion date, PG year from original start date', async () => {
  const r = (await db.query(`select app.month_start('2024-01-31', 2)::text s2, app.month_end('2024-01-31', 1)::text e1,
       app.completion_date('2024-07-01', 36)::text c, app.programme_month('2024-07-01', '2024-07-01') m1,
       app.programme_month('2024-07-01', '2025-06-30') m12, app.programme_month('2024-07-01', '2025-07-01') m13,
       app.pg_year(12) y1, app.pg_year(13) y2, app.pg_year(36) y3`)).rows[0];
  assert.equal(r.s2, '2024-02-29');
  assert.equal(r.e1, '2024-02-28');
  assert.equal(r.c, '2027-06-30');
  assert.deepEqual([r.m1, r.m12, r.m13, r.y1, r.y2, r.y3], [1, 12, 13, 1, 2, 3]);
  const eom = (await db.query(`select app.programme_month('2024-01-31', '2024-02-28') a, app.programme_month('2024-01-31', '2024-02-29') b,
       app.programme_month('2024-01-31', '2024-03-30') c, app.programme_month('2024-01-31', '2024-03-31') d,
       app.programme_month('2024-01-31', '2024-01-30') e`)).rows[0];
  assert.deepEqual([eom.a, eom.b, eom.c, eom.d, eom.e], [1, 2, 2, 3, 0]);
  const ov = await one(ids.a, `select current_month, pg_year, completion_date from v_student_overview where student_id = $1`, [ids.a]);
  assert.equal(ov.current_month, 14);
  assert.equal(ov.pg_year, 2);
});

console.log('RLS / RBAC');
await test('Anonymous users read nothing', async () => {
  await rejects(as(null, 'select * from submissions'), /permission denied/);
  await rejects(as(null, 'select * from profiles'), /permission denied/);
});

await test('Student sees only own records; assigned faculty sees assigned students only; HOD sees all', async () => {
  const aRows = await as(ids.a, 'select distinct student_id from submissions');
  assert.deepEqual(aRows.map((r) => r.student_id), [ids.a]);
  const f1 = (await as(ids.f1, 'select distinct student_id from submissions')).map((r) => r.student_id);
  assert.deepEqual(f1, [ids.a]);
  const f3 = (await as(ids.f3, 'select distinct student_id from submissions')).map((r) => r.student_id).sort();
  assert.deepEqual(f3, [ids.b, ids.c].sort());
  const hod = await as(HOD, 'select distinct student_id from submissions');
  assert.equal(hod.length, 3);
  const prof = await as(ids.a, `select id from profiles where role = 'STUDENT'`);
  assert.deepEqual(prof.map((r) => r.id), [ids.a]);
});

await test('Direct table writes are denied to API roles', async () => {
  await rejects(as(ids.a, `insert into submissions (student_id, template_code) values ($1, 'CLINICAL_CASE')`, [ids.a]), /permission denied/);
  await rejects(as(HOD, `update profiles set role = 'HOD' where id = $1`, [ids.a]), /permission denied/);
  await rejects(as(ids.f1, `update submissions set status = 'VERIFIED'`), /permission denied/);
  await rejects(as(ids.a, `delete from audit_logs`), /permission denied/);
});

await test('Students cannot see annual reviews until completed', async () => {
  const r = await as(ids.a, `select * from submissions where template_code = 'ANNUAL_REVIEW'`);
  assert.equal(r.length, 0);
});

console.log('Clinical log workflow');
const clinical = {
  date: '2025-01-10', case_ref: 'W-001', age_sex: '3y/M', setting: 'WARD', diagnosis: 'Severe pneumonia',
  key_findings: 'Tachypnoea, crepitations', management: 'Oxygen, antibiotics', role: 'PS', outcome: 'DISCHARGED',
  learning_point: 'Early recognition of hypoxia', faculty: '',
};
await test('Draft save, required-field validation, submit, faculty verify, lock', async () => {
  const tpl = (await db.query(`select schema from form_templates where code = 'CLINICAL_CASE'`)).rows[0].schema;
  const data = {};
  for (const f of tpl.fields.filter((x) => x.owner === 'STUDENT')) {
    if (f.type === 'FACULTY') data[f.key] = ids.f1;
    else if (f.type === 'SELECT') data[f.key] = (f.options ?? [{ value: 'PS' }])[0].value;
    else if (f.type === 'DATE') data[f.key] = '2025-01-10';
    else if (f.type === 'COMPETENCIES') data[f.key] = 'PG-PED-01,PG-PED-02';
    else data[f.key] = clinical[f.key] || 'Sample text';
  }
  data.setting = 'WARD';
  if ('presentation' in data) data.presentation = 'LONG_CASE';
  const draft = await one(ids.a, `select * from save_submission(null, 'CLINICAL_CASE', $1::jsonb)`, [JSON.stringify({ date: '2025-01-10', diagnosis: 'Pneumonia' })]);
  assert.equal(draft.status, 'DRAFT');
  await rejects(as(ids.a, `select * from submit_submission($1)`, [draft.id]), /PT422.*required/);
  await rejects(as(ids.a, `select * from save_submission($1, 'CLINICAL_CASE', $2::jsonb, 99)`, [draft.id, JSON.stringify(data)]), /PT409.*CONFLICT/);
  const saved = await one(ids.a, `select * from save_submission($1, 'CLINICAL_CASE', $2::jsonb, $3)`, [draft.id, JSON.stringify(data), draft.version]);
  assert.deepEqual(saved.competency_codes.sort(), ['PG-PED-01', 'PG-PED-02']);
  const sub = await one(ids.a, `select * from submit_submission($1)`, [draft.id]);
  assert.equal(sub.status, 'SUBMITTED');
  assert.equal(sub.submitted_by_name, 'Asha Student');
  await rejects(as(ids.a, `select * from save_submission($1, 'CLINICAL_CASE', $2::jsonb)`, [draft.id, JSON.stringify(data)]), /PT409.*locked/);
  await rejects(as(ids.f3, `select * from review_submission($1, 'VERIFY')`, [draft.id]), /PT403/);
  await rejects(as(ids.f1, `select * from review_submission($1, 'RETURN')`, [draft.id]), /PT422.*reason/);
  const notif = await as(ids.f1, `select * from notifications where entity_id = $1`, [draft.id]);
  assert.ok(notif.length >= 1, 'assigned faculty notified');
  const v = await one(ids.f1, `select * from review_submission($1, 'VERIFY', '{"faculty_feedback":"Good"}'::jsonb)`, [draft.id]);
  assert.equal(v.status, 'VERIFIED');
  assert.equal(v.reviewed_by_name, 'Dr Fiona One');
  assert.equal(v.reviewed_by_designation, 'Associate Professor');
  ids.clinical = draft.id;
  const fb = await as(ids.a, `select * from faculty_feedback where entity_id = $1`, [draft.id]);
  assert.equal(fb[0].faculty_name, 'Dr Fiona One');
  await rejects(as(ids.a, `select delete_draft($1)`, [draft.id]), /PT403/);
});

await test('Field-level RBAC: student cannot write faculty fields, faculty cannot write student fields', async () => {
  await rejects(as(ids.a, `select * from save_submission(null, 'CLINICAL_CASE', '{"faculty_feedback":"self"}'::jsonb)`), /PT403/);
  const d = await one(ids.a, `select * from save_submission(null, 'TEACHING', '{"topic":"Fever"}'::jsonb)`);
  await as(ids.a, `select delete_draft($1)`, [d.id]);
  assert.equal((await as(ids.a, `select * from submissions where id = $1`, [d.id])).length, 0);
});

await test('Faculty reference must be an assigned faculty member', async () => {
  await rejects(as(ids.a, `select * from save_submission(null, 'PROCEDURE', $1::jsonb)`, [JSON.stringify({ faculty: ids.f3 })]), /PT422.*assigned/);
});

await test('Returned record can be corrected and resubmitted; audit trail kept', async () => {
  const d = await one(ids.a, `select * from save_submission(null, 'ACADEMIC_ACTIVITY', $1::jsonb)`, [JSON.stringify({
    date: '2025-02-01', activity_type: 'SEMINAR', topic: 'Neonatal sepsis', presenter: 'Self', faculty: ids.f2, learning_objectives: 'Antibiotic stewardship' })]);
  const tpl = (await db.query(`select schema from form_templates where code = 'ACADEMIC_ACTIVITY'`)).rows[0].schema;
  const full = {};
  for (const f of tpl.fields.filter((x) => x.owner === 'STUDENT')) {
    if (f.type === 'FACULTY') full[f.key] = ids.f2;
    else if (f.type === 'SELECT') full[f.key] = f.options[0].value;
    else if (f.type === 'DATE') full[f.key] = '2025-02-01';
    else if (f.type !== 'COMPETENCIES' && f.type !== 'ATTACHMENT') full[f.key] = 'Text';
  }
  full.activity_type = 'SEMINAR';
  await as(ids.a, `select * from save_submission($1, 'ACADEMIC_ACTIVITY', $2::jsonb)`, [d.id, JSON.stringify(full)]);
  await as(ids.a, `select * from submit_submission($1)`, [d.id]);
  const r = await one(ids.f2, `select * from review_submission($1, 'RETURN', '{}'::jsonb, 'Add key learning detail')`, [d.id]);
  assert.equal(r.status, 'RETURNED');
  assert.equal(r.return_reason, 'Add key learning detail');
  await as(ids.a, `select * from save_submission($1, 'ACADEMIC_ACTIVITY', $2::jsonb)`, [d.id, JSON.stringify({ ...full, topic: 'Neonatal sepsis – revised' })]);
  const rs = await one(ids.a, `select * from submit_submission($1)`, [d.id]);
  assert.equal(rs.status, 'SUBMITTED');
  assert.equal(rs.return_reason, null);
  const audit = await as(ids.a, `select action from audit_logs where entity_id = $1 order by id`, [d.id]);
  const actions = audit.map((x) => x.action);
  for (const a of ['SAVE_DRAFT', 'SUBMIT', 'RETURN', 'RESUBMIT']) assert.ok(actions.includes(a), `audit has ${a}: ${actions}`);
  await as(ids.f2, `select * from review_submission($1, 'VERIFY', '{"faculty_feedback":"Good"}'::jsonb)`, [d.id]);
});

await test('Audit log is immutable even for the database owner path', async () => {
  await rejects(db.query(`update audit_logs set action = 'X'`), /immutable/);
});

console.log('Reflection');
const words = (n) => Array.from({ length: n }, (_, i) => `word${i}`).join(' ');
await test('Reflection sections require 50–200 words', async () => {
  const base = { date: '2025-03-01', clinical_situation: 'Delayed escalation', what_happened: words(49), what_learned: words(50), do_differently: words(200) };
  const d = await one(ids.a, `select * from save_submission(null, 'REFLECTION', $1::jsonb)`, [JSON.stringify(base)]);
  await rejects(as(ids.a, `select * from submit_submission($1)`, [d.id]), /at least 50 words/);
  await as(ids.a, `select * from save_submission($1, 'REFLECTION', $2::jsonb)`, [d.id, JSON.stringify({ ...base, what_happened: words(201) })]);
  await rejects(as(ids.a, `select * from submit_submission($1)`, [d.id]), /at most 200 words/);
  await as(ids.a, `select * from save_submission($1, 'REFLECTION', $2::jsonb)`, [d.id, JSON.stringify({ ...base, what_happened: words(50) })]);
  assert.equal((await one(ids.a, `select * from submit_submission($1)`, [d.id])).status, 'SUBMITTED');
  await rejects(as(ids.f1, `select * from review_submission($1, 'VERIFY')`, [d.id]), /Faculty Feedback is required/);
  assert.equal((await one(ids.f1, `select * from review_submission($1, 'VERIFY', '{"faculty_feedback":"Insightful"}'::jsonb)`, [d.id])).status, 'VERIFIED');
});

console.log('Mini-CEX / DOPS');
await test('Only the selected assessor (or HOD) can assess; ratings 1–9 enforced', async () => {
  const slot = await one(ids.a, `select id, version from submissions where template_code = 'MINI_CEX' and slot_no = 1`);
  await as(ids.a, `select * from save_submission($1, 'MINI_CEX', $2::jsonb)`, [slot.id, JSON.stringify({
    date: '2025-01-20', unit: 'Unit 1', patient_problem: 'Fever with rash', assessor: ids.f1 })]);
  const s = await one(ids.a, `select * from submit_submission($1)`, [slot.id]);
  assert.equal(s.assessor_id, ids.f1);
  assert.equal(s.slot_no, 1);
  await rejects(as(ids.f2, `select * from review_submission($1, 'VERIFY')`, [slot.id]), /selected assessor/);
  const ratings = { history_taking: 6, examination: 7, clinical_reasoning: 6, communication: 8, management_plan: 6, professionalism: 9 };
  const tpl = (await db.query(`select schema from form_templates where code = 'MINI_CEX'`)).rows[0].schema;
  const fac = {};
  for (const f of tpl.fields.filter((x) => x.owner === 'FACULTY')) {
    fac[f.key] = f.type === 'RATING_9' ? String(ratings[f.key] ?? 5) : f.type === 'BOOLEAN' ? 'false' : f.type === 'SELECT' ? f.options[0].value : 'Feedback';
  }
  await rejects(as(ids.f1, `select * from review_submission($1, 'VERIFY', $2::jsonb)`, [slot.id, JSON.stringify({ ...fac, examination: '10' })]), /between 1 and 9/);
  const v = await one(ids.f1, `select * from review_submission($1, 'VERIFY', $2::jsonb)`, [slot.id, JSON.stringify(fac)]);
  assert.equal(v.status, 'VERIFIED');
  const typed = await one(ids.a, `select history_taking, examination, remedial_required from mini_cex_submissions where id = $1`, [slot.id]);
  assert.equal(typed.examination, 7);
  assert.equal(typed.remedial_required, false);
});

console.log('Monthly assessments');
await test('Future months are locked; submit → faculty → HOD/mentor → completed', async () => {
  await rejects(as(ids.a, `select * from save_submission(null, 'MONTHLY', '{"posting_unit":"Ward"}'::jsonb, null, 20)`), /PT403.*opens on/);
  const tpl = (await db.query(`select schema from form_templates where code = 'MONTHLY'`)).rows[0].schema;
  const sd = {};
  const fd = {};
  const hd = {};
  for (const f of tpl.fields) {
    const v = f.type === 'RATING_9' ? '7' : f.type === 'BME' ? 'M' : f.type === 'BOOLEAN' ? 'false' : f.type === 'SELECT' ? f.options[0].value : f.type === 'DATE' ? '2025-01-31' : 'Text';
    if (f.type === 'ATTACHMENT' || f.type === 'COMPETENCIES') continue;
    if (f.owner === 'STUDENT') sd[f.key] = f.type === 'FACULTY' ? ids.f1 : v;
    if (f.owner === 'FACULTY') fd[f.key] = v;
    if (f.owner === 'HOD') hd[f.key] = v;
  }
  const m1 = await one(ids.a, `select * from save_submission(null, 'MONTHLY', $1::jsonb, null, 1)`, [JSON.stringify(sd)]);
  assert.equal(m1.period_no, 1);
  const s = await one(ids.a, `select * from submit_submission($1)`, [m1.id]);
  assert.equal(s.status, 'FACULTY_REVIEW_PENDING');
  await rejects(as(ids.f1, `select * from hod_review_submission($1, $2::jsonb)`, [m1.id, JSON.stringify(hd)]), /PT409|PT403/);
  const f = await one(ids.f2, `select * from review_submission($1, 'VERIFY', $2::jsonb)`, [m1.id, JSON.stringify(fd)]);
  assert.equal(f.status, 'HOD_REVIEW_PENDING');
  await rejects(as(ids.f2, `select * from hod_review_submission($1, $2::jsonb)`, [m1.id, JSON.stringify(hd)]), /PT403/);
  const h = await one(ids.f1, `select * from hod_review_submission($1, $2::jsonb)`, [m1.id, JSON.stringify(hd)]);
  assert.equal(h.status, 'COMPLETED');
  assert.equal(h.hod_reviewed_by_name, 'Dr Fiona One', JSON.stringify(h));
  const typed = await one(ids.a, `select month_start, month_end, pg_year from monthly_assessments where id = $1`, [m1.id]);
  assert.equal(typed.pg_year, 1, JSON.stringify(typed));
  assert.equal(String(typed.month_start instanceof Date ? typed.month_start.toISOString().slice(0, 10) : typed.month_start), startA);
  const rc = await one(HOD, `select * from reopen_submission($1, 'Attendance figure incorrect')`, [m1.id]);
  assert.equal(rc.status, 'RETURNED');
});

console.log('Annual review');
await test('Faculty-only, per-domain attribution, correction requires reason, student sees after completion', async () => {
  await rejects(as(ids.a, `select * from save_annual_review($1, 1, '{}'::jsonb)`, [ids.a]), /PT403/);
  await rejects(as(ids.f3, `select * from save_annual_review($1, 1, '{"clinical_knowledge_rating":"M"}'::jsonb)`, [ids.a]), /PT403/);
  const r1 = await one(ids.f1, `select * from save_annual_review($1, 1, $2::jsonb)`, [ids.a, JSON.stringify({ clinical_knowledge_rating: 'E', clinical_knowledge_comments: 'Strong' })]);
  assert.equal(r1.status, 'DRAFT');
  assert.equal(r1.faculty_data.clinical_knowledge_faculty_name, 'Dr Fiona One');
  await rejects(as(ids.f2, `select * from save_annual_review($1, 1, $2::jsonb)`, [ids.a, JSON.stringify({ clinical_knowledge_rating: 'B' })]), /another faculty member/);
  await rejects(as(ids.f2, `select * from save_annual_review($1, 1, $2::jsonb, true)`, [ids.a, JSON.stringify({ neonatal_care_rating: 'M' })]), /PT422/);
  const tpl = (await db.query(`select schema from form_templates where code = 'ANNUAL_REVIEW'`)).rows[0].schema;
  const all = {};
  for (const d of tpl.domains) {
    if (d.key === 'clinical_knowledge') continue;
    all[`${d.key}_rating`] = 'M';
    all[`${d.key}_comments`] = `${d.label} evidence`;
  }
  for (const f of tpl.fields.filter((x) => x.owner === 'FACULTY' && !x.group)) all[f.key] = 'Overall good progress';
  assert.equal((await as(ids.a, `select * from submissions where template_code = 'ANNUAL_REVIEW'`)).length, 0);
  const done = await one(ids.f2, `select * from save_annual_review($1, 1, $2::jsonb, true)`, [ids.a, JSON.stringify(all)]);
  assert.equal(done.status, 'COMPLETED');
  assert.equal(done.faculty_data.clinical_knowledge_faculty_name, 'Dr Fiona One');
  assert.equal(done.faculty_data.neonatal_care_faculty_name, 'Dr Farid Two');
  const seen = await as(ids.a, `select * from submissions where template_code = 'ANNUAL_REVIEW'`);
  assert.equal(seen.length, 1);
  await rejects(as(ids.f2, `select * from save_annual_review($1, 1, '{"neonatal_care_rating":"E"}'::jsonb)`, [ids.a]), /correction with a reason/);
  await rejects(as(ids.f1, `select * from save_annual_review($1, 1, '{"neonatal_care_rating":"E"}'::jsonb, false, 'typo')`, [ids.a]), /PT403/);
  const corr = await one(ids.f2, `select * from save_annual_review($1, 1, '{"neonatal_care_rating":"E"}'::jsonb, false, 'Rating entered incorrectly')`, [ids.a]);
  assert.equal(corr.faculty_data.neonatal_care_rating, 'E');
  const a = await as(HOD, `select reason from audit_logs where entity_id = $1 and action = 'CORRECTION'`, [corr.id]);
  assert.equal(a[0].reason, 'Rating entered incorrectly');
});

console.log('Targets');
await test('Target progress counts verified records once per target via template rules', async () => {
  const rows = await as(ids.a, `select target_key, recorded, submitted, verified from v_target_progress where student_id = $1`, [ids.a]);
  const by = Object.fromEntries(rows.map((r) => [r.target_key, r]));
  assert.equal(by.GENERAL_ADMISSIONS.verified, 1);
  assert.equal(by.SEMINARS.verified, 1);
  assert.equal(by.MINI_CEX.verified, 1);
  assert.equal(by.NICU_NEONATES.recorded, 0);
  assert.equal(rows.length, 34);
  const other = await as(ids.a, `select * from v_target_progress where student_id = $1`, [ids.b]);
  assert.equal(other.length, 0);
});

await test('HOD target change creates a new version', async () => {
  const t = await one(HOD, `select * from set_target('SEMINARS', 15, null, null, 'Revised by academic committee')`);
  assert.equal(t.version, 2);
  assert.equal((await one(ids.a, `select target_value from v_target_progress where student_id = $1 and target_key = 'SEMINARS'`, [ids.a])).target_value, 15);
  await rejects(as(ids.f1, `select * from set_target('SEMINARS', 1)`), /PT403/);
});

console.log('Programme start date');
await test('Only HOD can change start date, reason required and audited', async () => {
  await rejects(as(ids.a, `select * from set_programme_start($1, '2020-01-01', null, 'x')`, [ids.a]), /PT403/);
  await rejects(as(HOD, `select * from set_programme_start($1, '2024-01-01')`, [ids.b]), /reason/);
  const newStart = await sqlDate(`current_date - interval '3 months'`);
  await as(HOD, `select * from set_programme_start($1, $2, null, 'Joining date corrected per university letter')`, [ids.b, newStart]);
  const ov = await one(ids.b, `select current_month, programme_start_date::text s from v_student_overview where student_id = $1`, [ids.b]);
  assert.equal(ov.current_month, 4);
  const a = await as(HOD, `select reason, changes from audit_logs where action = 'PROGRAMME_START_CHANGE' and student_id = $1`, [ids.b]);
  assert.equal(a[0].reason, 'Joining date corrected per university letter');
});

console.log('Competency sign-off & certificate');
await test('Final approval requires independent stage; RETURN requires comment', async () => {
  await as(ids.a, `select * from request_signoff('PG-PED-01', 'Ready')`);
  const q = await as(ids.f1, `select * from v_review_queue where kind = 'SIGNOFF'`);
  assert.ok(q.some((x) => x.template_code === 'PG-PED-01'));
  await rejects(as(ids.f1, `select * from faculty_signoff($1, 'PG-PED-01', 'APPROVE')`, [ids.a]), /independent/);
  await rejects(as(ids.f1, `select * from faculty_signoff($1, 'PG-PED-01', 'RETURN')`, [ids.a]), /comment/);
  for (const st of ['OBSERVED', 'SUPERVISED', 'INDEPENDENT']) await as(ids.f1, `select * from faculty_signoff($1, 'PG-PED-01', $2)`, [ids.a, st]);
  const ap = await one(ids.f1, `select * from faculty_signoff($1, 'PG-PED-01', 'APPROVE', 'Competent')`, [ids.a]);
  assert.equal(ap.status, 'APPROVED');
  assert.equal(ap.final_by_name, 'Dr Fiona One');
  await rejects(as(ids.f1, `select * from faculty_signoff($1, 'PG-PED-01', 'RETURN', 'x')`, [ids.a]), /already approved/);
  await rejects(as(ids.f3, `select * from faculty_signoff($1, 'PG-PED-02', 'OBSERVED')`, [ids.a]), /PT403/);
});

await test('Certificate stays locked until all 22 approved AND programme complete', async () => {
  const st = await one(ids.a, `select certification_status() s`);
  assert.equal(st.s.status, 'NOT_ELIGIBLE');
  await rejects(as(HOD, `select * from issue_certificate($1)`, [ids.a]), /prerequisites/);
  const codes = (await db.query(`select code from competencies order by sort_order`)).rows.map((r) => r.code);
  for (const c of codes.slice(0, 21)) {
    await as(ids.f3, `select * from faculty_signoff($1, $2, 'INDEPENDENT')`, [ids.c, c]);
    await as(ids.f3, `select * from faculty_signoff($1, $2, 'APPROVE')`, [ids.c, c]);
  }
  const mid = await one(ids.c, `select certification_status() s`);
  assert.equal(mid.s.status, 'AWAITING_FINAL_SIGNOFF');
  assert.equal(mid.s.approved, 21);
  assert.equal((await as(ids.c, 'select * from certificates')).length, 0);
});

await test('Auto-issue on final approval with snapshot, number and no fabricated signatures', async () => {
  const codes = (await db.query(`select code from competencies order by sort_order`)).rows.map((r) => r.code);
  await as(ids.f3, `select * from faculty_signoff($1, $2, 'INDEPENDENT')`, [ids.c, codes[21]]);
  await as(ids.f3, `select * from faculty_signoff($1, $2, 'APPROVE')`, [ids.c, codes[21]]);
  const certs = await as(ids.c, 'select * from certificates');
  assert.equal(certs.length, 1);
  assert.match(certs[0].certificate_no, /^MDPED\/\d{4}\/\d{5}$/);
  assert.equal(certs[0].snapshot.student_name, 'Chitra Student');
  assert.equal(certs[0].snapshot.competencies_approved, 22);
  const sig = Object.fromEntries(certs[0].snapshot.signatories.map((s) => [s.role, s]));
  assert.equal(sig['Guide / Mentor'].name, 'Dr Faye Three');
  assert.equal(sig['Unit Head'].verification, null);
  assert.deepEqual(Object.keys(sig), ['Guide / Mentor', 'Unit Head', 'Head of Department', 'Dean / Principal']);
  assert.match(certs[0].snapshot.statement, /^Certified that the resident has maintained this logbook during postgraduate training and has undergone periodic assessment\./);
  const st = await one(ids.c, `select certification_status() s`);
  assert.equal(st.s.status, 'CERTIFICATE_ISSUED');
  await rejects(as(HOD, `select * from issue_certificate($1)`, [ids.c]), /already/);
  await rejects(as(HOD, `select * from faculty_signoff($1, $2, 'REVOKE', 'Error')`, [ids.c, codes[0]]), /Revoke the issued certificate/);
  await as(HOD, `select revoke_certificate($1, 'Re-verification')`, [ids.c]);
  const rv = await one(HOD, `select * from faculty_signoff($1, $2, 'REVOKE', 'Evidence missing')`, [ids.c, codes[0]]);
  assert.equal(rv.status, 'RETURNED');
});

console.log('Views, storage & misc');
await test('All typed entity views exist and respect RLS', async () => {
  for (const v of ['clinical_case_logs', 'neonatal_case_logs', 'picu_logs', 'procedure_logs', 'mini_cex_submissions', 'dops_submissions',
    'academic_activity_records', 'dissertation_milestones', 'teaching_competency_records', 'critical_incident_reflections',
    'monthly_assessments', 'annual_comprehensive_reviews']) {
    const rows = await as(ids.b, `select distinct student_id from public.${v}`);
    assert.ok(rows.every((r) => r.student_id === ids.b), v);
  }
  const cl = await as(ids.f1, `select diagnosis, setting from clinical_case_logs`);
  assert.equal(cl.length, 1);
});

await test('Review queue shows only actionable items for the reviewer', async () => {
  const d = await one(ids.b, `select * from save_submission(null, 'NEONATAL_CASE', '{}'::jsonb)`);
  assert.equal((await as(ids.f3, `select * from v_review_queue where id = $1`, [d.id])).length, 0);
  const q = await as(ids.f1, `select * from v_review_queue`);
  assert.ok(q.every((r) => r.student_id === ids.a));
});

await test('Evidence storage: students upload only to own editable records', async () => {
  const d = await one(ids.a, `select * from save_submission(null, 'PROCEDURE', '{"case_ref":"P-1"}'::jsonb)`);
  const path = `${ids.a}/${d.id}/consent.pdf`;
  await as(ids.a, `insert into storage.objects (bucket_id, name) values ('evidence', $1)`, [path]);
  await rejects(as(ids.a, `insert into storage.objects (bucket_id, name) values ('evidence', $1)`, [`${ids.b}/${d.id}/x.pdf`]), /row-level security/);
  await rejects(as(ids.a, `insert into storage.objects (bucket_id, name) values ('evidence', $1)`, [`${ids.a}/${ids.clinical}/x.pdf`]), /row-level security/);
  const att = await one(ids.a, `select * from register_attachment($1, $2, 'consent.pdf', 'application/pdf', 1000)`, [d.id, path]);
  assert.equal(att.student_id, ids.a);
  assert.equal((await as(ids.f1, `select * from storage.objects where name = $1`, [path])).length, 1);
  assert.equal((await as(ids.b, `select * from storage.objects where name = $1`, [path])).length, 0);
});

await test('Deactivated users lose access immediately', async () => {
  await as(HOD, `select set_user_active($1, false)`, [ids.f2]);
  assert.equal((await as(ids.f2, `select * from submissions`)).length, 0);
  await rejects(as(ids.f2, `select * from save_annual_review($1, 2, '{}'::jsonb)`, [ids.a]), /PT40[13]/);
  await as(HOD, `select set_user_active($1, true)`, [ids.f2]);
});

await test('Daily job creates de-duplicated reminders', async () => {
  await db.query('select run_daily_jobs()');
  await db.query('select run_daily_jobs()');
  const r = (await db.query(`select dedupe_key, count(*)::int n from notifications where dedupe_key is not null group by 1 having count(*) > 1`)).rows;
  assert.equal(r.length, 0);
});

await test('Settings: HOD-only, unknown keys rejected', async () => {
  await rejects(as(ids.f1, `select * from update_institution_settings('{"auto_issue_certificate":false}'::jsonb)`), /PT403/);
  await rejects(as(HOD, `select * from update_institution_settings('{"hack":1}'::jsonb)`), /Unknown setting/);
  const s = await one(HOD, `select * from update_institution_settings('{"institution_name":"Govt Medical College"}'::jsonb)`);
  assert.equal(s.institution_name, 'Govt Medical College');
});

await test('Bootstrap script provisions the first HOD on sign-up', async () => {
  await db.exec(readFileSync(join(here, '..', 'scripts', 'bootstrap_hod.sql'), 'utf8'));
  const id = await signUp('hod@your-college.edu');
  const p = await one(id, `select role, designation from profiles where id = auth.uid()`);
  assert.equal(p.role, 'HOD');
  assert.equal(p.designation, 'Professor & HOD');
  assert.equal((await one(id, `select institution_name from institution_settings`)).institution_name, 'Your Medical College');
});

console.log(`\n${passed} passed, ${failures.length} failed`);
if (failures.length) process.exit(1);
