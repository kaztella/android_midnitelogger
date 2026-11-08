package jp.terada.midnitelogger;

import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Main-line diagnostic that can become the production 32-word Daily Log engine.
 * Reads Cat3 + Cat4 over the SAME TCP connection, using official FC104 block length.
 */
public final class DailyLog32WordDiagnostic {
    private final Modbus modbus;
    private final Modbus.L logger;

    public DailyLog32WordDiagnostic(Modbus.L logger) {
        this.logger = logger;
        this.modbus = new Modbus(logger);
    }

    public void run(int baseDayIndex) throws Exception {
        logger.log("INFO", "32WORD診断開始 same-TCP baseDayIndex=" + baseDayIndex);
        int[][] blocks = modbus.readDailyLogBlocksSameConnection(
                Config.CLASSIC_IP, Config.MODBUS_PORT, Config.UNIT_ID,
                baseDayIndex, new int[]{3,4}, 32);

        int[] cat3 = blocks[0];
        int[] cat4 = blocks[1];
        for (int pos = 31; pos >= 0; pos--) {
            int dayIndex = baseDayIndex + (31 - pos);
            String date = decodeDate(cat3[pos]);
            logger.log("INFO", "32W MAP pos=" + two(pos)
                    + " dayIndex=" + dayIndex
                    + " cat3Raw=" + cat3[pos]
                    + " date=" + date
                    + " cat4HighPower=" + cat4[pos]);
        }
        logger.log("OK", "32WORD診断完了 same-TCP Cat3+Cat4 words=32");
    }

    private static String decodeDate(int low) {
        try {
            int y = 2000 + ((low >>> 9) & 0x3f);
            int m = low & 0x0f;
            int d = (low >>> 4) & 0x1f;
            GregorianCalendar c = new GregorianCalendar();
            c.setLenient(false); c.clear(); c.set(y, m - 1, d, 0, 0, 0); c.getTime();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return f.format(c.getTime());
        } catch (Exception e) {
            return "INVALID(" + low + ")";
        }
    }

    private static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }
}
