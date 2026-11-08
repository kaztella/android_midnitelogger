package jp.terada.midnitelogger;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

public class Modbus {
    public interface L { void log(String level, String message); }

    private static final long DAILY_CATEGORY_WAIT_MS = 3000L;
    private static final long DAILY_BLOCK_CATEGORY_WAIT_MS = 5000L;
    private static final int DAILY_BLOCK_WORDS = 32;

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

    /* Manual one-day Daily Log path. One FC104 read per category. */
    public int[] readDailyLogCategories(String host, int port, int unit, int dayIndex, int[] categories) throws IOException {
        if (dayIndex < 0 || dayIndex > 1023) throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        int[] values = new int[categories.length];
        for (int i = 0; i < categories.length; i++) {
            values[i] = readDailyCategoryOnce(host, port, unit, categories[i], dayIndex);
            if (i + 1 < categories.length) sleepChecked(DAILY_CATEGORY_WAIT_MS);
        }
        return values;
    }

    public int[] readDailyLog(String host, int port, int unit, int dayIndex) throws IOException {
        return readDailyLogCategories(host, port, unit, dayIndex, new int[]{0,1,2,3,4,5,6,7,8});
    }

    public int readDailyCategoryOnce(String host, int port, int unit, int category, int dayIndex) throws IOException {
        if (category < 0 || category > 63) throw new IllegalArgumentException("invalid category=" + category);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10000);
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[] block = readDailyLogBlockOnConnection(out, in, unit, category, dayIndex, false);
            return block[block.length - 1];
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /* Compatibility method for existing diagnostics. One 32-word block, one TCP connection. */
    public int[] readDailyLogBlock(String host, int port, int unit, int category, int requestIndex) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            return readDailyLogBlockOnConnection(out, in, unit, category, requestIndex, true);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /*
     * Full 32-day block acquisition.
     * ONE TCP connection carries all nine FC104 category requests.
     * This is the production candidate currently exercised by the 32word diagnostic button.
     */
    public DailyLogBlock readDailyLogBlockAll(String host, int port, int unit, int requestIndex) throws IOException {
        if (requestIndex < 1 || requestIndex > 1023) throw new IllegalArgumentException("invalid requestIndex=" + requestIndex);

        Socket socket = new Socket();
        try {
            logger.log("INFO", "FC104 ALL TCP接続開始 " + host + ":" + port + " requestIndex=" + requestIndex);
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            logger.log("OK", "FC104 ALL TCP接続成功 requestIndex=" + requestIndex);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            int[] cat3 = readDailyLogBlockOnConnection(out, in, unit, 3, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat6 = readDailyLogBlockOnConnection(out, in, unit, 6, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat0 = readDailyLogBlockOnConnection(out, in, unit, 0, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat1 = readDailyLogBlockOnConnection(out, in, unit, 1, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat2 = readDailyLogBlockOnConnection(out, in, unit, 2, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat4 = readDailyLogBlockOnConnection(out, in, unit, 4, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat5 = readDailyLogBlockOnConnection(out, in, unit, 5, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat7 = readDailyLogBlockOnConnection(out, in, unit, 7, requestIndex, true);
            sleepChecked(DAILY_BLOCK_CATEGORY_WAIT_MS);
            int[] cat8 = readDailyLogBlockOnConnection(out, in, unit, 8, requestIndex, true);

            // A complete 32-word date array accidentally returned for a different category is a clear switch failure.
            if (Arrays.equals(cat3, cat6)) throw new IOException("FC104 category switch failed Cat6==Cat3");
            if (Arrays.equals(cat3, cat0)) throw new IOException("FC104 category switch failed Cat0==Cat3");
            if (Arrays.equals(cat3, cat1)) throw new IOException("FC104 category switch failed Cat1==Cat3");
            if (Arrays.equals(cat3, cat2)) throw new IOException("FC104 category switch failed Cat2==Cat3");
            if (Arrays.equals(cat3, cat4)) throw new IOException("FC104 category switch failed Cat4==Cat3");
            if (Arrays.equals(cat3, cat5)) throw new IOException("FC104 category switch failed Cat5==Cat3");
            if (Arrays.equals(cat3, cat7)) throw new IOException("FC104 category switch failed Cat7==Cat3");
            if (Arrays.equals(cat3, cat8)) throw new IOException("FC104 category switch failed Cat8==Cat3");

            logger.log("OK", "FC104 ALL 9 categories取得成功 requestIndex=" + requestIndex);
            return new DailyLogBlock(requestIndex, cat3, cat6, cat0, cat1, cat2, cat4, cat5, cat7, cat8);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            logger.log("INFO", "FC104 ALL TCP切断 requestIndex=" + requestIndex);
        }
    }

    private int[] readDailyLogBlockOnConnection(DataOutputStream out, DataInputStream in,
                                                 int unit, int category, int requestIndex,
                                                 boolean require32Words) throws IOException {
        if (category < 0 || category > 63) throw new IllegalArgumentException("invalid category=" + category);
        int tid = tx++ & 0xffff;
        int address = ((category & 0x3f) << 10) | (requestIndex & 0x03ff);

        ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        DataOutputStream q = new DataOutputStream(b);
        q.writeShort(tid); q.writeShort(0); q.writeShort(10); q.writeByte(unit);
        q.writeByte(0x68); q.writeByte(5); q.writeByte(2); q.writeShort(0); q.writeInt(address); q.flush();

        logger.log("SEND", "FC104 BLOCK category=" + category + " requestIndex=" + requestIndex + " tid=" + tid);
        out.write(b.toByteArray()); out.flush();

        byte[] header = new byte[7];
        in.readFully(header);
        int responseTid = u16(header, 0), protocol = u16(header, 2), length = u16(header, 4), responseUnit = header[6] & 0xff;
        if (responseTid != tid) throw new IOException("FC104 block TID mismatch expected=" + tid + " actual=" + responseTid);
        if (protocol != 0) throw new IOException("FC104 block protocol=" + protocol);
        if (responseUnit != (unit & 0xff)) throw new IOException("FC104 block unit mismatch expected=" + unit + " actual=" + responseUnit);
        if (length < 2 || length > 254) throw new IOException("FC104 block invalid MBAP length=" + length);

        byte[] pdu = new byte[length - 1];
        in.readFully(pdu);
        if (pdu.length < 11) throw new IOException("FC104 block short PDU=" + pdu.length);
        if ((pdu[0] & 0xff) != 0x68) throw new IOException("FC104 block unexpected function=" + (pdu[0] & 0xff));
        if ((pdu[1] & 0xff) != 5 || (pdu[2] & 0xff) != 2) throw new IOException("FC104 block command echo mismatch");

        int echoedBe = u32be(pdu, 5), echoedLe = u32le(pdu, 5);
        if (echoedBe != address && echoedLe != address) {
            throw new IOException("FC104 block address mismatch expected=" + address + " be=" + echoedBe + " le=" + echoedLe);
        }

        int payloadBytes = pdu.length - 9;
        if ((payloadBytes & 1) != 0) throw new IOException("FC104 block payload alignment error bytes=" + payloadBytes);
        int wordCount = payloadBytes / 2;
        if (require32Words && wordCount != DAILY_BLOCK_WORDS) {
            throw new IOException("FC104 block words expected=32 actual=" + wordCount);
        }
        if (wordCount < 1) throw new IOException("FC104 block no data words");

        int[] words = new int[wordCount];
        for (int i = 0; i < wordCount; i++) words[i] = u16le(pdu, 9 + i * 2);
        logger.log("RECV", "FC104 BLOCK category=" + category + " requestIndex=" + requestIndex + " words=" + wordCount);
        return words;
    }

    private static void sleepChecked(long ms) throws IOException {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("DailyLog wait interrupted", e); }
    }

    private static int u16(byte[] data, int offset) { return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff); }
    private static int u16le(byte[] data, int offset) { return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8); }
    private static int u32be(byte[] d, int o) { return ((d[o]&255)<<24)|((d[o+1]&255)<<16)|((d[o+2]&255)<<8)|(d[o+3]&255); }
    private static int u32le(byte[] d, int o) { return (d[o]&255)|((d[o+1]&255)<<8)|((d[o+2]&255)<<16)|((d[o+3]&255)<<24); }
}
