package jp.terada.midnitelogger;

public final class Config {
    public static final String CLASSIC_IP = "192.168.10.1";
    public static final int MODBUS_PORT = 502;
    public static final int UNIT_ID = 1;

    public static final String PREFS = "midnite_prefs";
    public static final String CLASSIC_WIFI_SSID = "NETGEAR_2GEXT";
    public static final String DEFAULT_UPLOAD_WIFI_SSIDS = "HomeWiFi,OfficeWiFi";
    public static final String BACKUP_DIR_NAME = "MidniteLogger";
    public static final String SETTINGS_FILE_NAME = "midnite_logger_settings.properties";
    public static final String DB_BACKUP_PREFIX = "midnite_backup_";

    public static final long LOG_INTERVAL_MS = 60000L;
    public static final long UPLOAD_RETRY_COOLDOWN_MS = 10L * 60L * 1000L;
    public static final long UPLOAD_RESTORE_WAIT_MS = 3L * 60L * 1000L;
    public static final int BATCH_SIZE = 100;
    public static final long UPLOAD_LIMIT_MS = 30000L;
    public static final long WIFI_SWITCH_TIMEOUT_MS = 15000L;

    private Config() {
    }
}
