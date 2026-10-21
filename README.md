# Midnite Logger v0.7.0
- Modbus TCP logging to SQLite every 60 s
- Supabase RPC batch upload, 100 rows per batch, max 30 s
- Duplicate-safe server RPC assumed: insert_measurements(jsonb)
- Event logs: non-ERROR deleted after 7 days; ERROR retained indefinitely
- Database record count, DB size, ERROR count, device free storage display
- DB backup to /MidniteLogger and merge latest backup
- Multi-metric 24-hour chart

Supabase settings:
- RPC URL: https://PROJECT.supabase.co/rest/v1/rpc/insert_measurements
- Publishable key only. Never use secret/service_role key.
