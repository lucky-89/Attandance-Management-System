-- =====================================================================
-- Finowise HR — Supabase schema
--
-- Paste this whole file into Supabase › SQL Editor › New query › Run.
-- It is safe to run again (it drops and recreates only its own objects;
-- the data tables are created only if missing).
--
-- One table, `records`, holds every HR store the app keeps — team,
-- config, hrAttendance, hrLeave, hrCompOff, hrRegularizations,
-- hrIncentive, hrAppraisals, notifications, auditLog — as JSON in the
-- same shapes the BOSS web app uses. The app works out the HR rules;
-- the database enforces the ones that must not be bypassable, exactly as
-- the BOSS server did:
--   * who may read and write which rows (row level security);
--   * attendance is append-only, and a punch is stamped by the SERVER's
--     clock, inside the punch window, not on a full-day approved leave;
--   * one active leave per person per date; only the approver or HR
--     decides; a requester can only ask and cancel;
--   * one comp-off per person per day; a decided one never changes;
--   * nobody approves their own comp-off or regularization.
-- =====================================================================

create extension if not exists pgcrypto;

-- ---------- tables ----------
create table if not exists public.firms (
  id         uuid primary key default gen_random_uuid(),
  name       text not null,
  timezone   text not null default 'Asia/Kolkata',
  created_at timestamptz not null default now()
);

create table if not exists public.members (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  firm_id     uuid not null references public.firms(id) on delete cascade,
  employee_id text not null,
  created_at  timestamptz not null default now(),
  unique (firm_id, employee_id)
);

create table if not exists public.records (
  firm_id    uuid not null references public.firms(id) on delete cascade,
  store      text not null check (store in ('team','config','hrAttendance','hrLeave','hrCompOff',
               'hrRegularizations','hrIncentive','hrAppraisals','notifications','auditLog')),
  id         text not null,
  user_id    text not null default '',
  data       jsonb not null,
  updated_at timestamptz not null default now(),
  updated_by text not null default '',
  primary key (firm_id, store, id)
);
create index if not exists records_firm_store_user on public.records (firm_id, store, user_id);

-- ---------- who is asking ----------
create or replace function public.my_firm() returns uuid
  language sql stable security definer set search_path = public as
  $$ select firm_id from members where user_id = auth.uid() $$;

create or replace function public.my_emp() returns text
  language sql stable security definer set search_path = public as
  $$ select employee_id from members where user_id = auth.uid() $$;

create or replace function public.team_of(emp text) returns jsonb
  language sql stable security definer set search_path = public as
  $$ select data from records where firm_id = my_firm() and store = 'team' and id = emp $$;

/* HR grants, read exactly as the app reads them: Admin holds all five;
   otherwise hrAccess.<grant> decides, and unset means the default
   (Attendance on, the rest off). */
create or replace function public.has_grant(t jsonb, g text) returns boolean
  language sql immutable as $$
  select case
    when t is null then false
    when lower(coalesce(t->>'role','')) = 'admin' then true
    when t->'hrAccess' ? g then coalesce((t->'hrAccess'->>g)::numeric, 0) <> 0
    else g = 'attendance'
  end $$;

create or replace function public.is_active(t jsonb) returns boolean
  language sql immutable as
  $$ select t is not null and (coalesce(t->>'status','') = '' or lower(t->>'status') = 'active') $$;

create or replace function public.is_hr_admin() returns boolean
  language sql stable security definer set search_path = public as
  $$ select has_grant(team_of(my_emp()), 'admin') $$;

create or replace function public.is_staff() returns boolean
  language sql stable security definer set search_path = public as
  $$ select has_grant(team_of(my_emp()), 'admin') or has_grant(team_of(my_emp()), 'staff') $$;

/* Somebody who has been asked to decide something of this person's, or
   is their reporting manager, may read what that decision needs. */
create or replace function public.i_approve_for(emp text) returns boolean
  language sql stable security definer set search_path = public as $$
  select exists (select 1 from records
                  where firm_id = my_firm() and store in ('hrLeave','hrCompOff')
                    and user_id = emp and data->>'approverId' = my_emp())
      or coalesce(team_of(emp)->>'parentUserId', '') = my_emp() $$;

-- ---------- row level security ----------
alter table public.firms   enable row level security;
alter table public.members enable row level security;
alter table public.records enable row level security;

drop policy if exists firms_read on public.firms;
create policy firms_read on public.firms for select using (id = my_firm());

drop policy if exists members_read on public.members;
create policy members_read on public.members for select using (firm_id = my_firm());

