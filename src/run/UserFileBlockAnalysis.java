package run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 深度分析用户上传的 R2000 (AC1015) DWG 文件
 * 重点: 提取 block 定义和引用
 *
 * R2000 文件结构特点:
 * - 无 Reed-Solomon 编码 (R2007 才引入)
 * - section 通过 header 中的偏移表定位
 * - 对象数据在实体/对象 section 中
 * - block 相关实体: BLOCK_HEADER (类型: 0x0A/9), INSERT (类型: 0x3C/51)
 */
public class UserFileBlockAnalysis {

    // R2000 常见对象类型 (DXF 类型代码)
    static final int TYPE_BLOCK_HEADER = 9;    // BLOCK_HEADER
    static final int TYPE_BLOCK_END = 8;       // BLOCK_END/ENDBLK
    static final int TYPE_INSERT = 51;          // INSERT (块引用)

    public static void main(String[] args) throws IOException {
        String path = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件: " + path);
        System.out.println("大小: " + data.length + " 字节");

        // 1. 分析 R2000 header 中的 section 指针
        analyzeR2000Sections(data);

        // 2. 分析 block 名称和定义区域
        analyzeBlockDefinitions(data);

        // 3. 查找所有 block 名称字符串
        findAllBlockNames(data);

        // 4. 搜索 INSERT 实体数据
        searchInsertEntities(data);

        // 5. 生成块使用统计
        generateBlockUsageReport(data);
    }

    private static void analyzeR2000Sections(byte[] data) {
        System.out.println("\n=== R2000 Header Section 分析 ===");

        // R2000 格式的 header 字段 (根据 OpenDesign 文档)
        // 0x00-0x05: "AC1015"
        // 0x06-0x0B: 填充 (0x00 00 00 00 00 06 01 DC 00 00)
        // 0x0C+: 各种指针

        // 打印 header 的 LE32 字段解释
        System.out.println("\nHeader 中的 LE32 潜在偏移值:");
        for (int offset = 0x06; offset < 0x80; offset += 2) {
            long le16 = (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8);
            long le32 = readLE32(data, offset);

            if (le32 > 0x100 && le32 < data.length) {
                System.out.printf("  @0x%04X: LE32=%d (0x%08X) - 可能指向实体数据%n",
                    offset, le32, le32);
            }
        }

        // 打印 0x50-0x60 区域 (可能是重要的 section 指针)
        System.out.println("\n0x50-0x80 区域:");
        dumpHex(data, 0x50, 48);

        // 打印 0xA0-0xC0 区域 (可能包含更多 section 信息)
        System.out.println("\n0xA0-0xC0 区域:");
        dumpHex(data, 0xA0, 32);
    }

    private static void analyzeBlockDefinitions(byte[] data) {
        System.out.println("\n=== Block 定义分析 ===");

        // 查找所有 block 名称字符串 (ASCII)
        // Block 名称通常出现在 BLOCK_HEADER 实体中
        String[] blockNames = {
            "*Model_Space", "*Paper_Space", "*Paper_Space0",
            "SW_NOTE_0", "SW_NOTE_0_1", "SW_SFSYMBOL_0",
            "SW_TABLEANNOTATION_0", "SW_CENTERMARKSYMBOL_0",
            "SW_CENTERMARKSYMBOL_1", "SW_CHAMFER_DIMENSION_0",
            "SW_CHAMFER_DIMENSION_1", "SW_CHAMFER_DIMENSION_2",
            "SW_CENTERMARKSYMBOL_2"
        };

        for (String name : blockNames) {
            int[] occurrences = findAllOccurrences(data, name.getBytes());
            if (occurrences.length > 0) {
                System.out.println("\n'" + name + "' 找到 " + occurrences.length + " 处:");
                for (int i = 0; i < Math.min(3, occurrences.length); i++) {
                    int off = occurrences[i];
                    System.out.printf("  @0x%06X - 前后 32 字节:%n", off);
                    System.out.print("    ");
                    for (int j = Math.max(0, off - 16); j < Math.min(off + 48, data.length); j++) {
                        System.out.printf("%02X ", data[j] & 0xFF);
                    }
                    System.out.println();
                }
            }
        }
    }

