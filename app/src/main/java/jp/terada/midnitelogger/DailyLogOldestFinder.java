package jp.terada.midnitelogger;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Finds the oldest valid Classic Daily Log dayIndex.
 * Assumption: valid logs are contiguous from dayIndex=1 through oldest dayIndex,
 * and indices beyond the oldest return an invalid timestamp or Modbus error.
 * API 14 compatible. Read-only FC104/0x68 only.
 */
public final class DailyLogOldestFinder {
    public static final int MIN_INDEX = 1;
    public static final int MAX_INDEX = 1023;

    public static final class Result {
        public final int oldestDayIndex;
        public final String oldestDate;
        public final int probes;

        Result(int oldestDayIndex, String oldestDate, int probes) {
            this.oldestDayIndex = oldestDayIndex;
            this.oldestDate = oldestDate;
            this.probes = probes;
        }
    }

    private final ClassicReader reader;
    private final Modbus.L logger;
    private int probes;

    public DailyLogOldestFinder(Modbus.L logger) {
        this.reader = new ClassicReader(logger);
        this.logger = logger;
    }

    /**
     * Exponential search followed by binary search.
     * Usually locates a roughly 380-day boundary in about 18 timestamp probes.
     */
    public Result find() throws Exception {
        probes = 0;
        Probe first = probe(MIN_INDEX);
        if (!first.valid) {
            throw new IllegalStateException("dayIndex=1 のDaily Logが有効ではありません");
        }

        int validLow = MIN_INDEX;
        int candidate = 2;
        while (candidate <= MAX_INDEX) {
            Probe p = probe(candidate);
            if (!p.valid) break;
            validLow = candidate;
            if (candidate == MAX_INDEX) break;
            candidate = candidate > MAX_INDEX / 2 ? MAX_INDEX : candidate * 2;
        }

        if (validLow == MAX_INDEX) {
            Probe oldest = probe(validLow);
            return new Result(validLow, oldest.date, probes);
        }

        int invalidHigh = candidate;
        while (invalidHigh - validLow > 1) {
            int middle = validLow + (invalidHigh - validLow) / 2;
            Probe p = probe(middle);
            if (p.valid) validLow = middle;
            else invalidHigh = middle;
        }

        // Boundary safety check. Two younger entries must be valid and the next older must be invalid.
        Probe oldest = probe(validLow);
        if (!oldest.valid) throw new IllegalStateException("探索境界が不安定です index=" + validLow);
        if (validLow > 1 && !probe(validLow - 1).valid)
            throw new IllegalStateException("Daily Logが連続していません index=" + (validLow - 1));
        if (validLow < MAX_INDEX && probe(validLow + 1).valid)
            throw new IllegalStateException("最古境界を確定できません index=" + (validLow + 1));

        logger.log("OK", "DailyLog最古位置 index=" + validLow
                + " date=" + oldest.date + " probes=" + probes);
        return new Result(validLow, oldest.date, probes);
    }

    private Probe probe(int dayIndex) throws Exception {
        Exception lastCommunicationError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            probes++;
            try {
                ClassicReader.DailyTimestamp t = reader.readDailyTimestamp(dayIndex);
                if (!isPlausible(t, dayIndex)) {
                    logger.log("INFO", "DailyLog範囲外 index=" + dayIndex
                            + " timestamp=" + t.date + " " + t.time);
                    return Probe.invalid();
                }
                logger.log("INFO", "DailyLog有効 index=" + dayIndex + " date=" + t.date);
                return Probe.valid(t.date);
            } catch (java.io.IOException e) {
                lastCommunicationError = e;
                logger.log("INFO", "DailyLog探索通信再試行 index=" + dayIndex
                        + " attempt=" + attempt + " reason=" + e.toString());
            } catch (IllegalArgumentException e) {
                logger.log("INFO", "DailyLog範囲外 index=" + dayIndex
                        + " reason=" + e.toString());
                return Probe.invalid();
            }
        }
        throw new java.io.IOException("DailyLog探索通信失敗 index=" + dayIndex,
                lastCommunicationError);
    }

    private static boolean isPlausible(ClassicReader.DailyTimestamp t, int dayIndex) {
        if (t == null || t.calendar == null) return false;
        Calendar today = new GregorianCalendar();
        clearTime(today);
        Calendar logged = (Calendar) t.calendar.clone();
        clearTime(logged);
        long diff = (today.getTimeInMillis() - logged.getTimeInMillis()) / 86400000L;
        // Classic clock and midnight boundary tolerance: +/- 2 days.
        return diff >= dayIndex - 2L && diff <= dayIndex + 2L;
    }

    private static void clearTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static final class Probe {
        final boolean valid;
        final String date;
        private Probe(boolean valid, String date) { this.valid = valid; this.date = date; }
        static Probe valid(String date) { return new Probe(true, date); }
        static Probe invalid() { return new Probe(false, null); }
    }
}
