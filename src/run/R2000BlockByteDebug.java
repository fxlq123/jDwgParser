package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;

/**
 * 字节级检查 BLOCK_HEADER 对象
 */
public class R2000BlockByteDebug {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 从前面的解析结果：
        // BLOCK_HEADER type=0x05 有36个
        // 选几个代表性的来检查
        long[] blockHeaderOffsets = {
            0x111C1, // handle 0x1D
            0x1118C, // handle 0x21
            0x6F0C,  // handle 0x25
            0x705B,  // handle 0xD0
            0x71EF,  // handle 0xFD
        };

        for (long offset : blockHeaderOffsets) {
            analyzeObjectAt(data, (int) offset);
        }
    }

    private static void analyzeObjectAt(byte[] data, int offset) {
        System.out.println();
        System.out.println("==============================================================");
        System.out.println("  对象偏移: 0x" + Integer.toHexString(offset) + " (" + offset + ")");
        System.out.println("==============================================================");

        // 先输出原始字节
        int rawSize = 128;
        System.out.println();
        System.out.println("  原始字节 (前" + rawSize + "字节):");
        for (int i = 0; i < Math.min(rawSize, data.length - offset); i++) {
            if (i % 16 == 0) {
                System.out.printf("    +%03X: ", i);
            }
            int b = data[offset + i] & 0xFF;
            System.out.printf("%02X ", b);
            if (i % 16 == 15) {
                // ASCII
                System.out.print(" |");
                for (int j = i - 15; j <= i; j++) {
                    char c = (char) (data[offset + j] & 0xFF);
                    System.out.print((c >= 32 && c < 127) ? c : '.');
                }
                System.out.println("|");
            }
        }
        if (Math.min(rawSize, data.length - offset) % 16 != 0) {
            int remaining = 16 - (Math.min(rawSize, data.length - offset) % 16);
            for (int i = 0; i < remaining; i++) System.out.print("   ");
            System.out.print(" |");
            int end = Math.min(rawSize, data.length - offset);
            for (int j = end - (end % 16); j < end; j++) {
                char c = (char) (data[offset + j] & 0xFF);
                System.out.print((c >= 32 && c < 127) ? c : '.');
            }
            System.out.println("|");
        }

        // 位级解析
        System.out.println();
        System.out.println("  位级渐进解析:");

        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            bbuf.seek((long) offset * 8);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            // 1. obj size (MS)
            int objSize = r.readModularShort();
            System.out.println("  [1] objSize (MS): " + objSize + " (0x" + Integer.toHexString(objSize) + ")");
            System.out.println("      当前bit位置: " + bbuf.position() + " (= 字节 " + (bbuf.position() / 8) + ")");

            // 2. type code (BS)
            int typeCode = r.readBitShort();
            System.out.println("  [2] typeCode (BS): " + typeCode + " (0x" + Integer.toHexString(typeCode) + ")");
            System.out.println("      当前bit位置: " + bbuf.position() + " (= 字节 " + (bbuf.position() / 8) + ")");

            // 3. num reactors (BL)
            int numReactors = r.readBitLong();
            System.out.println("  [3] numReactors (BL): " + numReactors);
            System.out.println("      当前bit位置: " + bbuf.position() + " (= 字节 " + (bbuf.position() / 8) + ")");

            // 4. owner handle (H)
            long ownerHandle = r.readHandle();
            System.out.println("  [4] ownerHandle (H): 0x" + Long.toHexString(ownerHandle));
            System.out.println("      当前bit位置: " + bbuf.position() + " (= 字节 " + (bbuf.position() / 8) + ")");

            // 5. reactor handles
            for (int i = 0; i < numReactors; i++) {
                long rh = r.readHandle();
                System.out.println("  [5." + i + "] reactorHandle: 0x" + Long.toHexString(rh));
            }
            if (numReactors > 0) {
                System.out.println("      当前bit位置: " + bbuf.position() + " (= 字节 " + (bbuf.position() / 8) + ")");
            }

            // 现在我们需要找出：在 reactor handles 之后是什么？
            // 可能是：block name(T), flags(BS), basePoint(3BD), xrefPath(T)

            // 尝试读取文本
            long beforeText = bbuf.position();
            try {
                String text1 = r.readText();
                int len1 = text1.length();
                // 清理控制字符
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < text1.length(); i++) {
                    char c = text1.charAt(i);
                    if (c >= 32 && c < 127) sb.append(c);
                    else sb.append('?');
                }
                text1 = sb.toString();
                System.out.println("  [?] 尝试读取 Text1: len=" + len1 + " text='" + text1 + "'");
                System.out.println("      当前bit位置: " + bbuf.position() + " (= 字节 " + (bbuf.position() / 8) + ")");
            } catch (Exception e) {
                System.out.println("  [?] Text1 读取失败: " + e.getMessage());
                bbuf.seek(beforeText);
            }

            // 尝试在不同位置读取：先读 BS flags
            // 回到 ownerHandle 之后
            // 我们已经跳过了 reactor handles，现在看后面

            // 尝试直接读 BitDouble
            try {
                double bd1 = r.readBitDouble();
                System.out.println("  [?] 尝试读取 BD1: " + bd1);
            } catch (Exception e) {
                System.out.println("  [?] BD1 读取失败: " + e.getMessage());
            }

            // 让我们看看剩余的字节
            long currentByte = bbuf.position() / 8;
            System.out.println();
            System.out.println("  在 ownerHandle+reactors 之后剩余的字节:");
            int startByte = (int) currentByte;
            int endByte = Math.min(offset + objSize + 2, offset + 50);
            for (int i = startByte; i < endByte; i++) {
                if ((i - startByte) % 16 == 0) {
                    System.out.printf("    +%03X: ", i - offset);
                }
                int b = data[i] & 0xFF;
                System.out.printf("%02X ", b);
                if ((i - startByte) % 16 == 15) {
                    System.out.print(" |");
                    for (int j = i - 15; j <= i; j++) {
                        char c = (char) (data[j] & 0xFF);
                        System.out.print((c >= 32 && c < 127) ? c : '.');
                    }
                    System.out.println("|");
                }
            }

        } catch (Exception e) {
            System.out.println("  解析失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
