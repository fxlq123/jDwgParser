package run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 分析 R2018 的 section 结构
 * 重点: 理解对象数据的真实位置和格式
 */
public class R2018SectionAnalysis {

    public static void main(String[] args) throws IOException {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件: " + path);
        System.out.println("大小: " + data.length + " 字节");

        // 1. 详细查看 header 字段
        analyzeHeaderFields(data);

        // 2. 查看文件中 "AcDb:" 标记附近的结构
        analyzeAcDbAreas(data);

        // 3. 查看 sentinel 附近结构
        analyzeSentinelArea(data, 0x212302);

        // 4. 查看文件末尾结构 (可能是对象表/句柄表)
        analyzeEndOfFile(data);

        // 5. 查找块名称 "Block1" 等
        findBlockNames(data);
    }

    private static void analyzeHeaderFields(byte[] data) {
        System.out.println("\n=== Header 字段详细分析 ===");

        // 0x00: AC1032 (6字节)
        System.out.println("0x00-0x05: '" + new String(data, 0, 6) + "' (版本)");

        // 分析后续字段
        System.out.println("\n按不同偏移量解析:");
        for (int offset = 6; offset < 0x80; offset++) {
            // 查看每个位置是否是合理值
            long le32 = readLE32(data, offset);
            long le64 = readLE64(data, offset);

            // 只打印看起来合理的值
            if (le32 > 100 && le32 < data.length) {
                System.out.printf("  @0x%04X: LE32=%d (0x%08X) - 可能是文件偏移%n",
                    offset, le32, le32);
            }
            if (le64 > 100 && le64 < data.length && offset % 8 == 0) {
                System.out.printf("  @0x%04X: LE64=%d (0x%016X) - 可能是文件偏移%n",
                    offset, le64, le64);
            }
        }

        // 具体查看已知重要位置
        System.out.println("\n重要字段位置:");
        System.out.printf("  @0x0A: LE16=%d (0x%04X)%n", readLE16(data, 0x0A), readLE16(data, 0x0A));
        System.out.printf("  @0x0C: LE32=%d (0x%08X)%n", readLE32(data, 0x0C), readLE32(data, 0x0C));
        System.out.printf("  @0x10: LE32=%d (0x%08X)%n", readLE32(data, 0x10), readLE32(data, 0x10));
        System.out.printf("  @0x14: LE32=%d (0x%08X)%n", readLE32(data, 0x14), readLE32(data, 0x14));
        System.out.printf("  @0x20: LE32=%d (0x%08X)%n", readLE32(data, 0x20), readLE32(data, 0x20));
        System.out.printf("  @0x28: LE32=%d (0x%08X)%n", readLE32(data, 0x28), readLE32(data, 0x28));
        System.out.printf("  @0x2C: LE32=%d (0x%08X)%n", readLE32(data, 0x2C), readLE32(data, 0x2C));
        System.out.printf("  @0x30: LE32=%d (0x%08X)%n", readLE32(data, 0x30), readLE32(data, 0x30));
        System.out.printf("  @0x34: LE32=%d (0x%08X)%n", readLE32(data, 0x34), readLE32(data, 0x34));
        System.out.printf("  @0x38: LE32=%d (0x%08X)%n", readLE32(data, 0x38), readLE32(data, 0x38));
        System.out.printf("  @0x3C: LE32=%d (0x%08X)%n", readLE32(data, 0x3C), readLE32(data, 0x3C));
    }

