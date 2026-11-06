-- fans let in from the waiting room per minute; null means the event has no waiting room
alter table events add column queue_per_minute integer check (queue_per_minute > 0);
alter table events add column on_sale_at timestamptz;

-- set when the fan lets a hold go; a hold that ran out instead earns a short cooldown
alter table holds add column released_at timestamptz;
