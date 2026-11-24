// Supabase Edge Function: invite-user
//
// Called by the app right after the HOD creates a row via the `create_invitation` RPC.
// It verifies that the caller is an active HOD, loads the PENDING invitation and sends the
// Supabase Auth invitation email. When the invitee accepts and sets a password, the
// `on_auth_user_created` trigger provisions their profile, enrolment and guide assignment.
//
// Deploy: supabase functions deploy invite-user
// Secrets: SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY are injected automatically.
import { createClient } from 'npm:@supabase/supabase-js@2';

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const reply = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, 'Content-Type': 'application/json' } });

const ALLOWED_REDIRECT = /^mdpeds:\/\/auth-callback(\/.*)?$/;

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return reply(405, { message: 'Method not allowed' });

  const url = Deno.env.get('SUPABASE_URL');
  const anon = Deno.env.get('SUPABASE_ANON_KEY');
  const service = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!url || !anon || !service) return reply(500, { message: 'Function is not configured' });

  const authHeader = req.headers.get('Authorization') ?? '';
  if (!authHeader.startsWith('Bearer ')) return reply(401, { message: 'Not signed in' });

  let payload: { invitation_id?: string; redirect_to?: string };
  try {
    payload = await req.json();
  } catch {
    return reply(400, { message: 'Invalid JSON body' });
  }
  const invitationId = payload.invitation_id;
  if (!invitationId || !/^[0-9a-f-]{36}$/i.test(invitationId)) return reply(422, { message: 'invitation_id is required' });
  const redirectTo = payload.redirect_to && ALLOWED_REDIRECT.test(payload.redirect_to) ? payload.redirect_to : 'mdpeds://auth-callback';

  // Identify the caller with their own JWT (RLS applies), then confirm they are an active HOD.
  const asCaller = createClient(url, anon, { global: { headers: { Authorization: authHeader } }, auth: { persistSession: false } });
  const { data: userData, error: userError } = await asCaller.auth.getUser();
  if (userError || !userData?.user) return reply(401, { message: 'Session expired. Please sign in again.' });

  const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: profile } = await admin.from('profiles').select('role, active').eq('id', userData.user.id).maybeSingle();
  if (!profile || profile.role !== 'HOD' || !profile.active) return reply(403, { message: 'Only the HOD can send invitations' });

  const { data: inv, error: invError } = await admin
    .from('invitations')
    .select('id, email, full_name, role, status')
    .eq('id', invitationId)
    .maybeSingle();
  if (invError) return reply(500, { message: 'Could not load the invitation' });
  if (!inv) return reply(404, { message: 'Invitation not found' });
  if (inv.status !== 'PENDING') return reply(409, { message: `Invitation is ${inv.status.toLowerCase()}` });

  const { error: sendError } = await admin.auth.admin.inviteUserByEmail(inv.email, {
    redirectTo,
    data: { full_name: inv.full_name, role: inv.role },
  });
  if (sendError) {
    const already = /already/i.test(sendError.message);
    return reply(already ? 409 : 502, { message: already ? 'An account with this email already exists' : `Invitation email failed: ${sendError.message}` });
  }
  return reply(200, { ok: true, invitation_id: inv.id });
});
