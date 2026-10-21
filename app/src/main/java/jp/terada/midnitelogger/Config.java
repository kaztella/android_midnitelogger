package jp.terada.midnitelogger;

public final class Config {
    public static final int PORT = 502;
    public static final int UNIT_ID = 1;
    public static final int LOG_INTERVAL_SECONDS = 60;
    public static final int UPLOAD_LIMIT_SECONDS = 30;
    public static final int CLASSIC_RECONNECT_SECONDS = 45;
    public static final int UPLOAD_BATCH_SIZE = 100;
    public static final int UPLOAD_MAX_BATCHES = 3;
    private Config() { }
}
