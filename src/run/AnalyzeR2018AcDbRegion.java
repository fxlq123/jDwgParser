package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.*;

public class AnalyzeR2018AcDbRegion {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];
        
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件: " + path);
        System.out.println("大小: " + data.length + " bytes (0x" + 
            Integer.toHexString(data.length) + ")");
        
        // === 1. 检查 0x26ff 附近的 "AcDb" 区域 ===
        System.out.println("\n=== 检查 0x26ff 附近的 'AcDb' 区域 ===");
        int center = 0x26ff;
        for (int i = Math.max(0, center - 32); i < Math.min(data.length, center + 128); i += 16) {
            System.out.printf("  %06X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char) b);
                else System.out.print(".");
            }
            System.out.println("|");
        }
        
        // === 2. 检查文件末尾 0x2136e9 附近 ===
        System.out.println("\n=== 检查 0x2136e9 附近的 'AcDb' 区域 ===");
        int center2 = 0x2136e9;
        for (int i = Math.max(0, center2 - 32); i < Math.min(data.length, center2 + 128); i += 16) {
            System.out.printf("  %06X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char) b);
                else System.out.print(".");
            }
            System.out.println("|");
        }
        
        // === 3. 尝试理解 R2018 HEADER ===
        // 仔细看看 0x00-0x7F 区域的数据结构
        System.out.println("\n=== 分析 HEADER 结构 ===");
        
        // 0x0C-0x0F 处的值
        int val0C = readLE32(data, 0x0C);
        System.out.println("  0x0C-0x0F (LE32): 0x" + Integer.toHexString(val0C) + " = " + val0C);
        
        // 尝试不同的偏移来理解 header
        // 可能的字段布局: 0x10 pageMapOffset, 0x18 pageMapSize 等
        System.out.println("\n  尝试 LE32 解释:");
        for (int off = 0x10; off < 0x80; off += 4) {
            long val = readLE32(data, off) & 0xFFFFFFFFL;
            if (val > 100 && val < data.length) {
                System.out.println("    0x" + String.format("%02X", off) + ": 0x" + Long.toHexString(val) + " = " + val);
            }
        }
        
        // === 4. 看看 0x100 附近（R2018FileStructureHandler 认为是数据起点） ===
        System.out.println("\n=== 0x100 附近数据 ===");
        for (int i = 0x100; i < Math.min(0x100 + 256, data.length); i += 16) {
            System.out.printf("  %06X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char) b);
                else System.out.print(".");
            }
            System.out.println("|");
        }
        
        // === 5. 尝试作为 R2007 来解析 — 但使用正确的字段偏移 ===
        // 也许 R2018 只是把字段位置变了
        System.out.println("\n=== 尝试在文件中查找 LZ77 压缩签名 ===");
        // LZ77 压缩数据通常以 0x0F 开头，或者有特定模式
        // 让我们搜索可能的压缩块起始位置
        for (int off = 0x100; off < data.length - 8; off += 0x100) {
            // 检查是否看起来像压缩数据的开始
            // R2007 section 有 section 哨兵 8D A1 C4 B8
            if ((data[off] & 0xFF) == 0x8D && (data[off+1] & 0xFF) == 0xA1 &&
                (data[off+2] & 0xFF) == 0xC4 && (data[off+3] & 0xFF) == 0xB8) {
                System.out.println("  找到 Section 哨兵 @ 0x" + Integer.toHexString(off));
            }
        }
        
        // === 6. 看看 0x80 区域是不是有意义的 ===
        // 0x80 到 0x480 是 R2007 的 RS编码 header payload
        System.out.println("\n=== 检查 0x80-0x100 区域 (可能是 RS 编码的 live data) ===");
        for (int i = 0x80; i < 0x100; i += 16) {
            System.out.printf("  %04X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.println();
        }
        
        // === 7. 尝试分析 0x480 附近是不是实际的 section 数据 ===
        System.out.println("\n=== 检查 0x480 附近 - 看看是不是有结构数据 ===");
        int start = 0x480;
        for (int row = 0; row < 8; row++) {
            System.out.printf("  %06X: ", start + row * 32);
            for (int col = 0; col < 32 && start + row * 32 + col < data.length; col++) {
                System.out.printf("%02X ", data[start + row * 32 + col]);
            }
            System.out.println();
        }
        
        // === 8. 关键：尝试用 R2010 解析器来解析这个文件 ===
        // 看看 R2007FileStructureHandler 或 R2010 的
        System.out.println("\n=== 尝试 R2007 风格: 检查 PageMap/SectionMap ===");
        // R2007 中: pageMapOffset 在 header 的某个位置
        // 但我们检查到 0x10 处的 LE64 不合理
        // 让我们尝试 LE32 解释：
        // 0x0C: 0x0001C003 = 114691 (看起来可能是某种标志)
        // 0x10: 0x001E0021 = 1966113
        // 让我们看看 0x20-0x40 区域
        System.out.println("  0x10 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x10)));
        System.out.println("  0x14 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x14)));
        System.out.println("  0x18 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x18)));
        System.out.println("  0x1C (LE32): 0x" + Integer.toHexString(readLE32(data, 0x1C)));
        System.out.println("  0x20 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x20)));
        System.out.println("  0x24 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x24)));
        System.out.println("  0x28 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x28)));
        System.out.println("  0x2C (LE32): 0x" + Integer.toHexString(readLE32(data, 0x2C)));
        System.out.println("  0x30 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x30)));
        System.out.println("  0x34 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x34)));
        System.out.println("  0x38 (LE32): 0x" + Integer.toHexString(readLE32(data, 0x38)));
        System.out.println("  0x3C (LE32): 0x" + Integer.toHexString(readLE32(data, 0x3C)));
        
        // === 9. 看 0x20 处的值: 0x00000120 = 288 — 可能是 pageMapSizeComp?
        // 0x24: 0x00000080 = 128
        // 0x28: 0x000015E0 = 5600 - 这可能是 sectionMapSizeComp
        // 让我们看看 0x20 后的字段
        System.out.println("\n=== 尝试用 R2007 的字段偏移来解释 ===");
        // 也许 R2018 使用不同的字段布局
        // 0x20: pageMapSizeComp?
        // 0x28: pageMapSizeUncomp?
        // 0x30: sectionMapId?
        long pmSizeComp = readLE32(data, 0x20) & 0xFFFFFFFFL;
        long pmSizeUncomp = readLE32(data, 0x28) & 0xFFFFFFFFL;
        long smId = readLE32(data, 0x30) & 0xFFFFFFFFL;
        long smSizeComp = readLE32(data, 0x34) & 0xFFFFFFFFL;
        long smSizeUncomp = readLE32(data, 0x38) & 0xFFFFFFFFL;
        System.out.println("  [0x20] pageMapSizeComp: " + pmSizeComp + " (0x" + Long.toHexString(pmSizeComp) + ")");
        System.out.println("  [0x28] pageMapSizeUncomp: " + pmSizeUncomp + " (0x" + Long.toHexString(pmSizeUncomp) + ")");
        System.out.println("  [0x30] sectionMapId: " + smId + " (0x" + Long.toHexString(smId) + ")");
        System.out.println("  [0x34] sectionMapSizeComp: " + smSizeComp + " (0x" + Long.toHexString(smSizeComp) + ")");
        System.out.println("  [0x38] sectionMapSizeUncomp: " + smSizeUncomp + " (0x" + Long.toHexString(smSizeUncomp) + ")");
        
        // 验证: 这些值是合理的！
        // pageMapSizeComp = 288, pageMapSizeUncomp = 128 — 但 uncomp 应该 >= comp
        // 或者反过来?
        // 0x20: 0x120 = 288
        // 0x24: 0x80 = 128
        // 0x28: 0x15E0 = 5600
        // 0x2C: 0x3 = 3
        // 0x30: 0x45 = 69
        // 0x34: 0x1D = 29
        // 0x38: 0x1D = 29
        // 这个解释不太对...
        
        // 让我们尝试另一种方式: 假设 pageMap 在 0x480 之后
        System.out.println("\n=== 检查 0x480 后的数据是否像 page map ===");
        // page map: LE32 pageId (1), LE32 pageSize (bytes 2-3)
        // 然后是 LZ77 压缩数据
        // page map entry: LE32 id, LE32 size, offset...
        long pmOff = 0x480;  // 假设 page map 从 0x480 开始
        // 看看前32字节
        for (int i = 0; i < 4 && (int)pmOff + i * 8 < data.length; i++) {
            int entryStart = (int)pmOff + i * 8;
            long id = readLE32(data, entryStart) & 0xFFFFFFFFL;
            long sz = readLE32(data, entryStart + 4) & 0xFFFFFFFFL;
            System.out.println("  Entry " + i + ": id=" + id + ", size=" + sz + " (0x" + 
                Long.toHexString(id) + ", 0x" + Long.toHexString(sz) + ")");
        }
        
        // 也许 R2018 的 page map 使用不同的编码方式
        // 让我检查 0x480 之后的数据是否像某种索引
        System.out.println("\n=== 尝试用 R2007 的 LZ77 解压 0x480 后的数据 ===");
        // R2007: 0x480 后是 page map, 它是 LZ77 压缩的
        // 但首先检查 0x480 是不是 page map (它看起来像调色板)
        
        // 关键思路: 检查 0x26ff 之前的区域
        // 0x26ff 有 "AcDb" — 它可能是 section 名称的一部分
        // 让我们检查这个位置附近
        System.out.println("\n=== 深入 0x2600 区域分析 ===");
        int regionStart = 0x2500;
        for (int i = regionStart; i < Math.min(regionStart + 512, data.length); i += 16) {
            System.out.printf("  %06X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char) b);
                else System.out.print(".");
            }
            System.out.println("|");
        }
    }
    
    static int readLE32(byte[] data, int off) {
        return (data[off] & 0xFF) |
               ((data[off + 1] & 0xFF) << 8) |
               ((data[off + 2] & 0xFF) << 16) |
               ((data[off + 3] & 0xFF) << 24);
    }
}
