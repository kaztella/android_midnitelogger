package jp.terada.midnitelogger;

import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * Main-line FC104 32-word diagnostic.
 * Reads Category 3 (date low) and Category 4 (high power) on ONE TCP session.
 */
public final class DailyLog32WordDiagnostic {
    private final Modbus modbus;
    private final Modbus.L logger;

    public DailyLog32WordDiagnostic(Modbus.L logger) {
        this.logger = logger;
        this.modbus = new Modbus(logger);
    }

    /** Compatibility constructor for the current MainActivity. */
    public DailyLog32WordDiagnostic(Database db, Modbus.L logger) {
        this(logger);
    }

    public void run(int baseDayIndex) throws Exception {
        logger.log("INFO", "32WORD診断開始 same-TCP baseDayIndex=" + baseDayIndex);

        int[][] blocks = modbus.readDailyLogBlocksSameConnection(
                Config.CLASSIC_IP,
                Config.MODBUS_PORT,
                Config.UNIT_ID,
                baseDayIndex,
                new int[]{3, 4},
                32);

        int[] cat3 = blocks[0];
        int[] cat4 = blocks[1];

        for (int pos = 31; pos >= 0; pos--) {
            int dayIndex = baseDayIndex + (31 - pos);
            String date = decodeDate(cat3[pos]);

            logger.log("INFO",
                    "32W MAP pos=" + two(pos)
                            + " dayIndex=" + dayIndex
                            + " cat3Raw=" + cat3[pos]
                            + " date=" + date
                            + " cat4HighPower=" + cat4[pos]);
        }

        logger.log("OK", "32WORD診断完了 same-TCP Cat3+Cat4 words=32");
    }

    private static String decodeDate(int low) {
        try {
            int year = 2000 + ((low >>> 9) & 0x3f);
            int month = low & 0x0f;
            int day = (low >>> 4) & 0x1f;

            GregorianCalendar calendar = new GregorianCalendar();
            calendar.setLenient(false);
            calendar.clear();
            calendar.set(year, month - 1, day, 0, 0, 0);
            calendar.getTime();

            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return format.format(calendar.getTime());
        } catch (Exception e) {
            return "INVALID(" + low + ")";
        }
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
