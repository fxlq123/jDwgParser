package run;

import io.dwg.core.io.*;
import io.dwg.core.util.*;
import io.dwg.core.version.*;
import io.dwg.entities.concrete.*;
import java.nio.file.*;
import java.util.*;

/**
 * 深入分析 R2018 文件数据字节，了解正确的 page map 位置和格式
 */
public class AnalyzeR2018DataOffsets {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];

        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("=== 分析: " + path + " (大小: " + data.length + ") ===");
        System.out.println();

        // 1. 分析头部字段
        System.out.println("=== 头部关键字段 ===");
        // 尝试各种可能的 page map 偏移
        // R2007 规范: 0x480 之后是 page map
        // R2018 可能头部更小

        // 分析 0x00-0x40 的 LE32 字段
        for (int off = 0x00; off <= 0x40; off += 4) {
            int val = readLE32(data, off);
            if (val > 0 && val < 0x1000000) {
                System.out.println("  0x" + String.format("%02X", off) +
                    ": LE32 = 0x" + Integer.toHexString(val) + " (" + val + ")");
            }
        }
        System.out.println();

        // 2. 找到 PNG 预览结束位置 (第一个 0x4163 之前的位置)
        System.out.println("=== 寻找第一个 0x4163 标记 ===");
        int firstMarker = -1;
        for (int i = 0x100; i < data.length - 2; i++) {
            if ((data[i] & 0xFF) == 0x63 && (data[i+1] & 0xFF) == 0x41) {
                firstMarker = i;
                break;
            }
        }
        if (firstMarker > 0) {
            System.out.println("第一个 0x4163 @ 0x" + Integer.toHexString(firstMarker));
            // 打印上下文
            System.out.println("前 32 字节:");
            printHex(data, firstMarker - 32, 32);
            System.out.println("标记 + 后续 32 字节:");
            printHex(data, firstMarker, 64);
        }
        System.out.println();

        // 3. 检查 0x80-0x1FF 区域，这可能是 RS 编码的头部
        System.out.println("=== 检查 0x80-0x1FF 区域 (可能是 RS 编码的头部) ===");
        for (int i = 0x80; i < 0x200; i += 32) {
            System.out.print("0x" + String.format("%03X", i) + ": ");
            for (int j = 0; j < 32 && i + j < data.length; j++) {
                System.out.printf("%02X ", data[i+j]);
            }
            System.out.println();
        }
        System.out.println();

        // 4. 尝试用 R2007 风格的 page map 提取 (0x480 之后)
        // R2007 格式: 0x480 + pageMapOffset 处是 page map
        // page map 经过 RS 解码和 LZ77 解压
        // 让我们尝试在 0x1000 附近寻找压缩数据
        System.out.println("=== 检查 0x1000-0x2000 区域 (可能是 page map / section data) ===");
        for (int start = 0x1000; start < 0x3000; start += 256) {
            int count = 0;
            for (int i = 0; i < 256 && start + i < data.length; i++) {
                if ((data[start+i] & 0xFF) == 0x00) count++;
            }
            if (count > 200) {
                System.out.println("@0x" + Integer.toHexString(start) +
                    ": 超过 200 个零字节 (共 " + count + ") - 可能是填充");
            }
        }
        System.out.println();

        // 5. 寻找非零字节的密集区域 (实际数据)
        System.out.println("=== 寻找实际数据区域 ===");
        int dataStart = -1;
        int dataEnd = -1;
        for (int i = 0; i < data.length; i++) {
            if (data[i] != 0 && dataStart < 0) {
                dataStart = i;
            }
            if (data[i] != 0) dataEnd = i;
        }
        System.out.println("非零数据区域: 0x" + Integer.toHexString(dataStart) +
            " - 0x" + Integer.toHexString(dataEnd));
        System.out.println();

        // 6. 查看对象 section 数据的前几个字节
        // 先尝试用当前 R2018FileStructureHandler
        io.dwg.format.r2018.R2018FileStructureHandler handler =
            new io.dwg.format.r2018.R2018FileStructureHandler();
        BitInput input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data));
        io.dwg.format.common.FileHeaderFields header = handler.readHeader(input);

        input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data));
        java.util.Map<String, SectionInputStream> sections =
            handler.readSections(input, header);

        SectionInputStream objStream = sections.get("AcDb:AcDbObjects");
        if (objStream != null) {
            System.out.println("=== AcDb:AcDbObjects section (前 128 字节) ===");
            byte[] objData = objStream.getData();
            for (int i = 0; i < Math.min(128, objData.length); i += 16) {
                System.out.printf("%04X: ", i);
                for (int j = 0; j < 16 && i + j < objData.length; j++) {
                    System.out.printf("%02X ", objData[i + j]);
                }
                System.out.print("  |");
                for (int j = 0; j < 16 && i + j < objData.length; j++) {
                    int b = objData[i + j] & 0xFF;
                    if (b >= 32 && b < 127) System.out.print((char)b);
                    else System.out.print('.');
                }
                System.out.println("|");
            }
            System.out.println();

            // 检查是否有明显的模式 - 比如前 4 字节是否是 size
            if (objData.length > 8) {
                int objSize = (objData[0] & 0xFF) |
                    ((objData[1] & 0xFF) << 8) |
                    ((objData[2] & 0xFF) << 16) |
                    ((objData[3] & 0xFF) << 24);
                System.out.println("假设前 4 字节是 LE32 对象大小: " + objSize);

                int objSizeShort = (objData[0] & 0xFF) | ((objData[1] & 0xFF) << 8);
                System.out.println("假设前 2 字节是 LE16 对象大小: " + objSizeShort);
            }
        }
        System.out.println();

        // 7. 分析对象流中的潜在对象格式
        // R2010+ 对象格式:
        //   size (MS) + UMC + BOT(2 bits) + type (variable bits) + 实体数据
        // 让我们尝试在对象 section 数据中找到有效的起始点

        if (objStream != null) {
            byte[] objData = objStream.getData();
            System.out.println("=== 尝试分析对象流中的 BOT 模式 ===");

            // 尝试不同的起始偏移
            for (int startOffset = 0; startOffset < Math.min(64, objData.length - 16); startOffset++) {
                // 检查是否有有效的 modular short
                // 简化的 MS: 第一个字节如果 < 128，就是一个字节的 MS
                int firstByte = objData[startOffset] & 0xFF;
                if (firstByte < 64 && firstByte > 0) {  // 小的值可能是 MS
                    // 尝试解码
                    int ms = firstByte;  // 假设单字节 MS
                    int nextByte = startOffset + 1 < objData.length ? objData[startOffset + 1] & 0xFF : 0;
                    if (ms >= 4 && ms <= 256) {
                        System.out.println("@偏移 " + startOffset + ": MS=" + ms +
                            ", next=0x" + Integer.toHexString(nextByte));
                        if (startOffset > 20) break;  // 只看前几个
                    }
                }
            }
        }
        System.out.println();

        // 8. 查看 R2007 样本的对象 section 数据格式（用于对比）
        System.out.println("=== 对比: 查看 R2007 文件的格式 ===");
        String r2007Path = "samples/2007/Arc.dwg";
        if (new java.io.File(r2007Path).exists()) {
            byte[] r2007data = Files.readAllBytes(Paths.get(r2007Path));
            System.out.println("R2007 " + r2007Path + " 大小: " + r2007data.length);

            // 用 R2007 handler 提取
            io.dwg.format.r2007.R2007FileStructureHandler r2007handler =
                new io.dwg.format.r2007.R2007FileStructureHandler();
            BitInput r2007input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(r2007data));
            io.dwg.format.common.FileHeaderFields r2007header = r2007handler.readHeader(r2007input);
            System.out.println("  pageMapOffset: 0x" + Long.toHexString(r2007header.pageMapOffset()));
            System.out.println("  pageMapSizeComp: " + r2007header.pageMapSizeComp());
            System.out.println("  pageMapSizeUncomp: " + r2007header.pageMapSizeUncomp());

            r2007input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(r2007data));
            java.util.Map<String, SectionInputStream> r2007sections =
                r2007handler.readSections(r2007input, r2007header);
            SectionInputStream r2007objects = r2007sections.get("AcDb:AcDbObjects");
            if (r2007objects != null) {
                byte[] r2007objData = r2007objects.getData();
                System.out.println("  对象 section 大小: " + r2007objData.length);
                System.out.println("  前 64 字节:");
                for (int i = 0; i < Math.min(64, r2007objData.length); i += 16) {
                    System.out.printf("  %04X: ", i);
                    for (int j = 0; j < 16 && i + j < r2007objData.length; j++) {
                        System.out.printf("%02X ", r2007objData[i + j]);
                    }
                    System.out.println();
                }
            }
        }
    }

    static int readLE32(byte[] data, int off) {
        if (off + 4 > data.length) return -1;
        return (data[off] & 0xFF) |
            ((data[off+1] & 0xFF) << 8) |
            ((data[off+2] & 0xFF) << 16) |
            ((data[off+3] & 0xFF) << 24);
    }

    static void printHex(byte[] data, int start, int length) {
        for (int i = 0; i < length; i += 16) {
            System.out.printf("  %04X: ", start + i);
            for (int j = 0; j < 16 && start + i + j < data.length; j++) {
                System.out.printf("%02X ", data[start + i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && start + i + j < data.length; j++) {
                int b = data[start + i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char)b);
                else System.out.print('.');
            }
            System.out.println("|");
        }
    }
}
