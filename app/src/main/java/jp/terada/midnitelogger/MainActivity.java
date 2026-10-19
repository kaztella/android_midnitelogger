package jp.terada.midnitelogger;
import android.app.*; import android.os.*; import android.content.*; import android.view.*; import android.widget.*;
public class MainActivity extends Activity {
 SharedPreferences p; TextView state,current,events;
 public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);p=getSharedPreferences("cfg",0);final EditText ip=findViewById(R.id.classicIp),ss=findViewById(R.id.loggerSsid),url=findViewById(R.id.uploadUrl);state=findViewById(R.id.state);current=findViewById(R.id.current);events=findViewById(R.id.events);ip.setText(p.getString("ip","192.168.10.1"));ss.setText(p.getString("ssid","ClassicLogger"));url.setText(p.getString("url",""));
 findViewById(R.id.start).setOnClickListener(new View.OnClickListener(){public void onClick(View v){p.edit().putString("ip",ip.getText().toString().trim()).putString("ssid",ss.getText().toString().trim()).putString("url",url.getText().toString().trim()).apply(); startService(new Intent(MainActivity.this,LoggerService.class).setAction("START")); refresh();}});
 findViewById(R.id.stop).setOnClickListener(new View.OnClickListener(){public void onClick(View v){stopService(new Intent(MainActivity.this,LoggerService.class));refresh();}});
 findViewById(R.id.upload).setOnClickListener(new View.OnClickListener(){public void onClick(View v){startService(new Intent(MainActivity.this,LoggerService.class).setAction("UPLOAD_NOW"));}}); }
 protected void onResume(){super.onResume();refresh();}
 void refresh(){ Database d=new Database(this); Record r=d.latest(); state.setText(p.getBoolean("running",false)?"● ロギング中":"● 停止中"); if(r!=null) current.setText("バッテリー電圧  "+r.batteryV+" V
Whizbang Jr.電流  "+r.wbA+" A
PV電圧  "+r.pvV+" V
PV電力  "+r.watts+" W
充電ステージ  "+r.stage+"
最終記録  "+r.ts+"
未送信  "+d.pendingCount()+" 件"); events.setText(d.recentEvents()); d.close(); }
}
