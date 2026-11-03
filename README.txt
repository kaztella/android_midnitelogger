Float RAW Diagnostic v2
Fixes the GitHub build errors shown for v1:
- uses Modbus.L (not nonexistent Modbus.Logger)
- includes Modbus.readDailyLogBlock(String,int,int,int,int)
- includes readDailyLogCategories/readDailyLog compatibility APIs
Diagnostic is read-only: no SQLite DailyLog write and no Supabase upload.
