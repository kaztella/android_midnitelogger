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

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }
}
