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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private Database db;
    private TextView status;
    private TextView values;
    private TextView events;
    private TextView storage;
    private TextView wifiStatus;
    private EditText url;
    private EditText key;
    private EditText device;
    private EditText classicWifiSsid;
    private EditText uploadWifiSsidList;
    private Spinner metric;
    private ChartView chart;
    private final Handler handler = new Handler();

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 5000L);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Database(this);
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
        root.addView(metric);

        chart = new ChartView(this);
        root.addView(chart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 500));
        addButton(root, "直近24時間グラフ更新", new View.OnClickListener() {
            @Override public void onClick(View view) { updateGraph(); }
        });

        addText(root, "通信/イベントログ（直近50件）");
        events = addText(root, "");
        addButton(root, "ログ更新", new View.OnClickListener() {
            @Override public void onClick(View view) { refresh(); }
        });

        setContentView(scroll);
        refresh();
        handler.postDelayed(refreshTask, 3000L);
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
        getSharedPreferences(Config.PREFS, 0).edit()
                .putString("supabase_rpc_url", url.getText().toString().trim())
                .putString("supabase_key", key.getText().toString().trim())
                .putString("device_id", device.getText().toString().trim())
                .putString("classic_wifi_ssid", classicWifiSsid.getText().toString().trim())
                .putString("upload_wifi_ssids", uploadWifiSsidList.getText().toString().trim())
                .apply();
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show();
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
        events.setText(db.recentEvents(50));
        storage.setText(String.format(Locale.US,
                "測定 %d件 / DB %.2f MB / ERROR %d件 / 端末空き %.2f GB",
                db.count(), db.dbBytes() / 1048576.0,
                db.errorCount(), db.freeBytes() / 1073741824.0));
        updateWifiStatus();
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

    private void updateGraph() {
        String[] columns = new String[]{
                "battery_v", "pv_v", "classic_a", "wb_a", "wb_precise_a",
                "pv_a", "watts", "daily_kwh", "batt_temp", "fet_temp", "pcb_temp"
        };
        String[] units = new String[]{"V", "V", "A", "A", "A", "A", "W", "kWh", "C", "C", "C"};
        int index = metric.getSelectedItemPosition();
        Date end = new Date();
        Date start = new Date(end.getTime() - 86400000L);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        List<float[]> points = db.chart(
                columns[index], format.format(start), format.format(end), 240);
        chart.set(points, metric.getSelectedItem() + " [" + units[index] + "]",
                start.getTime(), end.getTime());
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
        File base = getExternalFilesDir(null);

        if (base == null) {
            Toast.makeText(this, "外部ストレージを利用できません", Toast.LENGTH_LONG).show();
            return;
        }

        File directory = new File(base, "MidniteLogger");
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
