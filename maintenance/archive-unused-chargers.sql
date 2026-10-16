-- Applied 2026-10-07 at the operator's request. These four initial records have
-- never communicated or charged. Archiving keeps their identities available
-- for future configuration and avoids changes to any operational equipment.
update public.charge_points cp set active=false
where cp.ocpp_identity in('CP002','CP003','CP004','CP005')
  and cp.active=true and cp.online=false and cp.last_seen_at is null
  and not exists(select 1 from public.ocpp_sessions s where s.charge_point_id=cp.id)
  and not exists(select 1 from public.ocpp_messages m where m.charge_point_id=cp.id)
  and not exists(select 1 from public.charging_authorizations a where a.charge_point_id=cp.id)
returning ocpp_identity,name,active;
