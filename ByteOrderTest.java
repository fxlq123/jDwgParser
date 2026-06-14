import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ByteOrderTest {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int pos = 0x52b9;
        byte[] sub = java.util.Arrays.copyOfRange(data, pos, pos + 10);

        System.out.println("原始字节: ");
        for (int i = 0; i < 10; i++) System.out.printf("%02X ", sub[i] & 0xFF);
        System.out.println();

        // 用 LITTLE_ENDIAN
        System.out.println("\n=== ByteBuffer LITTLE_ENDIAN ===");
        ByteBuffer bbLE = ByteBuffer.wrap(sub).order(ByteOrder.LITTLE_ENDIAN);
        System.out.println("byte[0] = 0x" + String.format("%02X", bbLE.get(0) & 0xFF));
        System.out.println("byte[1] = 0x" + String.format("%02X", bbLE.get(1) & 0xFF));
        System.out.println("short[0] = 0x" + String.format("%04X", bbLE.getShort(0) & 0xFFFF));
        System.out.println("int[0] = 0x" + String.format("%08X", bbLE.getInt(0)));

        // 直接读取
        System.out.println("\n=== BitStreamReader 测试 ===");
        ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

        System.out.println("readModularShort() = " + r.readModularShort());

        // 保存当前位置
        long savedPos = buf.position();
        System.out.println("BS 前 bit position = " + savedPos);

        int bs = r.readBitShort();
        System.out.println("readBitShort() = " + bs + " (0x" + Integer.toHexString(bs) + ")");

        // 打印 BS 后的 bit position
        System.out.println("BS 后 bit position = " + buf.position());

        // 如果 BS = 48，检查 bit 10-25 的值
        System.out.println("\n=== 如果 BS = 48 (0x0030) ===");
        System.out.println("0x0030 的二进制: " + Integer.toBinaryString(0x0030));
        System.out.println("LE: lo=0x30, hi=0x00");
        System.out.println("文件字节 1,2: 0x00, 0x4C");

        // 关键: ByteBuffer LE 读取 short(0) 会得到 byte[0] + byte[1]<<8 = 0x72 + 0
        // 但 BitStreamReader 读取 16 bits 从 bit 10
        // bit 10 在字节内的位置是 bit 2 (因为 bit 8-9 是 opcode)
        // 所以 lo = byte[1] 的 bits 2-7 + byte[2] 的 bits 0-1
        System.out.println("\n=== 重新计算 (LE byte order) ===");
        System.out.println("byte[1] = 0x00 = 0b00000000");
        System.out.println("byte[2] = 0x4C = 0b01001100");
        System.out.println("bit 10-17 (lo): bits 2-7 of byte[1] + bits 0-1 of byte[2]");
        System.out.println("  = 0b00 0000 + 0b01 = 0b00000001 = 0x01");
        System.out.println("bit 18-25 (hi): bits 2-7 of byte[2]");
        System.out.println("  = 0b00 1001 = 0x12 (18 in decimal)");
        System.out.println("BS = lo | (hi << 8) = 0x01 | (0x12 << 8)");
        System.out.println("  = 0x01 | 0x1200 = 0x1201 = 4609");

        // 这也不对！让我直接检查
        System.out.println("\n=== 直接检查 bit 10-25 ===");
        buf.seek(10);
        System.out.println("从 bit 10 开始:");
        int val1 = 0;
        for (int i = 0; i < 8; i++) val1 = (val1 << 1) | (buf.readBit() ? 1 : 0);
        System.out.println("  bits 10-17 = " + val1 + " (0x" + Integer.toHexString(val1) + ")");
        int val2 = 0;
        for (int i = 0; i < 8; i++) val2 = (val2 << 1) | (buf.readBit() ? 1 : 0);
        System.out.println("  bits 18-25 = " + val2 + " (0x" + Integer.toHexString(val2) + ")");
        System.out.println("  BS = " + (val1 | (val2 << 8)) + " (0x" + Integer.toHexString(val1 | (val2 << 8)) + ")");
    }
}
