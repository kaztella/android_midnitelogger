MidNite Logger Wi-Fi/IP wait patch v2

Replace these four files in:
app/src/main/java/jp/terada/midnitelogger/

- LoggerService.java
- WifiNetworkManager.java
- SupabaseUploader.java
- Tls12.java

Changes:
1. Do not attempt Modbus while SupplicantState is not COMPLETED or IP is 0.0.0.0.
2. Wait for DHCP/IP after switching to NETGEAR_2GEXT.
3. Verify restoration using Modbus, not SSID text.
4. Retry Wi-Fi request and Modbus for up to Config.UPLOAD_RESTORE_WAIT_MS.
5. Preserve cumulative TLS 1.2 and URL/API key sanitation fixes.
6. Foreground notification supports both SHL24 (API 17) and Android 8+.

MainActivity must call restoreClassicNetwork(classicSsid), not the no-argument form.
