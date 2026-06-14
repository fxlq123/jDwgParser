package run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 深度分析 R2018 文件结构
 * 目标: 理解 header, section 位置, 对象数据的真实格式
 */
public class R2018DeepStructure {

    public static void main(String[] args) throws IOException {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];

        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件大小: " + data.length + " 字节 (0x" + Integer.toHexString(data.length) + ")");

        // 1. Header 分析
        analyzeHeader(data);

        // 2. 寻找已知字符串标记 (如 "AcDb", "BLOCK", "INSERT" 等)
        findSignatures(data);

        // 3. 分析潜在的对象数据区域
        analyzePotentialObjectAreas(data);
    }

    private static void analyzeHeader(byte[] data) {
        System.out.println("\n=== Header 分析 (前 256 字节) ===");

        // 打印为 hex + ASCII
        for (int i = 0; i < Math.min(256, data.length); i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                char c = (char) b;
                ascii.append((c >= 32 && c < 127) ? c : '.');
            }
            System.out.printf("0x%04X: %-48s |%s|%n", i, hex, ascii);
        }

        // 检查版本字符串
        String version = new String(data, 0, 6);
        System.out.println("\n版本字符串: '" + version + "'");

        // 读取 header 中的 LE32 字段
        System.out.println("\nHeader 中的 LE32 字段:");
        for (int off = 6; off < 0x80; off += 4) {
            long val = readLE32(data, off);
            if (val != 0) {
                System.out.printf("  0x%04X: %d (0x%08X)%n", off, val, val);
            }
        }
    }

    private static void findSignatures(byte[] data) {
        System.out.println("\n=== 寻找已知签名 ===");

        // 查找 "AC10" 系列
        findPattern(data, "AC10".getBytes());

        // 查找 "AcDb" (对象类型标记)
        findPattern(data, "AcDb".getBytes());

        // 查找 "BLOCK"
        findPattern(data, "BLOCK".getBytes());

        // 查找常见的 section 标记
        byte[] sentinel = new byte[]{
            (byte) 0x8D, (byte) 0xA1, (byte) 0xC4, (byte) 0xB8
        };
        findPattern(data, sentinel);
    }

    private static void findPattern(byte[] data, byte[] pattern) {
        String name = new String(pattern);
        boolean printable = true;
        for (byte b : pattern) {
            if (b < 32 || b >= 127) { printable = false; break; }
        }
        if (!printable) {
            StringBuilder sb = new StringBuilder();
            for (byte b : pattern) sb.append(String.format("%02X ", b & 0xFF));
            name = sb.toString().trim();
        }

        int count = 0;
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) { match = false; break; }
            }
            if (match) {
                if (count < 10) {
                    // 打印周围上下文
                    System.out.printf("  找到 '%s' @ 0x%06X (%d)%n", name, i, i);
                    // 打印附近数据
                    int start = Math.max(0, i - 16);
                    int end = Math.min(data.length, i + 64);
                    StringBuilder hex = new StringBuilder();
                    for (int k = start; k < end; k++) {
                        hex.append(String.format("%02X ", data[k] & 0xFF));
                        if ((k - start + 1) % 16 == 0) hex.append("\n              ");
                    }
                    System.out.println("    " + hex);
                }
                count++;
            }
        }
        if (count > 0) {
            System.out.println("  总共找到 " + count + " 处");
        }
    }

    private static void analyzePotentialObjectAreas(byte[] data) {
        System.out.println("\n=== 分析文件后半部分 ===");

        // 分析文件的各个区域
        int[] checkpoints = {
            0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000,
            data.length / 4, data.length / 2, data.length * 3 / 4,
            data.length - 256
        };

        for (int cp : checkpoints) {
            if (cp < 0 || cp >= data.length) continue;
            System.out.printf("\n@ 0x%06X (%d):%n", cp, cp);
            for (int i = 0; i < 64 && cp + i < data.length; i += 16) {
                StringBuilder hex = new StringBuilder();
                StringBuilder ascii = new StringBuilder();
                for (int j = 0; j < 16 && cp + i + j < data.length; j++) {
                    int b = data[cp + i + j] & 0xFF;
                    hex.append(String.format("%02X ", b));
                    char c = (char) b;
                    ascii.append((c >= 32 && c < 127) ? c : '.');
                }
                System.out.printf("  0x%06X: %s |%s|", cp + i, hex, ascii);
                System.out.println();
            }
        }

        // 检查零填充区域
        System.out.println("\n=== 零填充区域分析 ===");
        int zeroStart = -1;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                if (zeroStart == -1) zeroStart = i;
            } else {
                if (zeroStart != -1 && i - zeroStart > 100) {
                    System.out.printf("  零填充区域: 0x%06X - 0x%06X (长度: %d)%n",
                        zeroStart, i, i - zeroStart);
                }
                zeroStart = -1;
            }
        }
        if (zeroStart != -1 && data.length - zeroStart > 100) {
            System.out.printf("  零填充区域: 0x%06X - END (长度: %d)%n",
                zeroStart, data.length - zeroStart);
        }

        // 检查非零数据区域分布
        System.out.println("\n=== 数据密度分析 ===");
        int blockSize = 4096;
        for (int i = 0; i < data.length; i += blockSize) {
            int nonZero = 0;
            for (int j = 0; j < blockSize && i + j < data.length; j++) {
                if (data[i + j] != 0) nonZero++;
            }
            double density = nonZero / (double) Math.min(blockSize, data.length - i);
            if (density > 0.01) {
                System.out.printf("  0x%06X: 非零密度 = %.1f%%%n", i, density * 100);
            }
        }
    }

    private static long readLE32(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF)) |
               ((long)(data[offset+1] & 0xFF) << 8) |
               ((long)(data[offset+2] & 0xFF) << 16) |
               ((long)(data[offset+3] & 0xFF) << 24);
    }
}
