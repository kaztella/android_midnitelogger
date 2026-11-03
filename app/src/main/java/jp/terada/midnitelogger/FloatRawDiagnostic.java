package jp.terada.midnitelogger;

import java.util.GregorianCalendar;
import java.util.Locale;

public final class FloatRawDiagnostic {
    private FloatRawDiagnostic() {}

    public static void run(String ip,int port,int unitId,int startIndex,int endIndex,final Modbus.L logger) throws Exception {
        if(startIndex<1||endIndex>1023||startIndex>endIndex) throw new IllegalArgumentException("index range must be 1..1023");
        if(endIndex-startIndex+1>64) throw new IllegalArgumentException("maximum range is 64 days");
        Modbus modbus=new Modbus(logger);
        logger.log("INFO","Float RAW診断開始 index="+startIndex+".."+endIndex);
        int firstRequest=(startIndex/32)*32,lastRequest=(endIndex/32)*32;
        for(int request=firstRequest;request<=lastRequest;request+=32){
            int[] c2=modbus.readDailyLogBlock(ip,port,unitId,2,request);
            int[] c3=modbus.readDailyLogBlock(ip,port,unitId,3,request);
            for(int pos=0;pos<32;pos++){
                int index=request+(31-pos);
                if(index<startIndex||index>endIndex)continue;
                logger.log("INFO","FloatRAW request="+request+" pos="+pos+" index="+index+" cat2="+c2[pos]+" cat3="+c3[pos]+" date="+decodeDate(c3[pos]));
            }
        }
        logger.log("OK","Float RAW診断完了");
    }
    private static String decodeDate(int word){
        try{int y=2000+((word>>>9)&0x3f),m=word&0x0f,d=(word>>>4)&0x1f;GregorianCalendar c=new GregorianCalendar();c.setLenient(false);c.clear();c.set(y,m-1,d);c.getTime();return String.format(Locale.US,"%04d-%02d-%02d",y,m,d);}catch(Exception e){return "INVALID";}
    }
}
