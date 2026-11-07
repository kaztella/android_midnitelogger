package jp.terada.midnitelogger;

import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * DIAGNOSTIC: FC104 32-word Daily Log investigation.
 *
 * This class is part of the normal code line. It does not change the normal
 * 60-second logger or Daily Log rescue algorithm. It performs only two FC104
 * block reads (Category 3 = date and Category 4 = high power) with a 10-second
 * cooling interval and writes the result to the normal event log.
 *
 * If 32-word retrieval is proven reliable, readDailyLogBlock() can be reused
 * directly by the production Daily Log rescue implementation.
 */
public final class DailyLog32WordDiagnostic {
    private static final long BETWEEN_BLOCKS_WAIT_MS = 10000L;

    private final Database db;
    private final Modbus modbus;

    public DailyLog32WordDiagnostic(Database db, Modbus.L logger) {
        this.db = db;
        this.modbus = new Modbus(logger);
    }

    /**
     * Run one low-load diagnostic block around the estimated dayIndex.
     *
     * The old block reader interpreted the 32 returned words as indices
     * request + 31 ... request. We intentionally print both word position and
     * that legacy index mapping; the date words themselves are the authority
     * for confirming/rejecting that mapping.
     */
    public void run(int estimatedDayIndex) throws Exception {
        if (estimatedDayIndex < 1 || estimatedDayIndex > 1023) {
            throw new IllegalArgumentException("invalid estimatedDayIndex=" + estimatedDayIndex);
        }

        int requestIndex = blockRequestFor(estimatedDayIndex);
        db.event("INFO", "32WORD診断開始 estimatedIndex=" + estimatedDayIndex
                + " requestIndex=" + requestIndex);

        int[] dateWords = modbus.readDailyLogBlock(
                Config.CLASSIC_IP, Config.MODBUS_PORT, Config.UNIT_ID, 3, requestIndex);
        logDateBlock(requestIndex, dateWords);

        sleep(BETWEEN_BLOCKS_WAIT_MS);

        int[] powerWords = modbus.readDailyLogBlock(
                Config.CLASSIC_IP, Config.MODBUS_PORT, Config.UNIT_ID, 4, requestIndex);
        logPowerBlock(requestIndex, powerWords);

        db.event("OK", "32WORD診断完了 requestIndex=" + requestIndex);
    }

    private static int blockRequestFor(int dayIndex) {
        // Legacy mapping: request 352 represented indices 352..383.
        // Keep the target away from the block edge when possible.
        if (dayIndex <= 32) return 1;
        int request = ((dayIndex - 1) / 32) * 32;
        if (request < 1) request = 1;
        if (request > 992) request = 992;
        return request;
    }

    private void logDateBlock(int requestIndex, int[] words) {
        for (int pos = 0; pos < words.length; pos++) {
            int legacyIndex = requestIndex + (31 - pos);
            String decoded = decodeDateOrInvalid(words[pos]);
            db.event("INFO", "32W CAT3 pos=" + two(pos)
                    + " legacyIndex=" + legacyIndex
                    + " raw=" + words[pos]
                    + " hex=0x" + hex4(words[pos])
                    + " date=" + decoded);
        }
    }

    private void logPowerBlock(int requestIndex, int[] words) {
        for (int pos = 0; pos < words.length; pos++) {
            int legacyIndex = requestIndex + (31 - pos);
            db.event("INFO", "32W CAT4 pos=" + two(pos)
                    + " legacyIndex=" + legacyIndex
                    + " raw=" + words[pos]
                    + " hex=0x" + hex4(words[pos])
                    + " powerW=" + words[pos]);
        }
    }

    private static String decodeDateOrInvalid(int raw) {
        try {
            int year = 2000 + ((raw >>> 9) & 0x3f);
            int month = raw & 0x0f;
            int day = (raw >>> 4) & 0x1f;
            GregorianCalendar c = new GregorianCalendar();
            c.setLenient(false);
            c.clear();
            c.set(year, month - 1, day, 0, 0, 0);
            c.getTime();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return f.format(c.getTime());
        } catch (Exception e) {
            return "INVALID";
        }
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static String hex4(int value) {
        String s = Integer.toHexString(value & 0xffff).toUpperCase(Locale.US);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    private static void sleep(long ms) throws Exception {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("32WORD診断中断", e);
        }
    }
}
