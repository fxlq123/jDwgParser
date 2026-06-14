package run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 分析用户上传的 DWG 文件
 * 文件: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg
 */
public class UserFileAnalysis {

    public static void main(String[] args) throws IOException {
        // 查找文件
        String path = null;
        java.io.File dir = new java.io.File(".");
        for (java.io.File f : dir.listFiles()) {
            if (f.getName().endsWith(".dwg") || f.getName().endsWith(".DWG")) {
                if (f.getName().contains("210") || f.getName().contains("拨杆")) {
                    path = f.getName();
                    break;
                }
            }
        }

        if (path == null) {
            System.out.println("找不到用户文件，尝试其他位置...");
            path = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        }

        System.out.println("分析文件: " + path);
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件大小: " + data.length + " 字节 (0x" +
            Integer.toHexString(data.length) + ")");

        // 1. 读取版本字符串 (前 6 字节)
        String version = new String(data, 0, 6);
        System.out.println("\n=== 文件版本 ===");
        System.out.println("Version String: '" + version + "'");

        // 映射到已知版本
        String versionName = mapVersion(version);
        System.out.println("对应版本: " + versionName);

        // 2. 分析文件头部结构 (前 256 字节)
        System.out.println("\n=== 文件头部 (前 256 字节) ===");
        dumpHex(data, 0, 256);

        // 3. 分析 header 中的字段
        System.out.println("\n=== Header 字段分析 ===");
        // R13-R2000: header 结构不同
        // R2004+: 不同
        // R2007+: 使用 Reed-Solomon 编码
        // R2018: 明文 header + 分散 section

        if (version.startsWith("AC1032") || version.startsWith("AC1027") ||
            version.startsWith("AC1024") || version.startsWith("AC1021")) {
            // R2004+ 风格，检查 header 字段
            analyzeR2004Header(data);
        } else if (version.startsWith("AC1015") || version.startsWith("AC1014")) {
            // R13-R2000 风格
            analyzeR13Header(data);
        } else {
            System.out.println("未知版本格式，进行通用分析...");
        }

        // 4. 扫描文件中的字符串和标记
        System.out.println("\n=== 文件中可读字符串 ===");
        findStrings(data);

        // 5. 查找 UTF-16 字符串 (R2007+ 对象中的字符串)
        System.out.println("\n=== UTF-16 字符串 (Unicode) ===");
        findUTF16Strings(data);

        // 6. 检查文件是否有 section sentinel
        System.out.println("\n=== Section Sentinel 检查 ===");
        checkSentinels(data);

        // 7. 数据密度分析 - 找到对象数据区域
        System.out.println("\n=== 数据密度分析 ===");
        analyzeDataDensity(data);

        // 8. 查找潜在的 BLOCK 定义
        System.out.println("\n=== 查找 BLOCK 相关数据 ===");
        findBlockData(data);
    }

    private static String mapVersion(String version) {
        java.util.Map<String, String> versions = new java.util.HashMap<>();
        versions.put("AC1032", "AutoCAD 2018 (R2018)");
        versions.put("AC1027", "AutoCAD 2013 (R2013)");
        versions.put("AC1024", "AutoCAD 2010 (R2010)");
        versions.put("AC1021", "AutoCAD 2007 (R2007)");
        versions.put("AC1018", "AutoCAD 2004 (R2004)");
        versions.put("AC1015", "AutoCAD 2000 (R2000)");
        versions.put("AC1014", "AutoCAD R14");
        versions.put("AC1012", "AutoCAD R13");
        versions.put("AC1009", "AutoCAD R11/R12");
        return versions.getOrDefault(version, "未知版本");
    }

    private static void analyzeR2004Header(byte[] data) {
        // R2004+ header 结构 (简化)
        // 0x00-0x05: version string
        // 0x06-0x0F: unknown (通常 0)
        // 0x10+: section map/page map 指针

        // 读取潜在的 LE32 值
        System.out.println("\nHeader 中的潜在偏移/大小值 (LE32):");
        for (int offset = 0x08; offset < 0x80; offset += 4) {
            long val = readLE32(data, offset);
            if (val > 100 && val < data.length) {
                System.out.printf("  @0x%04X: %d (0x%08X) -> 检查:%n", offset, val, val);
                int off = (int) val;
                System.out.print("    ");
                for (int k = 0; k < 16 && off + k < data.length; k++) {
                    System.out.printf("%02X ", data[off + k] & 0xFF);
                }
                System.out.println();
            }
        }

        // 检查 0x80-0x100 区域 (通常是对象数据开始)
        if (data.length > 0x100) {
            System.out.println("\n0x080-0x100 区域:");
            dumpHex(data, 0x080, 128);
        }
    }

