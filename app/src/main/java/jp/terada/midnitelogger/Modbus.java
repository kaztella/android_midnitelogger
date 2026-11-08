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
 * FC104 request layout follows the MidNite Solar MODBUS Network specification:
 * function(0x68), device, data_length, reserved(16), address(32).
 * Daily-log address uses category in bits 15..10 and day index in bits 9..0.
 */
public class Modbus {
    public interface L {
        void log(String level, String message);
    }

    private static final int FC_READ_HOLDING = 3;
    private static final int FC_READ_INTERNAL = 0x68;
    private static final int DAILY_DEVICE = 5;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final L logger;
    private int tx = 1;

    public Modbus(L logger) {
        this.logger = logger;
    }

    /* ============================================================
     * Normal FC03 logging
     * ============================================================ */

    public int[][] readBlocks(String host, int port, int unit, int[][] blocks)
            throws IOException {

        if (blocks == null || blocks.length == 0) {
            throw new IllegalArgumentException("blocks is empty");
        }

        Socket socket = openSocket(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            int[][] results = new int[blocks.length][];
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == null || blocks[i].length < 2) {
                    throw new IllegalArgumentException("invalid block index=" + i);
                }
                results[i] = readHoldingOnConnection(
                        out, in, unit, blocks[i][0], blocks[i][1]);
            }
            return results;
        } finally {
            closeSocket(socket);
        }
    }

    private int[] readHoldingOnConnection(
            DataOutputStream out,
            DataInputStream in,
            int unit,
            int address,
            int count) throws IOException {

        if (count < 1 || count > 125) {
            throw new IllegalArgumentException("invalid register count=" + count);
        }

        int tid = nextTransactionId();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(tid);
        request.writeShort(0);
        request.writeShort(6);
        request.writeByte(unit);
        request.writeByte(FC_READ_HOLDING);
        request.writeShort(address);
        request.writeShort(count);
        request.flush();

        logger.log("SEND", "FC=3 Addr=" + address + " Count=" + count);
        out.write(buffer.toByteArray());
        out.flush();

        byte[] pdu = readPdu(in, tid, unit, "FC03 Addr=" + address);
        int function = pdu[0] & 0xff;
        checkModbusException(pdu, function, "FC03 Addr=" + address);

        if (function != FC_READ_HOLDING) {
            throw new IOException("Unexpected FC=" + function + " Addr=" + address);
        }
        if (pdu.length < 2) {
            throw new IOException("Short FC03 PDU Addr=" + address);
        }

        int byteCount = pdu[1] & 0xff;
        if (byteCount != count * 2 || pdu.length != byteCount + 2) {
            throw new IOException("Invalid FC03 length Addr=" + address
                    + " byteCount=" + byteCount + " pdu=" + pdu.length);
        }

        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = u16be(pdu, 2 + i * 2);
        }

        logger.log("RECV", "Modbus応答 " + count + " registers");
        return values;
    }

    /* ============================================================
     * FC104 Daily Log file transfer
     * ============================================================ */

    /**
     * Read one complete Daily Log record (categories 0..8) over ONE TCP session.
     * Each category is an official FC104 Read Internal request for 2 bytes.
     */
    public int[] readDailyLog(
            String host,
            int port,
            int unit,
            int dayIndex) throws IOException {

        validateDailyDayIndex(dayIndex);
        Socket socket = openSocket(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[] values = new int[9];
            for (int category = 0; category <= 8; category++) {
                values[category] = readDailyWordOnConnection(
                        out, in, unit, category, dayIndex);
            }
            return values;
        } finally {
            closeSocket(socket);
        }
    }

    /** Read timestamp categories 3 and 6 over ONE TCP session. */
    public int[] readDailyTimestampWords(
            String host,
            int port,
            int unit,
            int dayIndex) throws IOException {

        validateDailyDayIndex(dayIndex);
        Socket socket = openSocket(host, port);
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            return new int[]{
                    readDailyWordOnConnection(out, in, unit, 3, dayIndex),
                    readDailyWordOnConnection(out, in, unit, 6, dayIndex)
            };
        } finally {
            closeSocket(socket);
        }
    }

    private int readDailyWordOnConnection(
            DataOutputStream out,
            DataInputStream in,
            int unit,
            int category,
            int dayIndex) throws IOException {

        byte[] data = readInternalOnConnection(
                out, in, unit, DAILY_DEVICE, 2,
                dailyAddress(category, dayIndex));
        if (data.length != 2) {
            throw new IOException("FC104 Daily Log expected 2 bytes actual=" + data.length);
        }
        int value = u16le(data, 0);
        logger.log("RECV", "FC104 DailyLog dayIndex=" + dayIndex
                + " category=" + category + " value=" + value);
        return value;
    }

    /**
     * MidNite FC104 Read Internal parser:
     * function, device, data_length, reserved(16), address(32), data...
     */
    private byte[] readInternalOnConnection(
            DataOutputStream out,
            DataInputStream in,
            int unit,
            int device,
            int dataLength,
            int address) throws IOException {

        if (dataLength < 1 || dataLength > 200) {
            throw new IllegalArgumentException("FC104 dataLength must be 1..200");
        }

        int tid = nextTransactionId();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(tid);
        request.writeShort(0);
        request.writeShort(10);
        request.writeByte(unit);
        request.writeByte(FC_READ_INTERNAL);
        request.writeByte(device);
        request.writeByte(dataLength);
        request.writeShort(0);
        request.writeInt(address);
        request.flush();

        logger.log("SEND", "FC=104 device=" + device
                + " len=" + dataLength
                + " address=0x" + Integer.toHexString(address));
        out.write(buffer.toByteArray());
        out.flush();

        byte[] pdu = readPdu(in, tid, unit, "FC104");
        if (pdu.length < 9) throw new IOException("FC104 short PDU=" + pdu.length);
        int function = pdu[0] & 0xff;
        checkModbusException(pdu, function, "FC104");
        if (function != FC_READ_INTERNAL) throw new IOException("FC104 unexpected function=" + function);

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
        if (pdu.length != expectedPduLength) throw new IOException("FC104 PDU length mismatch expected=" + expectedPduLength + " actual=" + pdu.length);

        byte[] data = new byte[dataLength];
        System.arraycopy(pdu, 9, data, 0, dataLength);
        return data;
    }

    /* ============================================================
     * Common Modbus helpers
     * ============================================================ */

    private Socket openSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        logger.log("INFO", "TCP接続開始 " + host + ":" + port);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        logger.log("OK", "TCP接続成功");
        return socket;
    }

    private void closeSocket(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        logger.log("INFO", "TCP切断");
    }

    private byte[] readPdu(
            DataInputStream in,
            int expectedTid,
            int unit,
            String label) throws IOException {

        byte[] header = new byte[7];
        try {
            in.readFully(header);
        } catch (EOFException ex) {
            throw new IOException(label + " short MBAP", ex);
        }

        int tid = u16be(header, 0);
        int protocol = u16be(header, 2);
        int length = u16be(header, 4);
        int responseUnit = header[6] & 0xff;

        if (tid != expectedTid) {
            throw new IOException(label + " Transaction ID mismatch expected="
                    + expectedTid + " actual=" + tid);
        }

        if (protocol != 0) {
            throw new IOException(label + " Protocol ID=" + protocol);
        }

        if (responseUnit != (unit & 0xff)) {
            throw new IOException(label + " Unit ID mismatch expected="
                    + unit + " actual=" + responseUnit);
        }

        if (length < 2 || length > 254) {
            throw new IOException(label + " invalid MBAP length=" + length);
        }

        byte[] pdu = new byte[length - 1];
        in.readFully(pdu);
        return pdu;
    }

    private int nextTransactionId() {
        return tx++ & 0xffff;
    }

    private static void validateDailyDayIndex(int dayIndex) {
        if (dayIndex < 1 || dayIndex > 1023) {
            throw new IllegalArgumentException("dayIndex must be 1..1023: " + dayIndex);
        }
    }

    private static int dailyAddress(int category, int dayIndex) {
        if (category < 0 || category > 63) {
            throw new IllegalArgumentException("invalid category=" + category);
        }
        if (dayIndex < 0 || dayIndex > 1023) {
            throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        }
        return ((category & 0x3f) << 10) | (dayIndex & 0x03ff);
    }

    private static void checkModbusException(
            byte[] pdu,
            int function,
            String label) throws IOException {

        if ((function & 0x80) != 0) {
            int code = pdu.length > 1 ? (pdu[1] & 0xff) : -1;
            throw new IOException(label + " exception=" + code);
        }
    }


    private static int u16be(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8)
                | (data[offset + 1] & 0xff);
    }

    private static int u16le(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8);
    }

    private static int u32be(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static int u32le(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }
}
