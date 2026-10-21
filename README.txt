MidNite Logger v0.5.0 HEX logging patch

Replace:
app/src/main/java/jp/terada/midnitelogger/Modbus.java

Added logs:
[SEND-HEX] full 12-byte Modbus/TCP request
[RECV-HEX] full response, read according to MBAP Length

Exception errors now include response FC, original FC, Unit ID, and full HEX.
No register/function-code behavior has been changed.
