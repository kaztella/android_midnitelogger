package jp.terada.midnitelogger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity {
    private SharedPreferences preferences;
    private TextView stateView, currentView, eventsView;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences("cfg", MODE_PRIVATE);
        final EditText ip = (EditText) findViewById(R.id.classicIp);
        final EditText ssid = (EditText) findViewById(R.id.loggerSsid);
        final EditText url = (EditText) findViewById(R.id.uploadUrl);
        stateView = (TextView) findViewById(R.id.state);
        currentView = (TextView) findViewById(R.id.current);
        eventsView = (TextView) findViewById(R.id.events);
        ip.setText(preferences.getString("ip", "192.168.10.1"));
        ssid.setText(preferences.getString("ssid", "ClassicLogger"));
        url.setText(preferences.getString("url", ""));
        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                preferences.edit().putString("ip", ip.getText().toString().trim())
                        .putString("ssid", ssid.getText().toString().trim())
                        .putString("url", url.getText().toString().trim()).apply();
                startService(new Intent(MainActivity.this, LoggerService.class).setAction("START"));
                refresh();
            }
        });
        findViewById(R.id.stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stopService(new Intent(MainActivity.this, LoggerService.class)); refresh(); }
        });
        findViewById(R.id.upload).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startService(new Intent(MainActivity.this, LoggerService.class).setAction("UPLOAD_NOW")); }
        });
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    private void refresh() {
        Database db = new Database(this);
        try {
            Record r = db.latest();
            stateView.setText(preferences.getBoolean("running", false) ? "ロギング中" : "停止中");
            if (r != null) currentView.setText("バッテリー電圧  " + r.batteryV + " V\nWhizbang Jr.電流  " + r.wbCurrentA + " A\nPV電圧  " + r.pvV + " V\nPV電力  " + r.watts + " W\n充電ステージ  " + r.chargeStage + "\n最終記録  " + r.timestamp + "\n未送信  " + db.pendingCount() + " 件");
            eventsView.setText(db.recentEvents());
        } finally { db.close(); }
    }
}
