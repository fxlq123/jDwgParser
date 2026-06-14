package run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 解码 R2018 文件末尾的 section map
 *
 * 观察到的关键信息:
 * 1. 文件末尾 (0x213D00+) 有结构化数据
 * 2. 0x2136E9 处有 "AcDb:AcDsPrototype_1bx"
 * 3. 0x2137CF 处有 "Objects"
 * 4. 0x213CC5 处有 "Header"
 * 5. 0x1fb96a 处有 UTF-16 字符串 (编码对象区域)
 *
 * 这表明 R2018 格式:
 * - 对象数据分散在文件各处 (使用某种编码方式)
 * - 文件末尾有 section 索引/名称表
 * - Header 中有指向这些 section 的元数据
 */
public class R2018SectionMapDecode {

    public static void main(String[] args) throws IOException {
        String path = "samples/2018/Dynblocks.dwg";
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件: " + path + " (大小: " + data.length + " = 0x" +
            Integer.toHexString(data.length) + ")");

        // 1. 详细分析文件末尾 section 索引表
        analyzeEndSectionIndex(data);

        // 2. 分析 0x2137CF "Objects" 附近
        analyzeSectionNameArea(data, 0x2137CF, "Objects");

        // 3. 分析编码对象数据 - 找到 "Block" 字符串附近的数据
        analyzeEncodedObjectArea(data, 0x1fb96a);

        // 4. 找到更多可能的 section 数据位置
        findAllSectionDataOffsets(data);

        // 5. 查看 0x80 (header 指向的 offset)
        System.out.println("\n=== 0x0080 附近的数据 (header 指向) ===");
        dumpHex(data, 0x80, 128);
    }

    private static void analyzeEndSectionIndex(byte[] data) {
        System.out.println("\n=== 文件末尾 Section 索引表详细分析 ===");

        // 查看文件末尾的结构 (最后 4KB)
        int regionStart = 0x213000;
        int regionEnd = data.length;

        System.out.println("\n区域: 0x" + Integer.toHexString(regionStart) + " - 0x" +
            Integer.toHexString(regionEnd));

        // 找到可读字符串区域
        System.out.println("\n--- 可读 ASCII 字符串 ---");
        for (int i = regionStart; i < regionEnd; i++) {
            if (data[i] >= 32 && data[i] < 127) {
                // 开始读取字符串
                StringBuilder sb = new StringBuilder();
                int start = i;
                while (i < regionEnd && data[i] >= 32 && data[i] < 127) {
                    sb.append((char) data[i]);
                    i++;
                }
                if (sb.length() >= 3) {
                    System.out.println("  @0x" + Integer.toHexString(start) + ": '" + sb.toString() + "'");
                }
            }
        }

        // 分析 0x213D30+ 的 Entry 表
        System.out.println("\n--- Entry 表详细解析 (从 0x213D30 开始) ---");

        // 条目格式: [index LE16] [separator 5C] [flags/type LE16] [offset/size LE32]
        int pos = 0x213D30;
        while (pos < data.length - 8) {
            try {
                int field1 = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
                int field2 = (data[pos+2] & 0xFF) | ((data[pos+3] & 0xFF) << 8);
                long field3 = ((long)(data[pos+4] & 0xFF)) |
                             ((long)(data[pos+5] & 0xFF) << 8) |
                             ((long)(data[pos+6] & 0xFF) << 16) |
                             ((long)(data[pos+7] & 0xFF) << 24);

                // 如果 field2 的低字节是 0x5C ('\\')，这可能是一个分隔符
                // 格式可能是: [entry_num] 5C [type/flags] [value]

                // 检查是否看起来像有效的 entry
                if ((data[pos+1] == 0x5C) || (data[pos+2] == 0x5C)) {
                    // 0x5C 用作分隔符

                    int idx = data[pos] & 0xFF;
                    int flagByte = data[pos+2] & 0xFF;
                    int nextByte = data[pos+3] & 0xFF;

                    long value4 = 0;
                    for (int k = 0; k < 4 && pos + 4 + k < data.length; k++) {
                        value4 |= ((long)(data[pos + 4 + k] & 0xFF)) << (k * 8);
                    }

                    if (idx < 0x70 && flagByte < 0x20) {
                        System.out.printf("  @0x%06X: idx=%d, type=0x%02X, flag=0x%02X, value=%d (0x%08X)%n",
                            pos, idx, flagByte, nextByte, value4, value4);

                        // 检查这个 value 是否是有效的文件偏移
                        if (value4 > 0 && value4 < data.length) {
                            System.out.printf("    -> 检查 offset 0x%08X 处的数据:%n", value4);
                            int off = (int) value4;
                            System.out.print("       ");
                            for (int k = 0; k < 24 && off + k < data.length; k++) {
                                System.out.printf("%02X ", data[off + k] & 0xFF);
                            }
                            System.out.println();
                        }
                        pos += 8;
                        continue;
                    }
                }
                pos++;
            } catch (Exception e) {
                break;
            }
        }

        // 查看 0x213D00 附近的数据
        System.out.println("\n--- 0x213D00 附近的原始数据 ---");
        dumpHex(data, 0x213D00, 128);
    }

