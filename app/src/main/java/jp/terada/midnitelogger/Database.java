package jp.terada.midnitelogger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class Database extends SQLiteOpenHelper {
    public Database(Context context) { super(context, "midnite.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT,ts TEXT,battery_v REAL,pv_v REAL,classic_a REAL,daily_kwh REAL,watts INTEGER,stage INTEGER,cstate INTEGER,pv_a REAL,daily_ah INTEGER,lifetime_kwh INTEGER,lifetime_ah INTEGER,batt_temp REAL,fet_temp REAL,pcb_temp REAL,float_sec INTEGER,wb_pos_ah INTEGER,wb_neg_ah INTEGER,wb_net_ah INTEGER,wb_a REAL,wb_precise_a REAL,uploaded INTEGER DEFAULT 0,retry INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE event(id INTEGER PRIMARY KEY AUTOINCREMENT,ts TEXT,msg TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    public void insert(Record r) {
        ContentValues v = new ContentValues();
        v.put("ts", r.timestamp); v.put("battery_v", r.batteryV); v.put("pv_v", r.pvV);
        v.put("classic_a", r.classicBatteryA); v.put("daily_kwh", r.dailyKwh); v.put("watts", r.watts);
        v.put("stage", r.chargeStage); v.put("cstate", r.classicState); v.put("pv_a", r.pvA);
        v.put("daily_ah", r.dailyAh); v.put("lifetime_kwh", r.lifetimeKwh); v.put("lifetime_ah", r.lifetimeAh);
        v.put("batt_temp", r.batteryTempC); v.put("fet_temp", r.fetTempC); v.put("pcb_temp", r.pcbTempC);
        v.put("float_sec", r.floatSeconds); v.put("wb_pos_ah", r.wbPositiveAh); v.put("wb_neg_ah", r.wbNegativeAh);
        v.put("wb_net_ah", r.wbNetAh); v.put("wb_a", r.wbCurrentA); v.put("wb_precise_a", r.wbPreciseA);
        getWritableDatabase().insertOrThrow("log", null, v);
    }
    public void addEvent(String message) {
        ContentValues v = new ContentValues();
        v.put("ts", now()); v.put("msg", message);
        getWritableDatabase().insert("event", null, v);
    }
    public void clearEvents() { getWritableDatabase().delete("event", null, null); }
    public void trimEvents(int keep) {
        getWritableDatabase().execSQL("DELETE FROM event WHERE id NOT IN (SELECT id FROM event ORDER BY id DESC LIMIT " + keep + ")");
    }

    public int pendingCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM log WHERE uploaded=0", null);
        try { c.moveToFirst(); return c.getInt(0); } finally { c.close(); }
    }
    public Record latest() {
        Cursor c = getReadableDatabase().rawQuery("SELECT id,ts,battery_v,pv_v,watts,stage,wb_a FROM log ORDER BY id DESC LIMIT 1", null);
        try {
            if (!c.moveToFirst()) return null;
            Record r = new Record(); r.id = c.getLong(0); r.timestamp = c.getString(1);
            r.batteryV = c.getDouble(2); r.pvV = c.getDouble(3); r.watts = c.getLong(4);
            r.chargeStage = c.getInt(5); r.wbCurrentA = c.getDouble(6); return r;
        } finally { c.close(); }
    }
    public String recentEvents() {
        Cursor c = getReadableDatabase().rawQuery("SELECT ts,msg FROM event ORDER BY id DESC LIMIT 50", null);
        StringBuilder b = new StringBuilder("イベント履歴\n");
        try { while (c.moveToNext()) b.append(c.getString(0)).append("  ").append(c.getString(1)).append('\n'); }
        finally { c.close(); }
        return b.toString();
    }
    public Cursor pending(int limit) {
        return getReadableDatabase().rawQuery("SELECT id,ts,battery_v,pv_v,classic_a,daily_kwh,watts,stage,cstate,pv_a,daily_ah,lifetime_kwh,lifetime_ah,batt_temp,fet_temp,pcb_temp,float_sec,wb_pos_ah,wb_neg_ah,wb_net_ah,wb_a,wb_precise_a FROM log WHERE uploaded=0 ORDER BY id LIMIT " + limit, null);
    }
    public void acknowledge(long maxId) { getWritableDatabase().execSQL("UPDATE log SET uploaded=1 WHERE id<=?", new Object[]{maxId}); }

    public List<ChartView.Point> batteryVoltagePoints(long startMillis, long endMillis, int maxPoints) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String startText = format.format(new Date(startMillis));
        String endText = format.format(new Date(endMillis));
        String[] args = new String[]{startText, endText};
        Cursor countCursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM log WHERE ts>=? AND ts<?", args);
        int count;
        try { countCursor.moveToFirst(); count = countCursor.getInt(0); }
        finally { countCursor.close(); }
        int step = Math.max(1, (int) Math.ceil(count / (double) maxPoints));
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT ts,battery_v FROM log WHERE ts>=? AND ts<? ORDER BY id", args);
        List<ChartView.Point> result = new ArrayList<ChartView.Point>();
        int index = 0;
        try {
            while (cursor.moveToNext()) {
                if (index % step == 0 || index == count - 1) {
                    try { result.add(new ChartView.Point(format.parse(cursor.getString(0)).getTime(), cursor.getFloat(1))); }
                    catch (Exception ignored) { }
                }
                index++;
            }
        } finally { cursor.close(); }
        return result;
    }

    public static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
}
