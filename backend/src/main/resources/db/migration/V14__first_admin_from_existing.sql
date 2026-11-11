-- A site that already has an admin counts as having had its first one, so a reset link can't
-- make another.
insert into first_admin (email)
select email from users where role = 'ADMIN' order by created_at limit 1
on conflict do nothing;
