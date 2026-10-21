package jp.terada.midnitelogger;
import android.content.*; import android.database.*; import android.database.sqlite.*; import android.os.*; import java.io.*; import java.util.*;
public class Database extends SQLiteOpenHelper {
 public static final String NAME="midnite.db"; private static final int VER=3; private final Context ctx;
 public Database(Context c){super(c,NAME,null,VER);ctx=c.getApplicationContext();}
 public void onCreate(SQLiteDatabase d){
  d.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT,ts TEXT NOT NULL UNIQUE,battery_v REAL,pv_v REAL,classic_a REAL,pv_a REAL,watts INTEGER,daily_kwh REAL,charge_stage INTEGER,classic_state INTEGER,batt_temp REAL,fet_temp REAL,pcb_temp REAL,wb_a REAL,wb_precise_a REAL,last_voc_v REAL,highest_pv_v REAL,daily_ah REAL,lifetime_kwh INTEGER,lifetime_ah INTEGER,info_flags INTEGER,minutes_no_pv INTEGER,float_seconds INTEGER,reset_reason INTEGER,wb_positive_ah INTEGER,wb_negative_ah INTEGER,wb_net_ah INTEGER,uploaded INTEGER NOT NULL DEFAULT 0)");
  d.execSQL("CREATE TABLE event(id INTEGER PRIMARY KEY AUTOINCREMENT,ts TEXT NOT NULL,level TEXT NOT NULL,message TEXT NOT NULL)");
  d.execSQL("CREATE INDEX idx_log_uploaded ON log(uploaded,id)"); d.execSQL("CREATE INDEX idx_event_ts ON event(ts)");
 }
 public void onUpgrade(SQLiteDatabase d,int a,int b){ if(a<2){try{d.execSQL("CREATE INDEX idx_log_uploaded ON log(uploaded,id)");}catch(Exception ignored){}} if(a<3){try{d.execSQL("CREATE INDEX idx_event_ts ON event(ts)");}catch(Exception ignored){}} }
 public long insert(Record r){ContentValues v=values(r);return getWritableDatabase().insertWithOnConflict("log",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
 private ContentValues values(Record r){ContentValues v=new ContentValues();v.put("ts",r.ts);v.put("battery_v",r.batteryV);v.put("pv_v",r.pvV);v.put("classic_a",r.classicA);v.put("pv_a",r.pvA);v.put("watts",r.watts);v.put("daily_kwh",r.dailyKwh);v.put("charge_stage",r.chargeStage);v.put("classic_state",r.classicState);v.put("batt_temp",r.batteryTemp);v.put("fet_temp",r.fetTemp);v.put("pcb_temp",r.pcbTemp);v.put("wb_a",r.wbA);v.put("wb_precise_a",r.wbPreciseA);v.put("last_voc_v",r.lastVocV);v.put("highest_pv_v",r.highestPvV);v.put("daily_ah",r.dailyAh);v.put("lifetime_kwh",r.lifetimeKwh);v.put("lifetime_ah",r.lifetimeAh);v.put("info_flags",r.infoFlags);v.put("minutes_no_pv",r.minutesNoPv);v.put("float_seconds",r.floatSeconds);v.put("reset_reason",r.resetReason);v.put("wb_positive_ah",r.wbPositiveAh);v.put("wb_negative_ah",r.wbNegativeAh);v.put("wb_net_ah",r.wbNetAh);return v;}
 public void event(String level,String msg){ContentValues v=new ContentValues();v.put("ts",Time.now());v.put("level",level);v.put("message",msg);getWritableDatabase().insert("event",null,v);}
 public void purgeEvents(){getWritableDatabase().delete("event","level<>? AND ts < datetime('now','-7 days')",new String[]{"ERROR"});}
 public Record latest(){Cursor c=getReadableDatabase().rawQuery("SELECT * FROM log ORDER BY id DESC LIMIT 1",null);try{return c.moveToFirst()?from(c):null;}finally{c.close();}}
 private Record from(Cursor c){Record r=new Record();r.id=c.getLong(c.getColumnIndex("id"));r.ts=c.getString(c.getColumnIndex("ts"));r.batteryV=c.getDouble(c.getColumnIndex("battery_v"));r.pvV=c.getDouble(c.getColumnIndex("pv_v"));r.classicA=c.getDouble(c.getColumnIndex("classic_a"));r.pvA=c.getDouble(c.getColumnIndex("pv_a"));r.watts=c.getInt(c.getColumnIndex("watts"));r.dailyKwh=c.getDouble(c.getColumnIndex("daily_kwh"));r.chargeStage=c.getInt(c.getColumnIndex("charge_stage"));r.classicState=c.getInt(c.getColumnIndex("classic_state"));r.batteryTemp=c.getDouble(c.getColumnIndex("batt_temp"));r.fetTemp=c.getDouble(c.getColumnIndex("fet_temp"));r.pcbTemp=c.getDouble(c.getColumnIndex("pcb_temp"));r.wbA=c.getDouble(c.getColumnIndex("wb_a"));r.wbPreciseA=c.getDouble(c.getColumnIndex("wb_precise_a"));return r;}
 public List<Record> pending(int n){ArrayList<Record> a=new ArrayList<Record>();Cursor c=getReadableDatabase().rawQuery("SELECT * FROM log WHERE uploaded=0 ORDER BY id LIMIT "+n,null);try{while(c.moveToNext())a.add(from(c));}finally{c.close();}return a;}
 public void markUploaded(List<Record> rows){if(rows.isEmpty())return;long max=rows.get(rows.size()-1).id;ContentValues v=new ContentValues();v.put("uploaded",1);getWritableDatabase().update("log",v,"id<=? AND uploaded=0",new String[]{String.valueOf(max)});}
 public String recentEvents(int n) {
  StringBuilder builder = new StringBuilder();
  Cursor cursor = getReadableDatabase().rawQuery(
      "SELECT ts,level,message FROM event ORDER BY id DESC LIMIT " + n, null);
  try {
   while (cursor.moveToNext()) {
    builder.append(cursor.getString(0))
        .append(" [")
        .append(cursor.getString(1))
        .append("] ")
        .append(cursor.getString(2))
        .append("\n");
   }
  } finally {
   cursor.close();
  }
  return builder.toString();
 }
 public long count(){Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM log",null);try{c.moveToFirst();return c.getLong(0);}finally{c.close();}}
 public long errorCount(){Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM event WHERE level='ERROR'",null);try{c.moveToFirst();return c.getLong(0);}finally{c.close();}}
 public long dbBytes(){return ctx.getDatabasePath(NAME).length();}
 public long freeBytes(){return Environment.getDataDirectory().getFreeSpace();}
 public List<float[]> chart(String col,String start,String end,int max){HashSet<String> ok=new HashSet<String>(Arrays.asList("battery_v","pv_v","classic_a","wb_a","wb_precise_a","pv_a","watts","daily_kwh","batt_temp","fet_temp","pcb_temp"));if(!ok.contains(col))col="battery_v";ArrayList<float[]> all=new ArrayList<float[]>();Cursor c=getReadableDatabase().rawQuery("SELECT strftime('%s',ts),"+col+" FROM log WHERE ts>=? AND ts<? ORDER BY ts",new String[]{start,end});try{while(c.moveToNext())all.add(new float[]{c.getLong(0)*1000f,(float)c.getDouble(1)});}finally{c.close();}if(all.size()<=max)return all;ArrayList<float[]> out=new ArrayList<float[]>();double step=(double)all.size()/max;for(int i=0;i<max;i++)out.add(all.get((int)(i*step)));return out;}
 public File backup() throws IOException {File dir=new File(Environment.getExternalStorageDirectory(),"MidniteLogger");if(!dir.exists()&&!dir.mkdirs())throw new IOException("folder create failed");File src=ctx.getDatabasePath(NAME),dst=new File(dir,"midnite_backup_"+Time.fileStamp()+".db");copy(src,dst);return dst;}
 public int mergeBackup(File f)throws IOException {if(!f.exists())throw new IOException("backup not found");SQLiteDatabase d=getWritableDatabase();d.execSQL("ATTACH DATABASE ? AS backup",new Object[]{f.getAbsolutePath()});int before=(int)count();d.execSQL("INSERT OR IGNORE INTO log(ts,battery_v,pv_v,classic_a,pv_a,watts,daily_kwh,charge_stage,classic_state,batt_temp,fet_temp,pcb_temp,wb_a,wb_precise_a,last_voc_v,highest_pv_v,daily_ah,lifetime_kwh,lifetime_ah,info_flags,minutes_no_pv,float_seconds,reset_reason,wb_positive_ah,wb_negative_ah,wb_net_ah,uploaded) SELECT ts,battery_v,pv_v,classic_a,pv_a,watts,daily_kwh,charge_stage,classic_state,batt_temp,fet_temp,pcb_temp,wb_a,wb_precise_a,last_voc_v,highest_pv_v,daily_ah,lifetime_kwh,lifetime_ah,info_flags,minutes_no_pv,float_seconds,reset_reason,wb_positive_ah,wb_negative_ah,wb_net_ah,uploaded FROM backup.log");d.execSQL("DETACH DATABASE backup");return (int)count()-before;}
 private static void copy(File a,File b)throws IOException{InputStream i=new FileInputStream(a);OutputStream o=new FileOutputStream(b);byte[] z=new byte[8192];int n;while((n=i.read(z))>0)o.write(z,0,n);i.close();o.close();}
}
