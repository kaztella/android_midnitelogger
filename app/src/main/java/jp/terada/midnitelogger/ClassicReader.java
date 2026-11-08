package jp.terada.midnitelogger;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;

public class ClassicReader {
    private static final int MAX_REASONABLE_HIGH_POWER_W = 10000;
    private static final double MIN_REASONABLE_FET_TEMP_C = -100.0;
    private static final double MAX_REASONABLE_FET_TEMP_C = 200.0;

    private final Modbus modbus;
    public ClassicReader(Modbus.L logger){modbus=new Modbus(logger);}
    private static int s16(int value){return(short)value;}
    private static long u32(int low,int high){return((long)high<<16)|(low&65535L);}
    private static long s32(int low,int high){return(int)u32(low,high);}

    public Record read() throws Exception {
        int[][] blocks=modbus.readBlocks(Config.CLASSIC_IP,Config.MODBUS_PORT,Config.UNIT_ID,new int[][]{{4114,28},{4364,7},{4390,1}});
        int[] b=blocks[0],w=blocks[1],p=blocks[2]; Record r=new Record(); r.ts=Time.now();
        r.batteryV=b[0]/10.0;r.pvV=b[1]/10.0;r.classicA=s16(b[2])/10.0;r.dailyKwh=b[3]/10.0;r.watts=b[4];
        r.chargeStage=(b[5]>>8)&255;r.classicState=b[5]&255;r.pvA=b[6]/10.0;r.lastVocV=b[7]/10.0;r.highestPvV=b[8]/10.0;
        r.dailyAh=b[10];r.lifetimeKwh=u32(b[11],b[12]);r.lifetimeAh=u32(b[13],b[14]);r.infoFlags=u32(b[15],b[16]);
        r.batteryTemp=s16(b[17])/10.0;r.fetTemp=s16(b[18])/10.0;r.pcbTemp=s16(b[19])/10.0;r.minutesNoPv=b[20];r.floatSeconds=b[23];r.resetReason=b[27];
        r.wbPositiveAh=u32(w[0],w[1]);r.wbNegativeAh=u32(w[2],w[3]);r.wbNetAh=s32(w[4],w[5]);r.wbA=s16(w[6])/10.0;r.wbPreciseA=s16(p[0])/100.0;
        return r;
    }

    public static final class DailyTimestamp {
        public final Calendar calendar; public final String date; public final String time;
        DailyTimestamp(Calendar c,String d,String t){calendar=c;date=d;time=t;}
    }

    /** Read the two official Daily Log timestamp fields over one FC104 TCP session. */
    public DailyTimestamp readDailyTimestamp(int dayIndex)throws Exception {
        if(dayIndex<1||dayIndex>1023)throw new IllegalArgumentException("dayIndex must be 1..1023: "+dayIndex);
        int[] timestampWords=modbus.readDailyTimestampWords(Config.CLASSIC_IP,Config.MODBUS_PORT,Config.UNIT_ID,dayIndex);
        return decodeTimestamp(timestampWords[0],timestampWords[1]);
    }

    /**
     * Read one complete Daily Log record. Categories 0..8 are read as separate
     * FC104 file-transfer requests while keeping one TCP connection open.
     */
    public DailyLogRecord readDailyLog(int dayIndex)throws Exception {
        if(dayIndex<1||dayIndex>1023)throw new IllegalArgumentException("dayIndex must be 1..1023: "+dayIndex);
        int[]v=modbus.readDailyLog(Config.CLASSIC_IP,Config.MODBUS_PORT,Config.UNIT_ID,dayIndex);
        DailyTimestamp timestamp=decodeTimestamp(v[3],v[6]);
        int highPower=v[4];
        double highFet=s16(v[5])/10.0;
        if(highPower<0||highPower>MAX_REASONABLE_HIGH_POWER_W)
            throw new IOException("DailyLog highPower out of range dayIndex="+dayIndex+" value="+highPower);
        if(highFet<MIN_REASONABLE_FET_TEMP_C||highFet>MAX_REASONABLE_FET_TEMP_C)
            throw new IOException("DailyLog highFetTemp out of range dayIndex="+dayIndex+" value="+highFet);
        if(v[7]>2000) throw new IOException("DailyLog highPvV raw out of range dayIndex="+dayIndex+" value="+v[7]);
        if(v[8]>1000) throw new IOException("DailyLog highBatteryV raw out of range dayIndex="+dayIndex+" value="+v[8]);
        DailyLogRecord r=new DailyLogRecord(); r.logDate=timestamp.date; r.logTime=timestamp.time; r.dayIndex=dayIndex;
        r.energyKwh=v[0]/10.0;r.category1Raw=v[1];r.floatSeconds=v[2];r.highPowerW=highPower;r.highFetTemp=highFet;
        r.highPvV=v[7]/10.0;r.highBatteryV=v[8]/10.0;r.collectedAt=Time.now();
        return r;
    }
    private static DailyTimestamp decodeTimestamp(int low,int high) {
        int y=2000+((low>>>9)&0x3f),m=low&0x0f,d=(low>>>4)&0x1f,h=(high>>>6)&0x1f,min=high&0x3f;
        GregorianCalendar c=new GregorianCalendar();c.setLenient(false);c.clear();c.set(y,m-1,d,h,min,0);c.getTime();
        return new DailyTimestamp(c,format(c,"yyyy-MM-dd"),format(c,"HH:mm"));
    }
    private static String format(Calendar c,String pattern){SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setTimeZone(c.getTimeZone());return f.format(c.getTime());}
}
