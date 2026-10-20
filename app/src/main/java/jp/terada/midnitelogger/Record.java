package jp.terada.midnitelogger;

public class Record {
    public long id;
    public String timestamp;
    public double batteryV, pvV, classicBatteryA, dailyKwh, pvA;
    public double batteryTempC, fetTempC, pcbTempC, wbCurrentA, wbPreciseA;
    public long watts, dailyAh, lifetimeKwh, lifetimeAh, floatSeconds;
    public long wbPositiveAh, wbNegativeAh, wbNetAh;
    public int chargeStage, classicState;
}
