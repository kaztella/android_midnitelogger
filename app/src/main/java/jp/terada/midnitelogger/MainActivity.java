package jp.terada.midnitelogger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

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
    private TextView status, values, events, storage, uploadState, dailyRescueState, wifiStatus, chartRangeLabel, diagnosticState;
    private EditText dailyStartDate, dailyEndDate, url, key, device, classicWifiSsid, uploadWifiSsidList, chartMinValue, chartMaxValue;
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
    private volatile boolean diagnosticRunning = false;
    private volatile boolean diagnosticCancelRequested = false;
    private Thread diagnosticThread;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 5000L); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Database(this);
        loadPersistedSettingsFile();
        requestRuntimePermissions();

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
                if (dailyRescueRunning || diagnosticRunning) {
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
                    if (dailyRescueThread != null) dailyRescueThread.interrupt();
                    db.event("INFO", "停止要求: Daily Log集中救出を中止");
                }
                if (diagnosticRunning) {
                    diagnosticCancelRequested = true;
                    if (diagnosticThread != null) diagnosticThread.interrupt();
                    db.event("INFO", "停止要求: 32word診断を中止");
                }
                refresh();
            }
        });
        addButton(actionRow, "30秒Upload", new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveSettings();
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { db.event("OK", "Supabase送信 " + new SupabaseUploader(MainActivity.this, db).upload30s() + "件"); }
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
            @Override public void onClick(View view) {
                WifiNetworkManager wifi = new WifiNetworkManager(MainActivity.this);
                String ssid = getSharedPreferences(Config.PREFS,0).getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID);
                boolean ok = wifi.restoreClassicNetwork(ssid);
                Toast.makeText(MainActivity.this, ok ? "Wi-Fi/IP接続を確認" : "NETGEARへ戻れませんでした", Toast.LENGTH_SHORT).show();
                updateWifiStatus();
            }
        });

        LinearLayout dbRow = new LinearLayout(this); root.addView(dbRow);
        addButton(dbRow, "設定保存", new View.OnClickListener() { @Override public void onClick(View v) { saveSettings(); } });
        addButton(dbRow, "設定ファイルを開く", new View.OnClickListener() { @Override public void onClick(View v) { openSettingsFile(); } });
        addButton(dbRow, "DBバックアップ", new View.OnClickListener() { @Override public void onClick(View v) { backupDatabase(); } });
        addButton(dbRow, "DB統合", new View.OnClickListener() { @Override public void onClick(View v) { mergeLatestBackup(); } });
        storage = addText(root, "");
        uploadState = addText(root, "Upload状態を取得中...");

        addText(root, "Daily Log集中救出・Upload");
        LinearLayout dateRow = new LinearLayout(this); dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dailyStartDate = addEdit(dateRow); dailyStartDate.setHint("開始日 yyyy-MM-dd");
        dailyEndDate = addEdit(dateRow); dailyEndDate.setHint("終了日 yyyy-MM-dd");
        root.addView(dateRow);
        Calendar de = Calendar.getInstance(); Calendar ds = (Calendar) de.clone(); ds.add(Calendar.DAY_OF_MONTH, -30);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dailyStartDate.setText(df.format(ds.getTime())); dailyEndDate.setText(df.format(de.getTime()));
        addButton(root, "Daily Log集中救出・Upload", new View.OnClickListener() { @Override public void onClick(View v) { runDailyLogConcentrated(); } });
        dailyRescueState = addText(root, "Daily Log集中救出: 待機中");

        addText(root, "FC104 32word診断");
        addButton(root, "32word診断 (Cat3+Cat4 / 同一TCP)", new View.OnClickListener() { @Override public void onClick(View v) { run32WordDiagnostic(); } });
        diagnosticState = addText(root, "32word診断: 待機中");

        addText(root, "グラフ項目");
        metric = new Spinner(this);
        String[] names = {"バッテリー電圧","PV電圧","Classic電流","WB電流","WB精密電流","PV電流","PV電力","本日発電量","バッテリー温度","FET温度","基板温度"};
        metric.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        metric.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { loadChartSettingsForCurrentMetric(); updateGraph(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        root.addView(metric);

        addText(root, "グラフY軸（固定/自動）");
        LinearLayout rangeRow = new LinearLayout(this); rangeRow.setOrientation(LinearLayout.HORIZONTAL);
        chartAutoToggle = addButton(rangeRow, "Y軸: 自動", new View.OnClickListener() {
            @Override public void onClick(View v) { chartAutoY=!chartAutoY; chartAutoToggle.setText(chartAutoY?"Y軸: 自動":"Y軸: 固定"); persistGraphSettingsForCurrentMetric(); updateGraph(); }
        });
        chartMinValue=addEdit(rangeRow); chartMinValue.setHint("最小"); chartMaxValue=addEdit(rangeRow); chartMaxValue.setHint("最大"); root.addView(rangeRow);

        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL);
        addButton(nav,"時間-",new View.OnClickListener(){@Override public void onClick(View v){changeChartWindow(false);}});
        addButton(nav,"時間+",new View.OnClickListener(){@Override public void onClick(View v){changeChartWindow(true);}});
        addButton(nav,"←",new View.OnClickListener(){@Override public void onClick(View v){shiftChartWindow(-1);}});
        addButton(nav,"→",new View.OnClickListener(){@Override public void onClick(View v){shiftChartWindow(1);}});
        root.addView(nav); chartRangeLabel=addText(root,"表示範囲: 24時間");

        chart=new ChartView(this);
        chart.setChartGestureListener(new ChartView.ChartGestureListener(){
            @Override public void onPan(float r){long d=Math.round(Math.abs(r)*chartWindowMs); if(d<=0)return; chartOffsetMs += r<0?-d:d; persistGraphSettingsForCurrentMetric(); updateGraph();}
            @Override public void onZoom(float s){if(s<=0||Float.isNaN(s)||Float.isInfinite(s))return; long n=Math.round(chartWindowMs*s); long mn=1800000L,mx=604800000L; if(n<mn)n=mn;if(n>mx)n=mx;if(n!=chartWindowMs){chartWindowMs=n;persistGraphSettingsForCurrentMetric();updateGraph();}}
        });
        root.addView(chart,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,500));
        addButton(root,"グラフ更新",new View.OnClickListener(){@Override public void onClick(View v){updateGraph();}});

        addText(root,"通信/イベントログ（直近50件）"); events=addText(root,"");
        addButton(root,"ログ更新",new View.OnClickListener(){@Override public void onClick(View v){refresh();}});

        setContentView(scroll); loadChartSettingsForCurrentMetric(); refresh(); handler.postDelayed(refreshTask,3000L);
    }

    private void run32WordDiagnostic() {
        if (diagnosticRunning || dailyRescueRunning) { Toast.makeText(this,"Daily Log処理は既に実行中です",Toast.LENGTH_SHORT).show(); return; }
        final boolean wasRunning=getSharedPreferences(Config.PREFS,0).getBoolean("running",false);
        if(wasRunning) stopService(new Intent(this,LoggerService.class));
        diagnosticCancelRequested=false; diagnosticRunning=true; diagnosticState.setText("32word診断: 実行中...");
        diagnosticThread=new Thread(new Runnable(){@Override public void run(){String msg; final Modbus.L log=new Modbus.L(){@Override public void log(String l,String m){db.event(l,m);}};
            try{
                if(diagnosticCancelRequested||Thread.currentThread().isInterrupted())throw new InterruptedException("cancelled");
                // Direct block transfer. NO single-value timestamp access, therefore no len=2 FC104 request.
                new DailyLog32WordDiagnostic(db,log).run(1);
                msg="成功: Cat3+Cat4を同一TCPで32word取得";
            }catch(InterruptedException e){Thread.currentThread().interrupt();db.event("INFO","32WORD診断 ユーザー中止");msg="中止";}
            catch(Exception e){db.event("ERROR","32WORD診断失敗: "+e);msg="失敗: "+e.getMessage();}
            finally{diagnosticRunning=false;diagnosticThread=null;if(wasRunning&&!diagnosticCancelRequested)startService(new Intent(MainActivity.this,LoggerService.class));}
            final String show=msg;handler.post(new Runnable(){@Override public void run(){diagnosticState.setText("32word診断: "+show);refresh();}});
        }}); diagnosticThread.start();
    }

    private void runDailyLogConcentrated(){
        if(dailyRescueRunning||diagnosticRunning){Toast.makeText(this,"Daily Log処理は既に実行中です",Toast.LENGTH_SHORT).show();return;}
        final SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);fmt.setLenient(false);
        final Date start,end; try{start=fmt.parse(dailyStartDate.getText().toString().trim());end=fmt.parse(dailyEndDate.getText().toString().trim());}catch(Exception e){Toast.makeText(this,"日付は yyyy-MM-dd で入力してください",Toast.LENGTH_LONG).show();return;}
        if(start.after(end)){Toast.makeText(this,"開始日は終了日以前にしてください",Toast.LENGTH_LONG).show();return;}
        if(daysBetween(start,end)+1>400){Toast.makeText(this,"集中救出は最大400日までです",Toast.LENGTH_LONG).show();return;}
        final boolean wasRunning=getSharedPreferences(Config.PREFS,0).getBoolean("running",false); if(wasRunning)stopService(new Intent(this,LoggerService.class));
        dailyRescueCancelRequested=false;dailyRescueRunning=true;dailyRescueState.setText("Daily Log集中救出: 開始準備中");
        dailyRescueThread=new Thread(new Runnable(){@Override public void run(){int rescued=0,uploaded=0;String finalMessage;final Modbus.L log=new Modbus.L(){@Override public void log(String l,String m){db.event(l,m);}};WifiNetworkManager wifi=new WifiNetworkManager(MainActivity.this);
            try{
                ClassicReader reader=new ClassicReader(log); ClassicReader.DailyTimestamp newest=readTimestampWithRetry(reader,1,3); Date newestDate=newest.calendar.getTime();
                int from=1+(int)daysBetween(start,newestDate),to=1+(int)daysBetween(end,newestDate);int max=Math.max(from,to),min=Math.max(1,Math.min(from,to));
                for(int idx=max;idx>=min;idx--){checkDailyRescueCancelled();sleepDaily(idx==max?DAILY_FIRST_INDEX_WAIT_MS:DAILY_NEXT_INDEX_WAIT_MS);Calendar ex=Calendar.getInstance();ex.setTime(newestDate);ex.add(Calendar.DAY_OF_MONTH,-(idx-1));String expected=fmt.format(ex.getTime());DailyLogRecord row=null;Exception last=null;
                    for(int a=1;a<=3;a++){checkDailyRescueCancelled();try{DailyLogRecord r=reader.readDailyLog(idx);if(!expected.equals(r.logDate))throw new IOException("DailyLog日付不一致 expected="+expected+" actual="+r.logDate);row=r;break;}catch(DailyRescueCancelledException z){throw z;}catch(Exception e){last=e;db.event(a<3?"WARN":"ERROR","Daily集中再取得 date="+expected+" index="+idx+" attempt="+a+"/3 "+e);if(a==1)sleepDaily(DAILY_RETRY_WAIT_1_MS);else if(a==2)sleepDaily(DAILY_RETRY_WAIT_2_MS);}}
                    if(row==null){db.event("ERROR","Daily集中 未取得 date="+expected+" index="+idx+" cause="+last);sleepDaily(DAILY_FAILED_DAY_WAIT_MS);continue;}if(!db.hasDailyLogDate(row.logDate)&&db.insertDailyLog(row)>=0)rescued++;}
                finalMessage="完了: 救出 "+rescued+"件 / Upload "+uploaded+"件";
            }catch(DailyRescueCancelledException e){db.event("INFO","Daily Log集中救出 ユーザー中止");finalMessage="ユーザー操作により中止";}
            catch(Exception e){db.event("ERROR","Daily Log集中救出失敗: "+e);finalMessage="失敗: "+e.getMessage();}
            finally{dailyRescueRunning=false;dailyRescueThread=null;if(wasRunning&&!dailyRescueCancelRequested)startService(new Intent(MainActivity.this,LoggerService.class));}
            final String show=finalMessage;handler.post(new Runnable(){@Override public void run(){dailyRescueState.setText("Daily Log集中救出: "+show);refresh();}});
        }});dailyRescueThread.start();
    }

    private void checkDailyRescueCancelled() throws DailyRescueCancelledException{if(dailyRescueCancelRequested||Thread.currentThread().isInterrupted())throw new DailyRescueCancelledException("cancelled");}
    private ClassicReader.DailyTimestamp readTimestampWithRetry(ClassicReader r,int idx,int attempts)throws Exception{Exception last=null;for(int a=1;a<=attempts;a++){checkDailyRescueCancelled();try{return r.readDailyTimestamp(idx);}catch(Exception e){last=e;if(a==1)sleepDaily(DAILY_RETRY_WAIT_1_MS);else if(a==2)sleepDaily(DAILY_RETRY_WAIT_2_MS);}}throw last;}
    private void sleepDaily(long ms)throws IOException{checkDailyRescueCancelled();try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new DailyRescueCancelledException("interrupted");}checkDailyRescueCancelled();}
    private static long daysBetween(Date older,Date newer){Calendar a=Calendar.getInstance(),b=Calendar.getInstance();a.setTime(older);b.setTime(newer);clearDateTime(a);clearDateTime(b);return(b.getTimeInMillis()-a.getTimeInMillis())/86400000L;}
    private static void clearDateTime(Calendar c){c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);}
    private static final class DailyRescueCancelledException extends IOException{DailyRescueCancelledException(String m){super(m);}}

    private File getBackupDirectory(){File root=Environment.getExternalStorageDirectory();File dir=root==null?new File(getFilesDir(),Config.BACKUP_DIR_NAME):new File(root,Config.BACKUP_DIR_NAME);if(!dir.exists()&&!dir.mkdirs())return getFilesDir();return dir;}
    private File getSettingsFile(){return new File(getBackupDirectory(),Config.SETTINGS_FILE_NAME);}
    private void openSettingsFile(){File f=getSettingsFile();if(!f.exists())writePersistedSettingsFile();if(!f.exists())return;Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(Uri.fromFile(f),"text/plain");try{startActivity(Intent.createChooser(i,"設定ファイルを開く"));}catch(ActivityNotFoundException e){Toast.makeText(this,"テキストエディタが見つかりません",Toast.LENGTH_SHORT).show();}}
    private void loadPersistedSettingsFile(){File f=getSettingsFile();if(!f.exists())return;Properties p=new Properties();FileInputStream in=null;try{in=new FileInputStream(f);p.load(in);SharedPreferences.Editor e=getSharedPreferences(Config.PREFS,0).edit();if(p.getProperty("supabase_rpc_url")!=null)e.putString("supabase_rpc_url",p.getProperty("supabase_rpc_url"));if(p.getProperty("supabase_key")!=null)e.putString("supabase_key",p.getProperty("supabase_key"));if(p.getProperty("device_id")!=null)e.putString("device_id",p.getProperty("device_id"));if(p.getProperty("classic_wifi_ssid")!=null)e.putString("classic_wifi_ssid",p.getProperty("classic_wifi_ssid"));if(p.getProperty("upload_wifi_ssids")!=null)e.putString("upload_wifi_ssids",p.getProperty("upload_wifi_ssids"));e.apply();}catch(IOException ignored){}finally{if(in!=null)try{in.close();}catch(IOException ignored){}}}
    private void writePersistedSettingsFile(){Properties p=new Properties();SharedPreferences s=getSharedPreferences(Config.PREFS,0);p.setProperty("supabase_rpc_url",s.getString("supabase_rpc_url",Config.DEFAULT_SUPABASE_RPC_URL));p.setProperty("supabase_key",s.getString("supabase_key",""));p.setProperty("device_id",s.getString("device_id",""));p.setProperty("classic_wifi_ssid",s.getString("classic_wifi_ssid",Config.CLASSIC_WIFI_SSID));p.setProperty("upload_wifi_ssids",s.getString("upload_wifi_ssids",Config.DEFAULT_UPLOAD_WIFI_SSIDS));FileOutputStream o=null;try{o=new FileOutputStream(getSettingsFile());p.store(o,"Midnite Logger settings");}catch(IOException ignored){}finally{if(o!=null)try{o.close();}catch(IOException ignored){}}}
    private TextView addText(LinearLayout p,String t){TextView v=new TextView(this);v.setText(t);v.setTextSize(16);v.setTextColor(Color.DKGRAY);v.setPadding(4,6,4,6);p.addView(v);return v;}
    private EditText addEdit(LinearLayout p){EditText e=new EditText(this);e.setSingleLine(true);p.addView(e);return e;}
    private Button addButton(LinearLayout p,String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);p.addView(b);return b;}
    private void saveSettings(){getSharedPreferences(Config.PREFS,0).edit().putString("supabase_rpc_url",url.getText().toString().trim()).putString("supabase_key",key.getText().toString().trim()).putString("device_id",device.getText().toString().trim()).putString("classic_wifi_ssid",classicWifiSsid.getText().toString().trim()).putString("upload_wifi_ssids",uploadWifiSsidList.getText().toString().trim()).apply();writePersistedSettingsFile();Toast.makeText(this,"保存しました",Toast.LENGTH_SHORT).show();}
    private void refresh(){boolean running=getSharedPreferences(Config.PREFS,0).getBoolean("running",false);status.setText(diagnosticRunning?"32word診断中":dailyRescueRunning?"Daily Log集中救出中":running?"ロギング中":"停止中");Record r=db.latest();values.setText(r==null?"データ未取得":String.format(Locale.US,"最終記録 %s\nBattery %.2f V  PV %.2f V\nWB %.2f A  Power %d W\n充電状態 %s",r.ts,r.batteryV,r.pvV,r.wbPreciseA,r.watts,chargeStageText(r.chargeStage)));events.setText(formatEventLog(db.recentEvents(50)));storage.setText(String.format(Locale.US,"測定 %d件 / DB %.2f MB / ERROR %d件 / 端末空き %.2f GB",db.count(),db.dbBytes()/1048576.0,db.errorCount(),db.freeBytes()/1073741824.0));uploadState.setText(String.format(Locale.US,"通常ログ: Upload済み %d / 全記録 %d\nDaily Log: Upload済み %d / 全記録 %d",db.uploadedCount(),db.count(),db.dailyUploadedCount(),db.dailyCount()));updateWifiStatus();updateGraph();}
    private void updateWifiStatus(){WifiNetworkManager w=new WifiNetworkManager(this);String s=w.getConnectedSsid();wifiStatus.setText(w.isNetworkReady()?(s==null?"接続済み（SSID取得不可）\n":""+s+"\n")+w.connectionDiagnostics():"未接続\n"+w.connectionDiagnostics());}
    private String chargeStageText(int c){switch(c){case 0:return"Resting/停止";case 3:return"Absorb（定電圧充電）";case 4:return"Bulk MPPT（最大電力充電）";case 5:return"Float（維持充電）";case 6:return"Float MPPT";case 7:return"Equalize";default:return"コード "+c;}}
    private void updateGraph(){if(metric==null||chart==null)return;int i=metric.getSelectedItemPosition();String col=CHART_COLUMNS[i];long end=System.currentTimeMillis()-chartOffsetMs,start=end-chartWindowMs;SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);List<float[]> pts=db.chart(col,f.format(new Date(start)),f.format(new Date(end)),240);Float mn=null,mx=null;if(!chartAutoY){SharedPreferences p=getSharedPreferences(Config.PREFS,0);if(p.contains("graph_fixed_min_"+col))mn=p.getFloat("graph_fixed_min_"+col,0f);if(p.contains("graph_fixed_max_"+col))mx=p.getFloat("graph_fixed_max_"+col,0f);}chart.set(pts,metric.getSelectedItem()+" ["+CHART_UNITS[i]+"]",start,end,mn,mx,chartAutoY);chartRangeLabel.setText("表示範囲: "+formatRangeDescription(chartWindowMs));}
    private void loadChartSettingsForCurrentMetric(){if(metric==null||chartMinValue==null)return;String k=CHART_COLUMNS[metric.getSelectedItemPosition()];SharedPreferences p=getSharedPreferences(Config.PREFS,0);chartAutoY=p.getBoolean("graph_auto_"+k,true);if(chartAutoToggle!=null)chartAutoToggle.setText(chartAutoY?"Y軸: 自動":"Y軸: 固定");}
    private void persistGraphSettingsForCurrentMetric(){if(metric==null)return;String k=CHART_COLUMNS[metric.getSelectedItemPosition()];SharedPreferences.Editor e=getSharedPreferences(Config.PREFS,0).edit();e.putBoolean("graph_auto_"+k,chartAutoY);e.putLong("graph_window_ms",chartWindowMs);e.putLong("graph_offset_ms",chartOffsetMs);e.apply();}
    private void changeChartWindow(boolean in){long n=in?chartWindowMs/2:chartWindowMs*2;if(n<1800000L)n=1800000L;if(n>604800000L)n=604800000L;chartWindowMs=n;persistGraphSettingsForCurrentMetric();updateGraph();}
    private void shiftChartWindow(int d){chartOffsetMs+=d*Math.max(chartWindowMs/8,60000L);persistGraphSettingsForCurrentMetric();updateGraph();}
    private String formatRangeDescription(long ms){long h=ms/3600000L;if(h<24)return h+"時間";long d=h/24;return d<7?d+"日":"7日";}
    private CharSequence formatEventLog(String raw){if(raw==null)return"";SpannableStringBuilder b=new SpannableStringBuilder();String[]ls=raw.split("\\n");for(int i=0;i<ls.length;i++){if(i>0)b.append('\n');int st=b.length();b.append(ls[i]);if(ls[i].contains("ERROR"))b.setSpan(new ForegroundColorSpan(Color.RED),st,b.length(),0);}return b;}
    private void backupDatabase(){try{File f=db.backup();Toast.makeText(this,"保存: "+f.getAbsolutePath(),Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}
    private void mergeLatestBackup(){File d=getBackupDirectory();File[] fs=d.listFiles();if(fs==null||fs.length==0){Toast.makeText(this,"バックアップなし",Toast.LENGTH_SHORT).show();return;}Arrays.sort(fs,new Comparator<File>(){@Override public int compare(File a,File b){return Long.compare(b.lastModified(),a.lastModified());}});for(File f:fs)if(f.getName().startsWith(Config.DB_BACKUP_PREFIX)&&f.getName().endsWith(".db")){try{Toast.makeText(this,"統合 "+db.mergeBackup(f)+"件",Toast.LENGTH_LONG).show();refresh();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}return;}}
    private void requestRuntimePermissions(){if(Build.VERSION.SDK_INT<23)return;ArrayList<String>a=new ArrayList<String>();if(checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")!=PackageManager.PERMISSION_GRANTED)a.add("android.permission.ACCESS_FINE_LOCATION");if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")!=PackageManager.PERMISSION_GRANTED)a.add("android.permission.NEARBY_WIFI_DEVICES");if(!a.isEmpty())requestPermissions(a.toArray(new String[a.size()]),7);}
    private void postDailyState(final String t){handler.post(new Runnable(){@Override public void run(){if(dailyRescueState!=null)dailyRescueState.setText(t);}});}
    @Override protected void onDestroy(){handler.removeCallbacks(refreshTask);dailyRescueCancelRequested=true;diagnosticCancelRequested=true;if(dailyRescueThread!=null)dailyRescueThread.interrupt();if(diagnosticThread!=null)diagnosticThread.interrupt();super.onDestroy();}
}
