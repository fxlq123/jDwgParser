package run;

import java.nio.file.Paths;

/**
 * 检查块名字符串前后的字节，理解 R2000 BLOCK_HEADER 中的字段编码
 */
public class AnalyzeBlockNameFormat {
    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 关键位置:
        // *Paper_Space @ 0x548f (len=12)
        // *Model_Space @ 0x5a82 (len=12)
        // SW_NOTE_0 @ 0x54b8 (len=9)
        // SW_CENTERMARKSYMBOL_0 @ 0x5668 (len=21)
        // SW_CHAMFER_DIMENSION_0 @ 0x5968 (len=22)

        int[] positions = {
            0x548f,  // *Paper_Space
            0x5a82,  // *Model_Space  
            0x54b8,  // SW_NOTE_0
            0x5668,  // SW_CENTERMARKSYMBOL_0
            0x5968,  // SW_CHAMFER_DIMENSION_0
        };

        String[] names = { "*Paper_Space", "*Model_Space", "SW_NOTE_0", "SW_CENTERMARKSYMBOL_0", "SW_CHAMFER_DIMENSION_0" };

        for (int i = 0; i < positions.length; i++) {
            analyzePosition(data, positions[i], names[i]);
        }

        // 还检查 BLOCK_HEADER 对象开始处（之前找到的 0x52b9）
        System.out.println("\n=== BLOCK_HEADER 对象 @ 0x52b9 ===\n");
        printBytesAround(data, 0x52b9, 120);
    }

    static void analyzePosition(byte[] data, int pos, String name) {
        System.out.println("=== 字符串 '" + name + "' @ 0x" + Integer.toHexString(pos) + " ===");

        // 打印字符串前 32 字节和后 32 字节
        int start = Math.max(0, pos - 32);
        int end = Math.min(data.length, pos + name.length() + 32);

        System.out.print("\n  ");
        for (int i = start; i < end; i++) {
            int b = data[i] & 0xFF;
            System.out.printf("%02X ", b);
            if ((i - start + 1) % 16 == 0) System.out.print("  ");
            if ((i - start + 1) % 32 == 0) System.out.print("\n  ");
        }
        System.out.println();

        System.out.print("  ");
        for (int i = start; i < end; i++) {
            int b = data[i] & 0xFF;
            char c = (b >= 32 && b < 127) ? (char)b : '.';
            System.out.print(c + "  ");
            if ((i - start + 1) % 16 == 0) System.out.print("  ");
            if ((i - start + 1) % 32 == 0) System.out.print("\n  ");
        }
        System.out.println();

        // 标记字符串开始位置
        System.out.print("  ");
        for (int i = start; i < pos; i++) System.out.print("   ");
        System.out.println("^ (byte " + pos + " = 0x" + Integer.toHexString(pos) + ")");

        // 检查字符串前的字节（可能是长度前缀）
        System.out.println("\n  字符串前的 8 字节:");
        for (int j = 8; j >= 1; j--) {
            if (pos - j >= 0) {
                int b = data[pos - j] & 0xFF;
                System.out.printf("    -%d: 0x%02X (%d) '%s'%n", j, b, b,
                    (b >= 32 && b < 127) ? (char)b : ".");
            }
        }

        // 字符串长度及前后字节
        System.out.println("\n  字符串长度: " + name.length());
        System.out.println("  (pos - name.length()) = 0x" + Integer.toHexString(pos - name.length()));
        System.out.println("  pos + name.length() = 0x" + Integer.toHexString(pos + name.length()));

        // 检查是否为 length(BS) + text 格式
        // BS: 2 bits opcode + 8 bits for value = 10 bits total, or 2+16
        // 从 pos - 2 开始读取可能的 BS
        System.out.println("\n  检查是否为 BS(length) + ASCII 格式:");
        for (int skip = 1; skip <= 5; skip++) {
            int lengthBytePos = pos - skip;
            if (lengthBytePos >= 0) {
                int possibleLen = data[lengthBytePos] & 0xFF;
                if (possibleLen == name.length()) {
                    System.out.printf("    跳过 %d 字节: 0x%02X == 字符串长度 %d ✓%n",
                        skip, possibleLen, name.length());
                } else if (possibleLen > 0 && possibleLen < 64) {
                    // 也检查前面的字节
                    System.out.printf("    跳过 %d 字节: 0x%02X = %d (字符串长度: %d)%n",
                        skip, possibleLen, name.length());
                }
            }
        }

        // 尝试理解是否为 "MSB-first variable length" 或其他编码
        System.out.println("\n  检查字符串前字节的二进制:");
        for (int j = 4; j >= 1; j--) {
            if (pos - j >= 0) {
                int b = data[pos - j] & 0xFF;
                String binary = String.format("%8s", Integer.toBinaryString(b)).replace(' ', '0');
                System.out.printf("    -%d: 0x%02X = %s (bits)%n", j, b, binary);
            }
        }

        System.out.println();
    }

    static void printBytesAround(byte[] data, int start, int length) {
        int end = Math.min(data.length, start + length);
        
        for (int i = start; i < end; i++) {
            if ((i - start) % 16 == 0) {
                System.out.printf("  0x%04X: ", i);
            }
            int b = data[i] & 0xFF;
            System.out.printf("%02X ", b);
            if ((i - start + 1) % 16 == 0) {
                // ASCII interpretation
                System.out.print(" ");
                for (int j = i - 15; j <= i; j++) {
                    int c = data[j] & 0xFF;
                    System.out.print((c >= 32 && c < 127) ? (char)c : '.');
                }
                System.out.println();
            }
        }
        System.out.println();
    }
}
