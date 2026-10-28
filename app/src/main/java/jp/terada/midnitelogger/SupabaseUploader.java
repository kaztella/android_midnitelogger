package jp.terada.midnitelogger;
import android.content.*; import java.io.*; import java.net.*; import java.util.*; import javax.net.ssl.*; import org.json.*;
public class SupabaseUploader {
 private final Context c; private final Database db; public SupabaseUploader(Context x,Database d){c=x;db=d;}
 public int upload30s() throws Exception {
  SharedPreferences p=c.getSharedPreferences(Config.PREFS,0);
  String url=clean(p.getString("supabase_rpc_url","")); String key=clean(p.getString("supabase_key","")); String dev=clean(p.getString("device_id","shl24-01"));
  if(url.length()==0||key.length()==0)throw new IOException("Supabase URL/API key未設定"); long end=System.currentTimeMillis()+Config.UPLOAD_LIMIT_MS; int done=0;
  while(System.currentTimeMillis()<end){List<Record> rows=db.pending(Config.BATCH_SIZE);if(rows.isEmpty())break;JSONObject root=new JSONObject();JSONArray a=new JSONArray();for(Record r:rows)a.put(json(r,dev));root.put("p_rows",a);
   HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();
   if(h instanceof HttpsURLConnection && android.os.Build.VERSION.SDK_INT<21)((HttpsURLConnection)h).setSSLSocketFactory(new Tls12());
   h.setConnectTimeout(10000);h.setReadTimeout(15000);h.setRequestMethod("POST");h.setDoOutput(true);h.setRequestProperty("apikey",key);h.setRequestProperty("Content-Type","application/json; charset=utf-8");h.setRequestProperty("Connection","close");
   byte[] z=root.toString().getBytes("UTF-8");h.setFixedLengthStreamingMode(z.length);OutputStream o=h.getOutputStream();o.write(z);o.flush();o.close();int code=h.getResponseCode();if(code<200||code>=300){String body=read(h.getErrorStream());h.disconnect();throw new IOException("Supabase HTTP "+code+" "+body);}read(h.getInputStream());h.disconnect();db.markUploaded(rows);done+=rows.size();}
  return done;
 }
 private static String clean(String s){return s==null?"":s.replace("\r","").replace("\n","").trim();}
 private JSONObject json(Record r,String d)throws JSONException{JSONObject j=new JSONObject();j.put("device_id",d);j.put("measured_at",r.ts.replace(' ','T')+"+09:00");j.put("battery_v",r.batteryV);j.put("pv_v",r.pvV);j.put("classic_a",r.classicA);j.put("pv_a",r.pvA);j.put("watts",r.watts);j.put("daily_kwh",r.dailyKwh);j.put("charge_stage",r.chargeStage);j.put("classic_state",r.classicState);j.put("battery_temp",r.batteryTemp);j.put("fet_temp",r.fetTemp);j.put("pcb_temp",r.pcbTemp);j.put("wb_a",r.wbA);j.put("wb_precise_a",r.wbPreciseA);return j;}
 private static String read(InputStream i)throws IOException{if(i==null)return"";BufferedReader b=new BufferedReader(new InputStreamReader(i));StringBuilder s=new StringBuilder();String x;while((x=b.readLine())!=null)s.append(x);b.close();return s.toString();}
}
