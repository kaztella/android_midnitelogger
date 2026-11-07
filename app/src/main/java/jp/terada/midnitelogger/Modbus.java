package jp.terada.midnitelogger;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Modbus {
    public interface L { void log(String level, String message); }

    private final L logger;
    private int tx = 1;

    public Modbus(L logger) { this.logger = logger; }

    public int[][] readBlocks(String host, int port, int unit, int[][] blocks) throws IOException {
        if (blocks == null || blocks.length == 0) throw new IllegalArgumentException("blocks is empty");
        Socket socket = new Socket();
        try {
            logger.log("INFO", "TCP接続開始 " + host + ":" + port);
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            logger.log("OK", "TCP接続成功");
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[][] results = new int[blocks.length][];
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == null || blocks[i].length < 2) throw new IllegalArgumentException("invalid block index=" + i);
                results[i] = readOnConnection(out, in, unit, blocks[i][0], blocks[i][1]);
            }
            return results;
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            logger.log("INFO", "TCP切断");
        }
    }

    private int[] readOnConnection(DataOutputStream out, DataInputStream in, int unit, int addr, int count) throws IOException {
        if (count < 1 || count > 125) throw new IllegalArgumentException("invalid register count=" + count);
        int transactionId = tx++ & 0xffff;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(transactionId); request.writeShort(0); request.writeShort(6);
        request.writeByte(unit); request.writeByte(3); request.writeShort(addr); request.writeShort(count); request.flush();
        logger.log("SEND", "FC=3 Addr=" + addr + " Count=" + count);
        out.write(buffer.toByteArray()); out.flush();
        byte[] header = new byte[7]; in.readFully(header);
        int responseTransactionId = u16(header, 0), protocolId = u16(header, 2), length = u16(header, 4), responseUnit = header[6] & 0xff;
        if (responseTransactionId != transactionId) throw new IOException("Transaction ID mismatch expected=" + transactionId + " actual=" + responseTransactionId);
        if (protocolId != 0) throw new IOException("Unexpected Protocol ID=" + protocolId);
        if (responseUnit != (unit & 0xff)) throw new IOException("Unit ID mismatch expected=" + unit + " actual=" + responseUnit);
        if (length < 2 || length > 254) throw new IOException("Invalid MBAP length=" + length);
        byte[] pdu = new byte[length - 1];
        try { in.readFully(pdu); } catch (EOFException ex) { throw new IOException("Short Modbus response Addr=" + addr, ex); }
        int functionCode = pdu[0] & 0xff;
        if ((functionCode & 0x80) != 0) throw new IOException("Modbus exception=" + (pdu.length > 1 ? pdu[1] & 0xff : -1) + " FC=" + functionCode + " Addr=" + addr);
        if (functionCode != 3) throw new IOException("Unexpected FC=" + functionCode + " Addr=" + addr);
        if (pdu.length < 2) throw new IOException("Short Modbus PDU Addr=" + addr);
        int byteCount = pdu[1] & 0xff;
        if (byteCount != count * 2 || pdu.length != byteCount + 2) throw new IOException("Invalid FC03 length Addr=" + addr);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = u16(pdu, 2 + i * 2);
        logger.log("RECV", "Modbus応答 " + count + " registers");
        return values;
    }

    /**
     * Read requested Daily Log categories once each. Classic Daily Log access is slow;
     * callers are responsible for spacing category/day-index changes.
     */
    public int[] readDailyLogCategoriesOnce(String host, int port, int unit, int dayIndex, int[] categories, long categoryWaitMs) throws IOException {
        if (dayIndex < 1 || dayIndex > 1023) throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        int[] values = new int[categories.length];
        for (int i = 0; i < categories.length; i++) {
            values[i] = readDailyCategoryOnce(host, port, unit, categories[i], dayIndex);
            if (i + 1 < categories.length && categoryWaitMs > 0L) sleepChecked(categoryWaitMs);
        }
        return values;
    }

    public int readDailyCategoryOnce(String host, int port, int unit, int category, int dayIndex) throws IOException {
        if (category < 0 || category > 63) throw new IllegalArgumentException("invalid category=" + category);
        if (dayIndex < 1 || dayIndex > 1023) throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000); socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int tid = tx++ & 0xffff;
            int address = ((category & 0x3f) << 10) | (dayIndex & 0x03ff);
            ByteArrayOutputStream b = new ByteArrayOutputStream(16);
            DataOutputStream q = new DataOutputStream(b);
            q.writeShort(tid); q.writeShort(0); q.writeShort(10); q.writeByte(unit);
            q.writeByte(0x68); q.writeByte(5); q.writeByte(2); q.writeShort(0); q.writeInt(address); q.flush();
            logger.log("SEND", "FC=104 dayIndex=" + dayIndex + " category=" + category);
            out.write(b.toByteArray()); out.flush();

            byte[] header = new byte[7]; in.readFully(header);
            int responseTid = u16(header, 0), protocol = u16(header, 2), length = u16(header, 4), responseUnit = header[6] & 0xff;
            if (responseTid != tid) throw new IOException("FC104 Transaction ID mismatch expected=" + tid + " actual=" + responseTid);
            if (protocol != 0) throw new IOException("FC104 Protocol ID=" + protocol);
            if (responseUnit != (unit & 0xff)) throw new IOException("FC104 Unit ID mismatch expected=" + unit + " actual=" + responseUnit);
            if (length < 12 || length > 254) throw new IOException("FC104 invalid MBAP length=" + length);
            byte[] pdu = new byte[length - 1]; in.readFully(pdu);
            if (pdu.length < 11) throw new IOException("FC104 short PDU=" + pdu.length);
            if ((pdu[0] & 0xff) != 0x68) throw new IOException("FC104 unexpected function=" + (pdu[0] & 0xff));
            if ((pdu[1] & 0xff) != 5 || (pdu[2] & 0xff) != 2) throw new IOException("FC104 command echo mismatch");
            if (((pdu.length - 9) & 1) != 0) throw new IOException("FC104 payload alignment error length=" + pdu.length);
            int echoedBe = u32be(pdu, 5), echoedLe = u32le(pdu, 5);
            if (echoedBe != address && echoedLe != address) throw new IOException("FC104 address mismatch expected=" + address + " be=" + echoedBe + " le=" + echoedLe);
            int value = u16le(pdu, pdu.length - 2);
            logger.log("RECV", "FC104 dayIndex=" + dayIndex + " category=" + category + " value=" + value);
            return value;
        } finally { try { socket.close(); } catch (IOException ignored) {} }
    }

    /** Diagnostic-only 32-word FC104 reader. Not used by normal logging or Daily Log rescue. */
    public int[] readDailyLogBlock(String host, int port, int unit, int category, int dayIndex) throws IOException {
        if (category < 0 || category > 63) throw new IllegalArgumentException("invalid category=" + category);
        if (dayIndex < 1 || dayIndex > 1023) throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000); socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int tid = tx++ & 0xffff, address = ((category & 0x3f) << 10) | (dayIndex & 0x03ff);
            ByteArrayOutputStream b = new ByteArrayOutputStream(16); DataOutputStream q = new DataOutputStream(b);
            q.writeShort(tid); q.writeShort(0); q.writeShort(10); q.writeByte(unit); q.writeByte(0x68); q.writeByte(5); q.writeByte(2); q.writeShort(0); q.writeInt(address); q.flush();
            out.write(b.toByteArray()); out.flush();
            byte[] header = new byte[7]; in.readFully(header);
            if (u16(header,0)!=tid || u16(header,2)!=0 || (header[6]&0xff)!=(unit&0xff)) throw new IOException("FC104 block MBAP mismatch");
            int length=u16(header,4); byte[] pdu=new byte[length-1]; in.readFully(pdu);
            if (pdu.length!=73) throw new IOException("FC104 block PDU expected=73 actual="+pdu.length);
            int[] words=new int[32]; for(int i=0;i<32;i++) words[i]=u16le(pdu,9+i*2);
            return words;
        } finally { try { socket.close(); } catch(IOException ignored){} }
    }

    private static void sleepChecked(long ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("DailyLog wait interrupted", e); }
    }
    private static int u16(byte[] data, int offset) { return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff); }
    private static int u16le(byte[] data,int offset){return(data[offset]&0xff)|((data[offset+1]&0xff)<<8);}
    private static int u32be(byte[] d,int o){return((d[o]&255)<<24)|((d[o+1]&255)<<16)|((d[o+2]&255)<<8)|(d[o+3]&255);}
    private static int u32le(byte[] d,int o){return(d[o]&255)|((d[o+1]&255)<<8)|((d[o+2]&255)<<16)|((d[o+3]&255)<<24);}
}
