package jp.terada.midnitelogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LoggerService extends Service {
    private static final String CHANNEL_ID = "midnite_logger";
    private ScheduledExecutorService executor;
    private Database database;
    private SharedPreferences preferences;
    private volatile boolean uploadNow;

    @Override public void onCreate() {
        super.onCreate();
        database = new Database(this);
        preferences = getSharedPreferences("cfg", MODE_PRIVATE);
        try {
            startForegroundMode();
            preferences.edit().putBoolean("running", true).apply();
            DiagnosticLog.i(this, "Service開始");
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() {
                    try { runCycle(); }
                    catch (Throwable t) { DiagnosticLog.e(LoggerService.this, "周期処理の未処理例外 " + describe(t), t); }
                }
            }, 0, Config.LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            preferences.edit().putBoolean("running", false).apply();
            DiagnosticLog.e(this, "Service起動失敗 " + describe(t), t);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "UPLOAD_NOW".equals(intent.getAction())) {
            uploadNow = true;
            DiagnosticLog.i(this, "今すぐ30秒アップロード要求を受付");
        } else {
            DiagnosticLog.i(this, "ロギング開始要求を受付");
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (preferences != null) preferences.edit().putBoolean("running", false).apply();
        DiagnosticLog.i(this, "Service停止");
        if (executor != null) executor.shutdownNow();
        if (database != null) database.close();
        stopForeground(true);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation") private void startForegroundMode() {
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MidNite Logger",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Classicロギング動作通知");
            manager.createNotificationChannel(channel);
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("MidNite Logger")
                    .setContentText("Classicロギング中")
                    .setContentIntent(pending).setOngoing(true).build();
        } else {
            notification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setTicker("MidNite Logger")
                    .setContentTitle("MidNite Logger")
                    .setContentText("Classicロギング中")
                    .setContentIntent(pending).setOngoing(true).getNotification();
        }
        startForeground(1, notification);
    }

    private void runCycle() {
        String loggerSsid = preferences.getString("ssid", "ClassicLogger");
        String classicIp = preferences.getString("ip", "192.168.10.1");
        try {
            DiagnosticLog.i(this, "周期処理開始 接続先=" + classicIp + ":" + Config.PORT + " SSID=" + loggerSsid);
            WifiController wifi = new WifiController(this);
            DiagnosticLog.i(this, "ロガーWi-Fi接続開始 SSID=" + loggerSsid);
            if (!wifi.connect(loggerSsid, 20)) {
                DiagnosticLog.e(this, "ロガーWi-Fi接続失敗 SSID=" + loggerSsid, null);
                return;
            }
            DiagnosticLog.ok(this, "ロガーWi-Fi接続成功 SSID=" + loggerSsid);
            readAndStore(classicIp);
            if (uploadNow || scanDue()) {
                uploadNow = false;
                DiagnosticLog.i(this, "外部Wi-Fi探索開始");
                String candidate = wifi.bestConfiguredCandidate(loggerSsid);
                if (candidate == null) {
                    DiagnosticLog.i(this, "アップロード可能な外部Wi-Fiなし");
                    return;
                }
                DiagnosticLog.i(this, "外部Wi-Fi検出 SSID=" + candidate);
                readAndStore(classicIp);
                if (wifi.connect(candidate, 20)) {
                    DiagnosticLog.ok(this, "外部Wi-Fi接続成功 SSID=" + candidate);
                    int sent = Uploader.upload(database, preferences.getString("url", ""),
                            Config.UPLOAD_LIMIT_SECONDS, Config.UPLOAD_BATCH_SIZE);
                    DiagnosticLog.ok(this, sent + "件送信");
                } else {
                    DiagnosticLog.e(this, "外部Wi-Fi接続失敗 SSID=" + candidate, null);
                }
                if (wifi.connect(loggerSsid, 30)) {
                    DiagnosticLog.ok(this, "ロガーWi-Fi復帰成功");
                    readAndStore(classicIp);
                } else {
                    DiagnosticLog.e(this, "ロガーWi-Fi復帰失敗", null);
                }
            }
        } catch (Throwable t) {
            DiagnosticLog.e(this, "周期処理失敗 " + describe(t), t);
        }
    }

    private boolean scanDue() {
        long now = System.currentTimeMillis();
        long last = preferences.getLong("last_scan", 0);
        if (now - last > Config.SCAN_INTERVAL_SECONDS * 1000L) {
            preferences.edit().putLong("last_scan", now).apply();
            return true;
        }
        return false;
    }

    private void readAndStore(String ip) throws Exception {
        Modbus modbus = new Modbus(new Modbus.Listener() {
            @Override public void onLog(String level, String message) {
                if ("OK".equals(level)) DiagnosticLog.ok(LoggerService.this, message);
                else if ("SEND".equals(level)) DiagnosticLog.send(LoggerService.this, message);
                else if ("RECV".equals(level)) DiagnosticLog.recv(LoggerService.this, message);
                else DiagnosticLog.i(LoggerService.this, message);
            }
        });
        int[] b = modbus.readHoldingRegisters(ip, 4114, 28);
        int[] w = modbus.readHoldingRegisters(ip, 4364, 7);
        int[] precise = modbus.readHoldingRegisters(ip, 4390, 1);
        Record r = new Record();
        r.timestamp = Database.now(); r.batteryV = b[0] / 10.0; r.pvV = b[1] / 10.0;
        r.classicBatteryA = Modbus.signed16(b[2]) / 10.0; r.dailyKwh = b[3] / 10.0; r.watts = b[4];
        r.chargeStage = (b[5] >> 8) & 0xff; r.classicState = b[5] & 0xff; r.pvA = b[6] / 10.0;
        r.dailyAh = b[10]; r.lifetimeKwh = Modbus.unsigned32(b[11], b[12]); r.lifetimeAh = Modbus.unsigned32(b[13], b[14]);
        r.batteryTempC = Modbus.signed16(b[17]) / 10.0; r.fetTempC = Modbus.signed16(b[18]) / 10.0;
        r.pcbTempC = Modbus.signed16(b[19]) / 10.0; r.floatSeconds = b[23];
        r.wbPositiveAh = Modbus.unsigned32(w[0], w[1]); r.wbNegativeAh = Modbus.unsigned32(w[2], w[3]);
        r.wbNetAh = Modbus.signed32(w[4], w[5]); r.wbCurrentA = Modbus.signed16(w[6]) / 10.0;
        r.wbPreciseA = Modbus.signed16(precise[0]) / 100.0;
        DiagnosticLog.data(this, "Battery=" + r.batteryV + "V PV=" + r.pvV + "V Power=" + r.watts + "W WB=" + r.wbCurrentA + "A");
        database.insert(r);
        DiagnosticLog.ok(this, "SQLite保存完了 ts=" + r.timestamp);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
