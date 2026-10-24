package jp.terada.midnitelogger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class MainActivity extends Activity {
    private static final String[] CHART_COLUMNS = {
            "battery_v", "pv_v", "classic_a", "wb_a", "wb_precise_a",
            "pv_a", "watts", "daily_kwh", "batt_temp", "fet_temp", "pcb_temp"
    };
    private static final String[] CHART_UNITS = {"V", "V", "A", "A", "A", "A", "W", "kWh", "C", "C", "C"};

    private Database db;
    private TextView status;
    private TextView values;
    private TextView events;
    private TextView storage;
    private TextView wifiStatus;
    private TextView chartRangeLabel;
    private EditText url;
    private EditText key;
    private EditText device;
    private EditText classicWifiSsid;
    private EditText uploadWifiSsidList;
    private EditText chartMinValue;
    private EditText chartMaxValue;
    private Spinner metric;
    private Button chartAutoToggle;
    private ChartView chart;
    private final Handler handler = new Handler();
    private long chartWindowMs = 24L * 60L * 60L * 1000L;
    private long chartOffsetMs = 0L;
    private boolean chartAutoY = true;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 5000L);
        }
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
                startService(new Intent(MainActivity.this, LoggerService.class));
                refresh();
            }
        });
        addButton(actionRow, "停止", new View.OnClickListener() {
            @Override public void onClick(View view) {
                stopService(new Intent(MainActivity.this, LoggerService.class));
                refresh();
            }
        });
        addButton(actionRow, "30秒Upload", new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveSettings();
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            int count = new SupabaseUploader(MainActivity.this, db).upload30s();
                            db.event("OK", "Supabase送信 " + count + "件");
                        } catch (Exception ex) {
                            db.event("ERROR", "Upload: " + ex.getMessage());
                        }
                        handler.post(new Runnable() {
                            @Override public void run() { refresh(); }
                        });
                    }
                }).start();
            }
        });

        addText(root, "Supabase RPC URL");
        url = addEdit(root);
        addText(root, "Publishable key");
        key = addEdit(root);
        addText(root, "device_id");
        device = addEdit(root);
        addText(root, "Classic Wi‑Fi SSID");
        classicWifiSsid = addEdit(root);
        addText(root, "Upload Wi‑Fi SSIDs");
        uploadWifiSsidList = addEdit(root);
        addText(root, "現在の Wi‑Fi");
        wifiStatus = addText(root, "未確認");

        SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
        url.setText(prefs.getString("supabase_rpc_url", ""));
        key.setText(prefs.getString("supabase_key", ""));
        device.setText(prefs.getString("device_id", "shl24-01"));
        classicWifiSsid.setText(prefs.getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID));
        uploadWifiSsidList.setText(prefs.getString("upload_wifi_ssids", Config.DEFAULT_UPLOAD_WIFI_SSIDS));

        LinearLayout wifiRow = new LinearLayout(this);
        root.addView(wifiRow);
        addButton(wifiRow, "Wi‑Fi状態確認", new View.OnClickListener() {
            @Override public void onClick(View view) {
                updateWifiStatus();
            }
        });
        addButton(wifiRow, "NETGEAR復帰", new View.OnClickListener() {
            @Override public void onClick(View view) {
                WifiNetworkManager wifi = new WifiNetworkManager(MainActivity.this);
                boolean ok = wifi.restoreClassicNetwork();
                Toast.makeText(MainActivity.this,
                        ok ? "NETGEARへ復帰を試行" : "NETGEARへ戻れませんでした",
                        Toast.LENGTH_SHORT).show();
                updateWifiStatus();
            }
        });

        LinearLayout dbRow = new LinearLayout(this);
        root.addView(dbRow);
        addButton(dbRow, "設定保存", new View.OnClickListener() {
            @Override public void onClick(View view) { saveSettings(); }
        });
        addButton(dbRow, "DBバックアップ", new View.OnClickListener() {
            @Override public void onClick(View view) { backupDatabase(); }
        });
        addButton(dbRow, "DB統合", new View.OnClickListener() {
            @Override public void onClick(View view) { mergeLatestBackup(); }
        });
        storage = addText(root, "");

        addText(root, "グラフ項目");
        metric = new Spinner(this);
        String[] names = new String[]{
                "バッテリー電圧", "PV電圧", "Classic電流", "WB電流",
                "WB精密電流", "PV電流", "PV電力", "本日発電量",
                "バッテリー温度", "FET温度", "基板温度"
        };
        metric.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        metric.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadChartSettingsForCurrentMetric();
                updateGraph();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(metric);

        addText(root, "グラフY軸（固定/自動）");
        LinearLayout chartRangeRow = new LinearLayout(this);
        chartRangeRow.setOrientation(LinearLayout.HORIZONTAL);
        chartAutoToggle = addButton(chartRangeRow, "Y軸: 自動", new View.OnClickListener() {
            @Override public void onClick(View view) {
                chartAutoY = !chartAutoY;
                chartAutoToggle.setText(chartAutoY ? "Y軸: 自動" : "Y軸: 固定");
                persistGraphSettingsForCurrentMetric();
                updateGraph();
            }
        });
        chartMinValue = addEdit(chartRangeRow);
        chartMinValue.setHint("最小");
        chartMaxValue = addEdit(chartRangeRow);
        chartMaxValue.setHint("最大");
        root.addView(chartRangeRow);

        LinearLayout chartNavRow = new LinearLayout(this);
        chartNavRow.setOrientation(LinearLayout.HORIZONTAL);
        addButton(chartNavRow, "時間-", new View.OnClickListener() {
            @Override public void onClick(View view) {
                changeChartWindow(false);
            }
        });
        addButton(chartNavRow, "時間+", new View.OnClickListener() {
            @Override public void onClick(View view) {
                changeChartWindow(true);
            }
        });
        addButton(chartNavRow, "←", new View.OnClickListener() {
            @Override public void onClick(View view) {
                shiftChartWindow(-1);
            }
        });
        addButton(chartNavRow, "→", new View.OnClickListener() {
            @Override public void onClick(View view) {
                shiftChartWindow(1);
            }
        });
        root.addView(chartNavRow);
        chartRangeLabel = addText(root, "表示範囲: 24時間");

        chart = new ChartView(this);
        root.addView(chart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 500));
        addButton(root, "グラフ更新", new View.OnClickListener() {
            @Override public void onClick(View view) { updateGraph(); }
        });

        addText(root, "通信/イベントログ（直近50件）");
        events = addText(root, "");
        addButton(root, "ログ更新", new View.OnClickListener() {
            @Override public void onClick(View view) { refresh(); }
        });

        setContentView(scroll);
        loadChartSettingsForCurrentMetric();
        refresh();
        handler.postDelayed(refreshTask, 3000L);
    }

    private File getBackupDirectory() {
        File root = Environment.getExternalStorageDirectory();
        if (root != null) {
            File dir = new File(root, Config.BACKUP_DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                File fallback = new File(getFilesDir(), Config.BACKUP_DIR_NAME);
                if (!fallback.exists() && !fallback.mkdirs()) {
                    return getFilesDir();
                }
                return fallback;
            }
            return dir;
        }
        File dir = new File(getFilesDir(), Config.BACKUP_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            return getFilesDir();
        }
        return dir;
    }

    private File getSettingsFile() {
        return new File(getBackupDirectory(), Config.SETTINGS_FILE_NAME);
    }

    private void loadPersistedSettingsFile() {
        File file = getSettingsFile();
        if (!file.exists()) {
            return;
        }
        Properties props = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
            SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
            SharedPreferences.Editor edit = prefs.edit();
            String urlValue = props.getProperty("supabase_rpc_url", "");
            if (urlValue.length() > 0) edit.putString("supabase_rpc_url", urlValue);
            String keyValue = props.getProperty("supabase_key", "");
            if (keyValue.length() > 0) edit.putString("supabase_key", keyValue);
            String deviceValue = props.getProperty("device_id", "");
            if (deviceValue.length() > 0) edit.putString("device_id", deviceValue);
            String classicValue = props.getProperty("classic_wifi_ssid", "");
            if (classicValue.length() > 0) edit.putString("classic_wifi_ssid", classicValue);
            String uploadValue = props.getProperty("upload_wifi_ssids", "");
            if (uploadValue.length() > 0) edit.putString("upload_wifi_ssids", uploadValue);
            for (int i = 0; i < CHART_COLUMNS.length; i++) {
                String metricKey = CHART_COLUMNS[i];
                String autoKey = "graph_auto_" + metricKey;
                String autoValue = props.getProperty(autoKey, "");
                if (autoValue.length() > 0) {
                    edit.putBoolean(autoKey, "1".equals(autoValue) || "true".equalsIgnoreCase(autoValue));
                }
                String minKey = "graph_fixed_min_" + metricKey;
                String minValue = props.getProperty(minKey, "");
                if (minValue.length() > 0) {
                    try {
                        edit.putFloat(minKey, Float.parseFloat(minValue));
                    } catch (NumberFormatException ignored) { }
                }
                String maxKey = "graph_fixed_max_" + metricKey;
                String maxValue = props.getProperty(maxKey, "");
                if (maxValue.length() > 0) {
                    try {
                        edit.putFloat(maxKey, Float.parseFloat(maxValue));
                    } catch (NumberFormatException ignored) { }
                }
            }
            String windowValue = props.getProperty("graph_window_ms", "");
            if (windowValue.length() > 0) {
                try { edit.putLong("graph_window_ms", Long.parseLong(windowValue)); } catch (NumberFormatException ignored) { }
            }
            String offsetValue = props.getProperty("graph_offset_ms", "");
            if (offsetValue.length() > 0) {
                try { edit.putLong("graph_offset_ms", Long.parseLong(offsetValue)); } catch (NumberFormatException ignored) { }
            }
            edit.apply();
        } catch (IOException ignored) {
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void writePersistedSettingsFile() {
        File dir = getBackupDirectory();
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, Config.SETTINGS_FILE_NAME);
        Properties props = new Properties();
        SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
        props.setProperty("supabase_rpc_url", prefs.getString("supabase_rpc_url", ""));
        props.setProperty("supabase_key", prefs.getString("supabase_key", ""));
        props.setProperty("device_id", prefs.getString("device_id", ""));
        props.setProperty("classic_wifi_ssid", prefs.getString("classic_wifi_ssid", Config.CLASSIC_WIFI_SSID));
        props.setProperty("upload_wifi_ssids", prefs.getString("upload_wifi_ssids", Config.DEFAULT_UPLOAD_WIFI_SSIDS));
        for (int i = 0; i < CHART_COLUMNS.length; i++) {
            String metricKey = CHART_COLUMNS[i];
            props.setProperty("graph_auto_" + metricKey, prefs.getBoolean("graph_auto_" + metricKey, true) ? "1" : "0");
            if (prefs.contains("graph_fixed_min_" + metricKey)) {
                props.setProperty("graph_fixed_min_" + metricKey, Float.toString(prefs.getFloat("graph_fixed_min_" + metricKey, 0f)));
            }
            if (prefs.contains("graph_fixed_max_" + metricKey)) {
                props.setProperty("graph_fixed_max_" + metricKey, Float.toString(prefs.getFloat("graph_fixed_max_" + metricKey, 0f)));
            }
        }
        props.setProperty("graph_window_ms", Long.toString(chartWindowMs));
        props.setProperty("graph_offset_ms", Long.toString(chartOffsetMs));
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            props.store(out, "Midnite Logger settings");
        } catch (IOException ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) { }
            }
        }
    }

    private TextView addText(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(Color.DKGRAY);
        view.setPadding(4, 6, 4, 6);
        parent.addView(view);
        return view;
    }

    private EditText addEdit(LinearLayout parent) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        parent.addView(edit);
        return edit;
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        parent.addView(button);
        return button;
    }

    private void saveSettings() {
        SharedPreferences.Editor edit = getSharedPreferences(Config.PREFS, 0).edit();
        edit.putString("supabase_rpc_url", url.getText().toString().trim())
                .putString("supabase_key", key.getText().toString().trim())
                .putString("device_id", device.getText().toString().trim())
                .putString("classic_wifi_ssid", classicWifiSsid.getText().toString().trim())
                .putString("upload_wifi_ssids", uploadWifiSsidList.getText().toString().trim())
                .apply();
        persistGraphSettingsForCurrentMetric();
        writePersistedSettingsFile();
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show();
    }

    private void loadChartSettingsForCurrentMetric() {
        if (metric == null || chartMinValue == null || chartMaxValue == null) {
            return;
        }
        String key = CHART_COLUMNS[metric.getSelectedItemPosition()];
        SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
        chartAutoY = prefs.getBoolean("graph_auto_" + key, true);
        chartWindowMs = prefs.getLong("graph_window_ms", 24L * 60L * 60L * 1000L);
        chartOffsetMs = prefs.getLong("graph_offset_ms", 0L);
        if (prefs.contains("graph_fixed_min_" + key)) {
            chartMinValue.setText(Float.toString(prefs.getFloat("graph_fixed_min_" + key, 0f)));
        } else {
            chartMinValue.setText("");
        }
        if (prefs.contains("graph_fixed_max_" + key)) {
            chartMaxValue.setText(Float.toString(prefs.getFloat("graph_fixed_max_" + key, 0f)));
        } else {
            chartMaxValue.setText("");
        }
        if (chartAutoToggle != null) {
            chartAutoToggle.setText(chartAutoY ? "Y軸: 自動" : "Y軸: 固定");
        }
        if (chartRangeLabel != null) {
            chartRangeLabel.setText("表示範囲: " + formatRangeDescription(chartWindowMs));
        }
    }

    private void persistGraphSettingsForCurrentMetric() {
        if (metric == null || chartMinValue == null || chartMaxValue == null) {
            return;
        }
        String key = CHART_COLUMNS[metric.getSelectedItemPosition()];
        SharedPreferences.Editor edit = getSharedPreferences(Config.PREFS, 0).edit();
        edit.putBoolean("graph_auto_" + key, chartAutoY);
        edit.putLong("graph_window_ms", chartWindowMs);
        edit.putLong("graph_offset_ms", chartOffsetMs);
        String minText = chartMinValue.getText().toString().trim();
        if (minText.length() > 0) {
            try {
                edit.putFloat("graph_fixed_min_" + key, Float.parseFloat(minText));
            } catch (NumberFormatException ignored) { }
        } else {
            edit.remove("graph_fixed_min_" + key);
        }
        String maxText = chartMaxValue.getText().toString().trim();
        if (maxText.length() > 0) {
            try {
                edit.putFloat("graph_fixed_max_" + key, Float.parseFloat(maxText));
            } catch (NumberFormatException ignored) { }
        } else {
            edit.remove("graph_fixed_max_" + key);
        }
        edit.apply();
        writePersistedSettingsFile();
    }

    private void refresh() {
        boolean running = getSharedPreferences(Config.PREFS, 0)
                .getBoolean("running", false);
        status.setText(running ? "ロギング中" : "停止中");

        Record record = db.latest();
        if (record == null) {
            values.setText("データ未取得");
        } else {
            values.setText(String.format(Locale.US,
                    "最終記録 %s\nBattery %.2f V  PV %.2f V\nWB %.2f A  Power %d W\n充電状態 %s",
                    record.ts, record.batteryV, record.pvV,
                    record.wbPreciseA, record.watts, chargeStageText(record.chargeStage)));
        }
        events.setText(formatEventLog(db.recentEvents(50)));
        storage.setText(String.format(Locale.US,
                "測定 %d件 / DB %.2f MB / ERROR %d件 / 端末空き %.2f GB",
                db.count(), db.dbBytes() / 1048576.0,
                db.errorCount(), db.freeBytes() / 1073741824.0));
        updateWifiStatus();
        updateGraph();
    }

    private void updateWifiStatus() {
        WifiNetworkManager wifi = new WifiNetworkManager(this);
        String current = wifi.getConnectedSsid();
        wifiStatus.setText(current == null ? "未接続" : current);
    }

    private String chargeStageText(int code) {
        switch (code) {
            case 0: return "Resting/停止";
            case 3: return "Absorb（定電圧充電）";
            case 4: return "Bulk MPPT（最大電力充電）";
            case 5: return "Float（維持充電）";
            case 6: return "Float MPPT";
            case 7: return "Equalize";
            default: return "コード " + code;
        }
    }

    private void changeChartWindow(boolean zoomIn) {
        long minWindow = 30L * 60L * 1000L;
        long maxWindow = 7L * 24L * 60L * 60L * 1000L;
        long next = zoomIn ? (chartWindowMs / 2L) : (chartWindowMs * 2L);
        if (next < minWindow) next = minWindow;
        if (next > maxWindow) next = maxWindow;
        chartWindowMs = next;
        persistGraphSettingsForCurrentMetric();
        updateGraph();
    }

    private void shiftChartWindow(int direction) {
        long step = Math.max(chartWindowMs / 8L, 60L * 1000L);
        chartOffsetMs += direction * step;
        persistGraphSettingsForCurrentMetric();
        updateGraph();
    }

    private String formatRangeDescription(long rangeMs) {
        long hours = rangeMs / (60L * 60L * 1000L);
        if (hours <= 0) {
            return "30分";
        }
        if (hours < 24L) {
            return hours + "時間";
        }
        long days = hours / 24L;
        if (days < 7L) {
            return days + "日";
        }
        return "7日";
    }

    private CharSequence formatEventLog(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = raw.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            String line = lines[i];
            if (line.contains("[ERROR]") || line.contains(" ERROR ") || line.contains("ERROR")) {
                int start = builder.length();
                builder.append(line);
                builder.setSpan(new ForegroundColorSpan(Color.RED), start, builder.length(), 0);
            } else {
                builder.append(line);
            }
        }
        return builder;
    }

    private void updateGraph() {
        if (metric == null || chart == null) {
            return;
        }
        int index = metric.getSelectedItemPosition();
        if (index < 0 || index >= CHART_COLUMNS.length) {
            return;
        }
        String column = CHART_COLUMNS[index];
        long endMs = System.currentTimeMillis() - chartOffsetMs;
        long startMs = endMs - chartWindowMs;
        Date end = new Date(endMs);
        Date start = new Date(startMs);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        List<float[]> points = db.chart(column, format.format(start), format.format(end), 240);
        Float fixedMin = null;
        Float fixedMax = null;
        if (!chartAutoY) {
            SharedPreferences prefs = getSharedPreferences(Config.PREFS, 0);
            if (prefs.contains("graph_fixed_min_" + column)) {
                fixedMin = prefs.getFloat("graph_fixed_min_" + column, 0f);
            }
            if (prefs.contains("graph_fixed_max_" + column)) {
                fixedMax = prefs.getFloat("graph_fixed_max_" + column, 0f);
            }
        }
        chart.set(points, metric.getSelectedItem() + " [" + CHART_UNITS[index] + "]",
                startMs, endMs, fixedMin, fixedMax, chartAutoY);
        if (chartRangeLabel != null) {
            chartRangeLabel.setText("表示範囲: " + formatRangeDescription(chartWindowMs));
        }
    }

    private void backupDatabase() {
        try {
            File file = db.backup();
            Toast.makeText(this, "保存: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void mergeLatestBackup() {
        File base = getBackupDirectory();

        if (base == null) {
            Toast.makeText(this, "外部ストレージを利用できません", Toast.LENGTH_LONG).show();
            return;
        }

        File directory = new File(base, Config.BACKUP_DIR_NAME);
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "バックアップフォルダーを作成できません: "
                    + directory.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }

        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(this, "バックアップなし", Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        try {
            int count = db.mergeBackup(files[0]);
            Toast.makeText(this, "統合 " + count + "件", Toast.LENGTH_LONG).show();
            refresh();
        } catch (Exception ex) {
            Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        ArrayList<String> permissions = new ArrayList<String>();
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add("android.permission.ACCESS_FINE_LOCATION");
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add("android.permission.NEARBY_WIFI_DEVICES");
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[permissions.size()]), 7);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }
}
