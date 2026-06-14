import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DebugBS {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 字节级分析 @ 0x52b9 ===");
        int pos = 0x52b9;
        for (int i = 0; i < 20; i++) {
            int b = data[pos + i] & 0xFF;
            System.out.printf("  byte[%d] @ 0x%x = 0x%02X = %3d  binary: %8s%n",
                i, pos + i, b, b, Integer.toBinaryString(b));
        }

        System.out.println("\n=== MS 解码测试 ===");
        // MS: 0x72 < 0x80 → 单字节, 值=0x72=114
        System.out.println("MS[0]: byte=0x72 = 114 < 0x80 → 单字节 MS, 值=114");

        System.out.println("\n=== BS 解码测试 (从 byte[1] = 0x00 开始) ===");
        int b1 = 0x00;
        int opcode = (b1 >> 6) & 0x3;
        System.out.printf("b1 = 0x%02X, opcode = %d%n", b1, opcode);
        System.out.println("opcode=0 → 单字节 BS, 值 = b1 & 0x3F = 0x00 = 0");
        System.out.println("但 BitStreamReader 读出的是 48!");

        System.out.println("\n=== 让我直接调用 BitStreamReader ===");
        byte[] sub = java.util.Arrays.copyOfRange(data, pos, pos + 20);
        ByteBuffer bb = ByteBuffer.wrap(sub);
        ByteBufferBitInput buf = new ByteBufferBitInput(bb);
        BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

        int ms = r.readModularShort();
        System.out.println("readModularShort() = " + ms);

        int bs = r.readBitShort();
        System.out.println("readBitShort() = " + bs + " (0x" + Integer.toHexString(bs) + ")");

        System.out.println("\n=== 手动模拟 BS 解码 ===");
        // readBitShort() reads 4 bits, then depending on value reads more
        // 0-15: return value
        // 16: read next 8 bits
        // 17: read next 16 bits
        // 18: read next 24 bits
        // 19: read next 32 bits
        buf.seek(8); // 回到 byte[1] 的开始
        int first4 = 0;
        for (int i = 0; i < 4; i++) first4 = (first4 << 1) | (buf.readBit() ? 1 : 0);
        System.out.println("前 4 bits: " + first4);
        if (first4 < 16) {
            System.out.println("前 4 bits < 16 → 直接返回值: " + first4);
        } else {
            System.out.println("前 4 bits >= 16，需要读取更多...");
        }

        System.out.println("\n=== 重新用二进制分析 byte[1] = 0x00 ===");
        System.out.println("0x00 = 00000000");
        System.out.println("高 2 位 (opcode) = 00 = 0");
        System.out.println("低 6 位 = 000000 = 0");
        System.out.println("所以 opcode=0, 值=0");

        System.out.println("\n=== 等等！让我检查 MS 的实际字节数 ===");
        buf.seek(0);
        // MS: 如果第一个字节 >= 0x80，需要读取第二个字节
        int first = 0;
        for (int i = 0; i < 8; i++) first = (first << 1) | (buf.readBit() ? 1 : 0);
        System.out.println("MS 第一个字节的 8 bits = 0x72 = " + first);
        if (first >= 0x80) {
            System.out.println("  >= 0x80，需要读取第二个 MS 字节");
            int second = 0;
            for (int i = 0; i < 8; i++) second = (second << 1) | (buf.readBit() ? 1 : 0);
            System.out.println("  第二个 MS 字节 = " + second);
            int msValue = (first & 0x7F) | ((second & 0x7F) << 7);
            System.out.println("  MS 值 = " + msValue);
        } else {
            System.out.println("  < 0x80，单字节 MS，值 = " + first);
        }

        System.out.println("\n=== BitStreamReader 内部: readModularShort ===");
        // 看 BitStreamReader.readModularShort 源码
        // 它使用 input.readBits() 读取位，不是字节对齐的
        // 所以 MS = readModularShort() 可能读取 < 8 位
        System.out.println("readModularShort 读取 7 位, 检查 continue 位");
        System.out.println("0x72 = 1110010, 最高位(第7位) = 1, 表示继续");
        System.out.println("然后读取下一个 8 位...");
    }
}
