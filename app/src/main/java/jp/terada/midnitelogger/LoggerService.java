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
    private long cycleNumber;

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
        final long cycle = ++cycleNumber;
        String classicIp = preferences.getString("ip", "192.168.10.1");
        String learnedSsid = preferences.getString("ssid", "");
        WifiController wifi = new WifiController(this);
        DiagnosticLog.i(this, "===== 周期 #" + cycle + " 開始 =====");
        try {
            DiagnosticLog.i(this, "接続先=" + classicIp + ":" + Config.PORT
                    + " 現在SSID=" + safe(wifi.currentSsid())
                    + " 端末IP=" + wifi.currentIpAddress());

            boolean readOk = tryReadCurrentNetwork(classicIp);
            if (!readOk && learnedSsid.length() > 0) {
                DiagnosticLog.i(this, "記憶済みClassic側SSIDへ復帰開始 SSID=" + learnedSsid);
                if (wifi.connect(learnedSsid, Config.CLASSIC_RECONNECT_SECONDS)) {
                    DiagnosticLog.i(this, "SSID接続を確認。Modbus応答で復帰を検証します");
                    try {
                        readAndStore(classicIp);
                        readOk = true;
                        DiagnosticLog.ok(this, "Classic側復帰完了。Modbus応答成功 SSID=" + learnedSsid);
                    } catch (Throwable verify) {
                        DiagnosticLog.e(this, "SSIDは一致しましたがModbus検証失敗 " + describe(verify), verify);
                    }
                } else {
                    DiagnosticLog.e(this, "Classic側Wi-Fi復帰失敗 SSID=" + learnedSsid, null);
                }
            } else if (!readOk) {
                DiagnosticLog.i(this, "Classic側SSIDは未学習です。Classic側Wi-Fiへ手動接続してください");
            }

            if (!readOk) {
                DiagnosticLog.e(this, "周期 #" + cycle + " は未記録。次周期で再試行します", null);
                return;
            }

            rememberClassicSsidAfterModbus(wifi);

            // 自動探索は行わない。アップロードボタンの明示操作時だけWi-Fiを切り替える。
            if (uploadNow) {
                uploadNow = false;
                performManualUpload(classicIp, wifi);
            }
        } catch (Throwable t) {
            DiagnosticLog.e(this, "周期処理失敗 " + describe(t), t);
        } finally {
            DiagnosticLog.i(this, "===== 周期 #" + cycle + " 完了・次回まで"
                    + Config.LOG_INTERVAL_SECONDS + "秒 =====");
        }
    }

    private boolean tryReadCurrentNetwork(String classicIp) {
        try {
            readAndStore(classicIp);
            return true;
        } catch (Throwable first) {
            DiagnosticLog.e(this, "現在のWi-FiでModbus通信失敗 " + describe(first), first);
            return false;
        }
    }

    private void performManualUpload(String classicIp, WifiController wifi) {
        String learnedSsid = preferences.getString("ssid", "");
        String url = preferences.getString("url", "").trim();
        if (learnedSsid.length() == 0) {
            DiagnosticLog.e(this, "Classic側SSID未確定のため外部Wi-Fi切替を中止", null);
            return;
        }
        if (url.length() == 0) {
            DiagnosticLog.e(this, "アップロードURL未設定のためWi-Fi切替を中止", null);
            return;
        }

        try {
            DiagnosticLog.i(this, "手動アップロード: 外部Wi-Fi探索開始");
            String candidate = wifi.bestConfiguredCandidate(learnedSsid);
            if (candidate == null) {
                DiagnosticLog.i(this, "アップロード可能な外部Wi-Fiなし、またはWi-Fi探索権限なし");
                return;
            }
            DiagnosticLog.i(this, "外部Wi-Fi検出 SSID=" + candidate);
            if (!wifi.connect(candidate, 20)) {
                DiagnosticLog.e(this, "外部Wi-Fi接続失敗 SSID=" + candidate, null);
                return;
            }

            DiagnosticLog.ok(this, "外部Wi-Fi接続成功 SSID=" + candidate
                    + " 端末IP=" + wifi.currentIpAddress());
            int sent = Uploader.upload(database, url,
                    Config.UPLOAD_LIMIT_SECONDS, Config.UPLOAD_BATCH_SIZE);
            DiagnosticLog.ok(this, sent + "件送信");
        } catch (Throwable uploadError) {
            DiagnosticLog.e(this, "アップロード処理失敗 " + describe(uploadError), uploadError);
        } finally {
            restoreAndVerifyClassic(classicIp, learnedSsid, wifi);
        }
    }

    private void restoreAndVerifyClassic(String classicIp, String learnedSsid, WifiController wifi) {
        try {
            DiagnosticLog.i(this, "Classic側Wi-Fi復帰開始 SSID=" + learnedSsid);
            if (!wifi.connect(learnedSsid, Config.CLASSIC_RECONNECT_SECONDS)) {
                DiagnosticLog.e(this, "Classic側Wi-Fi復帰失敗。端末は外部ネットワークのまま可能性あり", null);
                return;
            }
            DiagnosticLog.i(this, "SSID復帰確認。Modbus応答で最終検証します 端末IP=" + wifi.currentIpAddress());
            readAndStore(classicIp);
            DiagnosticLog.ok(this, "Classic側復帰完了。Modbus応答・SQLite保存成功");
        } catch (Throwable restoreError) {
            DiagnosticLog.e(this, "Classic側復帰検証失敗 " + describe(restoreError), restoreError);
        }
    }

    private static String safe(String value) {
        return value == null || value.length() == 0 ? "取得不可" : value;
    }

    private void rememberClassicSsidAfterModbus(WifiController wifi) {
        String ssid = wifi.currentSsid();
        if (ssid != null && ssid.length() > 0) {
            String old = preferences.getString("ssid", "");
            preferences.edit().putString("ssid", ssid).apply();
            if (!ssid.equals(old)) DiagnosticLog.ok(this, "Modbus通信成功を確認。Classic側SSIDとして記憶 SSID=" + ssid);
            else DiagnosticLog.i(this, "Modbus通信成功。Classic側SSID確認 SSID=" + ssid);
            return;
        }
        if (!wifi.isLocationServiceEnabled()) {
            DiagnosticLog.e(this, "Modbus通信は成功しましたがSSID取得不可。端末の位置情報をONにしてください。UNKNOWN_SSIDは保存しません", null);
        } else {
            DiagnosticLog.e(this, "Modbus通信は成功しましたがSSID取得不可。アプリの位置情報/付近のデバイス権限を許可してください。UNKNOWN_SSIDは保存しません", null);
        }
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
