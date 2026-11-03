package jp.terada.midnitelogger;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.GregorianCalendar;

public final class DailyLogRescue {
    private static final int BATCH_DAYS = 5;
    private static final String KEY_NEXT = "daily_rescue_next_index_block_v4";
    private static final String KEY_DONE = "daily_rescue_complete_block_v4";
    private final Context context;
    private final Database db;
    private final Modbus.L logger;
    private final Modbus modbus;
    private final ClassicReader reader;

    public DailyLogRescue(Context context, Database db, Modbus.L logger) {
        this.context=context.getApplicationContext(); this.db=db; this.logger=logger;
        this.modbus=new Modbus(logger); this.reader=new ClassicReader(logger);
    }

    public int runBatch() throws Exception {
        SharedPreferences p=context.getSharedPreferences(Config.PREFS,0);
        if(p.getBoolean(KEY_DONE,false)) return 0;
        int next=p.getInt(KEY_NEXT,-1);
        if(next<1){
            next=findOldestIndex();
            p.edit().putInt(KEY_NEXT,next).apply();
            db.event("OK","DailyLog最古位置確定 index="+next);
        }
        int saved=0;
        while(next>=1 && saved<BATCH_DAYS){
            DailyLogRecord row=readRecordRetry(next);
            long id=db.insertDailyLog(row);
            if(!db.hasDailyLogDate(row.logDate)) throw new IllegalStateException("SQLite保存確認失敗 index="+next);
            db.event("OK","DailyLog救出 index="+next+" date="+row.logDate+" rowId="+id);
            next--; saved++; p.edit().putInt(KEY_NEXT,next).apply();
        }
        if(next<1){p.edit().putBoolean(KEY_DONE,true).apply();db.event("OK","DailyLog救出完了");}
        else db.event("INFO","DailyLog救出進捗 saved="+saved+" nextIndex="+next);
        return saved;
    }

    private int findOldestIndex() throws Exception {
        db.event("INFO","DailyLog 32word最古探索開始");
        int oldest=-1;
        for(int request=32; request<=416; request+=32){
            int[] words=modbus.readDailyLogBlock(Config.CLASSIC_IP,Config.MODBUS_PORT,Config.UNIT_ID,3,request);
            int valid=0, blockOldest=-1;
            for(int pos=0;pos<32;pos++){
                int index=request+(31-pos);
                if(isPlausible(words[pos],index)){valid++; if(index>blockOldest)blockOldest=index;}
            }
            db.event("INFO","DailyLog BLOCK request="+request+" valid="+valid+" oldest="+blockOldest);
            if(valid>0){if(blockOldest>oldest)oldest=blockOldest;}
            else if(oldest>0) break;
        }
        if(oldest<1) throw new IllegalStateException("有効なDailyLog blockが見つかりません");
        return oldest;
    }

    private DailyLogRecord readRecordRetry(int index) throws Exception {
        Exception last=null;
        for(int attempt=1;attempt<=2;attempt++){
            try{return reader.readDailyLog(index);}
            catch(java.io.EOFException e){last=e;}
            catch(java.net.SocketException e){last=e;}
            logger.log("INFO","DailyLog救出通信再試行 index="+index+" attempt="+attempt);
        }
        throw last;
    }

    private boolean isPlausible(int low,int expectedIndex){
        try{
            int year=2000+((low>>>9)&0x3f), month=low&0x0f, day=(low>>>4)&0x1f;
            GregorianCalendar logged=new GregorianCalendar(); logged.setLenient(false); logged.clear(); logged.set(year,month-1,day,0,0,0); logged.getTime();
            Calendar today=new GregorianCalendar(); clearTime(today); clearTime(logged);
            long age=(today.getTimeInMillis()-logged.getTimeInMillis())/86400000L;
            return age>=expectedIndex && age<=expectedIndex+2L;
        }catch(Exception e){return false;}
    }
    private static void clearTime(Calendar c){c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);}
}
