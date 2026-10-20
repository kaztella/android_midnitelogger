package jp.terada.midnitelogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Modbus {
    private int transactionId = 1;

    public int[] readHoldingRegisters(String host, int address, int count) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, Config.PORT), 5000);
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int id = transactionId++ & 0xffff;
            out.writeShort(id); out.writeShort(0); out.writeShort(6);
            out.writeByte(Config.UNIT_ID); out.writeByte(3);
            out.writeShort(address); out.writeShort(count); out.flush();
            int responseId = in.readUnsignedShort();
            in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedByte();
            int function = in.readUnsignedByte();
            if (function == 0x83) throw new IOException("Modbus exception " + in.readUnsignedByte());
            int byteCount = in.readUnsignedByte();
            if (responseId != id || function != 3 || byteCount != count * 2) throw new IOException("Invalid Modbus response");
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = in.readUnsignedShort();
            return values;
        } finally { try { socket.close(); } catch (Exception ignored) { } }
    }
    public static int signed16(int value) { return (short) value; }
    public static long unsigned32(int lowWord, int highWord) { return ((long) highWord << 16) | (lowWord & 0xffffL); }
    public static long signed32(int lowWord, int highWord) { return (int) unsigned32(lowWord, highWord); }
}
