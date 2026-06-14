import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MSPosTest {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        int pos = 0x52b9;
        byte[] sub = java.util.Arrays.copyOfRange(data, pos, pos + 10);

        System.out.println("原始字节: ");
        for (int i = 0; i < 10; i++) System.out.printf("%02X ", sub[i] & 0xFF);
        System.out.println();

        System.out.println("\n=== 测试 MS 和 BS 的 bit position ===");
        ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        System.out.println("初始 position: " + buf.position());

        BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

        int ms = r.readModularShort();
        System.out.println("MS = " + ms);
        System.out.println("MS 后 position: " + buf.position());

        int bs = r.readBitShort();
        System.out.println("BS = " + bs);
        System.out.println("BS 后 position: " + buf.position());

        System.out.println("\n=== 验证 ===");
        System.out.println("MS 读 " + ms + " bits → position = " + ms);
        System.out.println("BS 读 " + (buf.position() - ms) + " bits");

        // 重新测试，手动跟踪
        System.out.println("\n=== 手动跟踪 ===");
        ByteBufferBitInput buf2 = new ByteBufferBitInput(ByteBuffer.wrap(sub));
        System.out.println("初始: " + buf2.position());

        // 模拟 MS
        int bitCount = 0;
        int byteVal = 0;
        for (int i = 0; i < 8; i++) {
            byteVal = (byteVal << 1) | (buf2.readBit() ? 1 : 0);
            bitCount++;
        }
        System.out.println("读了 8 bits: " + byteVal + ", position: " + buf2.position());

        // 检查 MSB
        boolean cont = buf2.readBit();
        bitCount++;
        System.out.println("继续标志: " + cont + ", position: " + buf2.position());

        // 如果继续标志为 true，继续读取
        while (cont) {
            int nextByte = 0;
            for (int i = 0; i < 7; i++) {
                nextByte = (nextByte << 1) | (buf2.readBit() ? 1 : 0);
                bitCount++;
            }
            System.out.println("继续字节: " + nextByte + ", position: " + buf2.position());
            cont = buf2.readBit();
            bitCount++;
            System.out.println("继续标志: " + cont + ", position: " + buf2.position());
        }

        System.out.println("\n总共读了 " + bitCount + " bits, position: " + buf2.position());

        // BS opcode
        int opcode = 0;
        for (int i = 0; i < 2; i++) {
            opcode = (opcode << 1) | (buf2.readBit() ? 1 : 0);
        }
        System.out.println("BS opcode: " + opcode + ", position: " + buf2.position());

        if (opcode == 0) {
            int lo = 0;
            for (int i = 0; i < 8; i++) lo = (lo << 1) | (buf2.readBit() ? 1 : 0);
            System.out.println("BS lo: " + lo + " (0x" + Integer.toHexString(lo) + "), position: " + buf2.position());
            int hi = 0;
            for (int i = 0; i < 8; i++) hi = (hi << 1) | (buf2.readBit() ? 1 : 0);
            System.out.println("BS hi: " + hi + " (0x" + Integer.toHexString(hi) + "), position: " + buf2.position());
            int bsVal = lo | (hi << 8);
            System.out.println("BS value: " + bsVal + " (0x" + Integer.toHexString(bsVal) + ")");
        }
    }
}
