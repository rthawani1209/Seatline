-- which door staff may scan tickets at which event (organizers add them by email)
create table door_staff (
    event_id uuid not null references events (id) on delete cascade,
    user_id  uuid not null references users (id) on delete cascade,
    added_at timestamptz not null default now(),
    primary key (event_id, user_id)
);

create index door_staff_user on door_staff (user_id);
