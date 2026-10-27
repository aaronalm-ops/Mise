-- Mise: household sharing (M3). Run once in the Supabase SQL Editor of the project Mise uses.
-- Safe to run again. Uses only mise_* names, so nothing else in the project is touched.

create table if not exists mise_households (
  id uuid primary key default gen_random_uuid(),
  name text not null default 'Home',
  invite_code text unique not null default upper(substr(md5(random()::text), 1, 6)),
  created_by uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists mise_members (
  household_id uuid not null references mise_households(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null default '',
  joined_at timestamptz not null default now(),
  primary key (household_id, user_id)
);

-- Every shared item (recipe, plan entry, tick, extra item, aisle/staple setting) is one row.
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

drop policy if exists mise_households_read on mise_households;
create policy mise_households_read on mise_households for select to authenticated using (mise_is_member(id));
drop policy if exists mise_members_read on mise_members;
create policy mise_members_read on mise_members for select to authenticated using (mise_is_member(household_id));
drop policy if exists mise_rows_all on mise_rows;
create policy mise_rows_all on mise_rows for all to authenticated
  using (mise_is_member(household_id)) with check (mise_is_member(household_id));

create or replace function mise_create_household(p_name text, p_display text) returns json
language plpgsql security definer set search_path = public as $$
declare h mise_households;
begin
  if auth.uid() is null then raise exception 'Not signed in'; end if;
  insert into mise_households (name, created_by) values (coalesce(nullif(trim(p_name), ''), 'Home'), auth.uid()) returning * into h;
  insert into mise_members (household_id, user_id, display_name) values (h.id, auth.uid(), coalesce(p_display, ''));
  return json_build_object('id', h.id, 'name', h.name, 'invite_code', h.invite_code);
end $$;

create or replace function mise_join_household(p_code text, p_display text) returns json
language plpgsql security definer set search_path = public as $$
declare h mise_households;
begin
  if auth.uid() is null then raise exception 'Not signed in'; end if;
  select * into h from mise_households where invite_code = upper(trim(p_code));
  if not found then raise exception 'No household has that code'; end if;
  insert into mise_members (household_id, user_id, display_name) values (h.id, auth.uid(), coalesce(p_display, ''))
  on conflict (household_id, user_id) do update set display_name = excluded.display_name;
  return json_build_object('id', h.id, 'name', h.name, 'invite_code', h.invite_code);
end $$;

create or replace function mise_my_household() returns json
language sql security definer set search_path = public stable as $$
  select json_build_object(
    'id', h.id, 'name', h.name, 'invite_code', h.invite_code,
    'members', (select coalesce(json_agg(m.display_name order by m.joined_at), '[]'::json) from mise_members m where m.household_id = h.id))
  from mise_households h
  join mise_members me on me.household_id = h.id and me.user_id = auth.uid()
  order by me.joined_at
  limit 1
$$;

create or replace function mise_leave_household() returns void
language sql security definer set search_path = public as $$
  delete from mise_members where user_id = auth.uid()
$$;

grant select on mise_households, mise_members to authenticated;
grant select, insert, update on mise_rows to authenticated;
grant execute on function mise_create_household(text, text), mise_join_household(text, text),
  mise_my_household(), mise_leave_household(), mise_is_member(uuid) to authenticated;
