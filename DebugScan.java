import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DebugScan {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 从 0x5000 开始扫描前 50 个位置 ===");
        int pos = 0x5000;
        int shown = 0;

        for (int i = 0; i < 50 && pos < data.length - 4; i++) {
            byte first = data[pos];
            System.out.printf("[%2d] @ 0x%x: first=0x%02X", i, pos, first & 0xFF);

            if (first == 0) {
                System.out.println(" (skip: zero)");
                pos++;
                continue;
            }

            try {
                byte[] sub = java.util.Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                System.out.printf(" MS=%d", objSize);

                if (objSize <= 2 || objSize > 2000) {
                    System.out.println(" (skip: invalid MS)");
                    pos++;
                    continue;
                }

                int typeCode = r.readBitShort();
                System.out.printf(" type=%d (0x%02X)", typeCode, typeCode);

                if (typeCode < 0 || typeCode > 255) {
                    System.out.println(" (skip: invalid type)");
                    pos++;
                    continue;
                }

                System.out.println();
                shown++;
                pos += 2 + objSize;
            } catch (Exception e) {
                System.out.println(" (error: " + e.getClass().getSimpleName() + ")");
                pos++;
            }
        }

        System.out.println("\n找到 " + shown + " 个有效对象");

        // 直接测试 0x52b9
        System.out.println("\n=== 直接测试 0x52b9 ===");
        pos = 0x52b9;
        System.out.print("字节: ");
        for (int i = 0; i < 10; i++) System.out.printf("%02X ", data[pos+i] & 0xFF);
        System.out.println();

        try {
            byte[] sub = java.util.Arrays.copyOfRange(data, pos, data.length);
            ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
            BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

            int objSize = r.readModularShort();
            System.out.println("MS = " + objSize);

            int typeCode = r.readBitShort();
            System.out.println("type = " + typeCode + " (0x" + Integer.toHexString(typeCode) + ")");
        } catch (Exception e) {
            System.out.println("错误: " + e);
        }
    }
}
