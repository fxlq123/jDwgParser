package run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 深入分析 R2018 对象数据区域
 */
public class R2018ObjectDataAnalyzer {

    public static void main(String[] args) throws IOException {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件: " + path + " (大小: " + data.length + ")");

        // 1. 分析 0x1fb980 附近的 "Block" 字符串区域
        analyzeBlockNameArea(data, 0x1fb980);

        // 2. 分析文件末尾 section 表 - 解析 0x213D0D 后的结构
        analyzeEndSectionTable(data);

        // 3. 分析 header 中可能的偏移指针
        analyzeHeaderPointers(data);

        // 4. 尝试找到所有 "AcDb:Objects" 或类似字符串
        findSectionNames(data);
    }

    private static void analyzeBlockNameArea(byte[] data, int offset) {
        System.out.println("\n=== 'Block' 字符串区域 @ 0x" + Integer.toHexString(offset) + " ===");

        int start = Math.max(0, offset - 256);
        int end = Math.min(data.length, offset + 512);

        for (int i = start; i < end; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < end; j++) {
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                char c = (char) b;
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            String marker = (i <= offset && i + 16 > offset) ? " <-- Block" : "";
            System.out.printf("  0x%06X: %-48s |%s|%s%n", i, hex, ascii, marker);
        }

        // 尝试解析字符串 - 向后查找
        System.out.println("\n--- 解析附近的 UTF-16 字符串 ---");
        for (int i = Math.max(0, offset - 100); i < offset + 200; i++) {
            // 尝试找到可读字符串
            StringBuilder sb = new StringBuilder();
            int len = 0;
            for (int j = i; j < Math.min(i + 200, data.length - 1); j += 2) {
                char c = (char)((data[j] & 0xFF) | ((data[j+1] & 0xFF) << 8));
                if (c >= 32 && c < 127) {
                    sb.append(c);
                    len++;
                } else {
                    break;
                }
            }
            if (len >= 5) {
                System.out.println("  @0x" + Integer.toHexString(i) + ": '" + sb.toString() + "'");
                i += len * 2;
            }
        }
    }

    private static void analyzeEndSectionTable(byte[] data) {
        System.out.println("\n=== 文件末尾 Section 表分析 ===");

        // 从 0x213D0D 开始分析 - 看起来像 section 条目表
        // 分析模式: 看起来是 [type] 5C [size] 01 [offset-value]
        // 让我更仔细地看看结构

        int start = 0x213D00;
        int end = Math.min(data.length, start + 512);

        System.out.println("\n原始字节:");
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

        // 尝试解析文件末尾的 "Entry" 表
        // 观察到的模式: XX 5C YY 01 ZZZZ 00 00
        // 让我们从 0x213D3D 开始解析
        System.out.println("\n--- 尝试解析 Entry 表 (0x213D3D 开始) ---");

        int tableStart = 0x213D3D;
        int pos = tableStart;

        while (pos < data.length - 8) {
            // 尝试读取条目: [index LE16] 5C [flags LE16] 01 [LE32 offset/size]
            try {
                int entryType = data[pos] & 0xFF;
                int b1 = data[pos+1] & 0xFF;
                int b2 = data[pos+2] & 0xFF;
                int b3 = data[pos+3] & 0xFF;

                // 如果找到 "5C" (反斜杠) 作为分隔符
                if (b1 == 0x5C) {
                    int index = entryType;
                    int flags = b2 | (b3 << 8);

                    // 读取下一个 32-bit 值
                    long value = ((long)(data[pos+4] & 0xFF)) |
                                 ((long)(data[pos+5] & 0xFF) << 8) |
                                 ((long)(data[pos+6] & 0xFF) << 16) |
                                 ((long)(data[pos+7] & 0xFF) << 24);

                    if (index < 100 && flags < 100) {
                        System.out.printf("  Entry %d: flags=0x%04X, value=%d (0x%08X) @0x%06X%n",
                            index, flags, value, value, pos);
                        pos += 8;
                        continue;
                    }
                }
            } catch (Exception e) {
                break;
            }
            pos++;
        }
    }

    private static void analyzeHeaderPointers(byte[] data) {
        System.out.println("\n=== Header 指针分析 ===");

        // R2018 格式的 Header 结构 (根据 OpenDesign spec)
        // 0x00-0x05: "AC1032"
        // 之后可能包含: section 偏移/大小表

        // 让我们查看 header (0x00 - 0x100) 的结构化数据
        System.out.println("\nHeader 区域 (0x00 - 0x100):");

        // 尝试不同的字段宽度解析
        // 从 0x08 开始，读取可能的 LE32 对 (offset, size)
        System.out.println("\n从 0x08 开始按 LE32 对解析 (offset, size):");
        for (int i = 0x08; i < 0x80; i += 8) {
            long offset = readLE32(data, i);
            long size = readLE32(data, i + 4);
            if (offset > 0 && offset < data.length && size > 0 && size < 1000000) {
                System.out.printf("  @0x%02X: offset=%d (0x%08X), size=%d (0x%08X)%n",
                    i, offset, offset, size, size);

                // 打印这个偏移处的数据
                int off = (int) offset;
                System.out.print("    数据: ");
                for (int j = 0; j < 32 && off + j < data.length; j++) {
                    System.out.printf("%02X ", data[off + j] & 0xFF);
                }
                System.out.println();
            }
        }

        // 检查 header 中可能的 section 计数
        System.out.println("\nHeader 作为 LE16 数组解析:");
        for (int i = 0x06; i < 0x80; i += 2) {
            int val = (data[i] & 0xFF) | ((data[i+1] & 0xFF) << 8);
            if (val > 0 && val < 100) {
                System.out.printf("  @0x%02X: %d (0x%04X)%n", i, val, val);
            }
        }
    }

    private static void findSectionNames(byte[] data) {
        System.out.println("\n=== 搜索所有 AcDb 开头的 section 名称 ===");

        String[] knownSections = {
            "AcDb:AcDbObjects", "AcDb:AcDbClasses", "AcDb:AcDbHandles",
            "AcDb:Header", "AcDb:SummaryInfo", "AcDb:Preview",
            "AcDb:AppInfo", "AcDb:AcDsPrototype",
            "Objects", "Classes", "Handles", "Header",
            "Block", "INSERT", "LINE", "CIRCLE"
        };

        for (String section : knownSections) {
            int[] offsets = findAllOccurrences(data, section.getBytes());
            if (offsets.length > 0) {
                System.out.println("'" + section + "' 找到 " + offsets.length + " 处:");
                for (int off : offsets) {
                    System.out.printf("  @0x%06X%n", off);
                }
            }
        }

        // 搜索 "AcDb" 前缀并尝试读取整个 section 名称
        int[] acDbOffsets = findAllOccurrences(data, "AcDb".getBytes());
        if (acDbOffsets.length > 0) {
            System.out.println("\n--- 完整 AcDb* 名称解析 ---");
            for (int offset : acDbOffsets) {
                StringBuilder sb = new StringBuilder();
                for (int i = offset; i < Math.min(offset + 100, data.length); i++) {
                    byte b = data[i];
                    if (b >= 32 && b < 127) {
                        sb.append((char) b);
                    } else if (b == 0) {
                        break;
                    } else {
                        sb.append('.');
                        if (i - offset > 20) break;
                    }
                }
                System.out.println("  @0x" + Integer.toHexString(offset) + ": '" + sb.toString() + "'");
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

    private static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) |
               ((long)(data[offset+1] & 0xFF) << 8) |
               ((long)(data[offset+2] & 0xFF) << 16) |
               ((long)(data[offset+3] & 0xFF) << 24);
    }
}
