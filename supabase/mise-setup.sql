-- Mise: household sharing, version 2 (v0.6). Run in the Supabase SQL Editor of the project
-- Mise uses. Safe to run again, and safe to run over version 1: existing households, members
-- and shared rows are kept. Uses only mise_* names, so nothing else in the project is touched.
--
-- What version 2 changes:
-- - Invite codes are 10 characters from an alphabet with no look-alikes (no 0/O, 1/I/L).
-- - A household has at most 2 members, and join attempts are limited (10 per hour).
-- - Deleting an account no longer deletes the household the account created.
-- - Explicit grants only; rows can be read, added and changed, never hard-deleted.

create extension if not exists pgcrypto;

create table if not exists mise_households (
  id uuid primary key default gen_random_uuid(),
  name text not null default 'Home',
  invite_code text unique not null,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

create table if not exists mise_members (
  household_id uuid not null references mise_households(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null default '',
  joined_at timestamptz not null default now(),
  primary key (household_id, user_id)
);

create table if not exists mise_rows (
  household_id uuid not null references mise_households(id) on delete cascade,
  kind text not null,
  uid text not null,
  data jsonb not null default '{}'::jsonb,
  deleted boolean not null default false,
  updated_by uuid,
  updated_at timestamptz not null default now(),
  primary key (household_id, kind, uid)
);
create index if not exists mise_rows_changes on mise_rows (household_id, updated_at);

create table if not exists mise_join_attempts (
  user_id uuid not null,
  at timestamptz not null default now()
);
create index if not exists mise_join_attempts_user on mise_join_attempts (user_id, at);

-- Upgrade a version 1 install: creator becomes optional and survives account deletion.
alter table mise_households alter column created_by drop not null;
alter table mise_households alter column invite_code drop default;
do $$
declare c text;
begin
  select tc.constraint_name into c
  from information_schema.table_constraints tc
  join information_schema.key_column_usage k on k.constraint_name = tc.constraint_name and k.table_name = tc.table_name
  where tc.table_name = 'mise_households' and tc.constraint_type = 'FOREIGN KEY' and k.column_name = 'created_by'
  limit 1;
  if c is not null then execute format('alter table mise_households drop constraint %I', c); end if;
  alter table mise_households add constraint mise_households_created_by_fkey
    foreign key (created_by) references auth.users(id) on delete set null;
end $$;

-- 10 characters, 31-letter alphabet: about 10^15 codes.
create or replace function mise_new_code() returns text
language plpgsql volatile set search_path = public, extensions as $$
declare
  alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  b bytea := gen_random_bytes(10);
  s text := '';
  i int;
begin
  for i in 0..9 loop
    s := s || substr(alphabet, (get_byte(b, i) % 31) + 1, 1);
  end loop;
  return s;
end $$;

-- Older 6-character codes are replaced with new ones.
update mise_households set invite_code = mise_new_code() where length(invite_code) <> 10;

create or replace function mise_is_member(h uuid) returns boolean
language sql security definer set search_path = public stable as $$
  select exists (select 1 from mise_members where household_id = h and user_id = auth.uid())
$$;

create or replace function mise_touch() returns trigger language plpgsql as $$
begin
  new.updated_at := clock_timestamp();
  new.updated_by := auth.uid();
  return new;
end $$;

drop trigger if exists mise_rows_touch on mise_rows;
create trigger mise_rows_touch before insert or update on mise_rows
for each row execute function mise_touch();

alter table mise_households enable row level security;
alter table mise_members enable row level security;
alter table mise_rows enable row level security;
alter table mise_join_attempts enable row level security;

drop policy if exists mise_households_read on mise_households;
create policy mise_households_read on mise_households for select to authenticated using (mise_is_member(id));
drop policy if exists mise_members_read on mise_members;
create policy mise_members_read on mise_members for select to authenticated using (mise_is_member(household_id));
drop policy if exists mise_rows_all on mise_rows;
drop policy if exists mise_rows_read on mise_rows;
drop policy if exists mise_rows_insert on mise_rows;
drop policy if exists mise_rows_update on mise_rows;
create policy mise_rows_read on mise_rows for select to authenticated using (mise_is_member(household_id));
create policy mise_rows_insert on mise_rows for insert to authenticated with check (mise_is_member(household_id));
create policy mise_rows_update on mise_rows for update to authenticated
  using (mise_is_member(household_id)) with check (mise_is_member(household_id));

create or replace function mise_household_json(h mise_households) returns json
language sql stable set search_path = public as $$
  select json_build_object(
    'id', h.id, 'name', h.name, 'invite_code', h.invite_code,
    'members', (select coalesce(json_agg(m.display_name order by m.joined_at), '[]'::json) from mise_members m where m.household_id = h.id))
$$;

create or replace function mise_create_household(p_name text, p_display text) returns json
language plpgsql security definer set search_path = public as $$
declare h mise_households;
begin
  if auth.uid() is null then raise exception 'Not signed in'; end if;
  -- Already in a household: return it instead of creating a second one.
  select hh.* into h from mise_households hh join mise_members m on m.household_id = hh.id
    where m.user_id = auth.uid() order by m.joined_at limit 1;
  if found then return mise_household_json(h); end if;
  insert into mise_households (name, created_by, invite_code)
    values (coalesce(nullif(trim(p_name), ''), 'Home'), auth.uid(), mise_new_code()) returning * into h;
  insert into mise_members (household_id, user_id, display_name) values (h.id, auth.uid(), coalesce(p_display, ''));
  return mise_household_json(h);
end $$;

create or replace function mise_join_household(p_code text, p_display text) returns json
language plpgsql security definer set search_path = public as $$
declare
  h mise_households;
  n int;
  code text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
begin
  if auth.uid() is null then raise exception 'Not signed in'; end if;
  select count(*) into n from mise_join_attempts where user_id = auth.uid() and at > now() - interval '1 hour';
  if n >= 10 then raise exception 'Too many tries. Wait an hour, then try again.'; end if;
  insert into mise_join_attempts (user_id) values (auth.uid());
  delete from mise_join_attempts where at < now() - interval '1 day';

  select * into h from mise_households where invite_code = code;
  if not found then raise exception 'No household has that code. Check it on the other phone.'; end if;
  if exists (select 1 from mise_members where household_id = h.id and user_id = auth.uid()) then
    update mise_members set display_name = coalesce(p_display, display_name) where household_id = h.id and user_id = auth.uid();
    return mise_household_json(h);
  end if;
  if exists (select 1 from mise_members where user_id = auth.uid()) then
    raise exception 'You are already in a household. Leave it first.';
  end if;
  select count(*) into n from mise_members where household_id = h.id;
  if n >= 2 then raise exception 'That household is full (2 people).'; end if;
  insert into mise_members (household_id, user_id, display_name) values (h.id, auth.uid(), coalesce(p_display, ''));
  return mise_household_json(h);
end $$;

create or replace function mise_my_household() returns json
language sql security definer set search_path = public stable as $$
  select mise_household_json(h)
  from mise_households h
  join mise_members me on me.household_id = h.id and me.user_id = auth.uid()
  order by me.joined_at
  limit 1
$$;

create or replace function mise_leave_household() returns void
language sql security definer set search_path = public as $$
  delete from mise_members where user_id = auth.uid()
$$;

-- Grants: nothing by default, then exactly what the app needs.
revoke all on mise_households, mise_members, mise_rows, mise_join_attempts from anon, authenticated, public;
grant select on mise_households, mise_members to authenticated;
grant select, insert, update on mise_rows to authenticated;

revoke execute on function mise_new_code(), mise_is_member(uuid), mise_household_json(mise_households),
  mise_create_household(text, text), mise_join_household(text, text), mise_my_household(),
  mise_leave_household() from public, anon;
grant execute on function mise_create_household(text, text), mise_join_household(text, text),
  mise_my_household(), mise_leave_household(), mise_is_member(uuid) to authenticated;

-- Tell the API about the new functions straight away (otherwise it can take a minute).
notify pgrst, 'reload schema';
