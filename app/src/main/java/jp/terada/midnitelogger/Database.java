package jp.terada.midnitelogger;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Database extends SQLiteOpenHelper {
    public static final String NAME = "midnite.db";
    private static final int VER = 3;
    private final Context ctx;

    public Database(Context c) {
        super(c, NAME, null, VER);
        ctx = c.getApplicationContext();
    }

    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT,ts TEXT NOT NULL UNIQUE,battery_v REAL,pv_v REAL,classic_a REAL,pv_a REAL,watts INTEGER,daily_kwh REAL,charge_stage INTEGER,classic_state INTEGER,battery_temp REAL,fet_temp REAL,pcb_temp REAL,wb_a REAL,wb_precise_a REAL,uploaded INTEGER DEFAULT 0)");
        d.execSQL("CREATE TABLE event(id INTEGER PRIMARY KEY AUTOINCREMENT,ts TEXT NOT NULL,level TEXT NOT NULL,message TEXT NOT NULL)");
        d.execSQL("CREATE INDEX idx_log_uploaded ON log(uploaded,id)");
        d.execSQL("CREATE INDEX idx_event_ts ON event(ts)");
    }

    public void onUpgrade(SQLiteDatabase d, int a, int b) {
        if (a < 2) {
            try { d.execSQL("CREATE INDEX idx_log_uploaded ON log(uploaded,id)"); } catch (Exception ignored) {}
        }
        if (a < 3) {
            try { d.execSQL("CREATE INDEX idx_event_ts ON event(ts)"); } catch (Exception ignored) {}
        }
    }

    public long insert(Record r) {
        return getWritableDatabase().insertWithOnConflict("log", null, values(r), SQLiteDatabase.CONFLICT_IGNORE);
    }

    private ContentValues values(Record r) {
        ContentValues v = new ContentValues();
        v.put("ts", r.ts);
        v.put("battery_v", r.batteryV);
        v.put("pv_v", r.pvV);
        v.put("classic_a", r.classicA);
        v.put("pv_a", r.pvA);
        v.put("watts", r.watts);
        v.put("daily_kwh", r.dailyKwh);
        v.put("charge_stage", r.chargeStage);
        v.put("classic_state", r.classicState);
        v.put("battery_temp", r.battTemp);
        v.put("fet_temp", r.fetTemp);
        v.put("pcb_temp", r.pcbTemp);
        v.put("wb_a", r.wbA);
        v.put("wb_precise_a", r.wbPreciseA);
        v.put("uploaded", 0);
        return v;
    }

    public void event(String level, String msg) {
        ContentValues v = new ContentValues();
        v.put("ts", Time.now());
        v.put("level", level);
        v.put("message", msg);
        getWritableDatabase().insert("event", null, v);
    }

    public void purgeEvents() {
        getWritableDatabase().delete("event", "level<>? AND ts < datetime('now','-7 days')", new String[]{"ERROR"});
    }

    public Record latest() {
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM log ORDER BY id DESC LIMIT 1", null);
        try { return c.moveToFirst() ? from(c) : null; } finally { c.close(); }
    }

    private Record from(Cursor c) {
        Record r = new Record();
        r.id = c.getLong(c.getColumnIndex("id"));
        r.ts = c.getString(c.getColumnIndex("ts"));
        r.batteryV = c.getDouble(c.getColumnIndex("battery_v"));
        r.pvV = c.getDouble(c.getColumnIndex("pv_v"));
        r.classicA = c.getDouble(c.getColumnIndex("classic_a"));
        r.pvA = c.getDouble(c.getColumnIndex("pv_a"));
        r.watts = c.getInt(c.getColumnIndex("watts"));
        r.dailyKwh = c.getDouble(c.getColumnIndex("daily_kwh"));
        r.chargeStage = c.getInt(c.getColumnIndex("charge_stage"));
        r.classicState = c.getInt(c.getColumnIndex("classic_state"));
        r.battTemp = c.getDouble(c.getColumnIndex("battery_temp"));
        r.fetTemp = c.getDouble(c.getColumnIndex("fet_temp"));
        r.pcbTemp = c.getDouble(c.getColumnIndex("pcb_temp"));
        r.wbA = c.getDouble(c.getColumnIndex("wb_a"));
        r.wbPreciseA = c.getDouble(c.getColumnIndex("wb_precise_a"));
        return r;
    }

    public List<Record> pending(int n) {
        ArrayList<Record> a = new ArrayList<Record>();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM log WHERE uploaded=0 ORDER BY id LIMIT " + n, null);
        try {
            while (c.moveToNext()) {
                a.add(from(c));
            }
        } finally {
            c.close();
        }
        return a;
    }

    public void markUploaded(List<Record> rows) {
        if (rows.isEmpty()) return;
        long max = rows.get(rows.size() - 1).id;
        ContentValues v = new ContentValues();
        v.put("uploaded", 1);
        getWritableDatabase().update("log", v, "id <= ?", new String[]{String.valueOf(max)});
    }

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

    public long count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM log", null);
        try { c.moveToFirst(); return c.getLong(0); } finally { c.close(); }
    }

    public long errorCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM event WHERE level='ERROR'", null);
        try { c.moveToFirst(); return c.getLong(0); } finally { c.close(); }
    }

    public long dbBytes() {
        return ctx.getDatabasePath(NAME).length();
    }

    public long freeBytes() {
        return Environment.getDataDirectory().getFreeSpace();
    }

    public List<float[]> chart(String col, String start, String end, int max) {
        return new ArrayList<float[]>();
    }

    public File backup() throws IOException {
        File dir = new File(Environment.getExternalStorageDirectory(), Config.BACKUP_DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("backup folder create failed: " + dir.getAbsolutePath());
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, Config.DB_BACKUP_PREFIX + stamp + ".db");
        copyDatabase(file);
        return file;
    }

    public int mergeBackup(File f) throws IOException {
        if (!f.exists()) throw new IOException("backup not found");
        SQLiteDatabase d = getWritableDatabase();
        d.execSQL("ATTACH DATABASE '" + f.getAbsolutePath() + "' AS backup");
        try {
            d.execSQL("INSERT OR IGNORE INTO log(ts,battery_v,pv_v,classic_a,pv_a,watts,daily_kwh,charge_stage,classic_state,battery_temp,fet_temp,pcb_temp,wb_a,wb_precise_a,uploaded) SELECT ts,battery_v,pv_v,classic_a,pv_a,watts,daily_kwh,charge_stage,classic_state,battery_temp,fet_temp,pcb_temp,wb_a,wb_precise_a,0 FROM backup.log");
            return d.compileStatement("SELECT changes() ").simpleQueryForLong();
        } finally {
            d.execSQL("DETACH DATABASE backup");
        }
    }

    private void copyDatabase(File target) throws IOException {
        File src = ctx.getDatabasePath(NAME);
        if (!src.exists()) throw new IOException("database not found: " + src.getAbsolutePath());
        if (target.exists()) target.delete();
        copy(src, target);
    }

    private static void copy(File a, File b) throws IOException {
        InputStream i = new FileInputStream(a);
        OutputStream o = new FileOutputStream(b);
        try {
            byte[] z = new byte[8192];
            int n;
            while ((n = i.read(z)) > 0) {
                o.write(z, 0, n);
            }
            o.flush();
        } finally {
            i.close();
            o.close();
        }
    }
}
