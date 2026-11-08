package jp.terada.midnitelogger;

public final class DailyLogBlock {
    public final int requestIndex;
    public final int[] dateWords;
    public final int[] timeWords;
    public final int[] energyWords;
    public final int[] category1Words;
    public final int[] floatWords;
    public final int[] highPowerWords;
    public final int[] highFetWords;
    public final int[] highPvWords;
    public final int[] highBatteryWords;

    public DailyLogBlock(int requestIndex,
                         int[] dateWords,
                         int[] timeWords,
                         int[] energyWords,
                         int[] category1Words,
                         int[] floatWords,
                         int[] highPowerWords,
                         int[] highFetWords,
                         int[] highPvWords,
                         int[] highBatteryWords) {
        this.requestIndex = requestIndex;
        this.dateWords = dateWords;
        this.timeWords = timeWords;
        this.energyWords = energyWords;
        this.category1Words = category1Words;
        this.floatWords = floatWords;
        this.highPowerWords = highPowerWords;
        this.highFetWords = highFetWords;
        this.highPvWords = highPvWords;
        this.highBatteryWords = highBatteryWords;
    }
}
