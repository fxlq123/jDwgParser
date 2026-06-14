package run;

import io.dwg.core.io.*;
import io.dwg.core.util.*;
import io.dwg.core.version.DwgVersion;
import java.nio.file.*;
import java.util.*;

/**
 * 简单的 R2018 文件结构分析工具
 * 
 * R2018 (AC1032) 的特点:
 * - 明文头部，不使用 Reed-Solomon 编码
 * - 使用 Page Map → Section Map 结构定位数据
 * - 页面数据使用 LZ77 压缩（可能不需要 RS 编码）
 * - 头部大小: 0x100 (不是 R2007 的 0x480)
 */
public class SimpleR2018Analyzer {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];

        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("=== 文件: " + path + " ===");
        System.out.println("大小: " + data.length + " 字节 (0x" +
            Integer.toHexString(data.length) + ")");
        System.out.println();

        // 1. 检查版本字符串
        String version = new String(Arrays.copyOfRange(data, 0, 6));
        System.out.println("版本字符串: " + version);
        System.out.println();

        // 2. 打印头部 (前 256 字节 = 0x100)
        System.out.println("=== 头部 (0x00 - 0x0FF) ===");
        for (int i = 0; i < Math.min(0x100, data.length); i += 16) {
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

        // 3. 尝试从头部提取可能的字段
        // 假设: 0x00-0x05 = 版本, 然后是字段
        // 让我们尝试多种可能的字段布局
        System.out.println("=== 尝试多种字段布局解释 ===");

        // 布局 A: 类似 R2007 但无 RS 编码
        // 0x00(6): version
        // 0x06(10): zeros/reserved
        // 0x10(8): pageMapOffset (LE64, 相对 0x100)
        // 0x18(8): pageMapSizeComp
        // 0x20(8): pageMapSizeUncomp
        // 0x28(8): sectionMapId
        // 0x30(8): sectionMapSizeComp
        // 0x38(8): sectionMapSizeUncomp
        long pmo_A = readLE64(data, 0x10);
        long pmsc_A = readLE64(data, 0x18);
        long pmsu_A = readLE64(data, 0x20);
        long smid_A = readLE64(data, 0x28);
        long smsc_A = readLE64(data, 0x30);
        long smsU_A = readLE64(data, 0x38);

        System.out.println("布局 A (0x100 头部, LE64):");
        System.out.println("  pageMapOffset      : 0x" + Long.toHexString(pmo_A) + " = " + pmo_A);
        System.out.println("  pageMapSizeComp    : 0x" + Long.toHexString(pmsc_A) + " = " + pmsc_A);
        System.out.println("  pageMapSizeUncomp  : 0x" + Long.toHexString(pmsu_A) + " = " + pmsu_A);
        System.out.println("  sectionMapId       : 0x" + Long.toHexString(smid_A) + " = " + smid_A);
        System.out.println("  sectionMapSizeComp : 0x" + Long.toHexString(smsc_A) + " = " + smsc_A);
        System.out.println("  sectionMapSizeUncomp: 0x" + Long.toHexString(smsU_A) + " = " + smsU_A);
        System.out.println();

        // 4. 搜索文件中的关键标记
        System.out.println("=== 搜索关键标记 ===");

        // 搜索 "Section" 相关标记
        int acDbCount = 0;
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == 'A' && data[i+1] == 'c' && data[i+2] == 'D' && data[i+3] == 'b') {
                acDbCount++;
            }
        }
        System.out.println("'AcDb' 出现次数: " + acDbCount);

        // 搜索 0x4163 (LE16 = 'Ac') - R2007 中的 section 起始标记
        int marker4163 = 0;
        int first4163 = -1;
        for (int i = 0; i < data.length - 2; i++) {
            if ((data[i] & 0xFF) == 0x63 && (data[i+1] & 0xFF) == 0x41) {  // LE16 0x4163
                marker4163++;
                if (first4163 < 0) first4163 = i;
            }
        }
        System.out.println("LE16 0x4163 (\"Ac\") 标记出现次数: " + marker4163);
        if (first4163 >= 0) System.out.println("  第一次出现 @ 0x" + Integer.toHexString(first4163));
        System.out.println();

        // 5. 让我们检查 PNDIEND (如果有 PNG 预览)
        int iendPos = -1;
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == 'I' && data[i+1] == 'E' && data[i+2] == 'N' && data[i+3] == 'D') {
                iendPos = i;
                break;
            }
        }
        if (iendPos > 0) {
            System.out.println("PNG IEND @ 0x" + Integer.toHexString(iendPos));
            int dataAfterPng = iendPos + 8;  // IEND chunk = 4 bytes length + 4 bytes type + data + 4 bytes CRC
            // 实际上 IEND 后还需要 CRC
            // 但通常: length(4) + type(4) + data(length) + CRC(4)
            // IEND 的 length 是 0
            dataAfterPng = iendPos + 8;  // length(0) + type(IEND) + CRC(4) = 8
            System.out.println("PNG 后的数据开始 @ 0x" + Integer.toHexString(dataAfterPng));

            // 打印 PNG 后 256 字节
            System.out.println("PNG 后 256 字节:");
            for (int i = dataAfterPng; i < Math.min(dataAfterPng + 256, data.length); i += 16) {
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
        } else {
            System.out.println("文件中没有找到 PNG IEND");
        }
        System.out.println();

        // 6. 直接尝试找 page map 数据 (不使用 RS 编码)
        // R2018 可能直接用压缩数据存储
        System.out.println("=== 测试不同数据区域 ===");
        int[] testOffsets = {0x100, 0x200, 0x400, 0x480, 0x500, 0x800, 0x1000, 0x2000};
        for (int offset : testOffsets) {
            if (offset >= data.length) continue;
            int size = Math.min(64, data.length - offset);
            System.out.println("@0x" + Integer.toHexString(offset) + " (前 " + size + " 字节):");
            System.out.print("    ");
            for (int i = 0; i < size && offset + i < data.length; i++) {
                System.out.printf("%02X ", data[offset + i]);
                if (i % 16 == 15 && i != size - 1) {
                    System.out.println();
                    System.out.print("    ");
                }
            }
            System.out.println();
        }
        System.out.println();

        // 7. 尝试找到压缩的 page map
        // R2007 的 page map 格式: 解压后是 LE32 pageId, LE32 pageSize 的数组
        // R2018 可能类似
        System.out.println("=== 搜索零字节区域 (R2007 头部填充区域) ===");
        for (int i = 0x80; i < Math.min(0x500, data.length - 16); i += 16) {
            boolean allZero = true;
            for (int j = 0; j < 16; j++) {
                if (data[i + j] != 0) { allZero = false; break; }
            }
            if (allZero) {
                System.out.println("  @ 0x" + Integer.toHexString(i) + ": 全零");
            }
        }
    }

    static long readLE64(byte[] data, int off) {
        if (off + 8 > data.length) return -1;
        return ((long)(data[off] & 0xFF)) |
               ((long)(data[off+1] & 0xFF) << 8) |
               ((long)(data[off+2] & 0xFF) << 16) |
               ((long)(data[off+3] & 0xFF) << 24) |
               ((long)(data[off+4] & 0xFF) << 32) |
               ((long)(data[off+5] & 0xFF) << 40) |
               ((long)(data[off+6] & 0xFF) << 48) |
               ((long)(data[off+7] & 0xFF) << 56);
    }
}
