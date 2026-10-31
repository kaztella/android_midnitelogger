package jp.terada.midnitelogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LoggerService extends Service {
    private static final long STARTUP_NETWORK_WAIT_MS = 45000L;
    private static final long RESTORE_RETRY_MS = 5000L;
    private ScheduledExecutorService executor;
    private Database db;
    private WifiNetworkManager wifi;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastUploadAttemptMs;

    @Override
    public void onCreate() {
        super.onCreate();
        db = new Database(this);
        wifi = new WifiNetworkManager(this);
        startForegroundMode();
        acquireLocks();
        getSharedPreferences(Config.PREFS, 0).edit().putBoolean("running", true).apply();
        db.event("INFO", "Service開始");
        db.event("INFO", "BUILD=DAILY-RESCUE-V2");
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { cycle(); }
        }, 0L, Config.LOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cycle() {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
        String classicSsid = prefs.getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID);
        if (classicSsid == null || classicSsid.trim().length() == 0) classicSsid = Config.CLASSIC_WIFI_SSID;
        List<String> uploadSsids = wifi.parseUploadSsidList(
                prefs.getString("upload_wifi_ssids", Config.DEFAULT_UPLOAD_WIFI_SSIDS));
        try {
            db.event("INFO", "WiFi=" + wifi.connectionDiagnostics() + " targetClassic=" + classicSsid);
            if (wifi.shouldUploadOnCurrentNetwork(uploadSsids)) {
                lastUploadAttemptMs = now;
                db.event("OK", "外部WiFi接続中: upload実行");
                try {
                    int count = new SupabaseUploader(this, db).upload30s();
                    db.event("OK", "Upload完了 " + count + "件");
                } catch (Exception e) {
                    db.event("ERROR", "Upload失敗: " + e.toString());
                } finally {
                    restoreAndVerifyClassic(classicSsid);
                }
                return;
            }

            if (!wifi.isNetworkReady()) {
                db.event("INFO", "WiFi/IP未確立のためNETGEAR復帰を優先: " + wifi.connectionDiagnostics());
                restoreAndVerifyClassic(classicSsid);
                return;
            }

            if (tryClassicRead("通常周期")) {
                db.event("INFO", "DailyLog救出処理 ENTER");
                tryDailyLogRescueBatch();
                if (now - lastUploadAttemptMs >= Config.UPLOAD_RETRY_COOLDOWN_MS) {
                    String candidate = wifi.findUploadNetworkInScanResults(uploadSsids);
                    if (candidate != null) {
                        db.event("INFO", "upload WiFi検出: " + candidate + " 接続試行");
                        lastUploadAttemptMs = now;
                        if (wifi.connectToSsid(candidate))
                            db.event("OK", "upload WiFi接続完了: " + candidate + " " + wifi.connectionDiagnostics());
                        else
                            db.event("ERROR", "upload WiFi接続失敗: " + candidate + " " + wifi.connectionDiagnostics());
                    }
                }
                return;
            }

            db.event("INFO", "Classic通信不可のためNETGEAR復帰試行: " + wifi.connectionDiagnostics());
            restoreAndVerifyClassic(classicSsid);
        } catch (Exception e) {
            db.event("ERROR", "cycle error: " + e.toString());
        }
    }

    private boolean tryClassicRead(String reason) {
        if (!wifi.isNetworkReady()) {
            db.event("INFO", reason + ": WiFi/IP未確立のためModbus保留 " + wifi.connectionDiagnostics());
            return false;
        }
        try {
            db.event("INFO", reason + ": Classic Modbus読取り開始");
            ClassicReader reader = new ClassicReader(new Modbus.L() {
                @Override public void log(String level, String message) { db.event(level, message); }
            });
            Record record = reader.read();
            db.insert(record);
            db.event("DATA", "Battery=" + record.batteryV + " PV=" + record.pvV + " Power=" + record.watts);
            db.purgeEvents();
            return true;
        } catch (Exception e) {
            db.event("ERROR", reason + ": Classic Modbus失敗: " + e.toString());
            return false;
        }
    }

    private void tryDailyLogRescueBatch() {
        final Modbus.L log = new Modbus.L() {
            @Override public void log(String level, String message) { db.event(level, message); }
        };
        try {
            db.event("INFO", "DailyLogRescue.runBatch START");
            int saved = new DailyLogRescue(this, db, log).runBatch();
            db.event("INFO", "DailyLogRescue.runBatch END saved=" + saved);
            if (saved > 0) db.event("OK", "DailyLog今回救出=" + saved + "件");
        } catch (Exception e) {
            db.event("ERROR", "DailyLog救出失敗: " + e.toString());
        }
    }

    private boolean restoreAndVerifyClassic(String classicSsid) {
        long deadline = System.currentTimeMillis() + Config.UPLOAD_RESTORE_WAIT_MS;
        db.event("INFO", "Classic復帰開始 SSID=" + classicSsid + " " + wifi.connectionDiagnostics());
        boolean requested = wifi.restoreClassicNetwork(classicSsid);
        db.event("INFO", "Classic WiFi接続/IP待機結果=" + requested + " " + wifi.connectionDiagnostics());
        while (System.currentTimeMillis() < deadline) {
            if (!wifi.isNetworkReady()) {
                long remaining = deadline - System.currentTimeMillis();
                long wait = Math.min(STARTUP_NETWORK_WAIT_MS, Math.max(1000L, remaining));
                db.event("INFO", "Classic WiFi/IP待機中 " + wifi.connectionDiagnostics());
                wifi.waitForNetworkReady(wait);
            }
            if (wifi.isNetworkReady() && tryClassicRead("復帰確認")) {
                db.event("OK", "Classic復帰完了: IP取得・Modbus応答成功");
                return true;
            }
            try { Thread.sleep(RESTORE_RETRY_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (!wifi.isNetworkReady()) wifi.restoreClassicNetwork(classicSsid);
        }
        db.event("ERROR", "Classic復帰失敗: IP取得またはModbus応答なし " + wifi.connectionDiagnostics());
        return false;
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MidniteLogger:cycle");
            wakeLock.setReferenceCounted(false); wakeLock.acquire();
        }
        WifiManager manager = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (manager != null) {
            wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MidniteLogger:wifi");
            wifiLock.setReferenceCounted(false); wifiLock.acquire();
        }
    }

    @SuppressWarnings("deprecation")
    private void startForegroundMode() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager)getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel("midnite_logger", "MidNite Logger", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
            Notification n = new Notification.Builder(this, "midnite_logger").setContentTitle("MidNite Logger").setContentText("Running").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
            startForeground(1, n);
        } else {
            Notification n = new Notification.Builder(this).setContentTitle("MidNite Logger").setContentText("Running").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).getNotification();
            startForeground(1, n);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (db != null) db.event("INFO", "Service停止");
        getSharedPreferences(Config.PREFS, 0).edit().putBoolean("running", false).apply();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
