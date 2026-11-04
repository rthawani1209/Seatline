-- A hold is a fan's few minutes to pay for seats the ledger has set aside for them. The ledger
-- decides the seats; this table remembers the price we quoted so checkout charges exactly that.
create table holds (
    id          text primary key,
    event_id    uuid not null references events (id),
    fan_id      uuid not null references users (id),
    quote       jsonb not null,
    total_cents bigint not null,
    promo_code  text,
    expires_at  timestamptz not null,
    created_at  timestamptz not null default now()
);

create table orders (
    id          uuid primary key,
    hold_id     text not null unique references holds (id),
    event_id    uuid not null references events (id),
    fan_id      uuid not null references users (id),
    total_cents bigint not null,
    payment_ref text not null,
    created_at  timestamptz not null default now()
);

-- ids are the ledger's ticket ids
create table tickets (
    id          text primary key,
    order_id    uuid not null references orders (id),
    event_id    uuid not null references events (id),
    seat        text not null,
    tier        text not null,
    price_cents bigint not null,
    owner_id    uuid not null references users (id),
    status      text not null default 'VALID' check (status in ('VALID', 'RETURNED')),
    version     integer not null default 1,
    scanned_at  timestamptz
);

create index tickets_by_owner on tickets (owner_id);