    private static void analyzeR13Header(byte[] data) {
        // R13/R2000 header 结构
        System.out.println("\nR13/R2000 风格 header:");
        // 打印 header 中的字段值
        for (int offset = 0x06; offset < 0x80; offset += 4) {
            long val = readLE32(data, offset);
            if (val > 100 && val < data.length) {
                System.out.printf("  @0x%04X: %d (可能的 offset) ->%n", offset, val);
                int off = (int) val;
                System.out.print("    ");
                for (int k = 0; k < 16 && off + k < data.length; k++) {
                    System.out.printf("%02X ", data[off + k] & 0xFF);
                }
                System.out.println();
            }
        }
    }

    private static void findStrings(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] >= 32 && data[i] < 127) {
                StringBuilder sb = new StringBuilder();
                int start = i;
                int count = 0;
                while (i < data.length && data[i] >= 32 && data[i] < 127) {
                    sb.append((char) data[i]);
                    i++;
                    count++;
                    if (count > 60) break;
                }
                if (count >= 6) {
                    System.out.println("  @0x" + Integer.toHexString(start) + ": '" +
                        sb.toString().replace("'", "") + "'");
                }
            }
        }
    }

    private static void findUTF16Strings(byte[] data) {
        int stringCount = 0;
        for (int i = 0; i < data.length - 4; i++) {
            // 检测 ASCII 的 UTF-16 表示: 交替的 ASCII 字节和 0 字节
            if (data[i+1] == 0 && data[i+3] == 0 &&
                data[i] >= 0x20 && data[i] < 0x7F &&
                data[i+2] >= 0x20 && data[i+2] < 0x7F) {
                StringBuilder sb = new StringBuilder();
                int start = i;
                int count = 0;
                while (i + 1 < data.length && data[i+1] == 0 &&
                       data[i] >= 0x20 && data[i] < 0x7F) {
                    sb.append((char) data[i]);
                    i += 2;
                    count++;
                    if (count > 100) break;
                }
                if (count >= 5 && stringCount < 30) {
                    System.out.println("  @0x" + Integer.toHexString(start) + ": '" +
                        sb.toString() + "' (length=" + count + ")");
                    stringCount++;
                }
            }
        }
        if (stringCount == 0) {
            System.out.println("  未找到 UTF-16 字符串");
        }
    }

    private static void checkSentinels(byte[] data) {
        // 常见的 section sentinel 字节模式
        byte[][] patterns = {
            {(byte)0x8D, (byte)0xA1, (byte)0xC4, (byte)0xB8}, // R2007+
            {(byte)0xFE, (byte)0xFE, (byte)0xFE, (byte)0xFE},
            {(byte)0xAA, (byte)0x55, (byte)0xAA, (byte)0x55},
        };

        for (byte[] pattern : patterns) {
            int count = 0;
            java.util.List<Integer> offsets = new java.util.ArrayList<>();
            for (int i = 0; i < data.length - pattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if ((data[i+j] & 0xFF) != (pattern[j] & 0xFF)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    count++;
                    if (count <= 10) offsets.add(i);
                }
            }

            StringBuilder patternHex = new StringBuilder();
            for (byte b : pattern) patternHex.append(String.format("%02X ", b & 0xFF));
            System.out.println("  Pattern [" + patternHex.toString().trim() + "]: 找到 " +
                count + " 处");
            for (int off : offsets) {
                System.out.printf("    @0x%06X: ", off);
                for (int k = 0; k < 16 && off + k < data.length; k++) {
                    System.out.printf("%02X ", data[off + k] & 0xFF);
                }
                System.out.println();
            }
        }
    }

    private static void analyzeDataDensity(byte[] data) {
        int blockSize = Math.min(4096, data.length / 20);
        if (blockSize < 256) blockSize = 256;

        System.out.println("Block size: " + blockSize + " bytes");
        for (int i = 0; i < data.length; i += blockSize) {
            int nonZero = 0;
            int end = Math.min(i + blockSize, data.length);
            for (int j = i; j < end; j++) {
                if (data[j] != 0) nonZero++;
            }
            double density = nonZero / (double)(end - i);
            if (density > 0.3) {
                System.out.printf("  0x%06X - 0x%06X: 密度=%.1f%% (高数据区)%n",
                    i, end, density * 100);
            }
        }
    }

    private static void findBlockData(byte[] data) {
        // 查找 BLOCK/LAYER/INSERT 等关键字
        String[] keywords = {"BLOCK", "INSERT", "LAYER", "LINE", "CIRCLE", "ARC",
                            "TEXT", "Model", "model", "0", "拨杆", "件"};
        for (String kw : keywords) {
            byte[] bytes = kw.getBytes();
            int count = 0;
            for (int i = 0; i < data.length - bytes.length; i++) {
                boolean match = true;
                for (int j = 0; j < bytes.length; j++) {
                    if ((data[i+j] & 0xFF) != (bytes[j] & 0xFF)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    if (count < 5) {
                        System.out.printf("  '%s' @0x%06X: ", kw, i);
                        for (int k = Math.max(0, i-4); k < Math.min(i+20, data.length); k++) {
                            System.out.printf("%02X ", data[k] & 0xFF);
                        }
                        System.out.println();
                    }
                    count++;
                }
            }
            if (count > 0) {
                System.out.println("    总计: " + count + " 处");
            }
        }
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