drop policy if exists records_read on public.records;
create policy records_read on public.records for select using (
  firm_id = my_firm() and case store
    when 'team'          then true
    when 'config'        then true
    when 'notifications' then user_id = my_emp()
    when 'auditLog'      then is_staff()
    when 'hrIncentive'   then user_id = my_emp() or is_staff()
    when 'hrAppraisals'  then user_id = my_emp() or is_staff() or i_approve_for(user_id)
    when 'hrLeave'       then user_id = my_emp() or is_staff() or i_approve_for(user_id)
                              or data->>'handoverTo' = my_emp()
    else user_id = my_emp() or is_staff() or i_approve_for(user_id)
  end);

create or replace function public.may_write(p_store text, p_user text, p_data jsonb) returns boolean
  language sql stable security definer set search_path = public as $$
  select case p_store
    when 'team'              then is_hr_admin()
    when 'config'            then is_hr_admin()
    when 'hrAttendance'      then p_user = my_emp()
    when 'hrLeave'           then p_user = my_emp() or is_hr_admin() or p_data->>'approverId' = my_emp()
    when 'hrCompOff'         then p_user = my_emp() or is_hr_admin() or p_data->>'approverId' = my_emp()
    when 'hrRegularizations' then p_user = my_emp() or is_staff() or i_approve_for(p_user)
    when 'hrIncentive'       then is_hr_admin()
    when 'hrAppraisals'      then is_hr_admin() or coalesce(team_of(p_user)->>'parentUserId','') = my_emp()
    when 'notifications'     then true
    when 'auditLog'          then true
    else false
  end $$;

drop policy if exists records_insert on public.records;
create policy records_insert on public.records for insert
  with check (firm_id = my_firm() and may_write(store, user_id, data));

drop policy if exists records_update on public.records;
create policy records_update on public.records for update
  using (firm_id = my_firm() and (case when store = 'notifications' then user_id = my_emp()
                                       when store = 'auditLog' then false
                                       else may_write(store, user_id, data) end))
  with check (firm_id = my_firm() and may_write(store, user_id, data));
-- No delete policy: nothing is ever deleted through the app.

-- ---------- the guards ----------
create or replace function public.hhmm_mins(t text) returns int language sql immutable as
  $$ select split_part(t, ':', 1)::int * 60 + split_part(t, ':', 2)::int $$;

/* The server's "now", in the firm's own time zone. */
create or replace function public.firm_now() returns timestamp
  language sql stable security definer set search_path = public as
  $$ select now() at time zone coalesce((select timezone from firms where id = my_firm()), 'Asia/Kolkata') $$;

create or replace function public.guard_records() returns trigger
  language plpgsql security definer set search_path = public as $$
declare
  old_row  records;
  is_new   boolean;
  cfg      jsonb;
  me       text := my_emp();
  admin    boolean := is_hr_admin();
  o_s      jsonb; n_s jsonb; a jsonb; b jsonb; i int;
  w_from   int; w_until int; now_m int; today text;
  stamp    timestamptz;
  dates    text[]; kept text[];
  st_old   text; st_new text;
