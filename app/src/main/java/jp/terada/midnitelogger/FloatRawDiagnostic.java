package jp.terada.midnitelogger;

import java.util.GregorianCalendar;
import java.util.Locale;

/** Category 2/3 RAW diagnostic. Read-only: no daily_log save and no Supabase upload. */
public final class FloatRawDiagnostic {
    public interface Logger { void log(String level, String message); }
    private FloatRawDiagnostic() {}

    public static void run(String ip, int port, int unitId,
                           int startIndex, int endIndex, Logger logger) throws Exception {
        if (startIndex < 1 || endIndex > 1023 || startIndex > endIndex)
            throw new IllegalArgumentException("index range must be 1..1023");
        if (endIndex - startIndex + 1 > 64)
            throw new IllegalArgumentException("maximum range is 64 days");

        Modbus modbus = new Modbus(new Modbus.Logger() {
            @Override public void log(String level, String message) { logger.log(level, message); }
        });
        logger.log("INFO", "Float RAW diagnostic start index=" + startIndex + ".." + endIndex);
        int firstRequest = (startIndex / 32) * 32;
        int lastRequest = (endIndex / 32) * 32;
        for (int request = firstRequest; request <= lastRequest; request += 32) {
            int[] category2 = modbus.readDailyLogBlock(ip, port, unitId, 2, request);
            int[] category3 = modbus.readDailyLogBlock(ip, port, unitId, 3, request);
            for (int pos = 0; pos < 32; pos++) {
                int index = request + (31 - pos);
                if (index < startIndex || index > endIndex) continue;
                logger.log("INFO", "FloatRAW request=" + request + " pos=" + pos
                        + " index=" + index + " cat2=" + category2[pos]
                        + " cat3=" + category3[pos] + " date=" + decodeDate(category3[pos]));
            }
        }
        logger.log("OK", "Float RAW diagnostic complete");
    }

    private static String decodeDate(int word) {
        try {
            int year = 2000 + ((word >>> 9) & 0x3f);
            int month = word & 0x0f;
            int day = (word >>> 4) & 0x1f;
            GregorianCalendar c = new GregorianCalendar();
            c.setLenient(false); c.clear(); c.set(year, month - 1, day); c.getTime();
            return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
        } catch (Exception e) { return "INVALID"; }
    }
}
