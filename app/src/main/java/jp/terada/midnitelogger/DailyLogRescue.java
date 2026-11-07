package jp.terada.midnitelogger;

import android.content.Context;

/**
 * Legacy compatibility shim.
 * Automatic Daily Log rescue was intentionally removed because FC104 access can
 * destabilize Classic Daily Log selection. Daily Log is now rescued only by the
 * explicit date-range operation in MainActivity.
 */
public final class DailyLogRescue {
    private final Database db;
    public DailyLogRescue(Context context, Database db, Modbus.L logger) {
        this.db = db;
    }
    public int runBatch() {
        db.event("INFO", "DailyLogRescue.runBatch無効: Daily Logは手動集中救出のみ");
        return 0;
    }
}