    private static void findAllBlockNames(byte[] data) {
        System.out.println("\n=== 查找所有以 SW_ 开头的 block 名称 ===");

        // 搜索 "SW_" 前缀
        int[] swOffsets = findAllOccurrences(data, "SW_".getBytes());
        if (swOffsets.length > 0) {
            System.out.println("找到 " + swOffsets.length + " 处 'SW_':");
            for (int off : swOffsets) {
                // 读取完整字符串
                StringBuilder sb = new StringBuilder();
                int i = off;
                while (i < data.length && (data[i] >= 32 && data[i] < 127)) {
                    sb.append((char) data[i]);
                    i++;
                    if (sb.length() > 50) break;
                }
                System.out.println("  @0x" + Integer.toHexString(off) + ": '" + sb.toString() + "'");
            }
        }

        // 搜索其他可能的 block 名称
        System.out.println("\n=== 文件中所有可读长字符串 (>= 8 字符) ===");
        int count = 0;
        for (int i = 0x4000; i < data.length - 10; i++) {
            if (data[i] >= 32 && data[i] < 127) {
                StringBuilder sb = new StringBuilder();
                int start = i;
                int len = 0;
                while (i < data.length && data[i] >= 32 && data[i] < 127) {
                    sb.append((char) data[i]);
                    i++;
                    len++;
                    if (len > 100) break;
                }
                // 过滤掉看起来像随机字符串的内容 (包含太多大写字母+数字混合)
                String s = sb.toString();
                if (len >= 8 && count < 80) {
                    // 检查是否是有意义的字符串 (包含大写字母小写字母混合或特定模式)
                    if (s.matches("[A-Z][a-z_0-9]+") || s.contains("_") ||
                        s.matches("[A-Z0-9_]+") || s.equals("Standard") ||
                        s.equals("Continuous") || s.equals("CENTER") ||
                        s.equals("HIDDEN") || s.equals("ByLayer") || s.equals("ByBlock")) {
                        System.out.println("  @0x" + Integer.toHexString(start) + ": '" + s + "'");
                        count++;
                    }
                }
            }
        }
    }

    private static void searchInsertEntities(byte[] data) {
        System.out.println("\n=== 搜索 INSERT 实体 (块引用) ===");

        // 在 R2000 格式中:
        // INSERT 实体类型代码 = 0x3C (51) 或通过 DXF 组码识别
        // 实际上 DWG 对象的前几位包含类型码

        // 让我分析对象数据区域 (通常从 header 偏移指向的位置开始)
        // 搜索所有看起来像实体的数据块
        int objectAreaStart = 0x4000; // 对象数据通常从 0x4000 之后开始
        int foundCount = 0;

        // 搜索所有包含 block 名称的实体
        // 实际上 block 名称是字符串，我们找字符串附近的结构
        int[] modelSpaceOffsets = findAllOccurrences(data, "*Model_Space".getBytes());
        int[] paperSpaceOffsets = findAllOccurrences(data, "*Paper_Space".getBytes());

        System.out.println("*Model_Space 位置:");
        for (int off : modelSpaceOffsets) {
            System.out.printf("  @0x%06X (前 32 字节 + 后 32 字节)%n", off);
            System.out.print("    ");
            for (int j = Math.max(0, off - 32); j < Math.min(off + 32, data.length); j++) {
                System.out.printf("%02X ", data[j] & 0xFF);
            }
            System.out.println();
        }

        // 分析 BLOCK 名称字符串附近的数据区域
        System.out.println("\n=== 分析 BLOCK 名称前后数据结构 ===");
        for (String name : new String[]{"SW_NOTE_0", "SW_SFSYMBOL_0", "SW_TABLEANNOTATION_0"}) {
            int[] offsets = findAllOccurrences(data, name.getBytes());
            for (int off : offsets) {
                System.out.println("\n'" + name + "' @ 0x" + Integer.toHexString(off));
                // 打印前后 64 字节
                System.out.println("  前:");
                for (int j = Math.max(0, off - 64); j < off; j += 16) {
                    StringBuilder hex = new StringBuilder();
                    StringBuilder ascii = new StringBuilder();
                    for (int k = 0; k < 16 && j + k < off; k++) {
                        int b = data[j + k] & 0xFF;
                        hex.append(String.format("%02X ", b));
                        ascii.append((b >= 32 && b < 127) ? (char) b : '.');
                    }
                    System.out.printf("    0x%06X: %s |%s|%n", j, hex.toString(), ascii.toString());
                }
                System.out.println("  后:");
                for (int j = off; j < Math.min(off + 96, data.length); j += 16) {
                    StringBuilder hex = new StringBuilder();
                    StringBuilder ascii = new StringBuilder();
                    for (int k = 0; k < 16 && j + k < data.length; k++) {
                        int b = data[j + k] & 0xFF;
                        hex.append(String.format("%02X ", b));
                        ascii.append((b >= 32 && b < 127) ? (char) b : '.');
                    }
                    System.out.printf("    0x%06X: %s |%s|%n", j, hex.toString(), ascii.toString());
                }
            }
        }
    }

