# MidNite Logger for SHL24 / Galaxy

Version 0.6.0

- Normal 60-second Modbus logging has absolute priority.
- Automatic external Wi-Fi discovery/switching has been disabled.
- External Wi-Fi switching occurs only after the user presses "今すぐ30秒アップロード".
- Upload is cancelled before switching when the URL or learned Classic SSID is missing.
- After upload, matching the SSID alone is not considered recovery. Recovery completes only after Modbus reads and SQLite storage succeed.
- On normal Modbus failure, a learned Classic SSID is retried once; repeated switching is not performed within the same cycle.
- Each cycle logs its number, current SSID, device Wi-Fi IP, completion, and next 60-second interval.
- Socket source ports are expected to vary; device source IP is now displayed to identify network changes.
- Full SEND-HEX and RECV-HEX logging is included.
- Foreground-service, location, nearby-device, and UNKNOWN_SSID safeguards from v0.5.0 are retained.
