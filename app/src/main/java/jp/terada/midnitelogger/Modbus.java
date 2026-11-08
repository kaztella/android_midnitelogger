package jp.terada.midnitelogger;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * MidNite Classic Modbus TCP helper.
 *
 * FC104 follows MidNite Solar MODBUS Network Specification Rev C.4:
 *   function=0x68, device, data_length, reserved(16), address(32)
 * Daily-log address: category bits 15..10, day index bits 9..0.
 */
public class Modbus {
    public interface L { void log(String level, String message); }

    private static final int FC_READ_HOLDING = 3;
    private static final int FC_READ_INTERNAL = 0x68;
    private static final int DAILY_DEVICE = 5;
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_READ_TIMEOUT_MS = 10000;

    private final L logger;
    private int tx = 1;

    public Modbus(L logger) { this.logger = logger; }

    public int[][] readBlocks(String host, int port, int unit, int[][] blocks) throws IOException {
        if (blocks == null || blocks.length == 0) throw new IllegalArgumentException("blocks is empty");
        Socket socket = open(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[][] results = new int[blocks.length][];
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == null || blocks[i].length < 2) throw new IllegalArgumentException("invalid block index=" + i);
                results[i] = readOnConnection(out, in, unit, blocks[i][0], blocks[i][1]);
            }
            return results;
        } finally {
            close(socket);
        }
    }

    private int[] readOnConnection(DataOutputStream out, DataInputStream in, int unit, int addr, int count) throws IOException {
        if (count < 1 || count > 125) throw new IllegalArgumentException("invalid register count=" + count);
        int tid = nextTid();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(tid); request.writeShort(0); request.writeShort(6);
        request.writeByte(unit); request.writeByte(FC_READ_HOLDING); request.writeShort(addr); request.writeShort(count); request.flush();
        logger.log("SEND", "FC=3 Addr=" + addr + " Count=" + count);
        out.write(buffer.toByteArray()); out.flush();

        Response response = readResponse(in, tid, unit, "FC03 Addr=" + addr);
        byte[] pdu = response.pdu;
        int fc = pdu[0] & 0xff;
        checkException(pdu, fc, "FC03 Addr=" + addr);
        if (fc != FC_READ_HOLDING) throw new IOException("Unexpected FC=" + fc + " Addr=" + addr);
        if (pdu.length < 2) throw new IOException("Short Modbus PDU Addr=" + addr);
        int bytes = pdu[1] & 0xff;
        if (bytes != count * 2 || pdu.length != bytes + 2) throw new IOException("Invalid FC03 length Addr=" + addr);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = u16be(pdu, 2 + i * 2);
        logger.log("RECV", "Modbus応答 " + count + " registers");
        return values;
    }

    /** Official FC104 single-value read (2 bytes). Kept for compatibility. */
    public int readDailyCategoryOnce(String host, int port, int unit, int category, int dayIndex) throws IOException {
        Socket socket = open(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] data = readInternalOnConnection(out, in, unit, DAILY_DEVICE, 2, dailyAddress(category, dayIndex));
            if (data.length != 2) throw new IOException("FC104 single expected 2 bytes actual=" + data.length);
            int value = u16le(data, 0);
            logger.log("RECV", "FC104 dayIndex=" + dayIndex + " category=" + category + " value=" + value);
            return value;
        } finally {
            close(socket);
        }
    }

    public int[] readDailyLogCategoriesOnce(String host, int port, int unit, int dayIndex, int[] categories) throws IOException {
        if (categories == null) throw new IllegalArgumentException("categories is null");
        int[] out = new int[categories.length];
        for (int i = 0; i < categories.length; i++) out[i] = readDailyCategoryOnce(host, port, unit, categories[i], dayIndex);
        return out;
    }

    public int[] readDailyLog(String host, int port, int unit, int dayIndex) throws IOException {
        return readDailyLogCategoriesOnce(host, port, unit, dayIndex, new int[]{0,1,2,3,4,5,6,7,8});
    }

    // Compatibility entry points used by the current ClassicReader.
    public int[] readDailyLogCategories(String host, int port, int unit, int dayIndex, int[] categories) throws IOException {
        return readDailyLogCategoriesOnce(host, port, unit, dayIndex, categories);
    }

    public int readDailyCategoryReliable(String host, int port, int unit, int category, int dayIndex) throws IOException {
        return readDailyCategoryOnce(host, port, unit, category, dayIndex);
    }


    /**
     * Official FC104 block read. 32 words = 64 bytes requested explicitly.
     * The returned array is in payload order. For the currently observed Classic mapping,
     * word position 31 corresponds to requested dayIndex, pos 30 to dayIndex+1, etc.
     */
    public int[] readDailyLogBlock(String host, int port, int unit, int category, int dayIndex) throws IOException {
        Socket socket = open(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            return readDailyLogBlockOnConnection(out, in, unit, category, dayIndex, 32);
        } finally {
            close(socket);
        }
    }

    /**
     * Read multiple daily categories over ONE TCP connection.
     * This is the key diagnostic/production candidate: no reconnect between Cat3 and Cat4.
     */
    public int[][] readDailyLogBlocksSameConnection(String host, int port, int unit,
                                                     int dayIndex, int[] categories,
                                                     int words) throws IOException {
        if (categories == null || categories.length == 0) throw new IllegalArgumentException("categories is empty");
        if (words < 1 || words > 100) throw new IllegalArgumentException("words must be 1..100"); // FC104 max 200 bytes
        Socket socket = open(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[][] result = new int[categories.length][];
            for (int i = 0; i < categories.length; i++) {
                result[i] = readDailyLogBlockOnConnection(out, in, unit, categories[i], dayIndex, words);
            }
            return result;
        } finally {
            close(socket);
        }
    }

    private int[] readDailyLogBlockOnConnection(DataOutputStream out, DataInputStream in,
                                                 int unit, int category, int dayIndex,
                                                 int words) throws IOException {
        int bytes = words * 2;
        byte[] data = readInternalOnConnection(out, in, unit, DAILY_DEVICE, bytes, dailyAddress(category, dayIndex));
        if (data.length != bytes) throw new IOException("FC104 block expected=" + bytes + " actual=" + data.length);
        int[] result = new int[words];
        for (int i = 0; i < words; i++) result[i] = u16le(data, i * 2);
        logger.log("RECV", "FC104 BLOCK dayIndex=" + dayIndex + " category=" + category + " words=" + words);
        return result;
    }

    /** FC104 request/response parser based on the published structure. */
    private byte[] readInternalOnConnection(DataOutputStream out, DataInputStream in,
                                            int unit, int device, int dataLength,
                                            int address) throws IOException {
        if (dataLength < 1 || dataLength > 200) throw new IllegalArgumentException("FC104 dataLength must be 1..200");
        int tid = nextTid();

        ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        DataOutputStream q = new DataOutputStream(b);
        q.writeShort(tid); q.writeShort(0); q.writeShort(10); q.writeByte(unit);
        q.writeByte(FC_READ_INTERNAL);
        q.writeByte(device);
        q.writeByte(dataLength);
        q.writeShort(0);          // reserved
        q.writeInt(address);      // network-order 32-bit field
        q.flush();

        logger.log("SEND", "FC=104 device=" + device + " len=" + dataLength + " address=0x" + Integer.toHexString(address));
        out.write(b.toByteArray()); out.flush();

        Response response = readResponse(in, tid, unit, "FC104");
        byte[] pdu = response.pdu;
        if (pdu.length < 9) throw new IOException("FC104 short PDU=" + pdu.length);
        int fc = pdu[0] & 0xff;
        checkException(pdu, fc, "FC104");
        if (fc != FC_READ_INTERNAL) throw new IOException("FC104 unexpected function=" + fc);
        int responseDevice = pdu[1] & 0xff;
        int responseLength = pdu[2] & 0xff;
        if (responseDevice != device) throw new IOException("FC104 device mismatch expected=" + device + " actual=" + responseDevice);
        if (responseLength != dataLength) throw new IOException("FC104 data_length mismatch expected=" + dataLength + " actual=" + responseLength);

        int echoedBe = u32be(pdu, 5);
        int echoedLe = u32le(pdu, 5);
        if (echoedBe != address && echoedLe != address) {
            throw new IOException("FC104 address mismatch expected=" + address + " be=" + echoedBe + " le=" + echoedLe);
        }

        int expectedPduLength = 9 + dataLength;
        if (pdu.length != expectedPduLength) {
            throw new IOException("FC104 PDU length mismatch expected=" + expectedPduLength + " actual=" + pdu.length);
        }
        byte[] data = new byte[dataLength];
        System.arraycopy(pdu, 9, data, 0, dataLength);
        return data;
    }

    private Socket open(String host, int port) throws IOException {
        Socket socket = new Socket();
        logger.log("INFO", "TCP接続開始 " + host + ":" + port);
        socket.connect(new InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        logger.log("OK", "TCP接続成功");
        return socket;
    }

    private void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
        logger.log("INFO", "TCP切断");
    }

    private int nextTid() { return tx++ & 0xffff; }

    private static int dailyAddress(int category, int dayIndex) {
        if (category < 0 || category > 63) throw new IllegalArgumentException("invalid category=" + category);
        if (dayIndex < 0 || dayIndex > 1023) throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        return ((category & 0x3f) << 10) | (dayIndex & 0x03ff);
    }

    private Response readResponse(DataInputStream in, int expectedTid, int unit, String label) throws IOException {
        byte[] header = new byte[7];
        try { in.readFully(header); } catch (EOFException ex) { throw new IOException(label + " short MBAP", ex); }
        int tid = u16be(header, 0), protocol = u16be(header, 2), length = u16be(header, 4), responseUnit = header[6] & 0xff;
        if (tid != expectedTid) throw new IOException(label + " Transaction ID mismatch expected=" + expectedTid + " actual=" + tid);
        if (protocol != 0) throw new IOException(label + " Protocol ID=" + protocol);
        if (responseUnit != (unit & 0xff)) throw new IOException(label + " Unit ID mismatch expected=" + unit + " actual=" + responseUnit);
        if (length < 2 || length > 254) throw new IOException(label + " invalid MBAP length=" + length);
        byte[] pdu = new byte[length - 1];
        in.readFully(pdu);
        return new Response(pdu);
    }

    private static void checkException(byte[] pdu, int fc, String label) throws IOException {
        if ((fc & 0x80) != 0) throw new IOException(label + " exception=" + (pdu.length > 1 ? (pdu[1] & 0xff) : -1));
    }

    private static final class Response {
        final byte[] pdu;
        Response(byte[] pdu) { this.pdu = pdu; }
    }

    private static int u16be(byte[] data, int offset) { return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff); }
    private static int u16le(byte[] data, int offset) { return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8); }
    private static int u32be(byte[] d, int o) { return ((d[o]&255)<<24)|((d[o+1]&255)<<16)|((d[o+2]&255)<<8)|(d[o+3]&255); }
    private static int u32le(byte[] d, int o) { return (d[o]&255)|((d[o+1]&255)<<8)|((d[o+2]&255)<<16)|((d[o+3]&255)<<24); }
}
