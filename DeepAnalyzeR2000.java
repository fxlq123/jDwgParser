import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 深入分析 R2000 对象格式 - 手动解析已知位置的对象
 */
public class DeepAnalyzeR2000 {

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 分析 0x52b9 附近的对象 (已知第一个对象) ===\n");

        // 已知: 0x52b9 是第一个对象位置
        // 让我们手动解析它
        traceParseObject(data, 0x52b9, 64);

        // 还看看已知包含 "*Model_Space" 的位置
        // block name @ 0x5a82
        System.out.println("\n=== 分析 '*Model_Space' @ 0x5a82 附近 ===\n");
        traceParseObject(data, 0x5a82 - 32, 64);

        // 还看看 0x548f (*Paper_Space)
        System.out.println("\n=== 分析 '*Paper_Space' @ 0x548f 附近 ===\n");
        traceParseObject(data, 0x548f - 32, 64);

        // 还看看 0x54b8 (SW_NOTE_0)
        System.out.println("\n=== 分析 'SW_NOTE_0' @ 0x54b8 附近 ===\n");
        traceParseObject(data, 0x54b8 - 32, 64);
    }

    private static void traceParseObject(byte[] data, int offset, int maxBytes) {
        System.out.printf("解析 @ 0x%x (%d 范围)%n%n", offset, maxBytes);

        // 显示原始字节
        System.out.print("Hex: ");
        for (int i = 0; i < Math.min(maxBytes, data.length - offset); i++) {
            System.out.printf("%02X ", data[offset + i] & 0xFF);
            if ((i + 1) % 16 == 0) {
                System.out.print("  ");
                for (int j = i - 15; j <= i; j++) {
                    int b = data[offset + j] & 0xFF;
                    if (b >= 32 && b < 127) System.out.print((char)b);
                    else System.out.print(".");
                }
                System.out.println();
                System.out.print("     ");
            }
        }
        System.out.println();

        // 使用 BitStreamReader 解析
        byte[] slice = Arrays.copyOfRange(data, offset, data.length);
        ByteBufferBitInput input = new ByteBufferBitInput(ByteBuffer.wrap(slice));
        BitStreamReader reader = new BitStreamReader(input, DwgVersion.R2000);

        try {
            // 读 MS
            int ms = reader.readModularShort();
            System.out.printf("  MS (object size): %d (0x%x)%n", ms, ms);

            // 读 BS (type)
            int bs = reader.readBitShort();
            System.out.printf("  BS (type code): %d (0x%03x)%n", bs, bs);

            System.out.printf("  当前位位置: %d bits = %d bytes%n",
                reader.position(), reader.position() / 8);

            // 尝试读 bitsize (RL = 32 bits)
            try {
                int bitsize = 0;
                for (int i = 0; i < 4; i++) {
                    bitsize |= (reader.getInput().readBits(8) & 0xFF) << (i * 8);
                }
                System.out.printf("  bitsize (RL): %d%n", bitsize);
            } catch (Exception e) {
                System.out.printf("  bitsize: 错误 %s%n", e.getMessage());
            }

            // 尝试读 handle
            try {
                long handle = reader.readHandle();
                System.out.printf("  handle (H): 0x%X%n", handle);
            } catch (Exception e) {
                System.out.printf("  handle: 错误 %s%n", e.getMessage());
            }

            // 尝试读 EED size
            try {
                int eedSize = reader.readBitShort();
                System.out.printf("  eedSize (BS): %d%n", eedSize);
            } catch (Exception e) {
                System.out.printf("  eedSize: 错误 %s%n", e.getMessage());
            }

            // 尝试读取可能的 block_name
            try {
                String text = reader.readText();
                System.out.printf("  text (T): '%s'%n", text);
            } catch (Exception e) {
                System.out.printf("  text: 错误 %s%n", e.getMessage());
            }

        } catch (Exception e) {
            System.out.println("解析错误: " + e);
        }

        System.out.println();
    }
}
