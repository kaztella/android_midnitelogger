package jp.terada.midnitelogger;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.GregorianCalendar;

/** Rescues finalized Classic Daily Logs from oldest to newest, five days per cycle. */
public final class DailyLogRescue {
    private static final int SEARCH_START_INDEX = 400;
    private static final int BATCH_DAYS = 5;

    private final Context context;
    private final Database db;
    private final ClassicReader reader;
    private final Modbus.L logger;

    public DailyLogRescue(Context context, Database db, Modbus.L logger) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.logger = logger;
        this.reader = new ClassicReader(logger);
    }

    public int runBatch() throws Exception {
        SharedPreferences p = context.getSharedPreferences(Config.PREFS, 0);
        if (p.getBoolean("daily_rescue_complete_v3", false)) return 0;

        int next = p.getInt("daily_rescue_next_index_v3", -1);
        if (next < 1) {
            next = findOldestIndex();
            p.edit().putInt("daily_rescue_oldest_index_v3", next)
                    .putInt("daily_rescue_next_index_v3", next).apply();
            db.event("OK", "DailyLog最古位置確定 index=" + next);
        }

        int saved = 0;
        while (next >= 1 && saved < BATCH_DAYS) {
            DailyLogRecord row = reader.readDailyLog(next);
            long id = db.insertDailyLog(row);
            if (!db.hasDailyLogDate(row.logDate)) {
                throw new IllegalStateException("DailyLog保存確認失敗 index=" + next
                        + " date=" + row.logDate);
            }
            db.event("OK", "DailyLog救出 index=" + next + " date=" + row.logDate
                    + " rowId=" + id);
            next--;
            saved++;
            p.edit().putInt("daily_rescue_next_index_v3", next).apply();
        }

        if (next < 1) {
            p.edit().putBoolean("daily_rescue_complete_v3", true).apply();
            db.event("OK", "DailyLog救出完了");
        } else {
            db.event("INFO", "DailyLog救出進捗 saved=" + saved + " nextIndex=" + next);
        }
        return saved;
    }

    private int findOldestIndex() throws Exception {
        db.event("INFO", "DailyLog最古探索開始 index=" + SEARCH_START_INDEX);
        for (int index = SEARCH_START_INDEX; index >= 1; index--) {
            try {
                ClassicReader.DailyTimestamp t = reader.readDailyTimestamp(index);
                if (isPlausible(t, index)) return index;
            } catch (IllegalArgumentException e) {
                // Invalid date words mean this entry is outside retained history.
            }
        }
        throw new IllegalStateException("有効なDailyLogが見つかりません");
    }

    private static boolean isPlausible(ClassicReader.DailyTimestamp t, int index) {
        if (t == null || t.calendar == null) return false;
        Calendar today = new GregorianCalendar();
        clearTime(today);
        Calendar logged = (Calendar)t.calendar.clone();
        clearTime(logged);
        long age = (today.getTimeInMillis() - logged.getTimeInMillis()) / 86400000L;
        // Real-device observation: index 1 was two calendar days old. Allow clock boundary tolerance.
        return age >= index - 1L && age <= index + 3L;
    }

    private static void clearTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
    }
}
