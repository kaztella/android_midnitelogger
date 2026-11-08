package jp.terada.midnitelogger;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

public final class DailyLog32WordDiagnostic {
    private final Database db;
    private final Modbus modbus;

    public DailyLog32WordDiagnostic(Database db, Modbus.L logger) {
        this.db = db;
        this.modbus = new Modbus(logger);
    }

    public void run(int estimatedDayIndex) throws Exception {
        int requestIndex = blockRequestFor(estimatedDayIndex);
        db.event("INFO", "32WORD ALL診断開始 estimatedIndex=" + estimatedDayIndex + " requestIndex=" + requestIndex);
        DailyLogBlock block = modbus.readDailyLogBlockAll(
                Config.CLASSIC_IP, Config.MODBUS_PORT, Config.UNIT_ID, requestIndex);
        validateDateSequence(block);
        logKnownMapping(block);
        db.event("OK", "32WORD ALL診断完了 requestIndex=" + requestIndex);
    }

    private void validateDateSequence(DailyLogBlock block) throws Exception {
        Calendar previous = null;
        for (int pos = 31; pos >= 0; pos--) {
            Calendar current = decodeDate(block.dateWords[pos]);
            if (previous != null) {
                Calendar expected = (Calendar) previous.clone();
                expected.add(Calendar.DAY_OF_MONTH, -1);
                if (!sameDate(expected, current)) {
                    throw new Exception("32WORD date sequence error pos=" + pos);
                }
            }
            previous = current;
        }
    }

    private void logKnownMapping(DailyLogBlock b) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int pos = 31; pos >= 0; pos--) {
            try {
                String date = f.format(decodeDate(b.dateWords[pos]).getTime());
                int index = b.requestIndex + (31 - pos);
                int highPower = b.highPowerWords[pos];
                double highFet = ((short)b.highFetWords[pos]) / 10.0;
                db.event("INFO", "32W ALL pos=" + pos + " index=" + index + " date=" + date
                        + " energy=" + (b.energyWords[pos] / 10.0)
                        + " cat1=" + b.category1Words[pos]
                        + " float=" + b.floatWords[pos]
                        + " highPower=" + highPower
                        + " highFet=" + highFet
                        + " timeRaw=" + b.timeWords[pos]
                        + " highPv=" + (b.highPvWords[pos] / 10.0)
                        + " highBatt=" + (b.highBatteryWords[pos] / 10.0));
            } catch (Exception e) {
                db.event("WARN", "32W ALL decode fail pos=" + pos + " " + e);
            }
        }
    }

    private static Calendar decodeDate(int raw) {
        int y = 2000 + ((raw >>> 9) & 0x3f), m = raw & 0x0f, d = (raw >>> 4) & 0x1f;
        GregorianCalendar c = new GregorianCalendar();
        c.setLenient(false); c.clear(); c.set(y, m - 1, d, 0, 0, 0); c.getTime(); return c;
    }

    private static boolean sameDate(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static int blockRequestFor(int dayIndex) {
        if (dayIndex <= 32) return 1;
        int r = ((dayIndex - 1) / 32) * 32;
        if (r < 1) r = 1;
        if (r > 992) r = 992;
        return r;
    }
}
