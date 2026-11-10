-- Returned tickets whose seat the ledger hasn't put back on sale yet (it was unreachable at the
-- time). The reconciler keeps retrying until it has.
create table ledger_returns (
    ticket_id  text primary key references tickets (id),
    fan_id     uuid not null,
    created_at timestamptz not null default now()
);
