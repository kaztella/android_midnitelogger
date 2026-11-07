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

    private Database db;
    private TextView status, values, events, storage, uploadState, dailyRescueState, wifiStatus, chartRangeLabel;
    private EditText dailyStartDate, dailyEndDate, url, key, device, classicWifiSsid, uploadWifiSsidList, chartMinValue, chartMaxValue;
    private Spinner metric;
    private Button chartAutoToggle;
    private ChartView chart;
    private final Handler handler = new Handler();
    private long chartWindowMs = 24L * 60L * 60L * 1000L;
    private long chartOffsetMs = 0L;
    private boolean chartAutoY = true;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 5000L); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Database(this);
        loadPersistedSettingsFile();
        requestRuntimePermissions();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(12,12,12,20); scroll.addView(root);
        status=addText(root,"状態"); values=addText(root,"データ未取得");
        LinearLayout actionRow=new LinearLayout(this); root.addView(actionRow);
        addButton(actionRow,"開始",new View.OnClickListener(){@Override public void onClick(View v){startService(new Intent(MainActivity.this,LoggerService.class));refresh();}});
        addButton(actionRow,"停止",new View.OnClickListener(){@Override public void onClick(View v){stopService(new Intent(MainActivity.this,LoggerService.class));refresh();}});
        addButton(actionRow,"30秒Upload",new View.OnClickListener(){@Override public void onClick(View v){saveSettings();new Thread(new Runnable(){@Override public void run(){try{int count=new SupabaseUploader(MainActivity.this,db).upload30s();db.event("OK","Supabase送信 "+count+"件");}catch(Exception ex){db.event("ERROR","Upload: "+ex.getMessage());}handler.post(new Runnable(){@Override public void run(){refresh();}});}}).start();}});

        addText(root,"Supabase RPC URL"); url=addEdit(root); addText(root,"Publishable key"); key=addEdit(root); addText(root,"device_id"); device=addEdit(root);
        addText(root,"Classic Wi-Fi SSID"); classicWifiSsid=addEdit(root); addText(root,"Upload Wi-Fi SSIDs"); uploadWifiSsidList=addEdit(root); addText(root,"現在の Wi-Fi"); wifiStatus=addText(root,"未確認");
        SharedPreferences prefs=getSharedPreferences(Config.PREFS,0);
        url.setText(prefs.getString("supabase_rpc_url",Config.DEFAULT_SUPABASE_RPC_URL)); key.setText(prefs.getString("supabase_key","")); device.setText(prefs.getString("device_id","shl24-01")); classicWifiSsid.setText(prefs.getString("classic_wifi_ssid",Config.CLASSIC_WIFI_SSID)); uploadWifiSsidList.setText(prefs.getString("upload_wifi_ssids",Config.DEFAULT_UPLOAD_WIFI_SSIDS));
        LinearLayout wifiRow=new LinearLayout(this); root.addView(wifiRow);
        addButton(wifiRow,"Wi-Fi状態確認",new View.OnClickListener(){@Override public void onClick(View v){updateWifiStatus();}});
        addButton(wifiRow,"NETGEAR復帰",new View.OnClickListener(){@Override public void onClick(View v){WifiNetworkManager wifi=new WifiNetworkManager(MainActivity.this);String ssid=getSharedPreferences(Config.PREFS,0).getString("classic_wifi_ssid",Config.CLASSIC_WIFI_SSID);boolean ok=wifi.restoreClassicNetwork(ssid);Toast.makeText(MainActivity.this,ok?"NETGEARへ復帰を試行":"NETGEARへ戻れませんでした",Toast.LENGTH_SHORT).show();updateWifiStatus();}});

        LinearLayout dbRow=new LinearLayout(this); root.addView(dbRow);
        addButton(dbRow,"設定保存",new View.OnClickListener(){@Override public void onClick(View v){saveSettings();}});
        addButton(dbRow,"設定ファイルを開く",new View.OnClickListener(){@Override public void onClick(View v){openSettingsFile();}});
        addButton(dbRow,"DBバックアップ",new View.OnClickListener(){@Override public void onClick(View v){backupDatabase();}});
        addButton(dbRow,"DB統合",new View.OnClickListener(){@Override public void onClick(View v){mergeLatestBackup();}});
        storage=addText(root,""); uploadState=addText(root,"Upload状態を取得中...");

        addText(root,"Daily Log集中救出・Upload");
        LinearLayout dailyDateRow=new LinearLayout(this); dailyDateRow.setOrientation(LinearLayout.HORIZONTAL); dailyStartDate=addEdit(dailyDateRow);dailyStartDate.setHint("開始日 yyyy-MM-dd");dailyEndDate=addEdit(dailyDateRow);dailyEndDate.setHint("終了日 yyyy-MM-dd");root.addView(dailyDateRow);
        Calendar defaultEnd=Calendar.getInstance();Calendar defaultStart=(Calendar)defaultEnd.clone();defaultStart.add(Calendar.DAY_OF_MONTH,-30);SimpleDateFormat dailyFormat=new SimpleDateFormat("yyyy-MM-dd",Locale.US);dailyStartDate.setText(dailyFormat.format(defaultStart.getTime()));dailyEndDate.setText(dailyFormat.format(defaultEnd.getTime()));
        addButton(root,"Daily Log集中救出・Upload",new View.OnClickListener(){@Override public void onClick(View v){runDailyLogConcentrated();}}); dailyRescueState=addText(root,"Daily Log集中救出: 待機中");

        addText(root,"グラフ項目"); metric=new Spinner(this);String[] names={"バッテリー電圧","PV電圧","Classic電流","WB電流","WB精密電流","PV電流","PV電力","本日発電量","バッテリー温度","FET温度","基板温度"};metric.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));metric.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){loadChartSettingsForCurrentMetric();updateGraph();}@Override public void onNothingSelected(AdapterView<?>p){}});root.addView(metric);
        addText(root,"グラフY軸（固定/自動）"); LinearLayout chartRangeRow=new LinearLayout(this);chartRangeRow.setOrientation(LinearLayout.HORIZONTAL);chartAutoToggle=addButton(chartRangeRow,"Y軸: 自動",new View.OnClickListener(){@Override public void onClick(View v){chartAutoY=!chartAutoY;chartAutoToggle.setText(chartAutoY?"Y軸: 自動":"Y軸: 固定");persistGraphSettingsForCurrentMetric();updateGraph();}});chartMinValue=addEdit(chartRangeRow);chartMinValue.setHint("最小");chartMaxValue=addEdit(chartRangeRow);chartMaxValue.setHint("最大");root.addView(chartRangeRow);
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);addButton(nav,"時間-",new View.OnClickListener(){@Override public void onClick(View v){changeChartWindow(false);}});addButton(nav,"時間+",new View.OnClickListener(){@Override public void onClick(View v){changeChartWindow(true);}});addButton(nav,"←",new View.OnClickListener(){@Override public void onClick(View v){shiftChartWindow(-1);}});addButton(nav,"→",new View.OnClickListener(){@Override public void onClick(View v){shiftChartWindow(1);}});root.addView(nav);chartRangeLabel=addText(root,"表示範囲: 24時間");
        chart=new ChartView(this);chart.setChartGestureListener(new ChartView.ChartGestureListener(){@Override public void onPan(float r){long d=Math.round(Math.abs(r)*chartWindowMs);if(d<=0)return;chartOffsetMs+=r<0?-d:d;persistGraphSettingsForCurrentMetric();updateGraph();}@Override public void onZoom(float s){if(s<=0||Float.isNaN(s)||Float.isInfinite(s))return;long n=Math.round(chartWindowMs*s),min=30L*60L*1000L,max=7L*24L*60L*60L*1000L;if(n<min)n=min;if(n>max)n=max;if(n!=chartWindowMs){chartWindowMs=n;persistGraphSettingsForCurrentMetric();updateGraph();}}});root.addView(chart,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,500));addButton(root,"グラフ更新",new View.OnClickListener(){@Override public void onClick(View v){updateGraph();}});
        addText(root,"通信/イベントログ（直近50件）");events=addText(root,"");addButton(root,"ログ更新",new View.OnClickListener(){@Override public void onClick(View v){refresh();}});
        setContentView(scroll);loadChartSettingsForCurrentMetric();refresh();handler.postDelayed(refreshTask,3000L);
    }

    private void runDailyLogConcentrated(){
        final String startText=dailyStartDate.getText().toString().trim(),endText=dailyEndDate.getText().toString().trim();final SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);fmt.setLenient(false);final Date startDate,endDate;
        try{startDate=fmt.parse(startText);endDate=fmt.parse(endText);}catch(Exception e){Toast.makeText(this,"日付は yyyy-MM-dd で入力してください",Toast.LENGTH_LONG).show();return;}
        if(startDate.after(endDate)){Toast.makeText(this,"開始日は終了日以前にしてください",Toast.LENGTH_LONG).show();return;}
        long requestedDays=daysBetween(startDate,endDate)+1L;if(requestedDays<1L||requestedDays>400L){Toast.makeText(this,"集中救出は最大400日までです",Toast.LENGTH_LONG).show();return;}
        saveSettings();final boolean wasRunning=getSharedPreferences(Config.PREFS,0).getBoolean("running",false);if(wasRunning)stopService(new Intent(MainActivity.this,LoggerService.class));dailyRescueState.setText("Daily Log集中救出: 開始準備中");
        new Thread(new Runnable(){@Override public void run(){int rescued=0,uploaded=0;String finalMessage;final Modbus.L log=new Modbus.L(){@Override public void log(String l,String m){db.event(l,m);}};WifiNetworkManager wifi=new WifiNetworkManager(MainActivity.this);SharedPreferences prefs=getSharedPreferences(Config.PREFS,0);String classicSsid=prefs.getString("classic_wifi_ssid",Config.CLASSIC_WIFI_SSID);if(classicSsid==null||classicSsid.trim().length()==0)classicSsid=Config.CLASSIC_WIFI_SSID;
            try{postDailyState("Classic側Wi-Fiへ復帰中...");if(!wifi.restoreClassicNetwork(classicSsid))throw new IOException("Classic Wi-Fi/IPへ復帰できません: "+wifi.connectionDiagnostics());ClassicReader reader=new ClassicReader(log);postDailyState("最新Daily Log日付を確認中...");ClassicReader.DailyTimestamp newest=readTimestampWithRetry(reader,1,3);Date newestDate=newest.calendar.getTime();db.event("INFO","Daily最新ログ index=1 date="+newest.date);
                int startIndex=1+(int)daysBetween(startDate,newestDate),endIndex=1+(int)daysBetween(endDate,newestDate);if(startIndex<1)startIndex=1;if(endIndex<1)endIndex=1;if(startIndex>1023||endIndex>1023)throw new IOException("指定日がClassic Daily Log範囲外です startIndex="+startIndex+" endIndex="+endIndex);int fromIndex=Math.max(startIndex,endIndex),toIndex=Math.min(startIndex,endIndex);db.event("INFO","Daily集中 index計算 latest="+newest.date+" start="+startText+" end="+endText+" range="+fromIndex+".."+toIndex);
                for(int dayIndex=fromIndex;dayIndex>=toIndex;dayIndex--){Calendar expected=Calendar.getInstance();expected.setTime(newestDate);expected.add(Calendar.DAY_OF_MONTH,-(dayIndex-1));final String expectedDate=fmt.format(expected.getTime());DailyLogRecord row=null;Exception lastError=null;
                    for(int attempt=1;attempt<=3;attempt++){try{postDailyState("Daily Log救出 "+expectedDate+" index="+dayIndex+" attempt="+attempt+"/3");db.event("INFO","Daily集中 1日取得開始 expected="+expectedDate+" index="+dayIndex+" attempt="+attempt);DailyLogRecord candidate=reader.readDailyLog(dayIndex);if(!expectedDate.equals(candidate.logDate))throw new IOException("DailyLog日付不一致 index="+dayIndex+" expected="+expectedDate+" actual="+candidate.logDate);row=candidate;lastError=null;db.event("OK","Daily集中 1日取得成功 date="+row.logDate+" index="+dayIndex+" attempt="+attempt);break;}catch(Exception e){lastError=e;db.event(attempt<3?"WARN":"ERROR","Daily集中 1日再取得 expected="+expectedDate+" index="+dayIndex+" attempt="+attempt+"/3 "+e.toString());if(attempt==1)sleepDaily(5000L);else if(attempt==2)sleepDaily(10000L);}}
                    if(row==null){db.event("ERROR","Daily集中 未取得 date="+expectedDate+" index="+dayIndex+" after 3 attempts"+(lastError==null?"":" cause="+lastError.toString()));sleepDaily(3000L);continue;}if(!db.hasDailyLogDate(row.logDate)){long id=db.insertDailyLog(row);if(id>=0)rescued++;}sleepDaily(3000L);
                }
                List<String> uploadSsids=wifi.parseUploadSsidList(prefs.getString("upload_wifi_ssids",Config.DEFAULT_UPLOAD_WIFI_SSIDS));String candidate=wifi.findUploadNetworkInScanResults(uploadSsids);if(wifi.shouldUploadOnCurrentNetwork(uploadSsids))candidate=wifi.getConnectedSsid();else if(candidate!=null){postDailyState("外部Wi-Fiへ切替中: "+candidate);if(!wifi.connectToSsid(candidate,30000L))candidate=null;}if(candidate!=null){postDailyState("Daily Log Supabase Upload中...");for(int batch=0;batch<10;batch++){int n=new SupabaseUploader(MainActivity.this,db).uploadDailyLogs();if(n<=0)break;uploaded+=n;}}else db.event("INFO","Daily集中: 外部Wi-Fiなし。救出データは未送信で保持");finalMessage="完了: 救出 "+rescued+"件 / Upload "+uploaded+"件";
            }catch(Exception e){db.event("ERROR","Daily Log集中救出失敗: "+e.toString());finalMessage="失敗: "+e.getMessage();}finally{postDailyState("Classic側へ復帰中...");wifi.restoreClassicNetwork(classicSsid);if(wasRunning)startService(new Intent(MainActivity.this,LoggerService.class));}
            final String show=finalMessage;handler.post(new Runnable(){@Override public void run(){dailyRescueState.setText("Daily Log集中救出: "+show);refresh();}});
        }}).start();
    }

    private ClassicReader.DailyTimestamp readTimestampWithRetry(ClassicReader reader,int dayIndex,int attempts)throws Exception{Exception last=null;for(int attempt=1;attempt<=attempts;attempt++){try{return reader.readDailyTimestamp(dayIndex);}catch(Exception e){last=e;db.event(attempt<attempts?"WARN":"ERROR","Daily timestamp再試行 index="+dayIndex+" attempt="+attempt+"/"+attempts+" "+e.toString());if(attempt==1)sleepDaily(5000L);else if(attempt==2)sleepDaily(10000L);}}throw last;}
    private static long daysBetween(Date older,Date newer){Calendar a=Calendar.getInstance();a.setTime(older);clearDateTime(a);Calendar b=Calendar.getInstance();b.setTime(newer);clearDateTime(b);return(b.getTimeInMillis()-a.getTimeInMillis())/(24L*60L*60L*1000L);}
    private static void clearDateTime(Calendar c){c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);}
    private static void sleepDaily(long ms)throws IOException{try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Daily集中待機中断",e);}}
    private void postDailyState(final String text){handler.post(new Runnable(){@Override public void run(){if(dailyRescueState!=null)dailyRescueState.setText(text);}});}

    private File getBackupDirectory(){File root=Environment.getExternalStorageDirectory();if(root!=null){File dir=new File(root,Config.BACKUP_DIR_NAME);if(!dir.exists()&&!dir.mkdirs()){File fallback=new File(getFilesDir(),Config.BACKUP_DIR_NAME);if(!fallback.exists()&&!fallback.mkdirs())return getFilesDir();return fallback;}return dir;}File dir=new File(getFilesDir(),Config.BACKUP_DIR_NAME);if(!dir.exists()&&!dir.mkdirs())return getFilesDir();return dir;}
    private File getSettingsFile(){return new File(getBackupDirectory(),Config.SETTINGS_FILE_NAME);}
    private void openSettingsFile(){File file=getSettingsFile();if(!file.exists()){writePersistedSettingsFile();if(!file.exists()){Toast.makeText(this,"設定ファイルを作成できませんでした",Toast.LENGTH_SHORT).show();return;}}Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(Uri.fromFile(file),"text/plain");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{startActivity(Intent.createChooser(intent,"設定ファイルを開く"));}catch(ActivityNotFoundException ex){Toast.makeText(this,"テキストエディタが見つかりませんでした",Toast.LENGTH_SHORT).show();}}
    private void loadPersistedSettingsFile(){File file=getSettingsFile();if(!file.exists())return;Properties props=new Properties();FileInputStream in=null;try{in=new FileInputStream(file);props.load(in);SharedPreferences prefs=getSharedPreferences(Config.PREFS,0);SharedPreferences.Editor edit=prefs.edit();String[] ks={"supabase_rpc_url","supabase_key","device_id","classic_wifi_ssid","upload_wifi_ssids"};for(String k:ks){String v=props.getProperty(k,"");if(v.length()>0)edit.putString(k,v);}for(String m:CHART_COLUMNS){String ak="graph_auto_"+m,av=props.getProperty(ak,"");if(av.length()>0)edit.putBoolean(ak,"1".equals(av)||"true".equalsIgnoreCase(av));String min="graph_fixed_min_"+m,mv=props.getProperty(min,"");if(mv.length()>0)try{edit.putFloat(min,Float.parseFloat(mv));}catch(Exception ignored){}String max="graph_fixed_max_"+m,xv=props.getProperty(max,"");if(xv.length()>0)try{edit.putFloat(max,Float.parseFloat(xv));}catch(Exception ignored){}}try{String v=props.getProperty("graph_window_ms","");if(v.length()>0)edit.putLong("graph_window_ms",Long.parseLong(v));v=props.getProperty("graph_offset_ms","");if(v.length()>0)edit.putLong("graph_offset_ms",Long.parseLong(v));}catch(Exception ignored){}edit.apply();}catch(IOException ignored){}finally{if(in!=null)try{in.close();}catch(IOException ignored){}}}
    private void writePersistedSettingsFile(){File file=getSettingsFile();Properties p=new Properties();SharedPreferences s=getSharedPreferences(Config.PREFS,0);p.setProperty("supabase_rpc_url",s.getString("supabase_rpc_url",Config.DEFAULT_SUPABASE_RPC_URL));p.setProperty("supabase_key",s.getString("supabase_key",""));p.setProperty("device_id",s.getString("device_id",""));p.setProperty("classic_wifi_ssid",s.getString("classic_wifi_ssid",Config.CLASSIC_WIFI_SSID));p.setProperty("upload_wifi_ssids",s.getString("upload_wifi_ssids",Config.DEFAULT_UPLOAD_WIFI_SSIDS));for(String m:CHART_COLUMNS){p.setProperty("graph_auto_"+m,s.getBoolean("graph_auto_"+m,true)?"1":"0");if(s.contains("graph_fixed_min_"+m))p.setProperty("graph_fixed_min_"+m,Float.toString(s.getFloat("graph_fixed_min_"+m,0)));if(s.contains("graph_fixed_max_"+m))p.setProperty("graph_fixed_max_"+m,Float.toString(s.getFloat("graph_fixed_max_"+m,0)));}p.setProperty("graph_window_ms",Long.toString(chartWindowMs));p.setProperty("graph_offset_ms",Long.toString(chartOffsetMs));FileOutputStream out=null;try{out=new FileOutputStream(file);p.store(out,"Midnite Logger settings");}catch(IOException ignored){}finally{if(out!=null)try{out.close();}catch(IOException ignored){}}}
    private TextView addText(LinearLayout parent,String text){TextView v=new TextView(this);v.setText(text);v.setTextSize(16);v.setTextColor(Color.DKGRAY);v.setPadding(4,6,4,6);parent.addView(v);return v;}
    private EditText addEdit(LinearLayout parent){EditText e=new EditText(this);e.setSingleLine(true);parent.addView(e);return e;}
    private Button addButton(LinearLayout parent,String text,View.OnClickListener listener){Button b=new Button(this);b.setText(text);b.setOnClickListener(listener);parent.addView(b);return b;}
    private void saveSettings(){SharedPreferences.Editor e=getSharedPreferences(Config.PREFS,0).edit();String r=url.getText().toString().trim();if(r.length()==0)r=Config.DEFAULT_SUPABASE_RPC_URL;e.putString("supabase_rpc_url",r).putString("supabase_key",key.getText().toString().trim()).putString("device_id",device.getText().toString().trim()).putString("classic_wifi_ssid",classicWifiSsid.getText().toString().trim()).putString("upload_wifi_ssids",uploadWifiSsidList.getText().toString().trim()).apply();persistGraphSettingsForCurrentMetric();writePersistedSettingsFile();Toast.makeText(this,"保存しました",Toast.LENGTH_SHORT).show();}
    private void loadChartSettingsForCurrentMetric(){if(metric==null||chartMinValue==null)return;String k=CHART_COLUMNS[metric.getSelectedItemPosition()];SharedPreferences p=getSharedPreferences(Config.PREFS,0);chartAutoY=p.getBoolean("graph_auto_"+k,true);chartWindowMs=p.getLong("graph_window_ms",24L*60L*60L*1000L);chartOffsetMs=p.getLong("graph_offset_ms",0L);chartMinValue.setText(p.contains("graph_fixed_min_"+k)?Float.toString(p.getFloat("graph_fixed_min_"+k,0)):"");chartMaxValue.setText(p.contains("graph_fixed_max_"+k)?Float.toString(p.getFloat("graph_fixed_max_"+k,0)):"");if(chartAutoToggle!=null)chartAutoToggle.setText(chartAutoY?"Y軸: 自動":"Y軸: 固定");}
    private void persistGraphSettingsForCurrentMetric(){if(metric==null||chartMinValue==null)return;String k=CHART_COLUMNS[metric.getSelectedItemPosition()];SharedPreferences.Editor e=getSharedPreferences(Config.PREFS,0).edit().putBoolean("graph_auto_"+k,chartAutoY).putLong("graph_window_ms",chartWindowMs).putLong("graph_offset_ms",chartOffsetMs);try{String x=chartMinValue.getText().toString().trim();if(x.length()>0)e.putFloat("graph_fixed_min_"+k,Float.parseFloat(x));else e.remove("graph_fixed_min_"+k);x=chartMaxValue.getText().toString().trim();if(x.length()>0)e.putFloat("graph_fixed_max_"+k,Float.parseFloat(x));else e.remove("graph_fixed_max_"+k);}catch(Exception ignored){}e.apply();writePersistedSettingsFile();}
    private void refresh(){boolean running=getSharedPreferences(Config.PREFS,0).getBoolean("running",false);status.setText(running?"ロギング中":"停止中");Record r=db.latest();if(r==null)values.setText("データ未取得");else values.setText(String.format(Locale.US,"最終記録 %s\nBattery %.2f V  PV %.2f V\nWB %.2f A  Power %d W\n充電状態 %s",r.ts,r.batteryV,r.pvV,r.wbPreciseA,r.watts,chargeStageText(r.chargeStage)));events.setText(formatEventLog(db.recentEvents(50)));storage.setText(String.format(Locale.US,"測定 %d件 / DB %.2f MB / ERROR %d件 / 端末空き %.2f GB",db.count(),db.dbBytes()/1048576.0,db.errorCount(),db.freeBytes()/1073741824.0));uploadState.setText(String.format(Locale.US,"通常ログ: Upload済み %d / 全記録 %d\nDaily Log: Upload済み %d / 全記録 %d",db.uploadedCount(),db.count(),db.dailyUploadedCount(),db.dailyCount()));updateWifiStatus();updateGraph();}
    private void updateWifiStatus(){String s=new WifiNetworkManager(this).getConnectedSsid();wifiStatus.setText(s==null?"未接続":s);}
    private String chargeStageText(int c){switch(c){case 0:return"Resting/停止";case 3:return"Absorb（定電圧充電）";case 4:return"Bulk MPPT（最大電力充電）";case 5:return"Float（維持充電）";case 6:return"Float MPPT";case 7:return"Equalize";default:return"コード "+c;}}
    private void changeChartWindow(boolean in){long min=30L*60L*1000L,max=7L*24L*60L*60L*1000L,n=in?chartWindowMs/2:chartWindowMs*2;if(n<min)n=min;if(n>max)n=max;chartWindowMs=n;persistGraphSettingsForCurrentMetric();updateGraph();}
    private void shiftChartWindow(int d){chartOffsetMs+=d*Math.max(chartWindowMs/8L,60000L);persistGraphSettingsForCurrentMetric();updateGraph();}
    private String formatRangeDescription(long m){long h=m/3600000L;if(h<=0)return"30分";if(h<24)return h+"時間";long d=h/24;if(d<7)return d+"日";return"7日";}
    private CharSequence formatEventLog(String raw){if(raw==null||raw.trim().isEmpty())return"";SpannableStringBuilder b=new SpannableStringBuilder();String[] ls=raw.split("\\n");for(int i=0;i<ls.length;i++){if(i>0)b.append('\n');String l=ls[i];int st=b.length();b.append(l);if(l.contains("ERROR"))b.setSpan(new ForegroundColorSpan(Color.RED),st,b.length(),0);}return b;}
    private void updateGraph(){if(metric==null||chart==null)return;int i=metric.getSelectedItemPosition();String col=CHART_COLUMNS[i];long end=System.currentTimeMillis()-chartOffsetMs,start=end-chartWindowMs;SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);List<float[]> pts=db.chart(col,f.format(new Date(start)),f.format(new Date(end)),240);Float min=null,max=null;if(!chartAutoY){SharedPreferences p=getSharedPreferences(Config.PREFS,0);if(p.contains("graph_fixed_min_"+col))min=p.getFloat("graph_fixed_min_"+col,0);if(p.contains("graph_fixed_max_"+col))max=p.getFloat("graph_fixed_max_"+col,0);}chart.set(pts,metric.getSelectedItem()+" ["+CHART_UNITS[i]+"]",start,end,min,max,chartAutoY);chartRangeLabel.setText("表示範囲: "+formatRangeDescription(chartWindowMs));}
    private void backupDatabase(){try{File f=db.backup();Toast.makeText(this,"保存: "+f.getAbsolutePath(),Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}
    private void mergeLatestBackup(){File base=getBackupDirectory();File[] fs=base.listFiles();if(fs==null||fs.length==0){Toast.makeText(this,"バックアップなし",Toast.LENGTH_SHORT).show();return;}Arrays.sort(fs,new Comparator<File>(){@Override public int compare(File l,File r){return Long.compare(r.lastModified(),l.lastModified());}});try{long n=db.mergeBackup(fs[0]);Toast.makeText(this,"統合 "+n+"件",Toast.LENGTH_LONG).show();refresh();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}
    private void requestRuntimePermissions(){if(Build.VERSION.SDK_INT<23)return;ArrayList<String> p=new ArrayList<String>();if(checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")!=PackageManager.PERMISSION_GRANTED)p.add("android.permission.ACCESS_FINE_LOCATION");if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")!=PackageManager.PERMISSION_GRANTED)p.add("android.permission.NEARBY_WIFI_DEVICES");if(!p.isEmpty())requestPermissions(p.toArray(new String[p.size()]),7);}
    @Override protected void onDestroy(){handler.removeCallbacks(refreshTask);super.onDestroy();}
}
