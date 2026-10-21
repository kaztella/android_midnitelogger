package jp.terada.midnitelogger;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

public class Modbus {
    public interface Listener { void onLog(String level, String message); }

    private int transactionId = 1;
    private final Listener listener;

    public Modbus() { this(null); }
    public Modbus(Listener listener) { this.listener = listener; }

    private void log(String level, String message) {
        if (listener != null) listener.onLog(level, message);
    }

    public int[] readHoldingRegisters(String host, int address, int count) throws IOException {
        Socket socket = new Socket();
        try {
            log("INFO", "TCP接続開始 " + host + ":" + Config.PORT);
            socket.connect(new InetSocketAddress(host, Config.PORT), 5000);
            socket.setSoTimeout(5000);
            log("OK", "TCP接続成功 " + host + ":" + Config.PORT);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int id = transactionId++ & 0xffff;

            byte[] request = buildReadHoldingRequest(id, address, count);
            log("SEND", "Modbus FC=3 Addr=" + address + " Count=" + count + " Unit=" + Config.UNIT_ID);
            log("SEND-HEX", toHex(request));
            out.write(request);
            out.flush();

            byte[] response = readModbusTcpFrame(in);
            log("RECV-HEX", toHex(response));
            return parseReadHoldingResponse(response, id, count);
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] buildReadHoldingRequest(int id, int address, int count) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(12);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeShort(id);                 // Transaction ID
        data.writeShort(0);                  // Protocol ID
        data.writeShort(6);                  // Remaining bytes: Unit + PDU
        data.writeByte(Config.UNIT_ID);      // Unit ID
        data.writeByte(3);                   // FC03 Read Holding Registers
        data.writeShort(address);            // Start address
        data.writeShort(count);              // Quantity
        data.flush();
        return bytes.toByteArray();
    }

    private static byte[] readModbusTcpFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[6];
        in.readFully(header);
        int length = ((header[4] & 0xff) << 8) | (header[5] & 0xff);
        if (length < 2 || length > 254) {
            throw new IOException("Invalid MBAP length=" + length + " header=" + toHex(header));
        }
        byte[] remaining = new byte[length];
        try {
            in.readFully(remaining);
        } catch (EOFException e) {
            throw new IOException("Short Modbus response expected=" + length + " bytes", e);
        }
        byte[] frame = new byte[header.length + remaining.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(remaining, 0, frame, header.length, remaining.length);
        return frame;
    }

    private int[] parseReadHoldingResponse(byte[] frame, int expectedId, int count) throws IOException {
        if (frame.length < 9) throw new IOException("Modbus response too short length=" + frame.length);

        int responseId = u16(frame, 0);
        int protocol = u16(frame, 2);
        int mbapLength = u16(frame, 4);
        int unit = frame[6] & 0xff;
        int function = frame[7] & 0xff;

        if ((function & 0x80) != 0) {
            int exceptionCode = frame[8] & 0xff;
            throw new IOException("Modbus exception code=" + exceptionCode
                    + " responseFC=0x" + hexByte(function)
                    + " originalFC=0x" + hexByte(function & 0x7f)
                    + " Unit=" + unit
                    + " HEX=" + toHex(frame));
        }

        int byteCount = frame[8] & 0xff;
        log("RECV", "Modbus応答 TID=" + responseId
                + " Protocol=" + protocol
                + " Length=" + mbapLength
                + " Unit=" + unit
                + " FC=" + function
                + " Bytes=" + byteCount);

        if (responseId != expectedId)
            throw new IOException("Transaction ID mismatch expected=" + expectedId + " actual=" + responseId);
        if (protocol != 0)
            throw new IOException("Invalid Protocol ID=" + protocol);
        if (function != 3)
            throw new IOException("Unexpected function FC=0x" + hexByte(function));
        if (byteCount != count * 2)
            throw new IOException("Unexpected byte count expected=" + (count * 2) + " actual=" + byteCount);
        if (frame.length != 9 + byteCount)
            throw new IOException("Frame length mismatch expected=" + (9 + byteCount) + " actual=" + frame.length);

        int[] values = new int[count];
        int offset = 9;
        for (int i = 0; i < count; i++) {
            values[i] = u16(frame, offset + i * 2);
        }
        return values;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static String toHex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) text.append(' ');
            text.append(hexByte(data[i] & 0xff));
        }
        return text.toString();
    }

    private static String hexByte(int value) {
        return String.format(Locale.US, "%02X", value & 0xff);
    }

    public static int signed16(int value) { return (short) value; }
    public static long unsigned32(int lowWord, int highWord) {
        return ((long) highWord << 16) | (lowWord & 0xffffL);
    }
    public static long signed32(int lowWord, int highWord) {
        return (int) unsigned32(lowWord, highWord);
    }
}