begin
  -- Rows carry their owner; the client cannot say otherwise.
  new.user_id := case
    when new.store = 'team' then new.id
    when new.store in ('config','auditLog') then ''
    else coalesce(new.data->>'userId', '') end;
  new.updated_at := now();
  new.updated_by := coalesce(me, '');

  if tg_op = 'UPDATE' then old_row := old;
  else select * into old_row from records where firm_id = new.firm_id and store = new.store and id = new.id;
  end if;
  is_new := old_row.id is null;
  -- An upsert of an existing row fires the INSERT trigger first; the UPDATE trigger judges it.
  if tg_op = 'INSERT' and not is_new then return new; end if;

  if not is_new and old_row.user_id <> new.user_id and new.store <> 'team' then
    raise exception 'A record cannot be moved to another person.';
  end if;

  select data into cfg from records where firm_id = new.firm_id and store = 'config' and id = 'hrConfig';
  cfg := coalesce(cfg, '{}'::jsonb);

  -- ===== attendance: append-only, server clock, punch window =====
  if new.store = 'hrAttendance' then
    o_s := coalesce(old_row.data->'sessions', '[]'::jsonb);
    n_s := coalesce(new.data->'sessions', '[]'::jsonb);
    if not is_new and (new.data->>'date' is distinct from old_row.data->>'date') then
      raise exception 'Attendance cannot move to another date.';
    end if;
    if jsonb_array_length(n_s) < jsonb_array_length(o_s) then raise exception 'Attendance is append-only: a session was dropped.'; end if;
    if jsonb_array_length(n_s) > 1 and jsonb_array_length(n_s) > jsonb_array_length(o_s) then
      raise exception 'You already punched in today. Punch In works once a day.';
    end if;
    w_from  := hhmm_mins(coalesce(nullif(cfg->'day'->>'punchInFrom', ''), '08:45'));
    w_until := hhmm_mins(coalesce(nullif(cfg->'day'->>'punchOutUntil', ''), '20:45'));
    now_m   := extract(hour from firm_now())::int * 60 + extract(minute from firm_now())::int;
    today   := to_char(firm_now(), 'YYYY-MM-DD');
    -- Earlier sessions keep what they recorded.
    for i in 0 .. jsonb_array_length(o_s) - 1 loop
      a := o_s->i; b := n_s->i;
      if (a->'in') is distinct from (b->'in') or (a->'inBy') is distinct from (b->'inBy') or (a->'inLoc') is distinct from (b->'inLoc') then
        raise exception 'Attendance is append-only: a recorded punch in cannot change.';
      end if;
      if (a->>'out') is distinct from (b->>'out') then
        if a->>'out' is not null then
          -- A later punch out, the earlier one kept in outHistory.
          if (b->'outHistory'->-1->>'out') is distinct from (a->>'out')
             or jsonb_array_length(coalesce(b->'outHistory','[]')) <> jsonb_array_length(coalesce(a->'outHistory','[]')) + 1 then
            raise exception 'Attendance is append-only: an earlier punch out cannot change.';
          end if;
        end if;
        stamp := (b->>'out')::timestamptz;
        if abs(extract(epoch from (now() - stamp))) > 600 then raise exception 'A punch out has to carry the time it was made.'; end if;
        if new.data->>'date' = today and now_m >= w_until then
          raise exception 'Punch Out closed at %. Ask for a regularization if your last punch out is wrong.', to_char(make_time(w_until/60, w_until%60, 0), 'HH24:MI');
        end if;
      elsif (a->'outHistory') is distinct from (b->'outHistory') then
        raise exception 'Attendance is append-only: an earlier punch out cannot change.';
      end if;
    end loop;
    -- A new punch in: now, inside the window, not on a full-day approved leave.
    if jsonb_array_length(n_s) > jsonb_array_length(o_s) then
      b := n_s->-1;
      stamp := (b->>'in')::timestamptz;
      if stamp is null or abs(extract(epoch from (now() - stamp))) > 600 then raise exception 'A punch in has to carry the time it was made.'; end if;
      if new.data->>'date' <> today then raise exception 'A punch in is for today only.'; end if;
      if now_m < w_from then raise exception 'Punch In opens at %.', to_char(make_time(w_from/60, w_from%60, 0), 'HH24:MI'); end if;
      if now_m >= w_until then raise exception 'The punch clock closed at % today.', to_char(make_time(w_until/60, w_until%60, 0), 'HH24:MI'); end if;
      if coalesce((cfg->'annualLeave'->>'compOffEnabled')::boolean, true) = false then
        if extract(dow from firm_now()) = 0 then raise exception 'You cannot punch in today. There is no attendance on Sundays.'; end if;
        if exists (select 1 from jsonb_array_elements(coalesce(cfg->'holidays','[]')) h where h->>'date' = today) then
          raise exception 'You cannot punch in today. It is a holiday.';
        end if;
      end if;
      if exists (select 1 from records l where l.firm_id = new.firm_id and l.store = 'hrLeave' and l.user_id = new.user_id
                   and l.data->>'status' = 'Approved' and l.data->>'from' <= today and today <= coalesce(nullif(l.data->>'to',''), l.data->>'from')
                   and coalesce(l.data->>'unit','full') <> 'half') then
        raise exception 'You cannot punch in today: you are on approved leave.';
      end if;
    end if;
  end if;

  -- ===== leave: who decides, one active request per date =====
  if new.store = 'hrLeave' then
    st_old := old_row.data->>'status'; st_new := new.data->>'status';
    if is_new and new.data->>'remainderOf' is null and st_new <> 'Pending' and not admin then
      raise exception 'A new leave request starts as Pending.';
    end if;
    if is_new and new.data->>'remainderOf' is not null then
      if not exists (select 1 from records p where p.firm_id = new.firm_id and p.store = 'hrLeave' and p.id = new.data->>'remainderOf'
                       and p.data->>'status' in ('Cancelled','Partially Cancelled')) then
        raise exception 'Kept dates can only follow a cancellation of the leave they belong to.';
      end if;
    end if;
    if not is_new and st_old is distinct from st_new then
      if st_new in ('Approved','Declined') then
        if st_old <> 'Pending' then raise exception 'That request was already decided.'; end if;
        if not admin and old_row.data->>'approverId' is distinct from me then raise exception 'That request is not yours to decide.'; end if;
        if not admin and new.user_id = me then raise exception 'You cannot decide your own leave.'; end if;
        if st_new = 'Declined' and coalesce(trim(new.data->>'remarks'), '') = '' then raise exception 'A declined request needs a reason.'; end if;
      elsif st_new in ('Cancelled','Partially Cancelled') then
        if st_old not in ('Pending','Approved') then raise exception 'This leave cannot be cancelled.'; end if;
        if not admin and new.user_id <> me then raise exception 'Only the person or HR can cancel a leave.'; end if;
        select array_agg(x) into dates from jsonb_array_elements_text(coalesce(new.data->'cancellation'->'dates','[]')) x;
        if dates is null then raise exception 'A cancelled leave must say which dates were cancelled.'; end if;
        select array_agg(x) into kept from jsonb_array_elements_text(coalesce(new.data->'cancellation'->'keep','[]')) x;
        if (st_new = 'Cancelled') <> (kept is null) then raise exception 'Cancelled means every date; Partially Cancelled means some are kept.'; end if;
      else
        raise exception 'A leave cannot go from % to %.', st_old, st_new;
      end if;
    elsif not is_new and not admin and new.user_id = me and old_row.data->>'approverId' is distinct from me then
      -- The requester may not edit a request in place (dates, category, approver).
      if (new.data - 'at') <> (old_row.data - 'at') then raise exception 'A leave request cannot be edited. Cancel it and ask again.'; end if;
    end if;
    if st_new in ('Pending','Approved') and (is_new or st_old is distinct from st_new
         or new.data->>'from' is distinct from old_row.data->>'from' or new.data->>'to' is distinct from old_row.data->>'to') then
      if exists (select 1 from records o where o.firm_id = new.firm_id and o.store = 'hrLeave' and o.user_id = new.user_id and o.id <> new.id
                   and o.data->>'status' in ('Pending','Approved')
                   and left(o.data->>'from', 10) <= left(coalesce(nullif(new.data->>'to',''), new.data->>'from'), 10)
                   and left(new.data->>'from', 10) <= left(coalesce(nullif(o.data->>'to',''), o.data->>'from'), 10)) then
        raise exception 'Leave is already requested or approved on some of those dates. Choose dates that do not overlap, or cancel the earlier request first.';
      end if;
    end if;
  end if;

  -- ===== comp-off: one per day, a decision is final =====
  if new.store = 'hrCompOff' then
    if exists (select 1 from records o where o.firm_id = new.firm_id and o.store = 'hrCompOff' and o.user_id = new.user_id
                 and o.id <> new.id and left(o.data->>'date', 10) = left(new.data->>'date', 10)) then
      raise exception 'Comp-off for % is already recorded. One day can be credited only once.', left(new.data->>'date', 10);
    end if;
    st_old := old_row.data->>'status'; st_new := new.data->>'status';
    if not is_new and st_old in ('Approved','Rejected')
       and (st_old is distinct from st_new or (old_row.data->'credit')::numeric is distinct from (new.data->'credit')::numeric) then
      raise exception 'This comp-off was already % and cannot be changed.', lower(st_old);
    end if;
    if st_new in ('Approved','Rejected') and (is_new or st_old is distinct from st_new) then
      if is_new and not admin then raise exception 'A comp-off starts as Pending.'; end if;
      if new.user_id = me and not admin then raise exception 'You cannot decide your own comp-off.'; end if;
      if not admin and old_row.data->>'approverId' is distinct from me then raise exception 'That comp-off is not yours to decide.'; end if;
    end if;
  end if;

  -- ===== regularization: decided by HR, never by the person =====
  if new.store = 'hrRegularizations' then
    st_old := old_row.data->>'status'; st_new := new.data->>'status';
    if is_new and st_new <> 'Pending' then raise exception 'A regularization starts as Pending.'; end if;
    if not is_new and st_old is distinct from st_new then
      if st_old <> 'Pending' then raise exception 'That request was already decided.'; end if;
      if new.user_id = me then raise exception 'You cannot decide your own regularization.'; end if;
      if not (is_staff() or i_approve_for(new.user_id)) then raise exception 'That request is not yours to decide.'; end if;
    end if;
  end if;

  -- ===== team: an administrator cannot remove the last administrator =====
  if new.store = 'team' and not is_new and lower(coalesce(old_row.data->>'role','')) = 'admin'
     and (lower(coalesce(new.data->>'role','')) <> 'admin' or not is_active(new.data)) then
    if (select count(*) from records t where t.firm_id = new.firm_id and t.store = 'team' and t.id <> new.id
          and lower(coalesce(t.data->>'role','')) = 'admin' and is_active(t.data)) = 0 then
      raise exception 'This is the only administrator. Make somebody else an administrator first.';
    end if;
  end if;
  if new.store = 'team' then
    -- Passwords live in Supabase Auth, never in the team record.
    new.data := new.data - 'passwordHash' - 'passwordSalt';
  end if;
  return new;
