-- Accounts. Emails are stored lower-cased so uniqueness can't be dodged with capital letters.
create table users (
    id              uuid primary key,
    email           text not null unique,
    password_hash   text not null,
    display_name    text not null,
    role            text not null check (role in ('FAN', 'ORGANIZER', 'DOOR_STAFF', 'ADMIN')),
    email_verified  boolean not null default false,
    totp_secret     text,           -- set once two-factor is confirmed
    totp_pending    text,           -- secret handed out by setup, not confirmed yet
    totp_last_step  bigint not null default 0,
    created_at      timestamptz not null default now()
);

-- Only a hash of each token is kept, so a database leak can't be used to verify accounts.
create table email_verifications (
    token_hash  text primary key,
    user_id     uuid not null references users (id) on delete cascade,
    expires_at  timestamptz not null
);

-- Emails waiting to be sent. In development nothing sends them; you read them from here.
create table outbox_emails (
    id          bigserial primary key,
    to_address  text not null,
    subject     text not null,
    body        text not null,
    created_at  timestamptz not null default now()
);
