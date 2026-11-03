package jp.terada.midnitelogger;

public class ClassicReader {
    private final Modbus modbus;

    public ClassicReader(Modbus.L logger) {
        modbus = new Modbus(logger);
    }

    private static int s16(int value) {
        return (short) value;
    }

    private static long u32(int low, int high) {
        return ((long) high << 16) | (low & 65535L);
    }

    private static long s32(int low, int high) {
        return (int) u32(low, high);
    }

    public Record read() throws Exception {
        int[][] blocks = modbus.readBlocks(
                Config.CLASSIC_IP,
                Config.MODBUS_PORT,
                Config.UNIT_ID,
                new int[][]{
                        {4114, 28},
                        {4364, 7},
                        {4390, 1}
                });

        int[] b = blocks[0];
        int[] w = blocks[1];
        int[] p = blocks[2];

        Record record = new Record();
        record.ts = Time.now();
        record.batteryV = b[0] / 10.0;
        record.pvV = b[1] / 10.0;
        record.classicA = s16(b[2]) / 10.0;
        record.dailyKwh = b[3] / 10.0;
        record.watts = b[4];
        record.chargeStage = (b[5] >> 8) & 255;
        record.classicState = b[5] & 255;
        record.pvA = b[6] / 10.0;
        record.lastVocV = b[7] / 10.0;
        record.highestPvV = b[8] / 10.0;
        record.dailyAh = b[10];
        record.lifetimeKwh = u32(b[11], b[12]);
        record.lifetimeAh = u32(b[13], b[14]);
        record.infoFlags = u32(b[15], b[16]);
        record.batteryTemp = s16(b[17]) / 10.0;
        record.fetTemp = s16(b[18]) / 10.0;
        record.pcbTemp = s16(b[19]) / 10.0;
        record.minutesNoPv = b[20];
        record.floatSeconds = b[23];
        record.resetReason = b[27];
        record.wbPositiveAh = u32(w[0], w[1]);
        record.wbNegativeAh = u32(w[2], w[3]);
        record.wbNetAh = s32(w[4], w[5]);
        record.wbA = s16(w[6]) / 10.0;
        record.wbPreciseA = s16(p[0]) / 100.0;
        return record;
    }
}
