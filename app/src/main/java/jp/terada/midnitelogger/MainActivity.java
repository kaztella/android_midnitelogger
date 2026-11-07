package jp.terada.midnitelogger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String[] CHART_COLUMNS = {
            "battery_v", "pv_v", "classic_a", "wb_a", "wb_precise_a",
            "pv_a", "watts", "daily_kwh", "battery_temp", "fet_temp", "pcb_temp"
    };
    private static final String[] CHART_UNITS = {"V", "V", "A", "A", "A", "A", "W", "kWh", "C", "C", "C"};

    private static final long DAILY_FIRST_INDEX_WAIT_MS = 5000L;
    private static final long DAILY_NEXT_INDEX_WAIT_MS = 10000L;
    private static final long DAILY_RETRY_WAIT_1_MS = 10000L;
    private static final long DAILY_RETRY_WAIT_2_MS = 30000L;
    private static final long DAILY_FAILED_DAY_WAIT_MS = 30000L;

    private Database db;
    private TextView status, values, events, storage, uploadState, dailyRescueState, wifiStatus, chartRangeLabel;
    private EditText dailyStartDate, dailyEndDate, url, key, device, classicWifiSsid, uploadWifiSsidList;
    private EditText chartMinValue, chartMaxValue;
    private Spinner metric;
    private Button chartAutoToggle;
    private ChartView chart;
    private final Handler handler = new Handler();
    private long chartWindowMs = 24L * 60L * 60L * 1000L;
    private long chartOffsetMs = 0L;
    private boolean chartAutoY = true;

    private volatile boolean dailyRescueRunning = false;
    private volatile boolean dailyRescueCancelRequested = false;
    private Thread dailyRescueThread;
    private volatile boolean dailyRawDiagnosticRunning = false;
    private Thread dailyRawDiagnosticThread;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 5000L); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Database(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 20);
        scroll.addView(root);

        status = addText(root, "状態");
        values = addText(root, "データ未取得");

        LinearLayout actionRow = new LinearLayout(this);
        root.addView(actionRow);
        addButton(actionRow, "開始", new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (dailyRescueRunning || dailyRawDiagnosticRunning) {
                    Toast.makeText(MainActivity.this, "Daily Log処理中は通常ロギングを開始できません", Toast.LENGTH_SHORT).show();
                    return;
                }
                startService(new Intent(MainActivity.this, LoggerService.class));
                refresh();
            }
        });
        addButton(actionRow, "停止", new View.OnClickListener() {
            @Override public void onClick(View view) {
                stopService(new Intent(MainActivity.this, LoggerService.class));
                if (dailyRescueRunning) {
                    dailyRescueCancelRequested = true;
                    db.event("INFO", "停止要求: Daily Log集中救出を中止");
                    if (dailyRescueState != null) dailyRescueState.setText("Daily Log集中救出: 停止要求中...");
                    if (dailyRescueThread != null) dailyRescueThread.interrupt();
                }
                if (dailyRawDiagnosticRunning) {
                    db.event("INFO", "停止要求: 32word診断を中止");
                    if (dailyRawDiagnosticThread != null) dailyRawDiagnosticThread.interrupt();
                }
                refresh();
            }
        });
        addButton(actionRow, "30秒Upload", new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveSettings();
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { int count = new SupabaseUploader(MainActivity.this, db).upload30s(); db.event("OK", "Supabase送信 " + count + "件"); }
                        catch (Exception ex) { db.event("ERROR", "Upload: " + ex.getMessage()); }
                        handler.post(new Runnable() { @Override public void run() { refresh(); } });
                    }
                }).start();
            }
        });

        addText(root, "Supabase RPC URL"); url = addEdit(root);
        addText(root, "Publishable key"); key = addEdit(root);
        addText(root, "device_id"); device = addEdit(root);
        addText(root, "Classic Wi-Fi SSID"); classicWifiSsid = addEdit(root);
        addText(root, "Upload Wi-Fi SSIDs"); uploadWifiSsidList = addEdit(root);
        addText(root, "現在の Wi-Fi"); wifiStatus = addText(root, "未確認");

        SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
        url.setText(prefs.getString("supabase_rpc_url", Config.DEFAULT_SUPABASE_RPC_URL));
        key.setText(prefs.getString("supabase_key", ""));
        device.setText(prefs.getString("device_id", "shl24-01"));
        classicWifiSsid.setText(prefs.getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID));
        uploadWifiSsidList.setText(prefs.getString("upload_wifi_ssids", Config.DEFAULT_UPLOAD_WIFI_SSIDS));

        LinearLayout wifiRow = new LinearLayout(this); root.addView(wifiRow);
        addButton(wifiRow, "Wi-Fi状態確認", new View.OnClickListener() { @Override public void onClick(View v) { updateWifiStatus(); } });
        addButton(wifiRow, "NETGEAR復帰", new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveSettings();
                WifiNetworkManager wifi = new WifiNetworkManager(MainActivity.this);
                String ssid = getSharedPreferences(Config.PREFS, 0).getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID);
                boolean ok = wifi.restoreClassicNetwork(ssid);
                Toast.makeText(MainActivity.this, ok ? "NETGEARへ復帰を試行" : "NETGEARへ戻れませんでした", Toast.LENGTH_SHORT).show();
                updateWifiStatus();
            }
        });

        LinearLayout dbRow = new LinearLayout(this); root.addView(dbRow);
        addButton(dbRow, "設定保存", new View.OnClickListener() { @Override public void onClick(View v) { saveSettings(); } });
        addButton(dbRow, "DBバックアップ", new View.OnClickListener() { @Override public void onClick(View v) { backupDatabase(); } });
        storage = addText(root, "");
        uploadState = addText(root, "Upload状態を取得中...");

        addText(root, "Daily Log集中救出・Upload");
        LinearLayout dailyDateRow = new LinearLayout(this); dailyDateRow.setOrientation(LinearLayout.HORIZONTAL);
        dailyStartDate = addEdit(dailyDateRow); dailyStartDate.setHint("開始日 yyyy-MM-dd");
        dailyEndDate = addEdit(dailyDateRow); dailyEndDate.setHint("終了日 yyyy-MM-dd"); root.addView(dailyDateRow);
        Calendar defaultEnd = Calendar.getInstance(); Calendar defaultStart = (Calendar) defaultEnd.clone(); defaultStart.add(Calendar.DAY_OF_MONTH, -30);
        SimpleDateFormat dailyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dailyStartDate.setText(dailyFormat.format(defaultStart.getTime())); dailyEndDate.setText(dailyFormat.format(defaultEnd.getTime()));
        addButton(root, "Daily Log集中救出・Upload", new View.OnClickListener() { @Override public void onClick(View v) { runDailyLogConcentrated(); } });
        addButton(root, "32word診断", new View.OnClickListener() { @Override public void onClick(View v) { runDaily32WordDiagnostic(); } });
        dailyRescueState = addText(root, "Daily Log: 待機中");

        addText(root, "グラフ項目");
        metric = new Spinner(this);
        String[] names = {"バッテリー電圧","PV電圧","Classic電流","WB電流","WB精密電流","PV電流","PV電力","本日発電量","バッテリー温度","FET温度","基板温度"};
        metric.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names)); root.addView(metric);
        chart = new ChartView(this); root.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500));
        chartRangeLabel = addText(root, "表示範囲: 24時間");
        addButton(root, "グラフ更新", new View.OnClickListener() { @Override public void onClick(View v) { updateGraph(); } });
        addText(root, "通信/イベントログ（直近50件）"); events = addText(root, "");
        addButton(root, "ログ更新", new View.OnClickListener() { @Override public void onClick(View v) { refresh(); } });

        setContentView(scroll);
        refresh();
        handler.postDelayed(refreshTask, 3000L);
    }

    private void runDaily32WordDiagnostic() {
        if (dailyRescueRunning || dailyRawDiagnosticRunning) {
            Toast.makeText(this, dailyRescueRunning ? "Daily Log集中救出中です" : "32word診断は既に実行中です", Toast.LENGTH_SHORT).show();
            return;
        }
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US); fmt.setLenient(false);
        final Date targetDate;
        try { targetDate = fmt.parse(dailyStartDate.getText().toString().trim()); }
        catch (Exception e) { Toast.makeText(this, "開始日を yyyy-MM-dd で入力してください", Toast.LENGTH_LONG).show(); return; }

        final boolean wasRunning = getSharedPreferences(Config.PREFS, 0).getBoolean("running", false);
        if (wasRunning) stopService(new Intent(this, LoggerService.class));
        dailyRawDiagnosticRunning = true;
        dailyRescueState.setText("32word診断: 準備中");

        dailyRawDiagnosticThread = new Thread(new Runnable() {
            @Override public void run() {
                String result;
                WifiNetworkManager wifi = new WifiNetworkManager(MainActivity.this);
                SharedPreferences p = getSharedPreferences(Config.PREFS, 0);
                String ssid = p.getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID);
                final Modbus.L log = new Modbus.L() { @Override public void log(String level, String message) { db.event(level, message); } };
                try {
                    postDailyState("32word診断: Classic側へ復帰中...");
                    if (!wifi.restoreClassicNetwork(ssid)) throw new IOException("Classic Wi-Fi/IPへ復帰できません: " + wifi.connectionDiagnostics());
                    ClassicReader reader = new ClassicReader(log);
                    ClassicReader.DailyTimestamp newest = readTimestampWithRetry(reader, 1, 3);
                    int estimatedIndex = 1 + (int) daysBetween(targetDate, newest.calendar.getTime());
                    if (estimatedIndex < 1 || estimatedIndex > 1023) throw new IOException("対象日がDaily Log範囲外 index=" + estimatedIndex);
                    db.event("INFO", "32WORD診断 target=" + fmt.format(targetDate) + " newest=" + newest.date + " estimatedIndex=" + estimatedIndex);
                    postDailyState("32word診断: 32word取得中 index=" + estimatedIndex);
                    new DailyLog32WordDiagnostic(db, log).run(estimatedIndex);
                    result = "完了。イベントログを確認してください";
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) { db.event("INFO", "32WORD診断 ユーザー中止"); result = "ユーザー操作により中止"; }
                    else { db.event("ERROR", "32WORD診断失敗: " + e); result = "失敗: " + e.getMessage(); }
                } finally {
                    wifi.restoreClassicNetwork(ssid);
                    dailyRawDiagnosticRunning = false;
                    dailyRawDiagnosticThread = null;
                    if (wasRunning && !Thread.currentThread().isInterrupted()) startService(new Intent(MainActivity.this, LoggerService.class));
                }
                final String show = result; handler.post(new Runnable() { @Override public void run() { dailyRescueState.setText("32word診断: " + show); refresh(); } });
            }
        });
        dailyRawDiagnosticThread.start();
    }

    private void runDailyLogConcentrated() {
        if (dailyRescueRunning || dailyRawDiagnosticRunning) {
            Toast.makeText(this, "Daily Log処理は既に実行中です", Toast.LENGTH_SHORT).show(); return;
        }
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US); fmt.setLenient(false);
        final Date startDate, endDate;
        try { startDate = fmt.parse(dailyStartDate.getText().toString().trim()); endDate = fmt.parse(dailyEndDate.getText().toString().trim()); }
        catch (Exception e) { Toast.makeText(this, "日付は yyyy-MM-dd で入力してください", Toast.LENGTH_LONG).show(); return; }
        if (startDate.after(endDate)) { Toast.makeText(this, "開始日は終了日以前にしてください", Toast.LENGTH_LONG).show(); return; }
        if (daysBetween(startDate, endDate) + 1 > 400) { Toast.makeText(this, "集中救出は最大400日までです", Toast.LENGTH_LONG).show(); return; }
        saveSettings();
        final boolean wasRunning = getSharedPreferences(Config.PREFS, 0).getBoolean("running", false);
        if (wasRunning) stopService(new Intent(this, LoggerService.class));
        dailyRescueCancelRequested = false; dailyRescueRunning = true;
        dailyRescueThread = new Thread(new Runnable() {
            @Override public void run() {
                String result; int rescued = 0;
                WifiNetworkManager wifi = new WifiNetworkManager(MainActivity.this);
                String ssid = getSharedPreferences(Config.PREFS,0).getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID);
                final Modbus.L log = new Modbus.L(){@Override public void log(String l,String m){db.event(l,m);}};
                try {
                    if (!wifi.restoreClassicNetwork(ssid)) throw new IOException("Classic Wi-Fi/IPへ復帰できません");
                    ClassicReader reader = new ClassicReader(log);
                    ClassicReader.DailyTimestamp newest = readTimestampWithRetry(reader,1,3);
                    Date newestDate = newest.calendar.getTime();
                    int fromIndex = 1 + (int)daysBetween(startDate,newestDate);
                    int toIndex = 1 + (int)daysBetween(endDate,newestDate);
                    if (fromIndex < toIndex) { int t=fromIndex; fromIndex=toIndex; toIndex=t; }
                    db.event("INFO","Daily集中 index計算 latest="+newest.date+" range="+fromIndex+".."+toIndex);
                    for(int dayIndex=fromIndex;dayIndex>=toIndex;dayIndex--){
                        checkDailyRescueCancelled();
                        long wait=dayIndex==fromIndex?DAILY_FIRST_INDEX_WAIT_MS:DAILY_NEXT_INDEX_WAIT_MS;
                        db.event("INFO","Daily集中 dayIndex切替待ち index="+dayIndex+" wait="+wait+"ms"); sleepDaily(wait);
                        Calendar expected=Calendar.getInstance(); expected.setTime(newestDate); expected.add(Calendar.DAY_OF_MONTH,-(dayIndex-1));
                        String expectedDate=fmt.format(expected.getTime()); DailyLogRecord row=null;
                        for(int attempt=1;attempt<=3;attempt++){
                            checkDailyRescueCancelled();
                            try{
                                db.event("INFO","Daily集中 1日取得開始 expected="+expectedDate+" index="+dayIndex+" attempt="+attempt);
                                DailyLogRecord c=reader.readDailyLog(dayIndex);
                                if(!expectedDate.equals(c.logDate))throw new IOException("DailyLog日付不一致 expected="+expectedDate+" actual="+c.logDate);
                                row=c; break;
                            }catch(DailyRescueCancelledException e){throw e;}
                            catch(Exception e){db.event(attempt<3?"WARN":"ERROR","Daily集中 1日再取得 expected="+expectedDate+" index="+dayIndex+" attempt="+attempt+"/3 "+e); if(attempt==1)sleepDaily(DAILY_RETRY_WAIT_1_MS); else if(attempt==2)sleepDaily(DAILY_RETRY_WAIT_2_MS);}
                        }
                        if(row==null){db.event("ERROR","Daily集中 未取得 date="+expectedDate+" index="+dayIndex+" after 3 attempts");sleepDaily(DAILY_FAILED_DAY_WAIT_MS);continue;}
                        if(!db.hasDailyLogDate(row.logDate)&&db.insertDailyLog(row)>=0)rescued++;
                    }
                    result="完了: 救出 "+rescued+"件";
                }catch(DailyRescueCancelledException e){db.event("INFO","Daily Log集中救出 ユーザー中止");result="ユーザー操作により中止";}
                catch(Exception e){db.event("ERROR","Daily Log集中救出失敗: "+e);result="失敗: "+e.getMessage();}
                finally{wifi.restoreClassicNetwork(ssid);dailyRescueRunning=false;dailyRescueThread=null;if(wasRunning&&!dailyRescueCancelRequested)startService(new Intent(MainActivity.this,LoggerService.class));}
                final String show=result;handler.post(new Runnable(){@Override public void run(){dailyRescueState.setText("Daily Log集中救出: "+show);refresh();}});
            }
        });
        dailyRescueThread.start();
    }

    private ClassicReader.DailyTimestamp readTimestampWithRetry(ClassicReader r,int index,int attempts)throws Exception{
        Exception last=null;for(int a=1;a<=attempts;a++){try{return r.readDailyTimestamp(index);}catch(Exception e){last=e;if(a<attempts)sleepDaily(a==1?DAILY_RETRY_WAIT_1_MS:DAILY_RETRY_WAIT_2_MS);}}
        throw last;
    }
    private void checkDailyRescueCancelled()throws DailyRescueCancelledException{if(dailyRescueCancelRequested||Thread.currentThread().isInterrupted())throw new DailyRescueCancelledException("停止");}
    private void sleepDaily(long ms)throws IOException{checkDailyRescueCancelled();try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new DailyRescueCancelledException("停止");}checkDailyRescueCancelled();}
    private static long daysBetween(Date older,Date newer){Calendar a=Calendar.getInstance(),b=Calendar.getInstance();a.setTime(older);b.setTime(newer);clearDateTime(a);clearDateTime(b);return(b.getTimeInMillis()-a.getTimeInMillis())/86400000L;}
    private static void clearDateTime(Calendar c){c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);}
    private static final class DailyRescueCancelledException extends IOException{DailyRescueCancelledException(String m){super(m);}}

    private void saveSettings(){getSharedPreferences(Config.PREFS,0).edit().putString("supabase_rpc_url",url.getText().toString().trim()).putString("supabase_key",key.getText().toString().trim()).putString("device_id",device.getText().toString().trim()).putString("classic_wifi_ssid",classicWifiSsid.getText().toString().trim()).putString("upload_wifi_ssids",uploadWifiSsidList.getText().toString().trim()).apply();}
    private void backupDatabase(){try{File f=db.backup();Toast.makeText(this,"保存: "+f.getAbsolutePath(),Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}
    private void refresh(){boolean running=getSharedPreferences(Config.PREFS,0).getBoolean("running",false);status.setText(running?"ロギング中":dailyRescueRunning?"Daily Log集中救出中":dailyRawDiagnosticRunning?"32word診断中":"停止中");Record r=db.latest();if(r!=null)values.setText(String.format(Locale.US,"最終記録 %s\nBattery %.2f V  PV %.2f V\nWB %.2f A  Power %d W",r.ts,r.batteryV,r.pvV,r.wbPreciseA,r.watts));events.setText(db.recentEvents(50));storage.setText(String.format(Locale.US,"測定 %d件 / DB %.2f MB / ERROR %d件",db.count(),db.dbBytes()/1048576.0,db.errorCount()));uploadState.setText("通常ログ: Upload済み "+db.uploadedCount()+" / 全記録 "+db.count()+"\nDaily Log: Upload済み "+db.dailyUploadedCount()+" / 全記録 "+db.dailyCount());updateWifiStatus();updateGraph();}
    private void updateWifiStatus(){String s=new WifiNetworkManager(this).getConnectedSsid();wifiStatus.setText(s==null?"未接続":s);}
    private void updateGraph(){if(metric==null||chart==null)return;int i=metric.getSelectedItemPosition();long end=System.currentTimeMillis()-chartOffsetMs,start=end-chartWindowMs;SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);List<float[]>p=db.chart(CHART_COLUMNS[i],f.format(new Date(start)),f.format(new Date(end)),240);chart.set(p,metric.getSelectedItem()+" ["+CHART_UNITS[i]+"]",start,end,null,null,true);}
    private void postDailyState(final String s){handler.post(new Runnable(){@Override public void run(){if(dailyRescueState!=null)dailyRescueState.setText(s);}});}
    private TextView addText(LinearLayout p,String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(16);v.setTextColor(Color.DKGRAY);v.setPadding(4,6,4,6);p.addView(v);return v;}
    private EditText addEdit(LinearLayout p){EditText e=new EditText(this);e.setSingleLine(true);p.addView(e);return e;}
    private Button addButton(LinearLayout p,String s,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setOnClickListener(l);p.addView(b);return b;}

    @Override protected void onDestroy(){handler.removeCallbacks(refreshTask);if(dailyRescueRunning){dailyRescueCancelRequested=true;if(dailyRescueThread!=null)dailyRescueThread.interrupt();}if(dailyRawDiagnosticRunning&&dailyRawDiagnosticThread!=null)dailyRawDiagnosticThread.interrupt();super.onDestroy();}
}
