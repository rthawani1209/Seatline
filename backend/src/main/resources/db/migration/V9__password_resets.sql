-- like email_verifications: only a hash of each link's token is kept, and a link works once
create table password_resets (
    token_hash  text primary key,
    user_id     uuid not null references users (id) on delete cascade,
    expires_at  timestamptz not null
);