    private static void analyzeSectionNameArea(byte[] data, int offset, String expectedName) {
        System.out.println("\n=== '" + expectedName + "' section 名称区域 @ 0x" +
            Integer.toHexString(offset) + " ===");

        // 查看前后各 256 字节
        int start = Math.max(0, offset - 256);
        int end = Math.min(data.length, offset + 256);

        for (int i = start; i < end; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < end; j++) {
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                char c = (char) b;
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            String marker = (i <= offset && i + 16 > offset) ? " <-- " + expectedName : "";
            System.out.printf("  0x%06X: %-48s |%s|%s%n", i, hex, ascii, marker);
        }
    }

    private static void analyzeEncodedObjectArea(byte[] data, int stringOffset) {
        System.out.println("\n=== 编码对象数据分析 (字符串 @ 0x" +
            Integer.toHexString(stringOffset) + ") ===");

        // 先看看字符串前面的数据
        System.out.println("\n字符串前后的数据:");
        int start = Math.max(0, stringOffset - 256);
        int end = Math.min(data.length, stringOffset + 256);

        for (int i = start; i < end; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < end; j++) {
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                char c = (char) b;
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            String marker = (i <= stringOffset && i + 16 > stringOffset) ? " <-- str" : "";
            System.out.printf("  0x%06X: %-48s |%s|%s%n", i, hex, ascii, marker);
        }

        // 解析附近的 UTF-16 字符串
        System.out.println("\n--- UTF-16 字符串列表 ---");
        for (int i = Math.max(0, stringOffset - 1000); i < stringOffset + 2000; i++) {
            // 尝试检测 UTF-16 字符串 (ASCII 字符的高字节为 0)
            if (i + 4 < data.length && data[i+1] == 0 && data[i+3] == 0 &&
                data[i] >= 0x20 && data[i] < 0x7F && data[i+2] >= 0x20 && data[i+2] < 0x7F) {
                // 看起来是 UTF-16，开始读取
                StringBuilder sb = new StringBuilder();
                int startPos = i;
                int count = 0;
                while (i + 1 < data.length && data[i+1] == 0 && data[i] >= 0x20 && data[i] < 0x7F) {
                    sb.append((char) data[i]);
                    i += 2;
                    count++;
                    if (count > 50) break;
                }
                if (count >= 4) {
                    System.out.println("  @0x" + Integer.toHexString(startPos) + ": '" + sb.toString() + "'");
                }
            }
        }
    }

    private static void findAllSectionDataOffsets(byte[] data) {
        System.out.println("\n=== 查找潜在的 section 数据区域 ===");

        // 策略: 扫描文件，寻找常见的 DWG 对象标记
        // 1. 找到所有非零数据密集区域
        // 2. 检查每个区域是否像编码对象

        // 首先, 从文件末尾 section 索引表中找到的条目,
        // 那些 value 在文件范围内的可能就是对象数据偏移

        // 让我仔细查看 0x213D40 之后的数据作为 section 索引表
        System.out.println("\n--- 从文件末尾解析潜在的 section 索引 ---");

        int indexStart = 0x213D30;
        for (int i = indexStart; i < data.length - 16; i++) {
            // 读取 8 字节作为: [type LE16] [size LE16] [offset LE32]
            int type = (data[i] & 0xFF) | ((data[i+1] & 0xFF) << 8);
            int size = (data[i+2] & 0xFF) | ((data[i+3] & 0xFF) << 8);
            long offset = ((long)(data[i+4] & 0xFF)) |
                         ((long)(data[i+5] & 0xFF) << 8) |
                         ((long)(data[i+6] & 0xFF) << 16) |
                         ((long)(data[i+7] & 0xFF) << 24);

            // 检查是否像合理的 section 条目:
            // - type 应该是一个小的枚举值
            // - offset 应该指向文件中的某个位置
            // - size 应该 > 0 且合理
            if (type < 200 && offset > 0x100 && offset < data.length &&
                size > 0 && size < 100000) {

                // 验证 offset 处的数据
                int off = (int) offset;
                // 检查是否像编码对象的开始
                int firstBytes = 0;
                for (int k = 0; k < 4 && off + k < data.length; k++) {
                    firstBytes = firstBytes * 256 + (data[off + k] & 0xFF);
                }

                System.out.printf("  @0x%06X: type=%d, size=%d, offset=0x%08X (data starts: %02X %02X %02X %02X)%n",
                    i, type, size, offset,
                    data[off] & 0xFF, data[off+1] & 0xFF,
                    data[off+2] & 0xFF, data[off+3] & 0xFF);

                // 跳 8 字节
                i += 7;
            }
        }

        // 分析文件中部的数据区域
        System.out.println("\n--- 文件中部数据区域扫描 ---");
        // 从 0x100 到文件末尾，每 4KB 检查一次数据密度
        for (int offset = 0x100; offset < data.length - 64; offset += 4096) {
            int nonZero = 0;
            for (int j = 0; j < 64; j++) {
                if (data[offset + j] != 0) nonZero++;
            }
            if (nonZero > 40) {
                // 高数据密度 - 可能是对象数据
                System.out.printf("  0x%06X: 高密度区域 (nonZero=%d/64)%n", offset, nonZero);
            }
        }
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
