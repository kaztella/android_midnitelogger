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

    public static final class DailyTimestampBlock {
        public final int dayIndex; final int[] dateWords; final int[] timeWords;
        DailyTimestampBlock(int dayIndex,int[] dateWords,int[] timeWords){this.dayIndex=dayIndex;this.dateWords=dateWords;this.timeWords=timeWords;}
        public DailyTimestamp timestampAt(int word)throws IOException{if(word<0||word>=32)throw new IllegalArgumentException("word must be 0..31");return decodeTimestamp(dateWords[word],timeWords[word]);}
    }
    public static final class DailyPayloadBlock {
        public final int dayIndex; private final int[][] categoryWords;
        DailyPayloadBlock(int dayIndex,int[][] words){this.dayIndex=dayIndex;this.categoryWords=words;}
        public DailyLogRecord recordAt(int word)throws IOException{if(word<0||word>=32)throw new IllegalArgumentException("word must be 0..31");return buildDailyLogRecord(dayIndex,word,categoryWords);}
    }
    public final class DailySession implements java.io.Closeable {
        private final Modbus.DailySession session;
        private DailySession(Modbus.DailySession session){this.session=session;}
        public DailyTimestampBlock readTimestampBlock(int dayIndex)throws IOException{
            int[] cat3=session.readDailyCategoryRecord(Config.UNIT_ID,3,dayIndex);
            int[] cat6=session.readDailyCategoryRecord(Config.UNIT_ID,6,dayIndex);
            return new DailyTimestampBlock(dayIndex,cat3,cat6);
        }
        public DailyPayloadBlock readPayloadBlock(int dayIndex,int[] cat3,int[] cat6)throws IOException{
            int[][] words=new int[9][];words[3]=cat3;words[6]=cat6;
            int[] categories={0,1,2,4,5,7,8};
            for(int category:categories)words[category]=session.readDailyCategoryRecord(Config.UNIT_ID,category,dayIndex);
            return new DailyPayloadBlock(dayIndex,words);
        }
        @Override public void close(){session.close();}
    }
    public DailySession openDailySession()throws IOException{return new DailySession(modbus.openDailySession(Config.CLASSIC_IP,Config.MODBUS_PORT));}
    public DailyTimestamp readDailyTimestamp(int dayIndex)throws Exception{DailySession s=openDailySession();try{return s.readTimestampBlock(dayIndex).timestampAt(31);}finally{s.close();}}
    public DailyLogRecord readDailyLog(int dayIndex)throws Exception{DailySession s=openDailySession();try{DailyTimestampBlock t=s.readTimestampBlock(dayIndex);return s.readPayloadBlock(dayIndex,t.dateWords,t.timeWords).recordAt(31);}finally{s.close();}}
    private static DailyLogRecord buildDailyLogRecord(int requestedDayIndex,int word,int[][] v)throws IOException {
        DailyTimestamp timestamp=decodeTimestamp(v[3][word],v[6][word]);
        int highPower=v[4][word]; double highFet=s16(v[5][word])/10.0;
        if(highPower<0||highPower>MAX_REASONABLE_HIGH_POWER_W)throw new IOException("DailyLog highPower out of range block="+requestedDayIndex+" word="+word+" value="+highPower);
        if(highFet<MIN_REASONABLE_FET_TEMP_C||highFet>MAX_REASONABLE_FET_TEMP_C)throw new IOException("DailyLog highFetTemp out of range block="+requestedDayIndex+" word="+word+" value="+highFet);
        if(v[7][word]>2000)throw new IOException("DailyLog highPvV raw out of range block="+requestedDayIndex+" word="+word+" value="+v[7][word]);
        if(v[8][word]>1000)throw new IOException("DailyLog highBatteryV raw out of range block="+requestedDayIndex+" word="+word+" value="+v[8][word]);
        DailyLogRecord r=new DailyLogRecord(); r.logDate=timestamp.date;r.logTime=timestamp.time;
        // For W31 this is exactly requestedDayIndex. Earlier words are older by (31-word).
        r.dayIndex=requestedDayIndex+(31-word);
        r.energyKwh=v[0][word]/10.0;r.category1Raw=v[1][word];r.floatSeconds=v[2][word];r.highPowerW=highPower;r.highFetTemp=highFet;
        r.highPvV=v[7][word]/10.0;r.highBatteryV=v[8][word]/10.0;r.collectedAt=Time.now();return r;
    }

    private static DailyTimestamp decodeTimestamp(int low,int high) throws IOException {
        try {
            int y=2000+((low>>>9)&0x3f),m=low&0x0f,d=(low>>>4)&0x1f,h=(high>>>6)&0x1f,min=high&0x3f;
            GregorianCalendar c=new GregorianCalendar();c.setLenient(false);c.clear();c.set(y,m-1,d,h,min,0);c.getTime();
            return new DailyTimestamp(c,format(c,"yyyy-MM-dd"),format(c,"HH:mm"));
        } catch(Exception e){throw new IOException("Invalid DailyLog timestamp low="+low+" high="+high,e);}
    }

    private static DailyTimestamp parseTimestamp(String date,String time)throws IOException {
        try {
            String[] d=date.split("-"),t=time.split(":");GregorianCalendar c=new GregorianCalendar();c.setLenient(false);c.clear();
            c.set(Integer.parseInt(d[0]),Integer.parseInt(d[1])-1,Integer.parseInt(d[2]),Integer.parseInt(t[0]),Integer.parseInt(t[1]),0);c.getTime();
            return new DailyTimestamp(c,date,time);
        }catch(Exception e){throw new IOException("Invalid timestamp "+date+" "+time,e);}
    }
    private static String format(Calendar c,String pattern){SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setTimeZone(c.getTimeZone());return f.format(c.getTime());}
}
