-- Emails are written to the outbox in the same transaction as whatever caused them, then sent by
-- OutboxSender when SMTP is set up. sent_at stays empty until the mail server took the message.
alter table outbox_emails add column sent_at timestamptz;
alter table outbox_emails add column attempts integer not null default 0;
create index outbox_unsent on outbox_emails (id) where sent_at is null;
