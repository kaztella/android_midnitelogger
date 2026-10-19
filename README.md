# MidNite Logger for SHL24
Android 4.2.2 (API 17) compatible source project. Minimum API is 14.

## Implemented
- Classic 192.168.10.1:502, Modbus Function 03 read only
- Registers 4115-4142, 4365-4371, 4391
- SQLite logging every 60 seconds
- Search configured external Wi-Fi every 15 minutes
- 30-second bounded upload, max 3 batches x 100 rows
- Always reconnect to logger SSID and verify by Modbus read
- Boot auto-start, foreground service, event history

## Important validation before unattended use
- Confirm Whizbang register scaling/sign against the Classic display.
- Android 4.2.2 may fail against modern TLS-only endpoints. Use a tested HTTPS endpoint or local relay.
- Add the NETGEAR SSID and external Wi-Fi candidates in Android Wi-Fi settings beforehand.
- Exclude device power-saving Wi-Fi sleep and keep the device safely powered.

## Build APK
Install Android Studio, open this folder, then Build > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk
Command line: gradlew assembleDebug (after generating/adding a Gradle wrapper).
