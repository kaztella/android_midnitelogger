package jp.terada.midnitelogger;
public final class Config {
 public static final String CLASSIC_IP="192.168.10.1"; public static final int MODBUS_PORT=502, UNIT_ID=1;
 public static final long LOG_INTERVAL_MS=60000L; public static final int BATCH_SIZE=100; public static final long UPLOAD_LIMIT_MS=30000L;
 public static final String PREFS="midnite_prefs"; private Config(){}
}
