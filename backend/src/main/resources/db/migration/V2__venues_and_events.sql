create table venues (
    id          uuid primary key,
    name        text not null,
    city        text not null,
    created_by  uuid not null references users (id),
    created_at  timestamptz not null default now()
);

create table sections (
    id        uuid primary key,
    venue_id  uuid not null references venues (id) on delete cascade,
    name      text not null,
    position  int not null,
    unique (venue_id, name)
);

-- label is what the ledger and tickets use, e.g. "FLOOR-B-12"; x/y place the seat on the map
create table seats (
    id            uuid primary key,
    section_id    uuid not null references sections (id) on delete cascade,
    label         text not null,
    row_label     text not null,
    number        int not null,
    accessible    boolean not null default false,
    limited_view  boolean not null default false,
    x             int not null,
    y             int not null
);
create unique index seats_label_per_section on seats (section_id, label);

create table events (
    id              uuid primary key,
    venue_id        uuid not null references venues (id),
    organizer_id    uuid not null references users (id),
    name            text not null,
    starts_at       timestamptz not null,
    status          text not null check (status in ('DRAFT', 'ON_SALE', 'CANCELLED')),
    per_fan_limit   int not null check (per_fan_limit between 1 and 20),
    -- all-in pricing: fees and tax are part of the price fans see from the start
    fee_bps         int not null check (fee_bps between 0 and 3000),
    fee_flat_cents  int not null check (fee_flat_cents between 0 and 2000),
    tax_bps         int not null check (tax_bps between 0 and 2500),
    created_at      timestamptz not null default now()
);

create table tiers (
    id           uuid primary key,
    event_id     uuid not null references events (id) on delete cascade,
    name         text not null,
    kind         text not null check (kind in ('STANDARD', 'EARLY_BIRD', 'STUDENT', 'VIP')),
    price_cents  int not null check (price_cents between 0 and 1000000),
    -- early bird prices stop at this time; null means no end
    ends_at      timestamptz
);

create table tier_sections (
    tier_id     uuid not null references tiers (id) on delete cascade,
    section_id  uuid not null references sections (id),
    primary key (tier_id, section_id)
);

create table promo_codes (
    id           uuid primary key,
    event_id     uuid not null references events (id) on delete cascade,
    code         text not null,
    percent_off  int not null check (percent_off between 1 and 100),
    max_uses     int not null check (max_uses > 0),
    used         int not null default 0,
    expires_at   timestamptz,
    unique (event_id, code)
);
