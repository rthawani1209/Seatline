-- a cancelled event's tickets are refunded in full and stop scanning
alter table tickets drop constraint tickets_status_check;
alter table tickets add constraint tickets_status_check check (status in ('VALID', 'RETURNED', 'CANCELLED'));
