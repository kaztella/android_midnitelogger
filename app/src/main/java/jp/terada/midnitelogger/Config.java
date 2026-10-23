package jp.terada.midnitelogger;

public final class Config {
    public static final String CLASSIC_IP = "192.168.10.1";
    public static final int MODBUS_PORT = 502;
    public static final int UNIT_ID = 1;

    public static final String PREFS = "midnite_prefs";
    public static final String CLASSIC_WIFI_SSID = "NETGEAR";
    public static final String DEFAULT_UPLOAD_WIFI_SSIDS = "HomeWiFi,OfficeWiFi";

    public static final long LOG_INTERVAL_MS = 60000L;
    public static final int BATCH_SIZE = 100;
    public static final long UPLOAD_LIMIT_MS = 30000L;
    public static final long WIFI_SWITCH_TIMEOUT_MS = 15000L;

    private Config() {
    }
}
