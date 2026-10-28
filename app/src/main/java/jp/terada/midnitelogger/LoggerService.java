package jp.terada.midnitelogger;

import android.app.*; import android.app.Service; import android.content.*; import android.net.wifi.WifiManager; import android.os.*;
import java.util.List; import java.util.concurrent.*;

public class LoggerService extends Service {
    private ScheduledExecutorService ex; private Database db; private WifiNetworkManager wifi;
    private PowerManager.WakeLock wakeLock; private WifiManager.WifiLock wifiLock; private long lastUploadAttemptMs;

    @Override public void onCreate() {
        super.onCreate(); db=new Database(this); wifi=new WifiNetworkManager(this); foreground();
        PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE); if(pm!=null){wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MidniteLogger:cycle");wakeLock.setReferenceCounted(false);wakeLock.acquire();}
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE); if(wm!=null){wifiLock=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"MidniteLogger:wifi");wifiLock.setReferenceCounted(false);wifiLock.acquire();}
        getSharedPreferences(Config.PREFS,0).edit().putBoolean("running",true).apply(); db.event("INFO","Service開始");
        ex=Executors.newSingleThreadScheduledExecutor();
        ex.scheduleWithFixedDelay(new Runnable(){@Override public void run(){cycle();}},0L,Config.LOG_INTERVAL_MS,TimeUnit.MILLISECONDS);
    }

    private void cycle() {
        final long now=System.currentTimeMillis(); SharedPreferences prefs=getSharedPreferences(Config.PREFS,0);
        String classic=prefs.getString("classic_wifi_ssid",Config.CLASSIC_WIFI_SSID); if(classic==null||classic.trim().length()==0)classic=Config.CLASSIC_WIFI_SSID;
        List<String> uploads=wifi.parseUploadSsidList(prefs.getString("upload_wifi_ssids",Config.DEFAULT_UPLOAD_WIFI_SSIDS));
        try {
            db.event("INFO","WiFi="+wifi.connectionDiagnostics()+" targetClassic="+classic);

            if (wifi.shouldUploadOnCurrentNetwork(uploads)) {
                lastUploadAttemptMs=now; db.event("OK","外部WiFi接続中: upload実行");
                try { int n=new SupabaseUploader(this,db).upload30s(); db.event("OK","Upload完了 "+n+"件"); }
                catch(Exception e){ db.event("ERROR","Upload失敗: "+e.toString()); }
                finally { restoreAndVerifyClassic(classic); }
                return;
            }

            // SSID is advisory only. Actual Modbus communication is the authority.
            if (tryClassicRead("通常周期")) {
                if (now-lastUploadAttemptMs>=Config.UPLOAD_RETRY_COOLDOWN_MS) {
                    String candidate=wifi.findUploadNetworkInScanResults(uploads);
                    if(candidate!=null){db.event("INFO","upload WiFi検出: "+candidate+" 接続試行");lastUploadAttemptMs=now;if(wifi.connectToSsid(candidate)){db.event("OK","upload WiFi接続完了: "+candidate);}else db.event("ERROR","upload WiFi接続失敗: "+candidate);}
                }
                return;
            }

            db.event("INFO","Classic通信不可のためNETGEAR復帰試行: "+wifi.connectionDiagnostics());
            restoreAndVerifyClassic(classic);
        } catch(Exception e){ db.event("ERROR","cycle error: "+e.toString()); }
    }

    private boolean tryClassicRead(String reason) {
        try {
            db.event("INFO",reason+": Classic Modbus読取り開始");
            ClassicReader r=new ClassicReader(new Modbus.L(){@Override public void log(String level,String msg){db.event(level,msg);}});
            Record x=r.read(); db.insert(x); db.event("DATA","Battery="+x.batteryV+" PV="+x.pvV+" Power="+x.watts); db.purgeEvents(); return true;
        } catch(Exception e){db.event("ERROR",reason+": Classic Modbus失敗: "+e.toString());return false;}
    }

    private boolean restoreAndVerifyClassic(String classicSsid) {
        db.event("INFO","Classic復帰開始 SSID="+classicSsid+" "+wifi.connectionDiagnostics());
        boolean network=wifi.restoreClassicNetwork(classicSsid);
        db.event("INFO","Classic WiFi復帰結果="+network+" "+wifi.connectionDiagnostics());
        long end=System.currentTimeMillis()+Config.UPLOAD_RESTORE_WAIT_MS;
        while(System.currentTimeMillis()<end){
            if(tryClassicRead("復帰確認")){db.event("OK","Classic復帰完了: Modbus応答成功");return true;}
            try{Thread.sleep(5000L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        db.event("ERROR","Classic復帰失敗: Modbus応答なし "+wifi.connectionDiagnostics()); return false;
    }

    private void foreground(){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NotificationManager.class);NotificationChannel c=new NotificationChannel("midnite_logger","MidNite Logger",NotificationManager.IMPORTANCE_LOW);nm.createNotificationChannel(c);startForeground(1,new Notification.Builder(this,"midnite_logger").setContentTitle("MidNite Logger").setSmallIcon(android.R.drawable.stat_notify_sync).setContentText("Running").setOngoing(true).build());}}
    @Override public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
    @Override public void onDestroy(){if(ex!=null)ex.shutdownNow();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();if(db!=null)db.event("INFO","Service停止");getSharedPreferences(Config.PREFS,0).edit().putBoolean("running",false).apply();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