end $$;

drop trigger if exists records_guard_ins on public.records;
drop trigger if exists records_guard_upd on public.records;
create trigger records_guard_ins before insert on public.records for each row execute function public.guard_records();
create trigger records_guard_upd before update on public.records for each row execute function public.guard_records();

-- ---------- joining ----------
/* The first administrator creates the firm. */
create or replace function public.create_firm(p_name text, p_timezone text, p_employee jsonb, p_config jsonb)
  returns jsonb language plpgsql security definer set search_path = public as $$
declare f uuid; emp jsonb; eid text;
begin
  if auth.uid() is null then raise exception 'Sign in first.'; end if;
  if exists (select 1 from members where user_id = auth.uid()) then raise exception 'This account already belongs to a firm.'; end if;
  if coalesce(trim(p_name), '') = '' then raise exception 'Enter the firm''s name.'; end if;
  eid := coalesce(nullif(p_employee->>'id', ''), 'TM-' || substr(gen_random_uuid()::text, 1, 8));
  emp := (p_employee - 'passwordHash' - 'passwordSalt')
         || jsonb_build_object('id', eid, 'email', lower(auth.email()), 'role', 'Admin', 'status', 'Active');
  insert into firms (name, timezone) values (trim(p_name), coalesce(nullif(p_timezone, ''), 'Asia/Kolkata')) returning id into f;
  insert into members (user_id, firm_id, employee_id) values (auth.uid(), f, eid);
  insert into records (firm_id, store, id, user_id, data, updated_by) values
    (f, 'team', eid, eid, emp, eid),
    (f, 'config', 'hrConfig', '', coalesce(p_config, '{}'::jsonb), eid);
  return jsonb_build_object('firm_id', f, 'employee_id', eid, 'name', trim(p_name));
