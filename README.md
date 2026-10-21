# MidNite Logger for SHL24 / Galaxy

Version 0.5.0

- Modbus success is the authority for identifying the Classic-side Wi-Fi.
- After all required Modbus register reads succeed and SQLite save completes, the current SSID is learned and stored as the Classic-side return SSID.
- UNKNOWN_SSID, blank SSID, and redacted SSID values are never stored.
- Runtime permission requests for location and nearby Wi-Fi devices are included for newer Android devices.
- If Android location services are off or permissions are denied, the app logs a concrete corrective message while continuing Modbus logging.
- Normal logging first uses the current connection. Wi-Fi reconnection is attempted only after Modbus failure and only when a valid Classic-side SSID has already been learned.
- External Wi-Fi upload and return-to-Classic behavior remains in place.
- Android 8+ foreground service permission/channel support and Android 4.2.2 compatibility are retained.
