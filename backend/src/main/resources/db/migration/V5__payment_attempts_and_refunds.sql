-- Every checkout writes its attempt down before charging, so if the API dies halfway a background
-- job can see money that was taken and either finish the order or give it back.
create table payment_attempts (
    idem_key    text primary key,
    hold_id     text not null references holds (id),
    card_token  text not null,
    amount_cents bigint not null,
    payment_ref text,
    state       text not null check (state in ('CHARGING', 'CHARGED', 'DECLINED', 'DONE', 'REFUNDED')),
    created_at  timestamptz not null default now()
);

create index payment_attempts_open on payment_attempts (created_at) where state in ('CHARGING', 'CHARGED');

-- refunds are queued, then sent; a failed send stays pending and is retried. The id says what the
-- refund is for, so two workers deciding on the same refund can only queue it once.
create table refunds (
    id          text primary key,
    payment_ref text not null,
    amount_cents bigint not null,
    reason      text not null,
    state       text not null default 'PENDING' check (state in ('PENDING', 'DONE')),
    created_at  timestamptz not null default now()
);

-- a promo code use is reserved when seats are held and handed back if the hold ends unpaid
alter table holds add column promo_returned boolean not null default false;
