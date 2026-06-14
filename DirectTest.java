import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DirectTest {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 直接验证字节 ===");
        int pos = 0x52b9;
        System.out.println("文件 offset 0x52b9 = " + pos + " = byte #" + pos);
        System.out.println("byte[0] = 0x" + String.format("%02X", data[pos] & 0xFF));
        System.out.println("byte[1] = 0x" + String.format("%02X", data[pos+1] & 0xFF));
        System.out.println("byte[2] = 0x" + String.format("%02X", data[pos+2] & 0xFF));
        System.out.println("byte[3] = 0x" + String.format("%02X", data[pos+3] & 0xFF));

        System.out.println("\n=== 测试1: subData 从 0 开始 ===");
        byte[] sub = new byte[10];
        System.arraycopy(data, pos, sub, 0, 10);
        System.out.print("subData: ");
        for (int i = 0; i < 10; i++) System.out.printf("%02X ", sub[i] & 0xFF);
        System.out.println();

        ByteBuffer bb1 = ByteBuffer.wrap(sub);
        ByteBufferBitInput buf1 = new ByteBufferBitInput(bb1);
        System.out.println("初始 bit position: " + buf1.position());

        BitStreamReader r1 = new BitStreamReader(buf1, DwgVersion.R2000);
        int ms1 = r1.readModularShort();
        System.out.println("MS = " + ms1);

        int bs1 = r1.readBitShort();
        System.out.println("BS = " + bs1 + " (0x" + Integer.toHexString(bs1) + ")");

        System.out.println("\n=== 测试2: subData 从 0x52b9-0x52b9 = 0 开始 ===");
        byte[] sub2 = new byte[data.length - pos];
        System.arraycopy(data, pos, sub2, 0, data.length - pos);

        ByteBuffer bb2 = ByteBuffer.wrap(sub2);
        ByteBufferBitInput buf2 = new ByteBufferBitInput(bb2);
        BitStreamReader r2 = new BitStreamReader(buf2, DwgVersion.R2000);
        int ms2 = r2.readModularShort();
        System.out.println("MS = " + ms2);

        int bs2 = r2.readBitShort();
        System.out.println("BS = " + bs2 + " (0x" + Integer.toHexString(bs2) + ")");

        System.out.println("\n=== 测试3: 手动按位读取 ===");
        ByteBufferBitInput buf3 = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        System.out.println("读取 MS (8 bits):");
        int msBits = 0;
        for (int i = 0; i < 8; i++) msBits = (msBits << 1) | (buf3.readBit() ? 1 : 0);
        System.out.println("  MS bits = " + msBits + " (0x" + Integer.toHexString(msBits) + ")");

        System.out.println("读取 BS opcode (2 bits):");
        int opcode = 0;
        for (int i = 0; i < 2; i++) opcode = (opcode << 1) | (buf3.readBit() ? 1 : 0);
        System.out.println("  opcode = " + opcode);

        if (opcode == 0) {
            System.out.println("opcode=0: 读取 16 位值");
            int val1 = 0;
            for (int i = 0; i < 8; i++) val1 = (val1 << 1) | (buf3.readBit() ? 1 : 0);
            int val2 = 0;
            for (int i = 0; i < 8; i++) val2 = (val2 << 1) | (buf3.readBit() ? 1 : 0);
            System.out.println("  byte[1] = " + val1 + " (0x" + Integer.toHexString(val1) + ")");
            System.out.println("  byte[2] = " + val2 + " (0x" + Integer.toHexString(val2) + ")");
            System.out.println("  BS = 0x" + Integer.toHexString(val1) + " + 0x" + Integer.toHexString(val2 << 8));
            System.out.println("  BS = " + (val1 | (val2 << 8)) + " (0x" + Integer.toHexString(val1 | (val2 << 8)) + ")");
        }

        System.out.println("\n=== 测试4: 字节顺序测试 ===");
        System.out.println("假设 BS = 0x00 0x30 = 48");
        System.out.println("LE: 0x00 + (0x30 << 8) = 0x3000 = 12288");
        System.out.println("BE: (0x00 << 8) + 0x30 = 0x0030 = 48");
        System.out.println("文件: 0x00 0x4C → " + (0x00 | (0x4C << 8)) + " ≠ 48");

        System.out.println("\n=== 结论: BS 不符合 opcode=0 的 2字节格式 ===");
        System.out.println("也许 opcode=1 (单字节 BS)?");

        // 检查 bit 9-10 的值
        System.out.println("\n重新分析 bit 8-9:");
        buf3.seek(8);
        int b9 = buf3.readBit() ? 1 : 0;
        int b10 = buf3.readBit() ? 1 : 0;
        System.out.println("bit[8] = " + b9 + ", bit[9] = " + b10);
        System.out.println("opcode = " + (b9 << 1 | b10) + " = 0b" + b9 + b10);

        System.out.println("\n如果 opcode=1, BS 是单字节:");
        buf3.seek(10);
        int val = 0;
        for (int i = 0; i < 8; i++) val = (val << 1) | (buf3.readBit() ? 1 : 0);
        System.out.println("BS (8 bits from bit 10) = " + val + " (0x" + Integer.toHexString(val) + ")");
    }
}
