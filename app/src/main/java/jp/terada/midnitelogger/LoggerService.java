package jp.terada.midnitelogger;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LoggerService extends Service {
    private ScheduledExecutorService executor;
    private Database database;
    private SharedPreferences preferences;
    private volatile boolean uploadNow;

    @Override public void onCreate() {
        super.onCreate(); database = new Database(this); preferences = getSharedPreferences("cfg", MODE_PRIVATE);
        preferences.edit().putBoolean("running", true).apply(); startForegroundMode();
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(new Runnable() { @Override public void run() { runCycle(); } }, 0, Config.LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "UPLOAD_NOW".equals(intent.getAction())) uploadNow = true;
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        preferences.edit().putBoolean("running", false).apply();
        if (executor != null) executor.shutdownNow(); if (database != null) database.close(); stopForeground(true); super.onDestroy();
    }
@SuppressWarnings("deprecation")
private void startForegroundMode() {
    PendingIntent pending = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT
    );

    Notification notification = new Notification.Builder(this)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setTicker("MidNite Logger")
            .setContentTitle("MidNite Logger")
            .setContentText("Classicロギング中")
            .setContentIntent(pending)
            .setOngoing(true)
            .getNotification();

    startForeground(1, notification);
}
    private void runCycle() {
        try {
            String loggerSsid = preferences.getString("ssid", "ClassicLogger");
            String classicIp = preferences.getString("ip", "192.168.10.1");
            WifiController wifi = new WifiController(this);
            if (!wifi.connect(loggerSsid, 20)) { database.addEvent("NETGEAR接続失敗"); return; }
            readAndStore(classicIp);
            if (uploadNow || scanDue()) {
                uploadNow = false; String candidate = wifi.bestConfiguredCandidate(loggerSsid);
                if (candidate != null) {
                    database.addEvent("外部Wi-Fi検出 " + candidate); readAndStore(classicIp);
                    if (wifi.connect(candidate, 20)) {
                        int sent = Uploader.upload(database, preferences.getString("url", ""), Config.UPLOAD_LIMIT_SECONDS, Config.UPLOAD_BATCH_SIZE);
                        database.addEvent(sent + "件送信");
                    }
                    if (wifi.connect(loggerSsid, 30)) { readAndStore(classicIp); database.addEvent("NETGEAR復帰・ロギング再開"); }
                    else database.addEvent("NETGEAR復帰失敗");
                }
            }
        } catch (Exception e) { database.addEvent("ERROR " + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage())); }
    }
    private boolean scanDue() {
        long now = System.currentTimeMillis(); long last = preferences.getLong("last_scan", 0);
        if (now - last > Config.SCAN_INTERVAL_SECONDS * 1000L) { preferences.edit().putLong("last_scan", now).apply(); return true; }
        return false;
    }
    private void readAndStore(String ip) throws Exception {
        Modbus modbus = new Modbus(); int[] b = modbus.readHoldingRegisters(ip, 4114, 28);
        int[] w = modbus.readHoldingRegisters(ip, 4364, 7); int[] precise = modbus.readHoldingRegisters(ip, 4390, 1);
        Record r = new Record(); r.timestamp = Database.now(); r.batteryV = b[0] / 10.0; r.pvV = b[1] / 10.0;
        r.classicBatteryA = Modbus.signed16(b[2]) / 10.0; r.dailyKwh = b[3] / 10.0; r.watts = b[4];
        r.chargeStage = (b[5] >> 8) & 0xff; r.classicState = b[5] & 0xff; r.pvA = b[6] / 10.0;
        r.dailyAh = b[10]; r.lifetimeKwh = Modbus.unsigned32(b[11], b[12]); r.lifetimeAh = Modbus.unsigned32(b[13], b[14]);
        r.batteryTempC = Modbus.signed16(b[17]) / 10.0; r.fetTempC = Modbus.signed16(b[18]) / 10.0;
        r.pcbTempC = Modbus.signed16(b[19]) / 10.0; r.floatSeconds = b[23];
        r.wbPositiveAh = Modbus.unsigned32(w[0], w[1]); r.wbNegativeAh = Modbus.unsigned32(w[2], w[3]);
        r.wbNetAh = Modbus.signed32(w[4], w[5]); r.wbCurrentA = Modbus.signed16(w[6]) / 10.0;
        r.wbPreciseA = Modbus.signed16(precise[0]) / 100.0; database.insert(r);
    }
}
