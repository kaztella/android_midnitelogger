package jp.terada.midnitelogger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import android.widget.RadioButton;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private SharedPreferences preferences;
    private TextView stateView, currentView, eventsView, chartPeriodView;
    private Button startButton, stopButton, uploadButton;
    private ChartView chartView;
    private EditText chartDateView, chartTimeView;
    private RadioButton fromSelectedView;
    private final SimpleDateFormat chartInputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
    private final SimpleDateFormat chartLabelFormat = new SimpleDateFormat("MM/dd HH:mm", Locale.US);

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
        startButton = (Button) findViewById(R.id.start);
        stopButton = (Button) findViewById(R.id.stop);
        uploadButton = (Button) findViewById(R.id.upload);
        chartView = (ChartView) findViewById(R.id.chart);
        chartDateView = (EditText) findViewById(R.id.chartDate);
        chartTimeView = (EditText) findViewById(R.id.chartTime);
        chartPeriodView = (TextView) findViewById(R.id.chartPeriod);
        fromSelectedView = (RadioButton) findViewById(R.id.fromSelected);
        Calendar initial = Calendar.getInstance();
        initial.set(Calendar.HOUR_OF_DAY, 0); initial.set(Calendar.MINUTE, 0);
        initial.set(Calendar.SECOND, 0); initial.set(Calendar.MILLISECOND, 0);
        setChartBase(initial.getTimeInMillis());
        ip.setText(preferences.getString("ip", "192.168.10.1"));
        ssid.setText(preferences.getString("ssid", "ClassicLogger"));
        url.setText(preferences.getString("url", ""));
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (preferences.getBoolean("running", false)) {
                    Toast.makeText(MainActivity.this, "すでにロギング中です", Toast.LENGTH_SHORT).show();
                    return;
                }
                preferences.edit().putString("ip", ip.getText().toString().trim())
                        .putString("ssid", ssid.getText().toString().trim())
                        .putString("url", url.getText().toString().trim()).apply();
                DiagnosticLog.i(MainActivity.this, "ロギング開始ボタン押下");
                try { startService(new Intent(MainActivity.this, LoggerService.class).setAction("START")); }
                catch (Throwable t) { DiagnosticLog.e(MainActivity.this, "Service開始要求失敗 " + t.getClass().getSimpleName(), t); }
                refresh();
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                DiagnosticLog.i(MainActivity.this, "停止ボタン押下");
                stopService(new Intent(MainActivity.this, LoggerService.class));
                preferences.edit().putBoolean("running", false).apply(); refresh();
            }
        });
        uploadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                DiagnosticLog.i(MainActivity.this, "今すぐ30秒アップロードボタン押下");
                try { startService(new Intent(MainActivity.this, LoggerService.class).setAction("UPLOAD_NOW")); }
                catch (Throwable t) { DiagnosticLog.e(MainActivity.this, "アップロード要求失敗 " + t.getClass().getSimpleName(), t); }
                refresh();
            }
        });
        findViewById(R.id.refreshLog).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { refresh(); }
        });
        findViewById(R.id.clearLog).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Database db = new Database(MainActivity.this);
                try { db.clearEvents(); } finally { db.close(); }
                refresh();
            }
        });
        findViewById(R.id.refreshChart).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { refreshChart(); }
        });
        findViewById(R.id.previousDay).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { shiftChartBase(-1); }
        });
        findViewById(R.id.nextDay).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { shiftChartBase(1); }
        });
    }
    @Override protected void onResume() { super.onResume(); refresh(); refreshChart(); }
    private void refresh() {
        Database db = new Database(this);
        try {
            Record r = db.latest();
            boolean running = preferences.getBoolean("running", false);
            stateView.setText(running ? "ロギング中" : "停止中");
            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
            if (r == null) currentView.setText("データ未取得");
            else currentView.setText("バッテリー電圧  " + r.batteryV + " V\nWhizbang Jr.電流  " + r.wbCurrentA + " A\nPV電圧  " + r.pvV + " V\nPV電力  " + r.watts + " W\n充電ステージ  " + r.chargeStage + "\n最終記録  " + r.timestamp + "\n未送信  " + db.pendingCount() + " 件");
            String logsText = db.recentEvents();
            eventsView.setText(logsText.length() == 0 ? "通信ログなし" : logsText);
        } finally { db.close(); }
    }
    private long parseChartBase() throws Exception {
        return chartInputFormat.parse(chartDateView.getText().toString().trim() + " " +
                chartTimeView.getText().toString().trim()).getTime();
    }

    private void setChartBase(long millis) {
        String[] parts = chartInputFormat.format(new Date(millis)).split(" ");
        chartDateView.setText(parts[0]);
        chartTimeView.setText(parts[1]);
    }

    private void shiftChartBase(int days) {
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(parseChartBase());
            calendar.add(Calendar.DAY_OF_MONTH, days);
            setChartBase(calendar.getTimeInMillis());
            refreshChart();
        } catch (Exception e) {
            chartPeriodView.setText("日時は yyyy-MM-dd / HH:mm で入力してください");
        }
    }

    private void refreshChart() {
        Database db = new Database(this);
        try {
            long selected = parseChartBase();
            long day = 24L * 60L * 60L * 1000L;
            long start = fromSelectedView.isChecked() ? selected : selected - day;
            long end = fromSelectedView.isChecked() ? selected + day : selected;
            chartPeriodView.setText("表示期間: " + chartLabelFormat.format(new Date(start)) +
                    " ～ " + chartLabelFormat.format(new Date(end)));
            chartView.setSeries("バッテリー電圧", "V", db.batteryVoltagePoints(start, end, 240));
        } catch (Exception e) {
            chartPeriodView.setText("日時は yyyy-MM-dd / HH:mm で入力してください");
            chartView.setSeries("バッテリー電圧", "V", null);
        } finally { db.close(); }
    }

}
