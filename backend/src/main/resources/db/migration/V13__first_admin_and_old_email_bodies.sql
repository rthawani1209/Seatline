-- SEATLINE_ADMIN_EMAIL is made an admin once only, so an admin who was later demoted can't get
-- the role back by resetting their password.
create table first_admin (
    email      text primary key,
    granted_at timestamptz not null default now()
);

-- Email bodies can hold confirm and reset links. Sent ones are emptied as they go out now; this
-- empties the ones sent before that, and the ones that gave up after too many tries.
update outbox_emails set body = '' where sent_at is not null or attempts >= 10;
