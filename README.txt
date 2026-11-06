MidNite Logger Daily Log integrity patch

Replace:
- app/src/main/java/jp/terada/midnitelogger/Modbus.java
- app/src/main/java/jp/terada/midnitelogger/ClassicReader.java

Manually apply:
- LOGGER_SERVICE_RETRY_PATCH.txt to LoggerService.java

Run in Supabase SQL Editor:
1. sql/02_backup_and_clean_known_bad_rows.sql (first run through count check; delete only after counts match)
2. sql/01_upsert_classic_daily_logs_do_nothing.sql

Daily Log behavior:
- Every FC104 category uses a fresh TCP connection.
- 200 ms between repeated reads.
- Two equal reads are required; on mismatch a third read is used by majority.
- All three different => IOException, no row is saved.
- 200 ms between categories and 500 ms after one daily record.
- FC104 validates transaction ID, protocol ID, unit ID, function, command echo,
  PDU alignment and requested internal address before accepting the final word.
- Physically impossible high power (>10000 W) or FET temperature (<-100 or >200 C)
  is independently reread; still invalid => no save.

Known regression checks:
- 2025-10-28 high_power_w = 1580
- 2025-10-29 high_power_w = 1821
- 2025-10-30 high_power_w = 1866
- 2025-10-22 high_fet_temp = confirm against MNGP
