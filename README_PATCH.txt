MidNite Logger Conscrypt TLS patch

Replace/add:
app/src/main/java/jp/terada/midnitelogger/ConscryptTls.java
app/src/main/java/jp/terada/midnitelogger/SupabaseUploader.java

Also edit app/build.gradle and add inside dependencies:
implementation 'org.conscrypt:conscrypt-android:2.5.2'

This patch intentionally does not modify LoggerService, WifiNetworkManager,
Modbus, Database, or the logging restoration code.

Expected SHL24 log before POST:
TLS provider=Conscrypt ... Android API=17
Supabase HTTPS接続開始 rows=...

On success:
Supabase batch成功 rows=...

If it still fails, the log will identify Conscrypt as provider and include the exception cause chain.
