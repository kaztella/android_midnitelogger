package jp.terada.midnitelogger;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Modbus {
    public interface L {
        void log(String level, String message);
    }

    private final L logger;
    private int tx = 1;

    public Modbus(L logger) {
        this.logger = logger;
    }

    /**
     * 1本のTCP接続で複数のModbusレジスタ範囲を連続読取りする。
     * blocksの各要素は {開始アドレス, レジスタ数}。
     */
    public int[][] readBlocks(String host, int port, int unit, int[][] blocks)
            throws IOException {
        if (blocks == null || blocks.length == 0) {
            throw new IllegalArgumentException("blocks is empty");
        }

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
                if (blocks[i] == null || blocks[i].length < 2) {
                    throw new IllegalArgumentException("invalid block index=" + i);
                }
                results[i] = readOnConnection(
                        out, in, unit, blocks[i][0], blocks[i][1]);
            }
            return results;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            logger.log("INFO", "TCP切断");
        }
    }

    private int[] readOnConnection(DataOutputStream out, DataInputStream in,
                                   int unit, int addr, int count)
            throws IOException {
        if (count < 1 || count > 125) {
            throw new IllegalArgumentException("invalid register count=" + count);
        }

        int transactionId = tx++ & 0xffff;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(transactionId);
        request.writeShort(0);       // Protocol ID
        request.writeShort(6);       // Unit ID以降の長さ
        request.writeByte(unit);
        request.writeByte(3);        // FC03 Holding Registers
        request.writeShort(addr);
        request.writeShort(count);
        request.flush();

        logger.log("SEND", "FC=3 Addr=" + addr + " Count=" + count);
        out.write(buffer.toByteArray());
        out.flush();

        byte[] header = new byte[7];
        in.readFully(header);

        int responseTransactionId = u16(header, 0);
        int protocolId = u16(header, 2);
        int length = u16(header, 4);
        int responseUnit = header[6] & 0xff;

        if (responseTransactionId != transactionId) {
            throw new IOException("Transaction ID mismatch expected="
                    + transactionId + " actual=" + responseTransactionId);
        }
        if (protocolId != 0) {
            throw new IOException("Unexpected Protocol ID=" + protocolId);
        }
        if (responseUnit != (unit & 0xff)) {
            throw new IOException("Unit ID mismatch expected=" + unit
                    + " actual=" + responseUnit);
        }
        if (length < 2 || length > 254) {
            throw new IOException("Invalid MBAP length=" + length);
        }

        byte[] pdu = new byte[length - 1];
        try {
            in.readFully(pdu);
        } catch (EOFException ex) {
            throw new IOException("Short Modbus response Addr=" + addr, ex);
        }

        int functionCode = pdu[0] & 0xff;
        if ((functionCode & 0x80) != 0) {
            int exceptionCode = pdu.length > 1 ? pdu[1] & 0xff : -1;
            throw new IOException("Modbus exception=" + exceptionCode
                    + " FC=" + functionCode + " Addr=" + addr);
        }
        if (functionCode != 3) {
            throw new IOException("Unexpected FC=" + functionCode
                    + " Addr=" + addr);
        }
        if (pdu.length < 2) {
            throw new IOException("Short Modbus PDU Addr=" + addr);
        }

        int byteCount = pdu[1] & 0xff;
        if (byteCount != count * 2) {
            throw new IOException("Byte count=" + byteCount
                    + " expected=" + (count * 2) + " Addr=" + addr);
        }
        if (pdu.length != byteCount + 2) {
            throw new IOException("PDU length=" + pdu.length
                    + " expected=" + (byteCount + 2) + " Addr=" + addr);
        }

        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = u16(pdu, 2 + i * 2);
        }
        logger.log("RECV", "Modbus応答 " + count + " registers");
        return values;
    }

    public int[] readDailyLogCategories(String host, int port, int unit,
                                         int dayIndex, int[] categories) throws IOException {
        if (dayIndex < 0 || dayIndex > 1023) throw new IllegalArgumentException("invalid dayIndex=" + dayIndex);
        if (categories == null || categories.length == 0) throw new IllegalArgumentException("categories is empty");
        Socket socket = new Socket();
        try {
            logger.log("INFO", "TCP接続開始 " + host + ":" + port);
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            logger.log("OK", "TCP接続成功");
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int[] values = new int[categories.length];
            for (int i = 0; i < categories.length; i++) {
                values[i] = readDailyOnConnection(out, in, unit, categories[i], dayIndex);
            }
            return values;
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            logger.log("INFO", "TCP切断");
        }
    }

    public int[] readDailyLogBlock(String host, int port, int unit,
                                   int category, int dayIndex) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try { return readDailyLogBlockOnce(host, port, unit, category, dayIndex); }
            catch (EOFException e) {
                last = new IOException("FC104 EOF category=" + category + " dayIndex=" + dayIndex, e);
                logger.log("INFO", "FC104 EOF再試行 Category=" + category + " DayIndex=" + dayIndex + " attempt=" + attempt);
            } catch (java.net.SocketException e) {
                last = new IOException("FC104 Socket category=" + category + " dayIndex=" + dayIndex, e);
                logger.log("INFO", "FC104 Socket再試行 Category=" + category + " DayIndex=" + dayIndex + " attempt=" + attempt);
            }
        }
        throw last;
    }

    private int[] readDailyLogBlockOnce(String host, int port, int unit,
                                        int category, int dayIndex) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000); socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int transactionId = tx++ & 0xffff;
            int address = ((category & 0x3f) << 10) | (dayIndex & 0x03ff);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16);
            DataOutputStream request = new DataOutputStream(buffer);
            request.writeShort(transactionId); request.writeShort(0); request.writeShort(10);
            request.writeByte(unit); request.writeByte(0x68); request.writeByte(5); request.writeByte(2);
            request.writeShort(0); request.writeInt(address); request.flush();
            logger.log("SEND", "FC104 BLOCK Category=" + category + " DayIndex=" + dayIndex);
            out.write(buffer.toByteArray()); out.flush();
            byte[] header = new byte[7]; in.readFully(header);
            if (u16(header,0) != transactionId || u16(header,2) != 0 || (header[6]&0xff)!=(unit&0xff))
                throw new IOException("FC104 block MBAP mismatch");
            int length = u16(header,4);
            if (length < 2 || length > 254) throw new IOException("FC104 block length=" + length);
            byte[] pdu = new byte[length-1]; in.readFully(pdu);
            if (pdu.length != 73) throw new IOException("FC104 block PDU expected=73 actual=" + pdu.length);
            if ((pdu[0]&0xff)!=0x68 || (pdu[1]&0xff)!=5 || (pdu[2]&0xff)!=2 || u16(pdu,3)!=0)
                throw new IOException("FC104 block header invalid");
            int[] words = new int[32];
            for (int i=0;i<32;i++) words[i]=u16le(pdu,9+i*2);
            logger.log("RECV", "FC104 BLOCK Category=" + category + " DayIndex=" + dayIndex + " Words=32");
            return words;
        } finally { try { socket.close(); } catch(IOException ignored) {} }
    }

    public int[] readDailyLog(String host, int port, int unit, int dayIndex) throws IOException {
        return readDailyLogCategories(host, port, unit, dayIndex,
                new int[]{0,1,2,3,4,5,6,7,8});
    }

    private int readDailyOnConnection(DataOutputStream out, DataInputStream in,
                                      int unit, int category, int dayIndex) throws IOException {
        if (category < 0 || category > 63) throw new IllegalArgumentException("invalid category=" + category);
        int transactionId = tx++ & 0xffff;
        int address = ((category & 0x3f) << 10) | (dayIndex & 0x03ff);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16);
        DataOutputStream request = new DataOutputStream(buffer);
        request.writeShort(transactionId);
        request.writeShort(0);
        request.writeShort(10);
        request.writeByte(unit);
        request.writeByte(0x68);
        request.writeByte(5);
        request.writeByte(2);
        request.writeShort(0);
        request.writeInt(address);
        request.flush();
        logger.log("SEND", "FC=104 Device=5 Category=" + category + " DayIndex=" + dayIndex);
        out.write(buffer.toByteArray()); out.flush();

        byte[] header = new byte[7]; in.readFully(header);
        int responseTransactionId = u16(header, 0);
        int protocolId = u16(header, 2);
        int length = u16(header, 4);
        int responseUnit = header[6] & 0xff;
        if (responseTransactionId != transactionId) throw new IOException("Transaction ID mismatch");
        if (protocolId != 0) throw new IOException("Unexpected Protocol ID=" + protocolId);
        if (responseUnit != (unit & 0xff)) throw new IOException("Unit ID mismatch");
        if (length < 2 || length > 254) throw new IOException("Invalid MBAP length=" + length);
        byte[] pdu = new byte[length - 1];
        try { in.readFully(pdu); } catch (EOFException e) { throw new IOException("Short FC104 response", e); }
        logger.log("INFO", "FC104応答 PDU_LEN=" + pdu.length
                + " Category=" + category + " DayIndex=" + dayIndex);
        int fc = pdu[0] & 0xff;
        if ((fc & 0x80) != 0) throw new IOException("Modbus exception=" + (pdu.length > 1 ? pdu[1] & 0xff : -1));
        if (fc != 0x68) throw new IOException("Unexpected FC=" + fc);
        if (pdu.length < 11) throw new IOException("Short FC104 PDU=" + pdu.length);
        int device = pdu[1] & 0xff;
        int dataLength = pdu[2] & 0xff;
        int reserved = u16(pdu, 3);
        long responseAddress = ((long)(pdu[5] & 0xff) << 24) | ((long)(pdu[6] & 0xff) << 16)
                | ((long)(pdu[7] & 0xff) << 8) | (long)(pdu[8] & 0xff);
        if (device != 5 || dataLength != 2 || reserved != 0 || pdu.length < 11)
            throw new IOException("Invalid FC104 response format"
                    + " device=" + device + " dataLength=" + dataLength
                    + " reserved=" + reserved + " pduLength=" + pdu.length);
        if (responseAddress != (address & 0xffffffffL)) throw new IOException("FC104 address mismatch");
        int value = u16le(pdu, pdu.length - 2);
        logger.log("RECV", "FC=104 Category=" + category + " DayIndex=" + dayIndex
                + " RawLE=" + value + " BlockWords=" + ((pdu.length - 9) / 2));
        return value;
    }

    private static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder b = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) b.append(' ');
            int v = data[i] & 0xff;
            if (v < 16) b.append('0');
            b.append(Integer.toHexString(v).toUpperCase());
        }
        return b.toString();
    }

    private static int u16le(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }
}
