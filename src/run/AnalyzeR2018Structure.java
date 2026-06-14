package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.*;

public class AnalyzeR2018Structure {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];
        
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件: " + path);
        System.out.println("大小: " + data.length + " bytes");
        System.out.println("版本: " + new String(data, 0, 6));
        System.out.println();
        
        // === 1. 检查头部 ===
        System.out.println("=== HEADER (前 256 字节) ===");
        for (int i = 0; i < Math.min(256, data.length); i += 16) {
            System.out.printf("  %04X: ", i);
            for (int j = 0; j < 16; j++) {
                if (i + j < data.length) System.out.printf("%02X ", data[i + j]);
                else System.out.print("   ");
            }
            System.out.print("  |");
            for (int j = 0; j < 16; j++) {
                if (i + j < data.length) {
                    int b = data[i + j] & 0xFF;
                    if (b >= 32 && b < 127) System.out.print((char) b);
                    else System.out.print(".");
                }
            }
            System.out.println("|");
        }
        
        // === 2. 尝试用 R2007 FileHeader 结构解释 ===
        System.out.println("\n=== 尝试 R2007 FileHeader 解释 ===");
        // R2007: 0x00-0x05 版本字符串, 0x06-0x0F 零, 0x10-0x7F live data, 0x80-0x47F RS编码区
        // 但 R2018 可能不使用 RS编码
        
        // 检查 0x80 开始是否有 section header 哨兵
        System.out.println("\n检查各个偏移处的 Section 哨兵 (8D A1 C4 B8):");
        for (int off = 0; off < Math.min(0x500, data.length - 4); off++) {
            if ((data[off] & 0xFF) == 0x8D && (data[off + 1] & 0xFF) == 0xA1 &&
                (data[off + 2] & 0xFF) == 0xC4 && (data[off + 3] & 0xFF) == 0xB8) {
                System.out.println("  找到 Section 哨兵 @ 0x" + Integer.toHexString(off));
            }
        }
        
        // === 3. 检查是否有 Page Map ===
        // R2007: 0x10 处是 pageMapOffset LE64, 0x18 pageMapSizeComp LE64, 0x20 pageMapSizeUncomp LE64
        System.out.println("\n=== 检查可能的 PageMap 偏移 ===");
        // 尝试读取 header 中类似于 R2007 的字段
        long pageMapOffset = readLE64(data, 0x10);
        long pageMapSizeComp = readLE64(data, 0x18);
        long pageMapSizeUncomp = readLE64(data, 0x20);
        long sectionMapId = readLE64(data, 0x28);
        long sectionMapSizeComp = readLE64(data, 0x30);
        long sectionMapSizeUncomp = readLE64(data, 0x38);
        
        System.out.println("  pageMapOffset:      0x" + Long.toHexString(pageMapOffset) + " (" + pageMapOffset + ")");
        System.out.println("  pageMapSizeComp:    0x" + Long.toHexString(pageMapSizeComp) + " (" + pageMapSizeComp + ")");
        System.out.println("  pageMapSizeUncomp:  0x" + Long.toHexString(pageMapSizeUncomp) + " (" + pageMapSizeUncomp + ")");
        System.out.println("  sectionMapId:       0x" + Long.toHexString(sectionMapId) + " (" + sectionMapId + ")");
        System.out.println("  sectionMapSizeComp: 0x" + Long.toHexString(sectionMapSizeComp) + " (" + sectionMapSizeComp + ")");
        System.out.println("  sectionMapSizeUncomp: 0x" + Long.toHexString(sectionMapSizeUncomp) + " (" + sectionMapSizeUncomp + ")");
        
        // 验证: pageMapOffset 应该是 0x480 之后, 且大小应该合理
        if (pageMapOffset > 0 && pageMapOffset < data.length && pageMapSizeComp > 0 && pageMapSizeComp < 0x100000) {
            System.out.println("\n  可能的 PageMap @ 0x" + Long.toHexString(0x480 + pageMapOffset));
            System.out.println("  数据: ");
            int start = (int)(0x480 + pageMapOffset);
            for (int i = 0; i < Math.min(64, (int)pageMapSizeComp); i += 16) {
                System.out.print("    ");
                for (int j = 0; j < 16 && i + j < pageMapSizeComp && start + i + j < data.length; j++) {
                    System.out.printf("%02X ", data[start + i + j]);
                }
                System.out.println();
            }
            
            // 尝试检查是否是 LZ77 压缩
            // LZ77: 通常以 0x0F 开头 (标记压缩), 或者有特定的模式
            // 让我们检查前几个字节
            if (start < data.length - 4) {
                System.out.println("  前4字节: " + 
                    String.format("%02X %02X %02X %02X", 
                        data[start] & 0xFF, data[start + 1] & 0xFF, 
                        data[start + 2] & 0xFF, data[start + 3] & 0xFF));
            }
        }
        
        // === 4. 检查 R2018 header 后的实际内容 ===
        System.out.println("\n=== 0x480 附近数据 (R2007 数据区起点) ===");
        int checkStart = 0x480;
        for (int i = 0; i < 96; i += 16) {
            System.out.printf("  %04X: ", checkStart + i);
            for (int j = 0; j < 16 && checkStart + i + j < data.length; j++) {
                System.out.printf("%02X ", data[checkStart + i + j]);
            }
            System.out.println();
        }
        
        // === 5. 检查是否有 Reed-Solomon 编码的签名 ===
        // RS(255,251) 编码的数据有特定的块结构
        System.out.println("\n=== 检查 Reed-Solomon 编码签名 ===");
        checkRSSignature(data, 0x80, 765);  // 765 字节 = 3 * 255
        
        // === 6. 扫描文件寻找 "ACDb" 或 "AcDb" 签名 ===
        System.out.println("\n=== 扫描已知签名 ===");
        searchPattern(data, new byte[]{'A', 'c', 'D', 'b'}, "AcDb");
        searchPattern(data, new byte[]{'A', 'C', '1', '0'}, "AC10");
        
        // === 7. 检查文件末尾是否有索引信息 ===
        System.out.println("\n=== 文件末尾 128 字节 ===");
        int endStart = Math.max(0, data.length - 128);
        for (int i = endStart; i < data.length; i += 16) {
            System.out.printf("  %06X: ", i);
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.println();
        }
    }
    
    static long readLE64(byte[] data, int off) {
        return ((long)(data[off] & 0xFF)) |
               ((long)(data[off + 1] & 0xFF) << 8) |
               ((long)(data[off + 2] & 0xFF) << 16) |
               ((long)(data[off + 3] & 0xFF) << 24) |
               ((long)(data[off + 4] & 0xFF) << 32) |
               ((long)(data[off + 5] & 0xFF) << 40) |
               ((long)(data[off + 6] & 0xFF) << 48) |
               ((long)(data[off + 7] & 0xFF) << 56);
    }
    
    static void checkRSSignature(byte[] data, int offset, int size) {
        System.out.println("  检查 " + size + " 字节 @ 0x" + Integer.toHexString(offset));
        // RS(255,251) 编码: 4 个 RS 校验字节每 251 字节
        // 765 字节应该被分成 3 块, 每块 255 字节 (251 数据 + 4 RS)
        int blockSize = 255;
        for (int b = 0; b < size / blockSize; b++) {
            int blockStart = offset + b * blockSize;
            System.out.print("  Block " + b + " @ 0x" + Integer.toHexString(blockStart) + ": ");
            // 最后4字节应该是 RS 编码的数据
            for (int i = blockSize - 8; i < blockSize && blockStart + i < data.length; i++) {
                System.out.printf("%02X ", data[blockStart + i] & 0xFF);
            }
            System.out.println();
        }
    }
    
    static void searchPattern(byte[] data, byte[] pattern, String name) {
        int count = 0;
        for (int i = 0; i < data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if ((data[i + j] & 0xFF) != (pattern[j] & 0xFF)) {
                    match = false;
                    break;
                }
            }
            if (match && count < 10) {
                System.out.println("  找到 '" + name + "' @ 0x" + Integer.toHexString(i));
                count++;
            }
        }
        System.out.println("  '" + name + "' 共找到 " + count + " 处");
    }
}
