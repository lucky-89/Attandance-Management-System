-- Plays a firm through the schema as real users would (role "authenticated",
-- row level security on) and prints PASS / FAIL per rule.
\set ON_ERROR_STOP off
\pset tuples_only on
\pset format unaligned

insert into auth.users values
  ('00000000-0000-0000-0000-00000000000a', 'asha@firm.in'),
  ('00000000-0000-0000-0000-00000000000b', 'bina@firm.in'),
  ('00000000-0000-0000-0000-00000000000c', 'ravi@firm.in'),
  ('00000000-0000-0000-0000-00000000000d', 'meera@firm.in'),
  ('00000000-0000-0000-0000-00000000000e', 'stranger@else.in');

create function t_run(who text, q text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000' || who, true);
  perform set_config('request.jwt.claim.email', (select email from auth.users where id = ('00000000-0000-0000-0000-00000000000' || who)::uuid), true);
  execute 'set local role authenticated';
  execute q;
end $$;
create function t_ok(label text, who text, q text) returns text language plpgsql as $$
begin perform t_run(who, q); return 'PASS  ' || label;
exception when others then return 'FAIL  ' || label || ' — ' || sqlerrm; end $$;
create function t_no(label text, who text, q text, pat text) returns text language plpgsql as $$
begin perform t_run(who, q); return 'FAIL  ' || label || ' — it was allowed';
exception when others then
  if sqlerrm ~* pat then return 'PASS  ' || label || ' (' || sqlerrm || ')'; end if;
  return 'FAIL  ' || label || ' — wrong error: ' || sqlerrm; end $$;
create function t_count(label text, who text, q text, want int) returns text language plpgsql as $$
declare n int;
begin
  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-00000000000' || who, true);
  execute 'set local role authenticated';
  execute 'select count(*) from (' || q || ') x' into n;
  return case when n = want then 'PASS  ' else 'FAIL  ' end || label || ' (saw ' || n || ', want ' || want || ')';
end $$;
grant execute on function t_run(text, text), t_ok(text, text, text), t_no(text, text, text, text), t_count(text, text, text, int) to authenticated;

-- An upsert, as PostgREST sends it.
create function up(st text, rid text, d jsonb) returns void language sql as $$
  insert into records (firm_id, store, id, data) values (my_firm(), st, rid, d)
  on conflict (firm_id, store, id) do update set data = excluded.data $$;
grant execute on function up(text, text, jsonb) to authenticated;
-- A plain insert (the app sends notifications and audit lines this way: no read-back needed).
create function ins(st text, rid text, d jsonb) returns void language sql as $$
  insert into records (firm_id, store, id, data) values (my_firm(), st, rid, d) $$;
grant execute on function ins(text, text, jsonb) to authenticated;

\echo '--- setting up the firm'
select t_ok('admin creates the firm', 'a', $q$select create_firm('Freedom LifeX', 'Asia/Kolkata', '{"id":"TM-A","name":"Asha"}',
  '{"day":{"punchInFrom":"00:00","punchOutUntil":"23:59"}}')$q$);
select t_no('admin cannot create a second firm', 'a', $q$select create_firm('Again', 'UTC', '{}', '{}')$q$, 'already belongs');
select t_ok('admin adds Bina (branch head)', 'a', $q$select up('team','TM-B','{"id":"TM-B","name":"Bina","email":"BINA@firm.in","role":"Branch Head","branch":"Varanasi"}')$q$);
select t_ok('admin adds Ravi', 'a', $q$select up('team','TM-R','{"id":"TM-R","name":"Ravi","email":"ravi@firm.in","role":"Executive","branch":"Varanasi","parentUserId":"TM-B","passwordHash":"x"}')$q$);
select t_ok('admin adds Meera', 'a', $q$select up('team','TM-M','{"id":"TM-M","name":"Meera","email":"meera@firm.in","role":"Executive"}')$q$);
select case when (select data ? 'passwordHash' from records where id = 'TM-R') then 'FAIL  password field stripped' else 'PASS  password field stripped from team record' end;

\echo '--- joining'
select t_ok('Bina claims her record (email case ignored)', 'b', $q$select claim_membership()$q$);
select t_ok('Ravi claims his record', 'c', $q$select claim_membership()$q$);
select t_ok('Meera claims her record', 'd', $q$select claim_membership()$q$);
select case when (select employee_id from members where user_id = '00000000-0000-0000-0000-00000000000c') = 'TM-R' then 'PASS  Ravi linked to TM-R' else 'FAIL  Ravi linked to TM-R' end;
select t_ok('a stranger claims nothing', 'e', $q$do $d$ begin if claim_membership() is not null then raise exception 'got a firm'; end if; end $d$$q$);
select t_count('the stranger reads nothing', 'e', $q$select * from records$q$, 0);
select t_no('the stranger writes nothing', 'e', format($q$insert into records (firm_id, store, id, data) values ('%s', 'notifications', 'X', '{"userId":"TM-R"}')$q$, (select id from firms limit 1)), 'row-level security');
select t_no('Ravi cannot change the team', 'c', $q$select up('team','TM-R','{"id":"TM-R","name":"Ravi","email":"ravi@firm.in","role":"Admin"}')$q$, 'row-level security');
select t_no('Ravi cannot change the config', 'c', $q$select up('config','hrConfig','{}')$q$, 'row-level security');

\echo '--- attendance'
select t_ok('Ravi punches in now', 'c', format($q$select up('hrAttendance','HRA-1', jsonb_build_object('id','HRA-1','userId','TM-R','date','%s',
  'sessions', jsonb_build_array(jsonb_build_object('in', now()::text, 'out', null, 'inBy','TM-R'))))$q$, to_char(now() at time zone 'Asia/Kolkata','YYYY-MM-DD')));
select t_no('Ravi cannot punch in for Meera', 'c', format($q$select up('hrAttendance','HRA-2', jsonb_build_object('id','HRA-2','userId','TM-M','date','%s',
  'sessions', jsonb_build_array(jsonb_build_object('in', now()::text))))$q$, to_char(now() at time zone 'Asia/Kolkata','YYYY-MM-DD')), 'row-level security');
select t_no('a backdated punch in is refused', 'd', format($q$select up('hrAttendance','HRA-3', jsonb_build_object('id','HRA-3','userId','TM-M','date','%s',
  'sessions', jsonb_build_array(jsonb_build_object('in', (now() - interval '2 hours')::text))))$q$, to_char(now() at time zone 'Asia/Kolkata','YYYY-MM-DD')), 'time it was made');
select t_no('a punch in for another day is refused', 'd', format($q$select up('hrAttendance','HRA-4', jsonb_build_object('id','HRA-4','userId','TM-M','date','%s',
  'sessions', jsonb_build_array(jsonb_build_object('in', now()::text))))$q$, to_char(now() at time zone 'Asia/Kolkata' - interval '3 days','YYYY-MM-DD')), 'today only');
select t_no('a second punch in is refused', 'c', $q$select up('hrAttendance','HRA-1', (select data || jsonb_build_object('sessions', data->'sessions' || jsonb_build_array(jsonb_build_object('in', now()::text))) from records where id='HRA-1'))$q$, 'once a day');
select t_no('a recorded punch in cannot be changed', 'c', $q$select up('hrAttendance','HRA-1', (select jsonb_set(data, '{sessions,0,in}', to_jsonb((now() - interval '1 hour')::text)) from records where id='HRA-1'))$q$, 'append-only');
select t_no('a session cannot be dropped', 'c', $q$select up('hrAttendance','HRA-1', (select data || '{"sessions":[]}' from records where id='HRA-1'))$q$, 'dropped');
select t_ok('Ravi punches out now', 'c', $q$select up('hrAttendance','HRA-1', (select jsonb_set(data, '{sessions,0,out}', to_jsonb(now()::text)) from records where id='HRA-1'))$q$);
select t_no('punch out again without keeping the earlier one is refused', 'c', $q$select up('hrAttendance','HRA-1', (select jsonb_set(data, '{sessions,0,out}', to_jsonb((now() + interval '1 minute')::text)) from records where id='HRA-1'))$q$, 'earlier punch out');
select t_ok('punch out again, keeping the earlier one, is allowed', 'c', $q$select up('hrAttendance','HRA-1', (select jsonb_set(jsonb_set(data, '{sessions,0,outHistory}',
  jsonb_build_array(jsonb_build_object('out', data->'sessions'->0->>'out'))), '{sessions,0,out}', to_jsonb((now() + interval '1 minute')::text)) from records where id='HRA-1'))$q$);
select t_count('Meera cannot read Ravi''s attendance', 'd', $q$select * from records where store='hrAttendance'$q$, 0);
select t_count('the admin reads everybody''s attendance', 'a', $q$select * from records where store='hrAttendance'$q$, 1);
select t_count('Bina, his manager, reads Ravi''s attendance', 'b', $q$select * from records where store='hrAttendance'$q$, 1);

\echo '--- leave'
select t_ok('Ravi asks for leave (with Bina)', 'c', $q$select up('hrLeave','L1','{"id":"L1","userId":"TM-R","from":"2030-01-07","to":"2030-01-08","status":"Pending","approverId":"TM-B","category":"Paid"}')$q$);
select t_no('an overlapping request is refused', 'c', $q$select up('hrLeave','L2','{"id":"L2","userId":"TM-R","from":"2030-01-08","to":"2030-01-09","status":"Pending","approverId":"TM-B"}')$q$, 'overlap');
select t_no('a request cannot start Approved', 'c', $q$select up('hrLeave','L3','{"id":"L3","userId":"TM-R","from":"2030-02-01","to":"2030-02-01","status":"Approved","approverId":"TM-B"}')$q$, 'starts as Pending');
select t_no('Ravi cannot approve his own leave', 'c', $q$select up('hrLeave','L1', (select data || '{"status":"Approved"}' from records where id='L1'))$q$, 'not yours');
select t_ok('Ravi names himself approver on a new request…', 'c', $q$select up('hrLeave','L4','{"id":"L4","userId":"TM-R","from":"2030-03-01","to":"2030-03-01","status":"Pending","approverId":"TM-R"}')$q$);
select t_no('…and still cannot approve it', 'c', $q$select up('hrLeave','L4', (select data || '{"status":"Approved"}' from records where id='L4'))$q$, 'own leave');
select t_no('Ravi cannot edit a request in place', 'c', $q$select up('hrLeave','L1', (select data || '{"to":"2030-01-20"}' from records where id='L1'))$q$, 'cannot be edited');
select t_no('Meera cannot decide Ravi''s leave', 'd', $q$select up('hrLeave','L1','{"id":"L1","userId":"TM-R","from":"2030-01-07","to":"2030-01-08","status":"Approved","approverId":"TM-B"}')$q$, 'row-level security');
select t_no('Bina cannot decline without a reason', 'b', $q$select up('hrLeave','L1', (select data || '{"status":"Declined"}' from records where id='L1'))$q$, 'needs a reason');
select t_ok('Bina approves it', 'b', $q$select up('hrLeave','L1', (select data || '{"status":"Approved","decidedBy":"TM-B"}' from records where id='L1'))$q$);
select t_no('a decided request cannot be decided again', 'b', $q$select up('hrLeave','L1', (select data || '{"status":"Declined","remarks":"x"}' from records where id='L1'))$q$, 'already decided');
select t_ok('Ravi cancels one of the two days', 'c', $q$select up('hrLeave','L1', (select data || '{"status":"Partially Cancelled","cancellation":{"dates":["2030-01-08"],"keep":["2030-01-07"],"reason":"plans"}}' from records where id='L1'))$q$);
select t_ok('the kept day becomes its own request', 'c', $q$select up('hrLeave','L5','{"id":"L5","userId":"TM-R","from":"2030-01-07","to":"2030-01-07","status":"Approved","approverId":"TM-B","remainderOf":"L1"}')$q$);
select t_no('a remainder of an uncancelled leave is refused', 'c', $q$select up('hrLeave','L6','{"id":"L6","userId":"TM-R","from":"2030-05-01","to":"2030-05-01","status":"Approved","remainderOf":"L4"}')$q$, 'Kept dates');
select t_count('Meera cannot read Ravi''s leave', 'd', $q$select * from records where store='hrLeave'$q$, 0);

\echo '--- comp-off'
select t_ok('Ravi''s extra day is recorded, Pending with Bina', 'c', $q$select up('hrCompOff','C1','{"id":"C1","userId":"TM-R","date":"2030-01-06","credit":1,"status":"Pending","approverId":"TM-B"}')$q$);
select t_no('a second record for the same day is refused', 'c', $q$select up('hrCompOff','C2','{"id":"C2","userId":"TM-R","date":"2030-01-06","credit":1,"status":"Pending","approverId":"TM-B"}')$q$, 'only once');
select t_no('Ravi cannot approve his own comp-off', 'c', $q$select up('hrCompOff','C1', (select data || '{"status":"Approved"}' from records where id='C1'))$q$, 'own comp-off');
select t_ok('Bina approves it', 'b', $q$select up('hrCompOff','C1', (select data || '{"status":"Approved"}' from records where id='C1'))$q$);
select t_no('the credit of a decided comp-off cannot change', 'a', $q$select up('hrCompOff','C1', (select data || '{"credit":3}' from records where id='C1'))$q$, 'cannot be changed');

\echo '--- regularization'
select t_ok('Meera asks HR to regularize', 'd', $q$select up('hrRegularizations','G1','{"id":"G1","userId":"TM-M","date":"2030-01-06","status":"Pending","periods":[{"from":"13:00","to":"18:00"}]}')$q$);
select t_no('Meera cannot approve her own', 'd', $q$select up('hrRegularizations','G1', (select data || '{"status":"Approved"}' from records where id='G1'))$q$, 'own regularization');
select t_no('Ravi (not HR) cannot approve it', 'c', $q$select up('hrRegularizations','G1','{"id":"G1","userId":"TM-M","date":"2030-01-06","status":"Approved"}')$q$, 'row-level security');
select t_ok('the admin approves it', 'a', $q$select up('hrRegularizations','G1', (select data || '{"status":"Approved"}' from records where id='G1'))$q$);

\echo '--- notifications, audit, incentive, appraisal'
select t_ok('Ravi notifies Bina', 'c', $q$select ins('notifications','N1','{"id":"N1","userId":"TM-B","title":"Leave request"}')$q$);
select t_count('Ravi cannot read Bina''s notifications', 'c', $q$select * from records where store='notifications'$q$, 0);
select t_count('Bina reads hers', 'b', $q$select * from records where store='notifications'$q$, 1);
select t_ok('Bina marks hers read', 'b', $q$select up('notifications','N1', (select data || '{"read":true}' from records where id='N1'))$q$);
select t_ok('anyone writes the audit log', 'c', $q$select ins('auditLog','A1','{"id":"A1","action":"Punched In"}')$q$);
select t_count('only HR reads the audit log', 'c', $q$select * from records where store='auditLog'$q$, 0);
select t_no('the audit log cannot be rewritten', 'a', $q$select up('auditLog','A1','{"id":"A1","action":"changed"}')$q$, 'row-level security');
select t_no('Ravi cannot write incentive sheets', 'c', $q$select up('hrIncentive','I1','{"id":"I1","userId":"TM-R","month":"2030-01"}')$q$, 'row-level security');
select t_ok('Bina, his manager, writes Ravi''s appraisal', 'b', $q$select up('hrAppraisals','P1','{"id":"P1","userId":"TM-R","year":"2030"}')$q$);
select t_no('Meera cannot write Ravi''s appraisal', 'd', $q$select up('hrAppraisals','P2','{"id":"P2","userId":"TM-R","year":"2031"}')$q$, 'row-level security');

\echo '--- administrators'
select t_no('the only admin cannot demote herself', 'a', $q$select up('team','TM-A', (select data || '{"role":"Executive"}' from records where id='TM-A'))$q$, 'only administrator');
select t_ok('the admin deactivates Meera', 'a', $q$select up('team','TM-M', (select data || '{"status":"Inactive"}' from records where id='TM-M'))$q$);
select t_no('a deactivated person is refused at sign in', 'd', $q$select claim_membership()$q$, 'deactivated');
