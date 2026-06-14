package run;

import io.dwg.core.io.*;
import io.dwg.core.util.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.*;
import java.util.*;

/**
 * 深入分析 R2018 头部字段和数据结构
 */
public class R2018DeepAnalysis {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];

        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("=== R2018 深度分析: " + path + " ===");
        System.out.println("文件大小: 0x" + Integer.toHexString(data.length));
        System.out.println();

        // 1. 分析头部字段 (使用 LE32 而不是 LE64)
        System.out.println("=== 头部字段分析 (LE32) ===");
        printField(data, 0x0C, "0x0C (可能是版本/标志)");
        printField(data, 0x10, "0x10");
        printField(data, 0x14, "0x14");
        printField(data, 0x18, "0x18");
        printField(data, 0x1C, "0x1C");
        printField(data, 0x20, "0x20");
        printField(data, 0x24, "0x24");
        printField(data, 0x28, "0x28");
        printField(data, 0x2C, "0x2C");
        printField(data, 0x30, "0x30");
        printField(data, 0x34, "0x34");
        printField(data, 0x38, "0x38");
        printField(data, 0x3C, "0x3C");
        System.out.println();

        // 2. 尝试 R2007 风格的头部 (0x80-0xFF 是 RS 编码 payload)
        // 但只有 128 字节，不足以做 RS 解码
        // 让我们看看 0x80 后的 128 字节是什么
        System.out.println("=== 0x80-0xFF 数据 ===");
        for (int i = 0x80; i < 0x100; i += 16) {
            System.out.printf("%04X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.println();
        }
        System.out.println();

        // 3. 分析 0x100 开始的 "PV" 标记
        System.out.println("=== 0x100 处 PV 标记分析 ===");
        for (int i = 0x100; i < Math.min(0x180, data.length); i += 16) {
            System.out.printf("%04X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char)b);
                else System.out.print('.');
            }
            System.out.println("|");
        }
        System.out.println();

        // 4. 分析 0x121fb (第一个 0x4163 标记位置)
        System.out.println("=== 第一个 0x4163 标记 @ 0x121fb ===");
        int start = 0x121fb - 32;
        int end = 0x121fb + 256;
        for (int i = start; i < Math.min(end, data.length); i += 16) {
            System.out.printf("%04X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char)b);
                else System.out.print('.');
            }
            System.out.println("|");
        }
        System.out.println();

        // 5. 搜索所有 0x4163 标记位置，了解数据分布
        System.out.println("=== 0x4163 标记分布图 ===");
        List<Integer> markers = new ArrayList<>();
        for (int i = 0; i < data.length - 2; i++) {
            if ((data[i] & 0xFF) == 0x63 && (data[i+1] & 0xFF) == 0x41) {
                markers.add(i);
            }
        }
        System.out.println("共找到 " + markers.size() + " 个标记");
        for (int i = 0; i < Math.min(20, markers.size()); i++) {
            int pos = markers.get(i);
            System.out.println("  [" + i + "] @ 0x" + Integer.toHexString(pos) +
                " (距上一个: " + (i > 0 ? (pos - markers.get(i-1)) : pos) + " 字节)");
        }
        if (markers.size() > 20) {
            System.out.println("  ... 还有 " + (markers.size() - 20) + " 个");
        }
        System.out.println();

        // 6. 分析前几个标记的数据结构
        if (markers.size() > 0) {
            System.out.println("=== 前 3 个 0x4163 标记的数据内容 ===");
            for (int idx = 0; idx < Math.min(3, markers.size()); idx++) {
                int mpos = markers.get(idx);
                // 0x4163 是 LE16，然后可能是 LE32 size
                System.out.println("标记 #" + idx + " @ 0x" + Integer.toHexString(mpos) + ":");
                // 读取之后的字段 (模仿 R2007 段头格式: LE32 sizeComp + LE32 sizeUncomp)
                int sizeComp = readLE32(data, mpos + 2);
                int sizeUncomp = readLE32(data, mpos + 6);
                System.out.println("  sizeComp   : 0x" + Integer.toHexString(sizeComp) + " = " + sizeComp);
                System.out.println("  sizeUncomp : 0x" + Integer.toHexString(sizeUncomp) + " = " + sizeUncomp);
                // 显示随后的 32 字节
                System.out.print("  数据: ");
                for (int j = 0; j < 32 && mpos + 10 + j < data.length; j++) {
                    System.out.printf("%02X ", data[mpos + 10 + j]);
                }
                System.out.println();
            }
        }
        System.out.println();

        // 7. 尝试找到 page map (R2018 可能使用与 R2007 类似的结构)
        // R2007: page map 是 LE32 pageId + LE32 pageSize 的数组
        // 而且 page map 是经过 LZ77 压缩的
        // 让我们看看第一个 0x4163 标记 (0x121fb) 之后是不是 page map
        // 解压后的数据格式应该是 LE32 数组
        System.out.println("=== 尝试分析 page map 结构 ===");
        System.out.println("假设第一个 0x4163 之后是压缩的 page map");
        System.out.println();

        // 读取第一个 0x4163 后的压缩数据
        if (markers.size() > 0) {
            int mpos = markers.get(0);
            int sizeComp = readLE32(data, mpos + 2);
            int sizeUncomp = readLE32(data, mpos + 6);
            System.out.println("Page map: compressed=" + sizeComp + ", uncompressed=" + sizeUncomp);
            System.out.println("Data starts @ 0x" + Integer.toHexString(mpos + 10));

            // 尝试 LZ77 解压
            try {
                byte[] compressed = new byte[sizeComp];
                System.arraycopy(data, mpos + 10, compressed, 0, sizeComp);
                Lz77Decompressor lz77 = new Lz77Decompressor();
                byte[] decompressed = lz77.decompress(compressed, sizeUncomp);
                System.out.println("解压成功! 解压后大小: " + decompressed.length);

                // 打印解压后的数据（前 256 字节）
                System.out.println("解压后数据 (前 256 字节):");
                for (int i = 0; i < Math.min(256, decompressed.length); i += 16) {
                    System.out.printf("%04X: ", i);
                    for (int j = 0; j < 16 && i + j < decompressed.length; j++) {
                        System.out.printf("%02X ", decompressed[i + j]);
                    }
                    System.out.print("  |");
                    for (int j = 0; j < 16 && i + j < decompressed.length; j++) {
                        int b = decompressed[i + j] & 0xFF;
                        if (b >= 32 && b < 127) System.out.print((char)b);
                        else System.out.print('.');
                    }
                    System.out.println("|");
                }

                // 尝试解析为 page map: LE32 pageId + LE32 pageSize
                System.out.println();
                System.out.println("尝试解析为 Page Map (LE32 pageId + LE32 pageSize):");
                int numPages = decompressed.length / 8;
                long totalSize = 0;
                long firstValidId = -1;
                int validPages = 0;
                for (int i = 0; i < numPages; i++) {
                    int pageId = readLE32(decompressed, i * 8);
                    int pageSize = readLE32(decompressed, i * 8 + 4);
                    if (pageId > 0 && pageId < 0x100000 && pageSize > 0 && pageSize < 0x100000) {
                        if (firstValidId < 0) firstValidId = pageId;
                        validPages++;
                        totalSize += pageSize;
                        if (validPages <= 10) {
                            System.out.println("  Page " + validPages + ": id=" + pageId +
                                ", size=0x" + Integer.toHexString(pageSize) + " (" + pageSize + " bytes)");
                        }
                    }
                }
                System.out.println("有效页面数: " + validPages + ", 总大小: " + totalSize + " bytes");

            } catch (Exception e) {
                System.out.println("LZ77 解压失败: " + e.getMessage());
            }
        }
        System.out.println();

        // 8. 查找所有 "AcDb:" section 名称
        System.out.println("=== 查找 AcDb section 名称 ===");
        for (int i = 0; i < data.length - 10; i++) {
            if (data[i] == 'A' && data[i+1] == 'c' && data[i+2] == 'D' && data[i+3] == 'b') {
                // 打印 64 字节上下文
                System.out.println("@0x" + Integer.toHexString(i) + ":");
                System.out.print("  ");
                for (int j = -4; j < 60 && i + j < data.length; j++) {
                    if (j >= 0 && i + j < data.length) {
                        System.out.printf("%02X ", data[i + j]);
                    } else if (i + j >= 0) {
                        System.out.print(".. ");
                    }
                }
                System.out.println();
                System.out.print("  ");
                for (int j = -4; j < 60 && i + j < data.length; j++) {
                    if (i + j >= 0) {
                        int b = data[i + j] & 0xFF;
                        if (b >= 32 && b < 127) System.out.print((char)b + "  ");
                        else System.out.print(".  ");
                    }
                }
                System.out.println();
            }
        }
    }

    static void printField(byte[] data, int offset, String name) {
        int val = readLE32(data, offset);
        System.out.println(name + ": 0x" + Integer.toHexString(val) + " (" + val + ")");
    }

    static int readLE32(byte[] data, int off) {
        if (off + 4 > data.length) return -1;
        return (data[off] & 0xFF) |
               ((data[off+1] & 0xFF) << 8) |
               ((data[off+2] & 0xFF) << 16) |
               ((data[off+3] & 0xFF) << 24);
    }
}
