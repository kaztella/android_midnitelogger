# MidNite Logger for SHL24 / Galaxy

Version 0.4.0

- Android 8.0+ notification channel support while retaining Android 4.2.2 compatibility.
- In-app communication diagnostics stored in SQLite and shown as the latest 50 entries.
- TCP connect, Modbus request/response, decoded values, SQLite save, Wi-Fi transitions, upload events, and exceptions are logged.
- Refresh/Clear log buttons.
- Duplicate logging-start prevention and consistent Start/Stop button state.
- Selectable 24-hour offline battery-voltage chart retained.

Build target: compileSdk 35, minSdk 14, targetSdk 28.
