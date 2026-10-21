package jp.terada.midnitelogger;
public class Record {
 public long id; public String ts; public double batteryV,pvV,classicA,pvA,dailyKwh,batteryTemp,fetTemp,pcbTemp,wbA,wbPreciseA,lastVocV,highestPvV,dailyAh;
 public int watts,chargeStage,classicState,minutesNoPv,floatSeconds,resetReason; public long lifetimeKwh,lifetimeAh,infoFlags,wbPositiveAh,wbNegativeAh,wbNetAh;
}
