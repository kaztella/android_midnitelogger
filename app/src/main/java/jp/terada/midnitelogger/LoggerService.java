package jp.terada.midnitelogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LoggerService extends Service {
    private ScheduledExecutorService ex;
    private Database db;
    private WifiNetworkManager wifiNetworkManager;

    @Override
    public void onCreate() {
        super.onCreate();
        db = new Database(this);
        wifiNetworkManager = new WifiNetworkManager(this);
        foreground();
        getSharedPreferences(Config.PREFS, 0).edit().putBoolean("running", true).apply();
        db.event("INFO", "Service開始");
        ex = Executors.newSingleThreadScheduledExecutor();
        ex.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                cycle();
            }
        }, 0L, Config.LOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void cycle() {
        try {
            SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
            String classicWifiSsid = prefs.getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID);
            if (classicWifiSsid == null || classicWifiSsid.trim().isEmpty()) {
                classicWifiSsid = Config.CLASSIC_WIFI_SSID;
            }
            String uploadWifiSsidCsv = prefs.getString("upload_wifi_ssids", Config.DEFAULT_UPLOAD_WIFI_SSIDS);
            List<String> uploadSsids = wifiNetworkManager.parseUploadSsidList(uploadWifiSsidCsv);

            String currentSsid = wifiNetworkManager.getConnectedSsid();
            db.event("INFO", "WiFi=" + (currentSsid == null ? "unknown" : currentSsid)
                    + " targetClassic=" + classicWifiSsid);

            if (wifiNetworkManager.shouldUploadOnCurrentNetwork(uploadSsids)) {
                db.event("OK", "外部WiFi接続中: upload実行");
                try {
                    int count = new SupabaseUploader(this, db).upload30s();
                    db.event("OK", "Upload完了 " + count + "件");
                    if (!wifiNetworkManager.restoreClassicNetwork()) {
                        db.event("ERROR", "NETGEAR復帰失敗");
                    } else {
                        db.event("INFO", "NETGEAR復帰試行完了");
                    }
                    return;
                } catch (Exception ex) {
                    db.event("ERROR", "Upload失敗: " + ex.getMessage());
                    if (!wifiNetworkManager.restoreClassicNetwork()) {
                        db.event("ERROR", "NETGEAR復帰失敗");
                    }
                    return;
                }
            }

            if (wifiNetworkManager.isConnectedTo(classicWifiSsid)) {
                db.event("INFO", "NETGEAR接続中: Classic読取り開始");
                ClassicReader reader = new ClassicReader(new Modbus.L() {
                    @Override
                    public void log(String level, String message) {
                        db.event(level, message);
                    }
                });
                Record record = reader.read();
                db.insert(record);
                db.event("DATA", "Battery=" + record.batteryV + " PV=" + record.pvV
                        + " Power=" + record.watts);
                return;
            }

            db.event("INFO", "Classic WiFi条件未満: 既存データ保持のみ current="
                    + (currentSsid == null ? "unknown" : currentSsid));
        } catch (Exception ex) {
            db.event("ERROR", "cycle error: " + ex.getMessage());
        }
    }

    private void foreground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    "midnite_logger",
                    "MidNite Logger",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, "midnite_logger")
                    .setContentTitle("MidNite Logger")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentText("Running")
                    .setOngoing(true)
                    .build();
            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ex != null) {
            ex.shutdownNow();
        }
        if (db != null) {
            db.event("INFO", "Service停止");
        }
        getSharedPreferences(Config.PREFS, 0).edit().putBoolean("running", false).apply();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
