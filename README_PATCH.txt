MidNite Logger improvement patch based on the files attached on 2026-11-06.

Modified:
- MainActivity.java
- Database.java
- ChartView.java

Features:
1) Daily Log concentrated rescue/upload with start/end dates, max 400 days.
2) Monitor shows normal uploaded/total and daily uploaded/total.
3) Chart bug fix: removed SQL LIMIT on first 240 rows; now downsamples evenly over entire selected range. Auto Y adds 5% margin.

The existing DailyLogRecord, DailyLogRescue, ConscryptTls, LoggerService, SupabaseUploader and Wi-Fi recovery logic are not overwritten.