end $$;

/* Everybody else: HR adds them with their email; they sign up with that
   email and this links the account to their employee record. */
create or replace function public.claim_membership()
  returns jsonb language plpgsql security definer set search_path = public as $$
declare m members; r records; n int; fname text;
begin
  if auth.uid() is null then raise exception 'Sign in first.'; end if;
  select * into m from members where user_id = auth.uid();
  if m.user_id is null then
    select count(*) into n from records where store = 'team' and lower(data->>'email') = lower(auth.email()) and is_active(data)
      and not exists (select 1 from members x where x.firm_id = records.firm_id and x.employee_id = records.id);
    if n = 0 then return null; end if;
    if n > 1 then raise exception 'This email is on more than one firm''s team. Ask HR to fix it.'; end if;
    select * into r from records where store = 'team' and lower(data->>'email') = lower(auth.email()) and is_active(data)
      and not exists (select 1 from members x where x.firm_id = records.firm_id and x.employee_id = records.id);
    insert into members (user_id, firm_id, employee_id) values (auth.uid(), r.firm_id, r.id) returning * into m;
  end if;
  if not is_active(team_of(m.employee_id)) then raise exception 'This account has been deactivated.'; end if;
  select name into fname from firms where id = m.firm_id;
  return jsonb_build_object('firm_id', m.firm_id, 'employee_id', m.employee_id, 'name', fname);
end $$;

-- ---------- grants ----------
grant usage on schema public to anon, authenticated;
grant select on public.firms, public.members to authenticated;
grant select, insert, update on public.records to authenticated;
grant execute on function public.create_firm(text, text, jsonb, jsonb), public.claim_membership() to authenticated;
revoke execute on function public.create_firm(text, text, jsonb, jsonb), public.claim_membership() from anon;
