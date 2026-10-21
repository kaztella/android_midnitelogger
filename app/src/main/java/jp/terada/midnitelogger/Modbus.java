package jp.terada.midnitelogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Modbus {
    public interface Listener { void onLog(String level, String message); }
    private int transactionId = 1;
    private final Listener listener;
    public Modbus() { this(null); }
    public Modbus(Listener listener) { this.listener = listener; }
    private void log(String level, String message) { if (listener != null) listener.onLog(level, message); }

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
            log("SEND", "Modbus FC=3 Addr=" + address + " Count=" + count + " Unit=" + Config.UNIT_ID);
            out.writeShort(id); out.writeShort(0); out.writeShort(6);
            out.writeByte(Config.UNIT_ID); out.writeByte(3);
            out.writeShort(address); out.writeShort(count); out.flush();
            int responseId = in.readUnsignedShort();
            int protocol = in.readUnsignedShort();
            int length = in.readUnsignedShort();
            int unit = in.readUnsignedByte();
            int function = in.readUnsignedByte();
            if ((function & 0x80) != 0) {
                int code = in.readUnsignedByte();
                throw new IOException("Modbus exception code=" + code + " FC=" + function);
            }
            int byteCount = in.readUnsignedByte();
            log("RECV", "Modbus応答 TID=" + responseId + " Protocol=" + protocol + " Length=" + length + " Unit=" + unit + " FC=" + function + " Bytes=" + byteCount);
            if (responseId != id || protocol != 0 || function != 3 || byteCount != count * 2)
                throw new IOException("Invalid Modbus response expectedTid=" + id + " actualTid=" + responseId + " FC=" + function + " Bytes=" + byteCount);
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = in.readUnsignedShort();
            return values;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }
    public static int signed16(int value) { return (short) value; }
    public static long unsigned32(int lowWord, int highWord) { return ((long) highWord << 16) | (lowWord & 0xffffL); }
    public static long signed32(int lowWord, int highWord) { return (int) unsigned32(lowWord, highWord); }
}
