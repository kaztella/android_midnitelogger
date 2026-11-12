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

    private static final int FC_READ_HOLDING = 3;
    private static final int FC_READ_INTERNAL = 0x68;
    private static final int DAILY_DEVICE = 5;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private final L logger;
    private int tx = 1;

    public Modbus(L logger) { this.logger = logger; }

    public int[][] readBlocks(String host, int port, int unit, int[][] blocks) throws IOException {
        if (blocks == null || blocks.length == 0) throw new IllegalArgumentException("blocks is empty");
        Socket socket = openSocket(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[][] results = new int[blocks.length][];
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == null || blocks[i].length < 2) throw new IllegalArgumentException("invalid block index=" + i);
                results[i] = readHoldingOnConnection(out, in, unit, blocks[i][0], blocks[i][1]);
            }
            return results;
        } finally { closeSocket(socket); }
    }

    private int[] readHoldingOnConnection(DataOutputStream out, DataInputStream in, int unit, int address, int count) throws IOException {
        if (count < 1 || count > 125) throw new IllegalArgumentException("invalid register count=" + count);
        int tid = nextTransactionId();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(tid); request.writeShort(0); request.writeShort(6); request.writeByte(unit);
        request.writeByte(FC_READ_HOLDING); request.writeShort(address); request.writeShort(count); request.flush();
        logger.log("SEND", "FC=3 Addr=" + address + " Count=" + count);
        out.write(buffer.toByteArray()); out.flush();
        byte[] pdu = readPdu(in, tid, unit, "FC03 Addr=" + address);
        int function = pdu[0] & 0xff;
        checkModbusException(pdu, function, "FC03 Addr=" + address);
        if (function != FC_READ_HOLDING) throw new IOException("Unexpected FC=" + function + " Addr=" + address);
        if (pdu.length < 2) throw new IOException("Short FC03 PDU Addr=" + address);
        int byteCount = pdu[1] & 0xff;
        if (byteCount != count * 2 || pdu.length != byteCount + 2) throw new IOException("Invalid FC03 length Addr=" + address + " byteCount=" + byteCount + " pdu=" + pdu.length);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = u16be(pdu, 2 + i * 2);
        logger.log("RECV", "Modbus応答 " + count + " registers");
        return values;
    }

    /**
     * Reusable FC104 Daily Log TCP session.
     * Concentrated rescue keeps this session open so Classic is not forced to
     * accept a new TCP connection for every day.
     */
    public final class DailySession implements java.io.Closeable {
        private final Socket socket;
        private final DataOutputStream out;
        private final DataInputStream in;
        private boolean closed;

        private DailySession(String host, int port) throws IOException {
            socket = openSocket(host, port);
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
        }

        public int[] readDay(int unit, int dayIndex) throws IOException {
            validateDailyDayIndex(dayIndex);
            if (closed) throw new IOException("FC104 DailySession already closed");
            int[] values = new int[9];
            for (int category = 0; category <= 8; category++) {
                values[category] = readDailyWordOnConnection(out, in, unit, category, dayIndex);
            }
            return values;
        }

        /**
         * Read the complete FC104 Daily Log file record for one category.
         * The on-wire data_length field stays at the proven value 2. For log files
         * Classic ignores that requested length and returns the complete 64-byte
         * record (32 little-endian words).
         */
        public int[] readDailyCategoryRecord(int unit, int category, int dayIndex) throws IOException {
            validateDailyDayIndex(dayIndex);
            if (category < 0 || category > 8) throw new IllegalArgumentException("category must be 0..8: " + category);
            if (closed) throw new IOException("FC104 DailySession already closed");
            byte[] data = readInternalOnConnection(out, in, unit, DAILY_DEVICE, 2, dailyAddress(category, dayIndex));
            if (data.length != 64) {
                throw new IOException("FC104 Daily Log record expected 64 bytes actual=" + data.length
                        + " Category=" + category + " DayIndex=" + dayIndex);
            }
            int[] words = new int[32];
            for (int i = 0; i < words.length; i++) words[i] = u16le(data, i * 2);
            logger.log("RECV", "FC104 RECORD Category=" + category + " DayIndex=" + dayIndex
                    + " PDU_LEN=" + (9 + data.length) + " BlockWords=" + words.length);
            return words;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            closeSocket(socket);
        }
    }

    public DailySession openDailySession(String host, int port) throws IOException {
        return new DailySession(host, port);
    }

    public int[] readDailyLog(String host, int port, int unit, int dayIndex) throws IOException {
        DailySession session = openDailySession(host, port);
        try { return session.readDay(unit, dayIndex); }
        finally { session.close(); }
    }

    private int readDailyWordOnConnection(DataOutputStream out, DataInputStream in, int unit, int category, int dayIndex) throws IOException {
        byte[] data = readInternalOnConnection(out, in, unit, DAILY_DEVICE, 2, dailyAddress(category, dayIndex));
        if (data.length < 2 || (data.length & 1) != 0) throw new IOException("FC104 Daily Log invalid data bytes=" + data.length + " Category=" + category + " DayIndex=" + dayIndex);
        int value = u16le(data, data.length - 2);
        logger.log("RECV", "FC104 response Category=" + category + " DayIndex=" + dayIndex + " PDU_LEN=" + (9 + data.length) + " BlockWords=" + (data.length / 2) + " RawLE=" + value);
        return value;
    }

    public int[] readDailyLogBlock(String host, int port, int unit, int category, int dayIndex) throws IOException {
        return readDailyLogBlock(host, port, unit, category, dayIndex, 32);
    }

    public int[] readDailyLogBlock(String host, int port, int unit, int category, int dayIndex, int words) throws IOException {
        validateDailyDayIndex(dayIndex);
        if (words < 1 || words > 100) throw new IllegalArgumentException("words must be 1..100");
        Socket socket = openSocket(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            return readDailyLogBlockOnConnection(out, in, unit, category, dayIndex, words);
        } finally { closeSocket(socket); }
    }

    public int[][] readDailyLogBlocksSameConnection(String host, int port, int unit, int dayIndex, int[] categories, int words) throws IOException {
        validateDailyDayIndex(dayIndex);
        if (categories == null || categories.length == 0) throw new IllegalArgumentException("categories is empty");
        if (words < 1 || words > 100) throw new IllegalArgumentException("words must be 1..100");
        Socket socket = openSocket(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[][] result = new int[categories.length][];
            for (int i = 0; i < categories.length; i++) result[i] = readDailyLogBlockOnConnection(out, in, unit, categories[i], dayIndex, words);
            return result;
        } finally { closeSocket(socket); }
    }

    private int[] readDailyLogBlockOnConnection(DataOutputStream out, DataInputStream in, int unit, int category, int dayIndex, int words) throws IOException {
        int dataLength = words * 2;
        byte[] data = readInternalOnConnection(out, in, unit, DAILY_DEVICE, dataLength, dailyAddress(category, dayIndex));
        if (data.length != dataLength) throw new IOException("FC104 block expected=" + dataLength + " actual=" + data.length);
        int[] values = new int[words];
        for (int i = 0; i < words; i++) values[i] = u16le(data, i * 2);
        logger.log("RECV", "FC104 BLOCK dayIndex=" + dayIndex + " category=" + category + " words=" + words);
        return values;
    }

    private byte[] readInternalOnConnection(DataOutputStream out, DataInputStream in, int unit, int device, int dataLength, int address) throws IOException {
        if (dataLength < 1 || dataLength > 200) throw new IllegalArgumentException("FC104 dataLength must be 1..200");
        int tid = nextTransactionId();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(tid); request.writeShort(0); request.writeShort(10); request.writeByte(unit);
        request.writeByte(FC_READ_INTERNAL); request.writeByte(device); request.writeByte(dataLength); request.writeShort(0); request.writeInt(address); request.flush();
        logger.log("SEND", "FC=104 device=" + device + " len=" + dataLength + " address=0x" + Integer.toHexString(address));
        out.write(buffer.toByteArray()); out.flush();

        byte[] pdu = readPdu(in, tid, unit, "FC104");
        if (pdu.length < 2) throw new IOException("FC104 short PDU=" + pdu.length);
        int function = pdu[0] & 0xff;
        checkModbusException(pdu, function, "FC104");
        if (function != FC_READ_INTERNAL) throw new IOException("FC104 unexpected function=" + function);
        if (pdu.length < 11) throw new IOException("FC104 short PDU=" + pdu.length);

        int responseDevice = pdu[1] & 0xff;
        int responseLength = pdu[2] & 0xff;
        int reserved = u16be(pdu, 3);
        if (responseDevice != device) throw new IOException("FC104 device mismatch expected=" + device + " actual=" + responseDevice);
        if (responseLength != dataLength) throw new IOException("FC104 data_length mismatch expected=" + dataLength + " actual=" + responseLength);
        if (reserved != 0) throw new IOException("FC104 reserved mismatch actual=" + reserved);

        int echoedBe = u32be(pdu, 5);
        int echoedLe = u32le(pdu, 5);
        if (echoedBe != address && echoedLe != address) throw new IOException("FC104 address mismatch expected=" + address + " be=" + echoedBe + " le=" + echoedLe);

        int responseDataBytes = pdu.length - 9;
        if (responseDataBytes < 2 || (responseDataBytes & 1) != 0) throw new IOException("FC104 invalid response data length=" + responseDataBytes + " PDU_LEN=" + pdu.length);
        byte[] data = new byte[responseDataBytes];
        System.arraycopy(pdu, 9, data, 0, responseDataBytes);
        return data;
    }

    private Socket openSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        logger.log("INFO", "TCP接続開始 " + host + ":" + port);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS); socket.setTcpNoDelay(true);
        logger.log("OK", "TCP接続成功"); return socket;
    }
    private void closeSocket(Socket socket) { if (socket == null) return; try { socket.close(); } catch (IOException ignored) {} logger.log("INFO", "TCP切断"); }
    private byte[] readPdu(DataInputStream in, int expectedTid, int unit, String label) throws IOException {
        byte[] header = new byte[7];
        try { in.readFully(header); } catch (EOFException ex) { throw new IOException(label + " short MBAP", ex); }
        int tid=u16be(header,0), protocol=u16be(header,2), length=u16be(header,4), responseUnit=header[6]&0xff;
        if (tid != expectedTid) throw new IOException(label + " Transaction ID mismatch expected=" + expectedTid + " actual=" + tid);
        if (protocol != 0) throw new IOException(label + " Protocol ID=" + protocol);
        if (responseUnit != (unit & 0xff)) throw new IOException(label + " Unit ID mismatch expected=" + unit + " actual=" + responseUnit);
        if (length < 2 || length > 254) throw new IOException(label + " invalid MBAP length=" + length);
        byte[] pdu = new byte[length-1]; in.readFully(pdu); return pdu;
    }
    private int nextTransactionId() { return tx++ & 0xffff; }
    private static void validateDailyDayIndex(int dayIndex) { if (dayIndex < 1 || dayIndex > 1023) throw new IllegalArgumentException("dayIndex must be 1..1023: " + dayIndex); }
    private static int dailyAddress(int category, int dayIndex) {
        if (category < 0 || category > 63) throw new IllegalArgumentException("invalid category=" + category);
        if (dayIndex < 0 || dayIndex > 1023) throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        return ((category & 0x3f) << 10) | (dayIndex & 0x03ff);
    }
    private static void checkModbusException(byte[] pdu, int function, String label) throws IOException { if ((function & 0x80) != 0) { int code=pdu.length>1?(pdu[1]&0xff):-1; throw new IOException(label + " exception=" + code); } }
    private static int u16be(byte[] data,int offset){return ((data[offset]&0xff)<<8)|(data[offset+1]&0xff);}    
    private static int u16le(byte[] data,int offset){return (data[offset]&0xff)|((data[offset+1]&0xff)<<8);}    
    private static int u32be(byte[] data,int offset){return ((data[offset]&0xff)<<24)|((data[offset+1]&0xff)<<16)|((data[offset+2]&0xff)<<8)|(data[offset+3]&0xff);}    
    private static int u32le(byte[] data,int offset){return (data[offset]&0xff)|((data[offset+1]&0xff)<<8)|((data[offset+2]&0xff)<<16)|((data[offset+3]&0xff)<<24);}    
}