    private static void generateBlockUsageReport(byte[] data) {
        System.out.println("\n=== Block 使用情况报告 ===");

        // 统计每个 block 名称出现的次数
        java.util.LinkedHashMap<String, Integer> blockCounts = new java.util.LinkedHashMap<>();

        String[] blocks = {
            "*Model_Space", "*Paper_Space",
            "SW_NOTE_0", "SW_NOTE_0_1", "SW_SFSYMBOL_0",
            "SW_TABLEANNOTATION_0", "SW_CENTERMARKSYMBOL_0",
            "SW_CENTERMARKSYMBOL_1", "SW_CENTERMARKSYMBOL_2",
            "SW_CHAMFER_DIMENSION_0", "SW_CHAMFER_DIMENSION_1",
            "SW_CHAMFER_DIMENSION_2"
        };

        System.out.println("Block 名称出现次数:");
        for (String b : blocks) {
            int count = countOccurrences(data, b.getBytes());
            blockCounts.put(b, count);
            System.out.printf("  %-40s : %d 次%n", b, count);
        }

        // 检查文件末尾的数据 (通常是实体数据结束位置)
        System.out.println("\n文件末尾 (最后 256 字节):");
        dumpHex(data, data.length - 256, 256);

        // 查找文件中所有字符串位置以了解数据分布
        System.out.println("\n文件中主要字符串位置分布:");
        for (String b : blocks) {
            int[] offsets = findAllOccurrences(data, b.getBytes());
            if (offsets.length > 0) {
                System.out.print("  '" + b + "': @ ");
                for (int o : offsets) {
                    System.out.print("0x" + Integer.toHexString(o) + " ");
                }
                System.out.println();
            }
        }

        // 总结报告
        System.out.println("\n==================== 报告摘要 ====================");
        System.out.println("文件版本: AC1015 (AutoCAD 2000)");
        System.out.println("文件大小: " + data.length + " 字节");
        System.out.println("Block 定义数量: " + blockCounts.size());
        System.out.println("注意: 这是 SolidWorks 导出的 DWG 文件");
        System.out.println("块命名模式: SW_<类型>_<序号>");
        System.out.println("  - SW_NOTE: 注释/标注块");
        System.out.println("  - SW_SFSYMBOL: 表面粗糙度符号块");
        System.out.println("  - SW_TABLEANNOTATION: 表格注释块");
        System.out.println("  - SW_CENTERMARKSYMBOL: 中心标记块");
        System.out.println("  - SW_CHAMFER_DIMENSION: 倒角尺寸块");
    }

    // 辅助方法
    private static int[] findAllOccurrences(byte[] data, byte[] pattern) {
        java.util.List<Integer> results = new java.util.ArrayList<>();
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if ((data[i+j] & 0xFF) != (pattern[j] & 0xFF)) {
                    match = false;
                    break;
                }
            }
            if (match) results.add(i);
        }
        return results.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int countOccurrences(byte[] data, byte[] pattern) {
        int count = 0;
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if ((data[i+j] & 0xFF) != (pattern[j] & 0xFF)) {
                    match = false;
                    break;
                }
            }
            if (match) count++;
        }
        return count;
    }

    private static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) |
               ((long)(data[offset+1] & 0xFF) << 8) |
               ((long)(data[offset+2] & 0xFF) << 16) |
               ((long)(data[offset+3] & 0xFF) << 24);
    }

    private static void dumpHex(byte[] data, int offset, int length) {
        int end = Math.min(data.length, offset + length);
        for (int i = offset; i < end; i += 16) {
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
}
