-- bumped whenever existing access tokens must stop working (role change, two-factor turned off)
alter table users add column token_version integer not null default 0;
