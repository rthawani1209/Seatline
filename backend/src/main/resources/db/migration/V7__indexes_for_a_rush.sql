-- Found by the sell-out load test: with tens of thousands of rows these lookups were full table
-- scans on every hold and checkout, and Postgres became the bottleneck.
create index holds_by_fan_and_event on holds (fan_id, event_id);
create index tickets_by_order on tickets (order_id);
create index tickets_by_event on tickets (event_id);
create index payment_attempts_by_hold on payment_attempts (hold_id);
