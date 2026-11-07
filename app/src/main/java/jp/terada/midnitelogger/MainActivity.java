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
            "battery_v",
            "pv_v",
            "classic_a",
            "wb_a",
            "wb_precise_a",
            "pv_a",
            "watts",
            "daily_kwh",
            "battery_temp",
            "fet_temp",
            "pcb_temp"
    };

    private static final String[] CHART_UNITS = {
            "V", "V", "A", "A", "A", "A",
            "W", "kWh", "C", "C", "C"
    };

    /*
     * Daily Log集中救出の待機時間
     *
     * ClassicのDaily Log処理は遅いため、
     * dayIndexを変更した直後は十分な時間を与える。
     */
    private static final long DAILY_FIRST_INDEX_WAIT_MS = 5000L;
    private static final long DAILY_NEXT_INDEX_WAIT_MS = 10000L;

    /*
     * 1日取得に失敗した場合の再試行待機時間
     */
    private static final long DAILY_RETRY_WAIT_1_MS = 10000L;
    private static final long DAILY_RETRY_WAIT_2_MS = 30000L;

    /*
     * 3回失敗して次の日へ移る場合の休止時間
     */
    private static final long DAILY_FAILED_DAY_WAIT_MS = 30000L;

    private Database db;

    private TextView status;
    private TextView values;
    private TextView events;
    private TextView storage;
    private TextView uploadState;
    private TextView dailyRescueState;
    private TextView wifiStatus;

    private EditText dailyStartDate;
    private EditText dailyEndDate;

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

    private long chartWindowMs =
            24L * 60L * 60L * 1000L;

    private long chartOffsetMs = 0L;

    private boolean chartAutoY = true;

    /*
     * Daily Log集中救出状態
     */
    private volatile boolean dailyRescueRunning = false;
    private volatile boolean dailyRescueCancelRequested = false;
    private Thread dailyRescueThread;

    private final Runnable refreshTask =
            new Runnable() {
                @Override
                public void run() {
                    refresh();
                    handler.postDelayed(
                            this,
                            5000L
                    );
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        db = new Database(this);

        loadPersistedSettingsFile();
        requestRuntimePermissions();

        ScrollView scroll =
                new ScrollView(this);

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setPadding(
                12,
                12,
                12,
                20
        );

        scroll.addView(root);

        status =
                addText(
                        root,
                        "状態"
                );

        values =
                addText(
                        root,
                        "データ未取得"
                );

        /*
         * =====================================
         * 開始 / 停止 / Upload
         * =====================================
         */

        LinearLayout actionRow =
                new LinearLayout(this);

        root.addView(actionRow);

        addButton(
                actionRow,
                "開始",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {

                        /*
                         * Daily Log集中救出中に通常FC03を開始すると、
                         * ClassicへFC03とFC104が同時に飛ぶ可能性がある。
                         */
                        if (dailyRescueRunning) {

                            Toast.makeText(
                                    MainActivity.this,
                                    "Daily Log集中救出中は通常ロギングを開始できません",
                                    Toast.LENGTH_SHORT
                            ).show();

                            db.event(
                                    "WARN",
                                    "通常ログ開始を拒否: Daily Log集中救出中"
                            );

                            return;
                        }

                        startService(
                                new Intent(
                                        MainActivity.this,
                                        LoggerService.class
                                )
                        );

                        refresh();
                    }
                }
        );

        addButton(
                actionRow,
                "停止",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {

                        /*
                         * 通常ロギング停止
                         */
                        stopService(
                                new Intent(
                                        MainActivity.this,
                                        LoggerService.class
                                )
                        );

                        /*
                         * Daily Log集中救出も同じ停止ボタンで停止
                         */
                        if (dailyRescueRunning) {

                            dailyRescueCancelRequested = true;

                            db.event(
                                    "INFO",
                                    "停止要求: Daily Log集中救出を中止"
                            );

                            if (dailyRescueState != null) {

                                dailyRescueState.setText(
                                        "Daily Log集中救出: 停止要求中..."
                                );
                            }

                            Thread thread =
                                    dailyRescueThread;

                            if (thread != null) {

                                thread.interrupt();
                            }

                        } else {

                            db.event(
                                    "INFO",
                                    "停止要求: 通常ロギング停止"
                            );
                        }

                        refresh();
                    }
                }
        );

        addButton(
                actionRow,
                "30秒Upload",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {

                        saveSettings();

                        new Thread(
                                new Runnable() {

                                    @Override
                                    public void run() {

                                        try {

                                            int count =
                                                    new SupabaseUploader(
                                                            MainActivity.this,
                                                            db
                                                    ).upload30s();

                                            db.event(
                                                    "OK",
                                                    "Supabase送信 "
                                                            + count
                                                            + "件"
                                            );

                                        } catch (Exception ex) {

                                            db.event(
                                                    "ERROR",
                                                    "Upload: "
                                                            + ex.getMessage()
                                            );
                                        }

                                        handler.post(
                                                new Runnable() {

                                                    @Override
                                                    public void run() {
                                                        refresh();
                                                    }
                                                }
                                        );
                                    }
                                }
                        ).start();
                    }
                }
        );

        /*
         * =====================================
         * Supabase / Wi-Fi設定
         * =====================================
         */

        addText(
                root,
                "Supabase RPC URL"
        );

        url = addEdit(root);

        addText(
                root,
                "Publishable key"
        );

        key = addEdit(root);

        addText(
                root,
                "device_id"
        );

        device = addEdit(root);

        addText(
                root,
                "Classic Wi-Fi SSID"
        );

        classicWifiSsid =
                addEdit(root);

        addText(
                root,
                "Upload Wi-Fi SSIDs"
        );

        uploadWifiSsidList =
                addEdit(root);

        addText(
                root,
                "現在の Wi-Fi"
        );

        wifiStatus =
                addText(
                        root,
                        "未確認"
                );

        SharedPreferences prefs =
                getSharedPreferences(
                        Config.PREFS,
                        0
                );

        url.setText(
                prefs.getString(
                        "supabase_rpc_url",
                        Config.DEFAULT_SUPABASE_RPC_URL
                )
        );

        key.setText(
                prefs.getString(
                        "supabase_key",
                        ""
                )
        );

        device.setText(
                prefs.getString(
                        "device_id",
                        "shl24-01"
                )
        );

        classicWifiSsid.setText(
                prefs.getString(
                        "classic_wifi_ssid",
                        Config.CLASSIC_WIFI_SSID
                )
        );

        uploadWifiSsidList.setText(
                prefs.getString(
                        "upload_wifi_ssids",
                        Config.DEFAULT_UPLOAD_WIFI_SSIDS
                )
        );

        LinearLayout wifiRow =
                new LinearLayout(this);

        root.addView(wifiRow);

        addButton(
                wifiRow,
                "Wi-Fi状態確認",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        updateWifiStatus();
                    }
                }
        );

        addButton(
                wifiRow,
                "NETGEAR復帰",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {

                        WifiNetworkManager wifi =
                                new WifiNetworkManager(
                                        MainActivity.this
                                );

                        String classicSsid =
                                getSharedPreferences(
                                        Config.PREFS,
                                        0
                                ).getString(
                                        "classic_wifi_ssid",
                                        Config.CLASSIC_WIFI_SSID
                                );

                        boolean ok =
                                wifi.restoreClassicNetwork(
                                        classicSsid
                                );

                        Toast.makeText(
                                MainActivity.this,
                                ok
                                        ? "NETGEARへ復帰を試行"
                                        : "NETGEARへ戻れませんでした",
                                Toast.LENGTH_SHORT
                        ).show();

                        updateWifiStatus();
                    }
                }
        );

        /*
         * =====================================
         * DB / 設定
         * =====================================
         */

        LinearLayout dbRow =
                new LinearLayout(this);

        root.addView(dbRow);

        addButton(
                dbRow,
                "設定保存",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        saveSettings();
                    }
                }
        );

        addButton(
                dbRow,
                "設定ファイルを開く",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        openSettingsFile();
                    }
                }
        );

        addButton(
                dbRow,
                "DBバックアップ",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        backupDatabase();
                    }
                }
        );

        addButton(
                dbRow,
                "DB統合",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        mergeLatestBackup();
                    }
                }
        );

        storage =
                addText(
                        root,
                        ""
                );

        uploadState =
                addText(
                        root,
                        "Upload状態を取得中..."
                );

        /*
         * =====================================
         * Daily Log集中救出
         * =====================================
         */

        addText(
                root,
                "Daily Log集中救出・Upload"
        );

        LinearLayout dailyDateRow =
                new LinearLayout(this);

        dailyDateRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        dailyStartDate =
                addEdit(dailyDateRow);

        dailyStartDate.setHint(
                "開始日 yyyy-MM-dd"
        );

        dailyEndDate =
                addEdit(dailyDateRow);

        dailyEndDate.setHint(
                "終了日 yyyy-MM-dd"
        );

        root.addView(dailyDateRow);

        Calendar defaultEnd =
                Calendar.getInstance();

        Calendar defaultStart =
                (Calendar) defaultEnd.clone();

        defaultStart.add(
                Calendar.DAY_OF_MONTH,
                -30
        );

        SimpleDateFormat dailyFormat =
                new SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.US
                );

        dailyStartDate.setText(
                dailyFormat.format(
                        defaultStart.getTime()
                )
        );

        dailyEndDate.setText(
                dailyFormat.format(
                        defaultEnd.getTime()
                )
        );

        addButton(
                root,
                "Daily Log集中救出・Upload",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {

                        runDailyLogConcentrated();
                    }
                }
        );

        dailyRescueState =
                addText(
                        root,
                        "Daily Log集中救出: 待機中"
                );

        /*
         * =====================================
         * グラフ
         * =====================================
         */

        addText(
                root,
                "グラフ項目"
        );

        metric =
                new Spinner(this);

        String[] names =
                new String[]{
                        "バッテリー電圧",
                        "PV電圧",
                        "Classic電流",
                        "WB電流",
                        "WB精密電流",
                        "PV電流",
                        "PV電力",
                        "本日発電量",
                        "バッテリー温度",
                        "FET温度",
                        "基板温度"
                };

        metric.setAdapter(
                new ArrayAdapter<String>(
                        this,
                        android.R.layout
                                .simple_spinner_dropdown_item,
                        names
                )
        );

        metric.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {

                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {

                        loadChartSettingsForCurrentMetric();
                        updateGraph();
                    }

                    @Override
                    public void onNothingSelected(
                            AdapterView<?> parent) {
                    }
                }
        );

        root.addView(metric);

        addText(
                root,
                "グラフY軸（固定/自動）"
        );

        LinearLayout chartRangeRow =
                new LinearLayout(this);

        chartRangeRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        chartAutoToggle =
                addButton(
                        chartRangeRow,
                        "Y軸: 自動",
                        new View.OnClickListener() {

                            @Override
                            public void onClick(View view) {

                                chartAutoY =
                                        !chartAutoY;

                                chartAutoToggle.setText(
                                        chartAutoY
                                                ? "Y軸: 自動"
                                                : "Y軸: 固定"
                                );

                                persistGraphSettingsForCurrentMetric();
                                updateGraph();
                            }
                        }
                );

        chartMinValue =
                addEdit(chartRangeRow);

        chartMinValue.setHint("最小");

        chartMaxValue =
                addEdit(chartRangeRow);

        chartMaxValue.setHint("最大");

        root.addView(chartRangeRow);

        LinearLayout chartNavRow =
                new LinearLayout(this);

        chartNavRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        addButton(
                chartNavRow,
                "時間-",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        changeChartWindow(false);
                    }
                }
        );

        addButton(
                chartNavRow,
                "時間+",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        changeChartWindow(true);
                    }
                }
        );

        addButton(
                chartNavRow,
                "←",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        shiftChartWindow(-1);
                    }
                }
        );

        addButton(
                chartNavRow,
                "→",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        shiftChartWindow(1);
                    }
                }
        );

        root.addView(chartNavRow);

        chartRangeLabel =
                addText(
                        root,
                        "表示範囲: 24時間"
                );

        chart =
                new ChartView(this);

        chart.setChartGestureListener(
                new ChartView.ChartGestureListener() {

                    @Override
                    public void onPan(
                            float deltaRatio) {

                        long deltaMs =
                                Math.round(
                                        Math.abs(
                                                deltaRatio
                                        )
                                                * chartWindowMs
                                );

                        if (deltaMs <= 0L) {
                            return;
                        }

                        if (deltaRatio < 0f) {

                            chartOffsetMs -=
                                    deltaMs;

                        } else {

                            chartOffsetMs +=
                                    deltaMs;
                        }

                        persistGraphSettingsForCurrentMetric();
                        updateGraph();
                    }

                    @Override
                    public void onZoom(
                            float scale) {

                        if (scale <= 0f
                                || Float.isNaN(scale)
                                || Float.isInfinite(scale)) {

                            return;
                        }

                        long next =
                                Math.round(
                                        chartWindowMs
                                                * scale
                                );

                        long minWindow =
                                30L
                                        * 60L
                                        * 1000L;

                        long maxWindow =
                                7L
                                        * 24L
                                        * 60L
                                        * 60L
                                        * 1000L;

                        if (next < minWindow) {
                            next = minWindow;
                        }

                        if (next > maxWindow) {
                            next = maxWindow;
                        }

                        if (next != chartWindowMs) {

                            chartWindowMs =
                                    next;

                            persistGraphSettingsForCurrentMetric();
                            updateGraph();
                        }
                    }
                }
        );

        root.addView(
                chart,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        500
                )
        );

        addButton(
                root,
                "グラフ更新",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        updateGraph();
                    }
                }
        );

        addText(
                root,
                "通信/イベントログ（直近50件）"
        );

        events =
                addText(
                        root,
                        ""
                );

        addButton(
                root,
                "ログ更新",
                new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        refresh();
                    }
                }
        );

        setContentView(scroll);

        loadChartSettingsForCurrentMetric();

        refresh();

        handler.postDelayed(
                refreshTask,
                3000L
        );
    }

    /*
     * ==========================================================
     * Daily Log集中救出
     * ==========================================================
     */

    private void runDailyLogConcentrated() {

        /*
         * 二重起動禁止
         */
        if (dailyRescueRunning) {

            Toast.makeText(
                    this,
                    "Daily Log集中救出は既に実行中です",
                    Toast.LENGTH_SHORT
            ).show();

            db.event(
                    "WARN",
                    "Daily Log集中救出の二重起動を拒否"
            );

            return;
        }

        final String startText =
                dailyStartDate
                        .getText()
                        .toString()
                        .trim();

        final String endText =
                dailyEndDate
                        .getText()
                        .toString()
                        .trim();

        final SimpleDateFormat fmt =
                new SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.US
                );

        fmt.setLenient(false);

        final Date startDate;
        final Date endDate;

        try {

            startDate =
                    fmt.parse(startText);

            endDate =
                    fmt.parse(endText);

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "日付は yyyy-MM-dd で入力してください",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        if (startDate.after(endDate)) {

            Toast.makeText(
                    this,
                    "開始日は終了日以前にしてください",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        long requestedDays =
                daysBetween(
                        startDate,
                        endDate
                ) + 1L;

        if (requestedDays < 1L
                || requestedDays > 400L) {

            Toast.makeText(
                    this,
                    "集中救出は最大400日までです",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        saveSettings();

        final boolean wasRunning =
                getSharedPreferences(
                        Config.PREFS,
                        0
                ).getBoolean(
                        "running",
                        false
                );

        /*
         * FC03とFC104を同時に送らない。
         */
        if (wasRunning) {

            stopService(
                    new Intent(
                            MainActivity.this,
                            LoggerService.class
                    )
            );
        }

        dailyRescueCancelRequested =
                false;

        dailyRescueRunning =
                true;

        dailyRescueState.setText(
                "Daily Log集中救出: 開始準備中"
        );

        dailyRescueThread =
                new Thread(
                        new Runnable() {

                            @Override
                            public void run() {

                                int rescued = 0;
                                int uploaded = 0;

                                String finalMessage;

                                final Modbus.L log =
                                        new Modbus.L() {

                                            @Override
                                            public void log(
                                                    String level,
                                                    String message) {

                                                db.event(
                                                        level,
                                                        message
                                                );
                                            }
                                        };

                                WifiNetworkManager wifi =
                                        new WifiNetworkManager(
                                                MainActivity.this
                                        );

                                SharedPreferences prefs =
                                        getSharedPreferences(
                                                Config.PREFS,
                                                0
                                        );

                                String classicSsid =
                                        prefs.getString(
                                                "classic_wifi_ssid",
                                                Config.CLASSIC_WIFI_SSID
                                        );

                                if (classicSsid == null
                                        || classicSsid.trim().length() == 0) {

                                    classicSsid =
                                            Config.CLASSIC_WIFI_SSID;
                                }

                                try {

                                    checkDailyRescueCancelled();

                                    postDailyState(
                                            "Classic側Wi-Fiへ復帰中..."
                                    );

                                    if (!wifi.restoreClassicNetwork(
                                            classicSsid)) {

                                        throw new IOException(
                                                "Classic Wi-Fi/IPへ復帰できません: "
                                                        + wifi.connectionDiagnostics()
                                        );
                                    }

                                    checkDailyRescueCancelled();

                                    ClassicReader reader =
                                            new ClassicReader(
                                                    log
                                            );

                                    /*
                                     * 最新Daily Logだけ確認。
                                     * 最古探索はしない。
                                     */
                                    postDailyState(
                                            "最新Daily Log日付を確認中..."
                                    );

                                    ClassicReader.DailyTimestamp newest =
                                            readTimestampWithRetry(
                                                    reader,
                                                    1,
                                                    3
                                            );

                                    Date newestDate =
                                            newest.calendar
                                                    .getTime();

                                    db.event(
                                            "INFO",
                                            "Daily最新ログ"
                                                    + " index=1"
                                                    + " date="
                                                    + newest.date
                                    );

                                    /*
                                     * 日付差からdayIndexを一発計算
                                     */
                                    int startIndex =
                                            1
                                                    + (int) daysBetween(
                                                    startDate,
                                                    newestDate
                                            );

                                    int endIndex =
                                            1
                                                    + (int) daysBetween(
                                                    endDate,
                                                    newestDate
                                            );

                                    if (startIndex < 1) {
                                        startIndex = 1;
                                    }

                                    if (endIndex < 1) {
                                        endIndex = 1;
                                    }

                                    if (startIndex > 1023
                                            || endIndex > 1023) {

                                        throw new IOException(
                                                "指定日がClassic Daily Log範囲外です"
                                                        + " startIndex="
                                                        + startIndex
                                                        + " endIndex="
                                                        + endIndex
                                        );
                                    }

                                    int fromIndex =
                                            Math.max(
                                                    startIndex,
                                                    endIndex
                                            );

                                    int toIndex =
                                            Math.min(
                                                    startIndex,
                                                    endIndex
                                            );

                                    db.event(
                                            "INFO",
                                            "Daily集中 index計算"
                                                    + " latest="
                                                    + newest.date
                                                    + " start="
                                                    + startText
                                                    + " end="
                                                    + endText
                                                    + " range="
                                                    + fromIndex
                                                    + ".."
                                                    + toIndex
                                    );

                                    /*
                                     * ==================================
                                     * 1日ずつ救出
                                     * ==================================
                                     */
                                    for (int dayIndex = fromIndex;
                                         dayIndex >= toIndex;
                                         dayIndex--) {

                                        checkDailyRescueCancelled();

                                        /*
                                         * dayIndexを切り替えた後、
                                         * Classic内部ログが切り替わる時間を与える。
                                         */
                                        long indexWait =
                                                dayIndex == fromIndex
                                                        ? DAILY_FIRST_INDEX_WAIT_MS
                                                        : DAILY_NEXT_INDEX_WAIT_MS;

                                        postDailyState(
                                                "Daily Log dayIndex切替待ち "
                                                        + dayIndex
                                                        + " / "
                                                        + (indexWait / 1000L)
                                                        + "秒"
                                        );

                                        db.event(
                                                "INFO",
                                                "Daily集中 dayIndex切替待ち"
                                                        + " index="
                                                        + dayIndex
                                                        + " wait="
                                                        + indexWait
                                                        + "ms"
                                        );

                                        sleepDaily(
                                                indexWait
                                        );

                                        Calendar expected =
                                                Calendar.getInstance();

                                        expected.setTime(
                                                newestDate
                                        );

                                        expected.add(
                                                Calendar.DAY_OF_MONTH,
                                                -(dayIndex - 1)
                                        );

                                        final String expectedDate =
                                                fmt.format(
                                                        expected.getTime()
                                                );

                                        DailyLogRecord row =
                                                null;

                                        Exception lastError =
                                                null;

                                        /*
                                         * 同じdayIndexのまま最大3回
                                         */
                                        for (int attempt = 1;
                                             attempt <= 3;
                                             attempt++) {

                                            checkDailyRescueCancelled();

                                            try {

                                                postDailyState(
                                                        "Daily Log救出 "
                                                                + expectedDate
                                                                + " index="
                                                                + dayIndex
                                                                + " attempt="
                                                                + attempt
                                                                + "/3"
                                                );

                                                db.event(
                                                        "INFO",
                                                        "Daily集中 1日取得開始"
                                                                + " expected="
                                                                + expectedDate
                                                                + " index="
                                                                + dayIndex
                                                                + " attempt="
                                                                + attempt
                                                );

                                                DailyLogRecord candidate =
                                                        reader.readDailyLog(
                                                                dayIndex
                                                        );

                                                /*
                                                 * 正しい日付になっているか確認。
                                                 *
                                                 * 違っていてもdayIndexは動かさない。
                                                 */
                                                if (!expectedDate.equals(
                                                        candidate.logDate)) {

                                                    throw new IOException(
                                                            "DailyLog日付不一致"
                                                                    + " index="
                                                                    + dayIndex
                                                                    + " expected="
                                                                    + expectedDate
                                                                    + " actual="
                                                                    + candidate.logDate
                                                    );
                                                }

                                                row =
                                                        candidate;

                                                lastError =
                                                        null;

                                                db.event(
                                                        "OK",
                                                        "Daily集中 1日取得成功"
                                                                + " date="
                                                                + row.logDate
                                                                + " index="
                                                                + dayIndex
                                                                + " attempt="
                                                                + attempt
                                                );

                                                break;

                                            } catch (DailyRescueCancelledException e) {

                                                throw e;

                                            } catch (Exception e) {

                                                lastError =
                                                        e;

                                                String level =
                                                        attempt < 3
                                                                ? "WARN"
                                                                : "ERROR";

                                                db.event(
                                                        level,
                                                        "Daily集中 1日再取得"
                                                                + " expected="
                                                                + expectedDate
                                                                + " index="
                                                                + dayIndex
                                                                + " attempt="
                                                                + attempt
                                                                + "/3 "
                                                                + e.toString()
                                                );

                                                if (attempt == 1) {

                                                    postDailyState(
                                                            "Daily Log再試行まで10秒待機..."
                                                    );

                                                    sleepDaily(
                                                            DAILY_RETRY_WAIT_1_MS
                                                    );

                                                } else if (attempt == 2) {

                                                    postDailyState(
                                                            "Daily Log再試行まで30秒待機..."
                                                    );

                                                    sleepDaily(
                                                            DAILY_RETRY_WAIT_2_MS
                                                    );
                                                }
                                            }
                                        }

                                        /*
                                         * 3回失敗
                                         */
                                        if (row == null) {

                                            db.event(
                                                    "ERROR",
                                                    "Daily集中 未取得"
                                                            + " date="
                                                            + expectedDate
                                                            + " index="
                                                            + dayIndex
                                                            + " after 3 attempts"
                                                            + (lastError == null
                                                            ? ""
                                                            : " cause="
                                                            + lastError.toString())
                                            );

                                            postDailyState(
                                                    "Daily Log未取得。Classicを30秒休止..."
                                            );

                                            sleepDaily(
                                                    DAILY_FAILED_DAY_WAIT_MS
                                            );

                                            continue;
                                        }

                                        /*
                                         * 1日分が全部正常な場合だけSQLiteへ保存。
                                         */
                                        if (!db.hasDailyLogDate(
                                                row.logDate)) {

                                            long id =
                                                    db.insertDailyLog(
                                                            row
                                                    );

                                            if (id >= 0) {
                                                rescued++;
                                            }
                                        }
                                    }

                                    checkDailyRescueCancelled();

                                    /*
                                     * ==================================
                                     * Upload
                                     * ==================================
                                     */

                                    List<String> uploadSsids =
                                            wifi.parseUploadSsidList(
                                                    prefs.getString(
                                                            "upload_wifi_ssids",
                                                            Config.DEFAULT_UPLOAD_WIFI_SSIDS
                                                    )
                                            );

                                    String candidate =
                                            wifi.findUploadNetworkInScanResults(
                                                    uploadSsids
                                            );

                                    if (wifi.shouldUploadOnCurrentNetwork(
                                            uploadSsids)) {

                                        candidate =
                                                wifi.getConnectedSsid();

                                    } else if (candidate != null) {

                                        postDailyState(
                                                "外部Wi-Fiへ切替中: "
                                                        + candidate
                                        );

                                        if (!wifi.connectToSsid(
                                                candidate,
                                                30000L)) {

                                            candidate =
                                                    null;
                                        }
                                    }

                                    checkDailyRescueCancelled();

                                    if (candidate != null) {

                                        postDailyState(
                                                "Daily Log Supabase Upload中..."
                                        );

                                        for (int batch = 0;
                                             batch < 10;
                                             batch++) {

                                            checkDailyRescueCancelled();

                                            int n =
                                                    new SupabaseUploader(
                                                            MainActivity.this,
                                                            db
                                                    ).uploadDailyLogs();

                                            if (n <= 0) {
                                                break;
                                            }

                                            uploaded +=
                                                    n;
                                        }

                                    } else {

                                        db.event(
                                                "INFO",
                                                "Daily集中: 外部Wi-Fiなし。"
                                                        + "救出データは未送信で保持"
                                        );
                                    }

                                    finalMessage =
                                            "完了: 救出 "
                                                    + rescued
                                                    + "件 / Upload "
                                                    + uploaded
                                                    + "件";

                                } catch (DailyRescueCancelledException e) {

                                    db.event(
                                            "INFO",
                                            "Daily Log集中救出 ユーザー中止"
                                    );

                                    finalMessage =
                                            "ユーザー操作により中止";

                                } catch (Exception e) {

                                    db.event(
                                            "ERROR",
                                            "Daily Log集中救出失敗: "
                                                    + e.toString()
                                    );

                                    finalMessage =
                                            "失敗: "
                                                    + e.getMessage();

                                } finally {

                                    /*
                                     * 必ずClassic側へ戻す。
                                     */
                                    postDailyState(
                                            "Classic側へ復帰中..."
                                    );

                                    wifi.restoreClassicNetwork(
                                            classicSsid
                                    );

                                    /*
                                     * ユーザーが停止した場合は、
                                     * 通常LoggerServiceを絶対に再開しない。
                                     */
                                    if (wasRunning
                                            && !dailyRescueCancelRequested) {

                                        startService(
                                                new Intent(
                                                        MainActivity.this,
                                                        LoggerService.class
                                                )
                                        );
                                    }

                                    dailyRescueRunning =
                                            false;

                                    dailyRescueThread =
                                            null;
                                }

                                final String show =
                                        finalMessage;

                                handler.post(
                                        new Runnable() {

                                            @Override
                                            public void run() {

                                                dailyRescueState.setText(
                                                        "Daily Log集中救出: "
                                                                + show
                                                );

                                                refresh();
                                            }
                                        }
                                );
                            }
                        }
                );

        dailyRescueThread.start();
    }

    /*
     * ==========================================================
     * Daily Log helper
     * ==========================================================
     */

    private void checkDailyRescueCancelled()
            throws DailyRescueCancelledException {

        if (dailyRescueCancelRequested
                || Thread.currentThread().isInterrupted()) {

            throw new DailyRescueCancelledException(
                    "Daily Log集中救出はユーザー操作で停止されました"
            );
        }
    }

    private ClassicReader.DailyTimestamp
    readTimestampWithRetry(
            ClassicReader reader,
            int dayIndex,
            int attempts)
            throws Exception {

        Exception last =
                null;

        for (int attempt = 1;
             attempt <= attempts;
             attempt++) {

            checkDailyRescueCancelled();

            try {

                return reader.readDailyTimestamp(
                        dayIndex
                );

            } catch (DailyRescueCancelledException e) {

                throw e;

            } catch (Exception e) {

                last = e;

                db.event(
                        attempt < attempts
                                ? "WARN"
                                : "ERROR",
                        "Daily timestamp再試行"
                                + " index="
                                + dayIndex
                                + " attempt="
                                + attempt
                                + "/"
                                + attempts
                                + " "
                                + e.toString()
                );

                if (attempt == 1) {

                    sleepDaily(
                            DAILY_RETRY_WAIT_1_MS
                    );

                } else if (attempt == 2) {

                    sleepDaily(
                            DAILY_RETRY_WAIT_2_MS
                    );
                }
            }
        }

        if (last != null) {
            throw last;
        }

        throw new IOException(
                "Daily timestamp取得失敗"
        );
    }

    private void sleepDaily(long ms)
            throws IOException {

        checkDailyRescueCancelled();

        try {

            Thread.sleep(ms);

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            throw new DailyRescueCancelledException(
                    "Daily Log集中救出は待機中に停止されました"
            );
        }

        checkDailyRescueCancelled();
    }

    private static long daysBetween(
            Date older,
            Date newer) {

        Calendar a =
                Calendar.getInstance();

        a.setTime(older);

        clearDateTime(a);

        Calendar b =
                Calendar.getInstance();

        b.setTime(newer);

        clearDateTime(b);

        return (
                b.getTimeInMillis()
                        - a.getTimeInMillis()
        ) / (
                24L
                        * 60L
                        * 60L
                        * 1000L
        );
    }

    private static void clearDateTime(
            Calendar c) {

        c.set(
                Calendar.HOUR_OF_DAY,
                0
        );

        c.set(
                Calendar.MINUTE,
                0
        );

        c.set(
                Calendar.SECOND,
                0
        );

        c.set(
                Calendar.MILLISECOND,
                0
        );
    }

    private static final class
    DailyRescueCancelledException
            extends IOException {

        DailyRescueCancelledException(
                String message) {

            super(message);
        }
    }

    /*
     * ==========================================================
     * Existing utility methods
     * ==========================================================
     */

    private File getBackupDirectory() {

        File root =
                Environment
                        .getExternalStorageDirectory();

        if (root != null) {

            File dir =
                    new File(
                            root,
                            Config.BACKUP_DIR_NAME
                    );

            if (!dir.exists()
                    && !dir.mkdirs()) {

                File fallback =
                        new File(
                                getFilesDir(),
                                Config.BACKUP_DIR_NAME
                        );

                if (!fallback.exists()
                        && !fallback.mkdirs()) {

                    return getFilesDir();
                }

                return fallback;
            }

            return dir;
        }

        File dir =
                new File(
                        getFilesDir(),
                        Config.BACKUP_DIR_NAME
                );

        if (!dir.exists()
                && !dir.mkdirs()) {

            return getFilesDir();
        }

        return dir;
    }

    private File getSettingsFile() {

        return new File(
                getBackupDirectory(),
                Config.SETTINGS_FILE_NAME
        );
    }

    private void openSettingsFile() {

        File file =
                getSettingsFile();

        if (!file.exists()) {

            writePersistedSettingsFile();

            if (!file.exists()) {

                Toast.makeText(
                        this,
                        "設定ファイルを作成できませんでした",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }
        }

        Intent intent =
                new Intent(
                        Intent.ACTION_VIEW
                );

        intent.setDataAndType(
                Uri.fromFile(file),
                "text/plain"
        );

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
        );

        try {

            startActivity(
                    Intent.createChooser(
                            intent,
                            "設定ファイルを開く"
                    )
            );

        } catch (ActivityNotFoundException ex) {

            Toast.makeText(
                    this,
                    "テキストエディタが見つかりませんでした",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void loadPersistedSettingsFile() {

        File file =
                getSettingsFile();

        if (!file.exists()) {
            return;
        }

        Properties props =
                new Properties();

        FileInputStream in =
                null;

        try {

            in =
                    new FileInputStream(
                            file
                    );

            props.load(in);

            SharedPreferences prefs =
                    getSharedPreferences(
                            Config.PREFS,
                            0
                    );

            SharedPreferences.Editor edit =
                    prefs.edit();

            String urlValue =
                    props.getProperty(
                            "supabase_rpc_url",
                            ""
                    );

            if (urlValue.length() > 0) {
                edit.putString(
                        "supabase_rpc_url",
                        urlValue
                );
            }

            String keyValue =
                    props.getProperty(
                            "supabase_key",
                            ""
                    );

            if (keyValue.length() > 0) {
                edit.putString(
                        "supabase_key",
                        keyValue
                );
            }

            String deviceValue =
                    props.getProperty(
                            "device_id",
                            ""
                    );

            if (deviceValue.length() > 0) {
                edit.putString(
                        "device_id",
                        deviceValue
                );
            }

            String classicValue =
                    props.getProperty(
                            "classic_wifi_ssid",
                            ""
                    );

            if (classicValue.length() > 0) {
                edit.putString(
                        "classic_wifi_ssid",
                        classicValue
                );
            }

            String uploadValue =
                    props.getProperty(
                            "upload_wifi_ssids",
                            ""
                    );

            if (uploadValue.length() > 0) {
                edit.putString(
                        "upload_wifi_ssids",
                        uploadValue
                );
            }

            for (int i = 0;
                 i < CHART_COLUMNS.length;
                 i++) {

                String metricKey =
                        CHART_COLUMNS[i];

                String autoKey =
                        "graph_auto_"
                                + metricKey;

                String autoValue =
                        props.getProperty(
                                autoKey,
                                ""
                        );

                if (autoValue.length() > 0) {

                    edit.putBoolean(
                            autoKey,
                            "1".equals(autoValue)
                                    || "true".equalsIgnoreCase(
                                    autoValue
                            )
                    );
                }

                String minKey =
                        "graph_fixed_min_"
                                + metricKey;

                String minValue =
                        props.getProperty(
                                minKey,
                                ""
                        );

                if (minValue.length() > 0) {

                    try {

                        edit.putFloat(
                                minKey,
                                Float.parseFloat(
                                        minValue
                                )
                        );

                    } catch (NumberFormatException ignored) {
                    }
                }

                String maxKey =
                        "graph_fixed_max_"
                                + metricKey;

                String maxValue =
                        props.getProperty(
                                maxKey,
                                ""
                        );

                if (maxValue.length() > 0) {

                    try {

                        edit.putFloat(
                                maxKey,
                                Float.parseFloat(
                                        maxValue
                                )
                        );

                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            String windowValue =
                    props.getProperty(
                            "graph_window_ms",
                            ""
                    );

            if (windowValue.length() > 0) {

                try {

                    edit.putLong(
                            "graph_window_ms",
                            Long.parseLong(
                                    windowValue
                            )
                    );

                } catch (NumberFormatException ignored) {
                }
            }

            String offsetValue =
                    props.getProperty(
                            "graph_offset_ms",
                            ""
                    );

            if (offsetValue.length() > 0) {

                try {

                    edit.putLong(
                            "graph_offset_ms",
                            Long.parseLong(
                                    offsetValue
                            )
                    );

                } catch (NumberFormatException ignored) {
                }
            }

            edit.apply();

        } catch (IOException ignored) {

        } finally {

            if (in != null) {

                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writePersistedSettingsFile() {

        File dir =
                getBackupDirectory();

        if (!dir.exists()
                && !dir.mkdirs()) {

            return;
        }

        File file =
                new File(
                        dir,
                        Config.SETTINGS_FILE_NAME
                );

        Properties props =
                new Properties();

        SharedPreferences prefs =
                getSharedPreferences(
                        Config.PREFS,
                        0
                );

        props.setProperty(
                "supabase_rpc_url",
                prefs.getString(
                        "supabase_rpc_url",
                        Config.DEFAULT_SUPABASE_RPC_URL
                )
        );

        props.setProperty(
                "supabase_key",
                prefs.getString(
                        "supabase_key",
                        ""
                )
        );

        props.setProperty(
                "device_id",
                prefs.getString(
                        "device_id",
                        ""
                )
        );

        props.setProperty(
                "classic_wifi_ssid",
                prefs.getString(
                        "classic_wifi_ssid",
                        Config.CLASSIC_WIFI_SSID
                )
        );

        props.setProperty(
                "upload_wifi_ssids",
                prefs.getString(
                        "upload_wifi_ssids",
                        Config.DEFAULT_UPLOAD_WIFI_SSIDS
                )
        );

        for (int i = 0;
             i < CHART_COLUMNS.length;
             i++) {

            String metricKey =
                    CHART_COLUMNS[i];

            props.setProperty(
                    "graph_auto_"
                            + metricKey,
                    prefs.getBoolean(
                            "graph_auto_"
                                    + metricKey,
                            true
                    )
                            ? "1"
                            : "0"
            );

            if (prefs.contains(
                    "graph_fixed_min_"
                            + metricKey)) {

                props.setProperty(
                        "graph_fixed_min_"
                                + metricKey,
                        Float.toString(
                                prefs.getFloat(
                                        "graph_fixed_min_"
                                                + metricKey,
                                        0f
                                )
                        )
                );
            }

            if (prefs.contains(
                    "graph_fixed_max_"
                            + metricKey)) {

                props.setProperty(
                        "graph_fixed_max_"
                                + metricKey,
                        Float.toString(
                                prefs.getFloat(
                                        "graph_fixed_max_"
                                                + metricKey,
                                        0f
                                )
                        )
                );
            }
        }

        props.setProperty(
                "graph_window_ms",
                Long.toString(
                        chartWindowMs
                )
        );

        props.setProperty(
                "graph_offset_ms",
                Long.toString(
                        chartOffsetMs
                )
        );

        FileOutputStream out =
                null;

        try {

            out =
                    new FileOutputStream(
                            file
                    );

            props.store(
                    out,
                    "Midnite Logger settings"
            );

        } catch (IOException ignored) {

        } finally {

            if (out != null) {

                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private TextView addText(
            LinearLayout parent,
            String text) {

        TextView view =
                new TextView(this);

        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(
                Color.DKGRAY
        );

        view.setPadding(
                4,
                6,
                4,
                6
        );

        parent.addView(view);

        return view;
    }

    private EditText addEdit(
            LinearLayout parent) {

        EditText edit =
                new EditText(this);

        edit.setSingleLine(true);

        parent.addView(edit);

        return edit;
    }

    private Button addButton(
            LinearLayout parent,
            String text,
            View.OnClickListener listener) {

        Button button =
                new Button(this);

        button.setText(text);

        button.setOnClickListener(
                listener
        );

        parent.addView(button);

        return button;
    }

    private void saveSettings() {

        SharedPreferences.Editor edit =
                getSharedPreferences(
                        Config.PREFS,
                        0
                ).edit();

        String rpcUrl =
                url.getText()
                        .toString()
                        .trim();

        if (rpcUrl.length() == 0) {

            rpcUrl =
                    Config.DEFAULT_SUPABASE_RPC_URL;
        }

        edit.putString(
                "supabase_rpc_url",
                rpcUrl
        );

        edit.putString(
                "supabase_key",
                key.getText()
                        .toString()
                        .trim()
        );

        edit.putString(
                "device_id",
                device.getText()
                        .toString()
                        .trim()
        );

        edit.putString(
                "classic_wifi_ssid",
                classicWifiSsid
                        .getText()
                        .toString()
                        .trim()
        );

        edit.putString(
                "upload_wifi_ssids",
                uploadWifiSsidList
                        .getText()
                        .toString()
                        .trim()
        );

        edit.apply();

        persistGraphSettingsForCurrentMetric();

        writePersistedSettingsFile();

        Toast.makeText(
                this,
                "保存しました",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void loadChartSettingsForCurrentMetric() {

        if (metric == null
                || chartMinValue == null
                || chartMaxValue == null) {

            return;
        }

        String key =
                CHART_COLUMNS[
                        metric.getSelectedItemPosition()
                        ];

        SharedPreferences prefs =
                getSharedPreferences(
                        Config.PREFS,
                        0
                );

        chartAutoY =
                prefs.getBoolean(
                        "graph_auto_" + key,
                        true
                );

        chartWindowMs =
                prefs.getLong(
                        "graph_window_ms",
                        24L
                                * 60L
                                * 60L
                                * 1000L
                );

        chartOffsetMs =
                prefs.getLong(
                        "graph_offset_ms",
                        0L
                );

        if (prefs.contains(
                "graph_fixed_min_" + key)) {

            chartMinValue.setText(
                    Float.toString(
                            prefs.getFloat(
                                    "graph_fixed_min_" + key,
                                    0f
                            )
                    )
            );

        } else {

            chartMinValue.setText("");
        }

        if (prefs.contains(
                "graph_fixed_max_" + key)) {

            chartMaxValue.setText(
                    Float.toString(
                            prefs.getFloat(
                                    "graph_fixed_max_" + key,
                                    0f
                            )
                    )
            );

        } else {

            chartMaxValue.setText("");
        }

        if (chartAutoToggle != null) {

            chartAutoToggle.setText(
                    chartAutoY
                            ? "Y軸: 自動"
                            : "Y軸: 固定"
            );
        }

        if (chartRangeLabel != null) {

            chartRangeLabel.setText(
                    "表示範囲: "
                            + formatRangeDescription(
                            chartWindowMs
                    )
            );
        }
    }

    private void persistGraphSettingsForCurrentMetric() {

        if (metric == null
                || chartMinValue == null
                || chartMaxValue == null) {

            return;
        }

        String key =
                CHART_COLUMNS[
                        metric.getSelectedItemPosition()
                        ];

        SharedPreferences.Editor edit =
                getSharedPreferences(
                        Config.PREFS,
                        0
                ).edit();

        edit.putBoolean(
                "graph_auto_" + key,
                chartAutoY
        );

        edit.putLong(
                "graph_window_ms",
                chartWindowMs
        );

        edit.putLong(
                "graph_offset_ms",
                chartOffsetMs
        );

        String minText =
                chartMinValue
                        .getText()
                        .toString()
                        .trim();

        if (minText.length() > 0) {

            try {

                edit.putFloat(
                        "graph_fixed_min_" + key,
                        Float.parseFloat(
                                minText
                        )
                );

            } catch (NumberFormatException ignored) {
            }

        } else {

            edit.remove(
                    "graph_fixed_min_" + key
            );
        }

        String maxText =
                chartMaxValue
                        .getText()
                        .toString()
                        .trim();

        if (maxText.length() > 0) {

            try {

                edit.putFloat(
                        "graph_fixed_max_" + key,
                        Float.parseFloat(
                                maxText
                        )
                );

            } catch (NumberFormatException ignored) {
            }

        } else {

            edit.remove(
                    "graph_fixed_max_" + key
            );
        }

        edit.apply();

        writePersistedSettingsFile();
    }

    private void refresh() {

        boolean running =
                getSharedPreferences(
                        Config.PREFS,
                        0
                ).getBoolean(
                        "running",
                        false
                );

        status.setText(
                running
                        ? "ロギング中"
                        : dailyRescueRunning
                        ? "Daily Log集中救出中"
                        : "停止中"
        );

        Record record =
                db.latest();

        if (record == null) {

            values.setText(
                    "データ未取得"
            );

        } else {

            values.setText(
                    String.format(
                            Locale.US,
                            "最終記録 %s\n"
                                    + "Battery %.2f V  PV %.2f V\n"
                                    + "WB %.2f A  Power %d W\n"
                                    + "充電状態 %s",
                            record.ts,
                            record.batteryV,
                            record.pvV,
                            record.wbPreciseA,
                            record.watts,
                            chargeStageText(
                                    record.chargeStage
                            )
                    )
            );
        }

        events.setText(
                formatEventLog(
                        db.recentEvents(50)
                )
        );

        storage.setText(
                String.format(
                        Locale.US,
                        "測定 %d件 / DB %.2f MB / ERROR %d件 / 端末空き %.2f GB",
                        db.count(),
                        db.dbBytes() / 1048576.0,
                        db.errorCount(),
                        db.freeBytes()
                                / 1073741824.0
                )
        );

        if (uploadState != null) {

            uploadState.setText(
                    String.format(
                            Locale.US,
                            "通常ログ: Upload済み %d / 全記録 %d\n"
                                    + "Daily Log: Upload済み %d / 全記録 %d",
                            db.uploadedCount(),
                            db.count(),
                            db.dailyUploadedCount(),
                            db.dailyCount()
                    )
            );
        }

        updateWifiStatus();
        updateGraph();
    }

    private void updateWifiStatus() {

        WifiNetworkManager wifi =
                new WifiNetworkManager(
                        this
                );

        String current =
                wifi.getConnectedSsid();

        wifiStatus.setText(
                current == null
                        ? "未接続"
                        : current
        );
    }

    private String chargeStageText(
            int code) {

        switch (code) {

            case 0:
                return "Resting/停止";

            case 3:
                return "Absorb（定電圧充電）";

            case 4:
                return "Bulk MPPT（最大電力充電）";

            case 5:
                return "Float（維持充電）";

            case 6:
                return "Float MPPT";

            case 7:
                return "Equalize";

            default:
                return "コード " + code;
        }
    }

    private void changeChartWindow(
            boolean zoomIn) {

        long minWindow =
                30L
                        * 60L
                        * 1000L;

        long maxWindow =
                7L
                        * 24L
                        * 60L
                        * 60L
                        * 1000L;

        long next =
                zoomIn
                        ? chartWindowMs / 2L
                        : chartWindowMs * 2L;

        if (next < minWindow) {
            next = minWindow;
        }

        if (next > maxWindow) {
            next = maxWindow;
        }

        chartWindowMs =
                next;

        persistGraphSettingsForCurrentMetric();
        updateGraph();
    }

    private void shiftChartWindow(
            int direction) {

        long step =
                Math.max(
                        chartWindowMs / 8L,
                        60L * 1000L
                );

        chartOffsetMs +=
                direction * step;

        persistGraphSettingsForCurrentMetric();
        updateGraph();
    }

    private String formatRangeDescription(
            long rangeMs) {

        long hours =
                rangeMs
                        / (60L
                        * 60L
                        * 1000L);

        if (hours <= 0) {
            return "30分";
        }

        if (hours < 24L) {
            return hours + "時間";
        }

        long days =
                hours / 24L;

        if (days < 7L) {
            return days + "日";
        }

        return "7日";
    }

    private CharSequence formatEventLog(
            String raw) {

        if (raw == null
                || raw.trim().isEmpty()) {

            return "";
        }

        SpannableStringBuilder builder =
                new SpannableStringBuilder();

        String[] lines =
                raw.split("\\n");

        for (int i = 0;
             i < lines.length;
             i++) {

            if (i > 0) {
                builder.append('\n');
            }

            String line =
                    lines[i];

            if (line.contains("[ERROR]")
                    || line.contains(" ERROR ")
                    || line.contains("ERROR")) {

                int start =
                        builder.length();

                builder.append(line);

                builder.setSpan(
                        new ForegroundColorSpan(
                                Color.RED
                        ),
                        start,
                        builder.length(),
                        0
                );

            } else {

                builder.append(line);
            }
        }

        return builder;
    }

    private void updateGraph() {

        if (metric == null
                || chart == null) {

            return;
        }

        int index =
                metric.getSelectedItemPosition();

        if (index < 0
                || index >= CHART_COLUMNS.length) {

            return;
        }

        String column =
                CHART_COLUMNS[index];

        long endMs =
                System.currentTimeMillis()
                        - chartOffsetMs;

        long startMs =
                endMs
                        - chartWindowMs;

        Date end =
                new Date(endMs);

        Date start =
                new Date(startMs);

        SimpleDateFormat format =
                new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US
                );

        List<float[]> points =
                db.chart(
                        column,
                        format.format(start),
                        format.format(end),
                        240
                );

        Float fixedMin =
                null;

        Float fixedMax =
                null;

        if (!chartAutoY) {

            SharedPreferences prefs =
                    getSharedPreferences(
                            Config.PREFS,
                            0
                    );

            if (prefs.contains(
                    "graph_fixed_min_" + column)) {

                fixedMin =
                        prefs.getFloat(
                                "graph_fixed_min_" + column,
                                0f
                        );
            }

            if (prefs.contains(
                    "graph_fixed_max_" + column)) {

                fixedMax =
                        prefs.getFloat(
                                "graph_fixed_max_" + column,
                                0f
                        );
            }
        }

        chart.set(
                points,
                metric.getSelectedItem()
                        + " ["
                        + CHART_UNITS[index]
                        + "]",
                startMs,
                endMs,
                fixedMin,
                fixedMax,
                chartAutoY
        );

        if (chartRangeLabel != null) {

            chartRangeLabel.setText(
                    "表示範囲: "
                            + formatRangeDescription(
                            chartWindowMs
                    )
            );
        }
    }

    private void postDailyState(
            final String text) {

        handler.post(
                new Runnable() {

                    @Override
                    public void run() {

                        if (dailyRescueState != null) {

                            dailyRescueState.setText(
                                    text
                            );
                        }
                    }
                }
        );
    }

    private void backupDatabase() {

        try {

            File file =
                    db.backup();

            Toast.makeText(
                    this,
                    "保存: "
                            + file.getAbsolutePath(),
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception ex) {

            Toast.makeText(
                    this,
                    ex.toString(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void mergeLatestBackup() {

        File base =
                getBackupDirectory();

        if (base == null) {

            Toast.makeText(
                    this,
                    "外部ストレージを利用できません",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        /*
         * getBackupDirectory()自体が既にMidniteLoggerを返すので、
         * ここではそのdirectoryをそのまま使用する。
         */
        File directory =
                base;

        if (!directory.exists()
                && !directory.mkdirs()) {

            Toast.makeText(
                    this,
                    "バックアップフォルダーを作成できません: "
                            + directory.getAbsolutePath(),
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        File[] files =
                directory.listFiles();

        if (files == null
                || files.length == 0) {

            Toast.makeText(
                    this,
                    "バックアップなし",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        Arrays.sort(
                files,
                new Comparator<File>() {

                    @Override
                    public int compare(
                            File left,
                            File right) {

                        return Long.compare(
                                right.lastModified(),
                                left.lastModified()
                        );
                    }
                }
        );

        /*
         * .db以外の設定ファイルを誤って選ばない。
         */
        File latestDb =
                null;

        for (File file : files) {

            if (file != null
                    && file.isFile()
                    && file.getName().startsWith(
                    Config.DB_BACKUP_PREFIX)
                    && file.getName().endsWith(".db")) {

                latestDb =
                        file;

                break;
            }
        }

        if (latestDb == null) {

            Toast.makeText(
                    this,
                    "DBバックアップなし",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        try {

            long count =
                    db.mergeBackup(
                            latestDb
                    );

            Toast.makeText(
                    this,
                    "統合 "
                            + count
                            + "件",
                    Toast.LENGTH_LONG
            ).show();

            refresh();

        } catch (Exception ex) {

            Toast.makeText(
                    this,
                    ex.toString(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void requestRuntimePermissions() {

        if (Build.VERSION.SDK_INT < 23) {
            return;
        }

        ArrayList<String> permissions =
                new ArrayList<String>();

        if (checkSelfPermission(
                "android.permission.ACCESS_FINE_LOCATION")
                != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    "android.permission.ACCESS_FINE_LOCATION"
            );
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(
                "android.permission.NEARBY_WIFI_DEVICES")
                != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    "android.permission.NEARBY_WIFI_DEVICES"
            );
        }

        if (!permissions.isEmpty()) {

            requestPermissions(
                    permissions.toArray(
                            new String[
                                    permissions.size()
                                    ]
                    ),
                    7
            );
        }
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(
                refreshTask
        );

        /*
         * Activity自体が終了してもDaily救出Threadを
         * 走り続けさせない。
         */
        if (dailyRescueRunning) {

            dailyRescueCancelRequested =
                    true;

            Thread thread =
                    dailyRescueThread;

            if (thread != null) {
                thread.interrupt();
            }
        }

        super.onDestroy();
    }
}