    private static void analyzeAcDbAreas(byte[] data) {
        System.out.println("\n=== 'AcDb' 标记附近的结构 ===");

        // 已知的 AcDb 位置
        int[] acDbOffsets = findAllOccurrences(data, "AcDb".getBytes());

        for (int offset : acDbOffsets) {
            System.out.println("\nAcDb 标记 @ 0x" + Integer.toHexString(offset));

            // 查看前面的 32 字节
            int contextStart = Math.max(0, offset - 32);
            int contextEnd = Math.min(data.length, offset + 256);

            for (int i = contextStart; i < contextEnd; i += 16) {
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int j = 0; j < 16 && i + j < contextEnd; j++) {
                    int b = data[i + j] & 0xFF;
                    hex.append(String.format("%02X ", b));
                    char c = (char) b;
                    ascii.append((c >= 32 && c < 127) ? c : '.');
                }
                String marker = (i <= offset && i + 16 > offset) ? " <--" : "";
                System.out.printf("  0x%06X: %-48s |%s|%s%n", i, hex, ascii, marker);
            }
        }
    }

    private static void analyzeSentinelArea(byte[] data, int sentinelOffset) {
        System.out.println("\n=== Section Sentinel @ 0x" + Integer.toHexString(sentinelOffset) + " ===");

        int start = Math.max(0, sentinelOffset - 64);
        int end = Math.min(data.length, sentinelOffset + 512);

        for (int i = start; i < end; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < end; j++) {
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                char c = (char) b;
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            String marker = (i <= sentinelOffset && i + 16 > sentinelOffset) ? " <-- sentinel" : "";
            System.out.printf("  0x%06X: %-48s |%s|%s%n", i, hex, ascii, marker);
        }
    }

    private static void analyzeEndOfFile(byte[] data) {
        System.out.println("\n=== 文件末尾结构 (最后 1024 字节) ===");

        int start = Math.max(0, data.length - 1024);
        for (int i = start; i < data.length; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                char c = (char) b;
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            System.out.printf("  0x%06X: %-48s |%s|%n", i, hex, ascii);
        }
    }

    private static void findBlockNames(byte[] data) {
        System.out.println("\n=== 查找潜在的块名称字符串 ===");

        // 查找 ASCII 字符串 "Block"
        int[] blockOffsets = findAllOccurrences(data, "Block".getBytes());
        for (int offset : blockOffsets) {
            System.out.println("\n'Block' @ 0x" + Integer.toHexString(offset));
            int start = Math.max(0, offset - 16);
            int end = Math.min(data.length, offset + 128);
            for (int i = start; i < end; i += 16) {
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int j = 0; j < 16 && i + j < end; j++) {
                    int b = data[i + j] & 0xFF;
                    hex.append(String.format("%02X ", b));
                    char c = (char) b;
                    ascii.append((c >= 32 && c < 127) ? c : '.');
                }
                System.out.printf("  0x%06X: %-48s |%s|%n", i, hex, ascii);
            }
        }

        // 查找 UTF-16LE "Block" (B=0x42 l=0x6C o=0x6F c=0x63 k=0x6B)
        byte[] utf16Block = new byte[]{0x42, 0x00, 0x6C, 0x00, 0x6F, 0x00, 0x63, 0x00, 0x6B, 0x00};
        int[] utf16Offsets = findAllOccurrences(data, utf16Block);
        if (utf16Offsets.length > 0) {
            System.out.println("\n找到 UTF-16 'Block' 字符串:");
            for (int offset : utf16Offsets) {
                System.out.println("  @ 0x" + Integer.toHexString(offset));
            }
        }

        // 查找 "*Model_Space" 和 "*Paper_Space"
        String[] testNames = {"*Model", "*Paper", "Model", "Paper"};
        for (String name : testNames) {
            int[] offsets = findAllOccurrences(data, name.getBytes());
            if (offsets.length > 0) {
                System.out.println("\n'" + name + "' 找到 " + offsets.length + " 处:");
                for (int i = 0; i < Math.min(5, offsets.length); i++) {
                    System.out.println("  @ 0x" + Integer.toHexString(offsets[i]));
                }
            }
        }
    }

    private static int[] findAllOccurrences(byte[] data, byte[] pattern) {
        java.util.List<Integer> results = new java.util.ArrayList<>();
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) { match = false; break; }
            }
            if (match) results.add(i);
        }
        return results.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int readLE16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8);
    }

    private static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) |
               ((long)(data[offset+1] & 0xFF) << 8) |
               ((long)(data[offset+2] & 0xFF) << 16) |
               ((long)(data[offset+3] & 0xFF) << 24);
    }

    private static long readLE64(byte[] data, int offset) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long)(data[offset+i] & 0xFF)) << (i * 8);
        }
        return result;
    }
}
