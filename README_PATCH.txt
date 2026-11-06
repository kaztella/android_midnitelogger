MidNite Logger Daily Slow Retry V1 - 2026-11-06

Replace 4 files:
- Modbus.java
- ClassicReader.java
- LoggerService.java
- MainActivity.java

Changes:
1) Daily FC104 same-category verification interval: 2 seconds.
2) Category switch interval: 3 seconds.
3) Day-index settling wait: 5 seconds.
4) One-day completion rest: 3 seconds.
5) FC104 TCP failure: wait 5 seconds and retry, max 3 attempts.
6) Concentrated rescue does not abort the whole range when one day/timestamp fails; it records ERROR and continues.
7) After manual concentrated rescue, automatic DailyLogRescue is suppressed for 10 minutes.
8) Normal Modbus: first failure WARN, wait 2 seconds, retry; second failure ERROR then existing Wi-Fi restoration flow.
9) RSSI remains diagnostic only. No RSSI cutoff.
10) Existing FC104 response validation and repeated-value protection from the prior integrity patch are retained.

Supabase ON CONFLICT DO NOTHING remains required for normal Daily Log rescue.
