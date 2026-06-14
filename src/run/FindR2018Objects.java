package run;

import io.dwg.core.io.*;
import io.dwg.core.util.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.*;
import java.util.*;

public class FindR2018Objects {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];
        
        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("文件: " + path);
        System.out.println("大小: " + data.length + " bytes (0x" + 
            Integer.toHexString(data.length) + ")");
        
        // === 1. 搜索 "AcDb" 所有出现位置 ===
        System.out.println("\n=== 所有 'AcDb' 位置详细分析 ===");
        List<Integer> acDbPositions = new ArrayList<>();
        for (int i = 0; i < data.length - 4; i++) {
            if ((data[i] & 0xFF) == 'A' && (data[i+1] & 0xFF) == 'c' &&
                (data[i+2] & 0xFF) == 'D' && (data[i+3] & 0xFF) == 'b') {
                acDbPositions.add(i);
            }
        }
        System.out.println("共找到 " + acDbPositions.size() + " 处 'AcDb'");
        
        for (int pos : acDbPositions) {
            System.out.println("\n  @ 0x" + Integer.toHexString(pos) + ":");
            // 打印 32 bytes 上下文
            System.out.print("    数据: ");
            for (int j = Math.max(0, pos - 16); j < Math.min(data.length, pos + 48); j++) {
                System.out.printf("%02X ", data[j]);
                if (j == pos - 1) System.out.print("[");
                if (j == pos + 3) System.out.print("]");
            }
            System.out.println();
            System.out.print("    ASCII: ");
            for (int j = Math.max(0, pos - 16); j < Math.min(data.length, pos + 48); j++) {
                int b = data[j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char) b);
                else System.out.print('.');
            }
            System.out.println();
        }
        
        // === 2. 尝试理解 0x560 之后的 PNG 数据 ===
        // 看看 PNG 数据有多大，之后是什么
        System.out.println("\n=== 分析 PNG 数据区域 ===");
        // PNG 图像以 "IDAT" (49 44 41 54) 开头
        // 让我们找到 PNG 的大小
        for (int i = 0x500; i < Math.min(0x3000, data.length - 4); i++) {
            if ((data[i] & 0xFF) == 'I' && (data[i+1] & 0xFF) == 'D' &&
                (data[i+2] & 0xFF) == 'A' && (data[i+3] & 0xFF) == 'T') {
                System.out.println("  找到 PNG IDAT chunk @ 0x" + Integer.toHexString(i));
            }
            // PNG 文件结束标记 "IEND"
            if ((data[i] & 0xFF) == 'I' && (data[i+1] & 0xFF) == 'E' &&
                (data[i+2] & 0xFF) == 'N' && (data[i+3] & 0xFF) == 'D') {
                System.out.println("  找到 PNG IEND chunk @ 0x" + Integer.toHexString(i));
                // 打印这个位置之后的数据
                System.out.println("  IEND 之后的数据:");
                for (int j = i + 8; j < Math.min(data.length, i + 128); j += 16) {
                    System.out.printf("    0x%X: ", j);
                    for (int k = 0; k < 16 && j + k < data.length; k++) {
                        System.out.printf("%02X ", data[j + k]);
                    }
                    System.out.print("  |");
                    for (int k = 0; k < 16 && j + k < data.length; k++) {
                        int b = data[j + k] & 0xFF;
                        if (b >= 32 && b < 127) System.out.print((char) b);
                        else System.out.print('.');
                    }
                    System.out.println("|");
                }
                break;
            }
        }
        
        // === 3. 尝试直接查找 page map ===
        // R2007 的 page map 是: LE32 pageId, LE32 pageSize 重复
        // 但它首先需要经过 RS(255,251) 解码，然后 LZ77 解压
        // 在 R2018 中，也许不用 RS 编码？让我们尝试直接找 LZ77 压缩的页面
        
        System.out.println("\n=== 尝试在 HEADER 中找到正确的字段位置 ===");
        // R2007 格式: 
        // 0x00-0x05: "AC1021" (版本字符串)
        // 0x06-0x0F: 零
        // 0x10: pageMapOffset (LE64) - 相对于 0x480
        // 0x18: pageMapSizeComp (LE64)
        // 0x20: pageMapSizeUncomp (LE64)
        // 0x28: sectionMapId (LE64) - page map 中指向 section map 的 ID
        // 0x30: sectionMapSizeComp (LE64)
        // 0x38: sectionMapSizeUncomp (LE64)
        // 0x40-0x7F: 其他字段
        // 0x80-0x47F: RS 编码的 header payload (1000 bytes = 4 * 255)
        
        // 在我们的 R2018 文件中:
        // 0x00-0x05: "AC1032" 
        // 0x06-0x0B: 零
        // 0x0C-0x0F: 03 C0 01 00 = LE32 0x0001C003 = 114691
        // 0x10-0x13: 00 21 00 1E = LE32 0x1E002100 = 503324928
        // 0x14-0x17: 00 00 00 00
        // 0x18-0x1B: 00 00 00 00
        // 0x1C-0x1F: 00 00 00 00
        // 0x20-0x23: 20 01 00 00 = LE32 0x0120 = 288
        // 0x24-0x27: 00 00 00 00
        // 0x28-0x2B: 80 00 00 00 = LE32 0x80 = 128
        // 0x2C-0x2F: E0 15 00 00 = LE32 0x15E0 = 5600
        // 0x30-0x33: 00 19 00 00 = LE32 0x1900 = 6400
        // 0x34-0x37: 03 00 00 00 = LE32 0x3 = 3
        // 0x38-0x3B: 45 00 00 00 = LE32 0x45 = 69
        // 0x3C-0x3F: 1D 00 00 00 = LE32 0x1D = 29
        // 0x40-0x7F: 全是 0!
        
        // 所以可能的字段布局是:
        // 0x20 (4 bytes): pageMapSizeComp = 288
        // 0x28 (4 bytes): pageMapSizeUncomp = 128 — 不对, uncomp 应该更大
        // 或者反过来?
        // 0x20: pageMapSizeUncomp = 288
        // 0x28: pageMapSizeComp = 128
        // 0x2C: sectionMapSizeComp = 5600
        // 0x30: sectionMapSizeUncomp = 6400
        // 0x34: sectionMapId = 3
        // 0x38: 其他 = 69
        // 0x3C: 其他 = 29
        
        // 或者这根本不是 R2007 风格的字段布局...
        // 让我们尝试另一种方法: 直接从文件中解码 RS 编码区域
        
        // 首先检查 0x80-0x47F 是不是 RS 编码数据
        // RS(255,251) 编码: 每 251 字节数据 + 4 字节纠错码
        // 所以 0x80-0x47F 是 1000 字节，正好是 4 * 255
        System.out.println("\n=== 尝试 RS 解码 0x80-0x47F 区域 ===");
        byte[] rsBlock = new byte[1020]; // 4 * 255
        System.arraycopy(data, 0x80, rsBlock, 0, Math.min(1020, data.length - 0x80));
        
        // 尝试使用项目已有的 ReedSolomon 解码器
        try {
            // RS(255,251): 每个 255 字节块解码出 251 字节数据
            // 总共 4 个块 → 4 * 251 = 1004 字节
            byte[] decoded = new byte[1004];
            for (int block = 0; block < 4; block++) {
                byte[] blockData = new byte[255];
                System.arraycopy(rsBlock, block * 255, blockData, 0, 255);
                // 解码
                // 使用项目已有的 ReedSolomon251Decoder
                try {
                    byte[] rsDecoded = ReedSolomon251Decoder.decode(blockData);
                    System.arraycopy(rsDecoded, 0, decoded, block * 251, 251);
                } catch (Exception e) {
                    System.out.println("  Block " + block + " 解码失败: " + e.getMessage());
                }
            }
            
            // 打印解码结果的前 64 字节
            System.out.println("  RS 解码后 (前 128 字节):");
            for (int i = 0; i < Math.min(128, decoded.length); i += 16) {
                System.out.printf("    %04X: ", i);
                for (int j = 0; j < 16 && i + j < decoded.length; j++) {
                    System.out.printf("%02X ", decoded[i + j]);
                }
                System.out.print("  |");
                for (int j = 0; j < 16 && i + j < decoded.length; j++) {
                    int b = decoded[i + j] & 0xFF;
                    if (b >= 32 && b < 127) System.out.print((char) b);
                    else System.out.print('.');
                }
                System.out.println("|");
            }
            
            // 尝试解释为 LE64 字段
            System.out.println("\n  尝试 LE64 解释:");
            for (int off = 0; off < 64; off += 8) {
                long val = readLE64(decoded, off);
                System.out.println("    +" + String.format("%02X", off) + ": 0x" + Long.toHexString(val) + " = " + val);
            }
            
            // === 4. 如果 RS 解码成功，尝试找到 page map 偏移 ===
            // R2007: pageMapOffset 是从 0x480 开始的偏移
            // 让我们假设 R2018 也是这样
            long pageMapOffset = readLE64(decoded, 0);
            long pageMapSizeComp = readLE64(decoded, 8);
            long pageMapSizeUncomp = readLE64(decoded, 16);
            long sectionMapId = readLE64(decoded, 24);
            long sectionMapSizeComp = readLE64(decoded, 32);
            long sectionMapSizeUncomp = readLE64(decoded, 40);
            
            System.out.println("\n  === 假设 R2007 风格字段位置 ===");
            System.out.println("  pageMapOffset:      0x" + Long.toHexString(pageMapOffset) + " = " + pageMapOffset);
            System.out.println("  pageMapSizeComp:    0x" + Long.toHexString(pageMapSizeComp) + " = " + pageMapSizeComp);
            System.out.println("  pageMapSizeUncomp:  0x" + Long.toHexString(pageMapSizeUncomp) + " = " + pageMapSizeUncomp);
            System.out.println("  sectionMapId:       0x" + Long.toHexString(sectionMapId) + " = " + sectionMapId);
            System.out.println("  sectionMapSizeComp: 0x" + Long.toHexString(sectionMapSizeComp) + " = " + sectionMapSizeComp);
            System.out.println("  sectionMapSizeUncomp: 0x" + Long.toHexString(sectionMapSizeUncomp) + " = " + sectionMapSizeUncomp);
            
            // 验证这些值是否合理
            if (pageMapOffset > 0 && pageMapSizeComp > 0 && pageMapSizeComp < 0x10000 &&
                pageMapSizeUncomp > pageMapSizeComp && pageMapSizeUncomp < 0x100000) {
                System.out.println("\n  ✓ 找到合理的 PageMap 参数!");
                
                // 找到 page map 数据
                long pmStart = 0x480 + pageMapOffset;
                System.out.println("  PageMap 应该 @ 0x" + Long.toHexString(pmStart));
                
                if (pmStart + pageMapSizeComp < data.length) {
                    // 提取 page map 数据
                    byte[] pmCompressed = new byte[(int)pageMapSizeComp];
                    System.arraycopy(data, (int)pmStart, pmCompressed, 0, (int)pageMapSizeComp);
                    
                    // 先尝试 RS 解码
                    System.out.println("  === PageMap 原始数据 (前 64 字节) ===");
                    for (int i = 0; i < Math.min(64, pmCompressed.length); i += 16) {
                        System.out.printf("    %04X: ", i);
                        for (int j = 0; j < 16 && i + j < pmCompressed.length; j++) {
                            System.out.printf("%02X ", pmCompressed[i + j]);
                        }
                        System.out.println();
                    }
                    
                    // RS 解码: 每 255 字节 → 251 字节
                    int numBlocks = (int)Math.ceil(pageMapSizeComp / 255.0);
                    byte[] rsDecoded = new byte[numBlocks * 251];
                    boolean rsOK = true;
                    try {
                        for (int b = 0; b < numBlocks; b++) {
                            int srcStart = b * 255;
                            byte[] blk = new byte[255];
                            System.arraycopy(pmCompressed, srcStart, blk, 0, 
                                Math.min(255, pmCompressed.length - srcStart));
                            byte[] dec = ReedSolomon251Decoder.decode(blk);
                            System.arraycopy(dec, 0, rsDecoded, b * 251, 251);
                        }
                        System.out.println("  ✓ PageMap RS 解码成功");
                    } catch (Exception e) {
                        System.out.println("  ✗ PageMap RS 解码失败: " + e.getMessage());
                        rsOK = false;
                    }
                    
                    if (rsOK) {
                        // 然后 LZ77 解压
                        try {
                            R2004Lz77 lz77 = new R2004Lz77();
                            byte[] pmUncompressed = lz77.decompress(rsDecoded, (int)pageMapSizeUncomp);
                            System.out.println("  ✓ PageMap LZ77 解压成功, 大小: " + pmUncompressed.length);
                            
                            System.out.println("  === PageMap 解压后数据 ===");
                            for (int i = 0; i < Math.min(256, pmUncompressed.length); i += 16) {
                                System.out.printf("    %04X: ", i);
                                for (int j = 0; j < 16 && i + j < pmUncompressed.length; j++) {
                                    System.out.printf("%02X ", pmUncompressed[i + j]);
                                }
                                System.out.print("  |");
                                for (int j = 0; j < 16 && i + j < pmUncompressed.length; j++) {
                                    int b = pmUncompressed[i + j] & 0xFF;
                                    if (b >= 32 && b < 127) System.out.print((char) b);
                                    else System.out.print('.');
                                }
                                System.out.println("|");
                            }
                            
                            // 解析 PageMap: LE32 pageId, LE32 pageSize
                            int numPages = pmUncompressed.length / 8;
                            System.out.println("\n  === PageMap 内容 ===");
                            for (int i = 0; i < numPages; i++) {
                                int pageId = readLE32(pmUncompressed, i * 8);
                                int pageSize = readLE32(pmUncompressed, i * 8 + 4);
                                if (pageId > 0 && pageSize > 0 && pageSize < 0x100000) {
                                    System.out.println("    Page " + i + ": id=" + pageId + ", size=" + pageSize);
                                    if (pageId == sectionMapId) {
                                        System.out.println("    ↑ 这是 SectionMap!");
                                        // 找到 section map 页面的偏移
                                        long sectionMapOffset = 0;
                                        for (int k = 0; k < i; k++) {
                                            long sz = readLE32(pmUncompressed, k * 8 + 4);
                                            // 页面大小可能有 padding
                                            sectionMapOffset += (sz + 7) & ~7L;
                                        }
                                        System.out.println("    SectionMap 在 PageMap 中的偏移: 0x" + Long.toHexString(sectionMapOffset));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.out.println("  ✗ PageMap LZ77 解压失败: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                } else {
                    System.out.println("  ✗ PageMap 偏移超出文件范围");
                }
            } else {
                System.out.println("  ✗ PageMap 参数不合理, 可能不是 R2007 风格");
            }
        } catch (Exception e) {
            System.out.println("RS 解码失败: " + e.getMessage());
        }
    }
    
    static int readLE32(byte[] data, int off) {
        return (data[off] & 0xFF) |
               ((data[off + 1] & 0xFF) << 8) |
               ((data[off + 2] & 0xFF) << 16) |
               ((data[off + 3] & 0xFF) << 24);
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
}
