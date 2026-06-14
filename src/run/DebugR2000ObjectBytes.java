package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;

import java.nio.file.Paths;
import java.util.*;

/**
 * 深入检查 R2000 对象的实际字节结构
 */
public class DebugR2000ObjectBytes {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("=== R2000 对象数据深入分析 ===\n");

        // 1. 获取 handle registry
        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        // 获取排序后的 handle 和它们的偏移
        List<Long> sortedHandles = new ArrayList<>();
        for (long h : registry.allHandles()) sortedHandles.add(h);
        Collections.sort(sortedHandles);
        System.out.println("总 handle 数: " + sortedHandles.size());

        // 2. 检查第一个对象 (handle=1, offset=21177) - 应该是 BLOCK_HEADER
        long h1 = 1L;
        long offset1 = registry.offsetFor(h1).orElse(-1L);
        System.out.println("\n=== 对象 1 (handle=0x" + Long.toHexString(h1) +
            ") @offset=" + offset1 + " (0x" + Long.toHexString(offset1) + ") ===");
        analyzeObjectAt(data, (int)offset1, "BLOCK_HEADER");

        // 3. 检查 INSERT 实体
        // 先找到 INSERT 的 handle 和 offset
        System.out.println("\n=== 查找 INSERT 实体 ===");
        DwgDocument doc = DwgReader.defaultReader().open(data);
        long firstInsertHandle = -1;
        long insertOffset = -1;

        for (long h : sortedHandles) {
            DwgObject obj = doc.objectMap().get(h);
            if (obj != null && obj instanceof DwgInsert) {
                firstInsertHandle = h;
                insertOffset = registry.offsetFor(h).orElse(-1L);
                System.out.println("找到 INSERT @handle=0x" + Long.toHexString(h) +
                    " offset=" + insertOffset);
                break;
            }
        }

        if (insertOffset > 0) {
            analyzeObjectAt(data, (int)insertOffset, "INSERT");
        }

        // 4. 显示对象数据的整体布局
        System.out.println("\n=== 对象数据区域 (21000-22000) ===");
        dumpRange(data, 21000, 22000);

        // 5. 检查几个可能是 block 定义的位置
        System.out.println("\n=== 检查可能是 BLOCK_HEADER 的对象 ===");
        // 在 R2000 中，block header 通常在对象表的开头
        // 让我检查前 50 个对象的类型码
        System.out.println("前 30 个对象:");
        for (int i = 0; i < Math.min(30, sortedHandles.size()); i++) {
            long h = sortedHandles.get(i);
            long off = registry.offsetFor(h).orElse(-1L);
            if (off > 0 && off < data.length - 16) {
                // 读取 MS (对象大小) 和 type code
                // R2000: MS 16bit LE + type code 16bit LE (BS)
                int ms = readModularShort(data, (int)off);
                int typeOffset = (int)off + 2; // MS之后是type code
                // 但 R2000 对象格式: obj_size (MS) + type_code (BS) + common_header + specific_data
                int typeCode = readBitShort(data, typeOffset);
                System.out.println("  [" + i + "] handle=0x" + Long.toHexString(h) +
                    " offset=" + off + " objSize=" + ms + " typeCode=0x" +
                    Integer.toHexString(typeCode) + "(" + typeCode + ")");
            }
        }

        // 6. 检查 R2000 中 BLOCK_HEADER 的类型码
        // 理论上是 0x30 (=48)
        System.out.println("\n=== 查找 typeCode=0x30 (BLOCK_HEADER) 的所有对象 ===");
        for (long h : sortedHandles) {
            long off = registry.offsetFor(h).orElse(-1L);
            if (off > 0 && off < data.length - 16) {
                // 读取 type code：MS之后
                int ms = readModularShort(data, (int)off);
                int typeCode;
                if (ms > 0 && ms < 5000) {
                    // MS之后是type (MS是16bit可变长)
                    // 先检查 MS 占用多少字节
                    // 简化: 直接跳 MS 字节数后读type
                    typeCode = readBitShort(data, (int)off + getMSByteSize(data, (int)off));
                } else {
                    typeCode = -1;
                }
                if (typeCode == 0x30) {
                    System.out.println("  BLOCK_HEADER: handle=0x" + Long.toHexString(h) +
                        " offset=" + off + " objSize=" + ms);
                    // 显示这个对象的内容
                    System.out.print("    bytes: ");
                    for (int i = 0; i < Math.min(64, ms+10); i++) {
                        System.out.printf("%02X ", data[(int)off + i] & 0xFF);
                    }
                    System.out.println();
                }
            }
        }
    }

    private static void analyzeObjectAt(byte[] data, int offset, String name) {
        if (offset < 0 || offset >= data.length - 32) {
            System.out.println("无效的 offset!");
            return;
        }

        // 读取 MS (obj_size)
        int ms = readModularShort(data, offset);
        System.out.println("obj_size (MS): " + ms);

        int msSize = getMSByteSize(data, offset);
        System.out.println("MS 占用 " + msSize + " 字节");

        // 读取 type code (BS, 16bit)
        int typeStart = offset + msSize;
        int typeCode = readBitShort(data, typeStart);
        System.out.println("type code: 0x" + Integer.toHexString(typeCode) + " (" + typeCode + ")");

        // 显示对象的原始字节
        System.out.print("原始字节 (obj_size+10): ");
        for (int i = 0; i < Math.min(ms + 16, 128); i++) {
            if (offset + i >= data.length) break;
            System.out.printf("%02X ", data[offset + i] & 0xFF);
            if (i == msSize - 1) System.out.print("| ");
            if (i == msSize + 1) System.out.print("| ");
        }
        System.out.println();

        // 尝试解析 block_name
        // BLOCK_HEADER 格式 (理论):
        //   common entity data (EntityHeaderReader)
        //   block_name (TU)
        //   flags (BS)
        //   base_point (3RD)
        //   xref_path (TU)
        //
        // 但 R2000 可能没有 common entity data (因为 BLOCK_HEADER 不是 entity!)
        // 让我跳过 entity header, 直接读 variable text

        // 显示附近的 ASCII 字符串
        System.out.print("ASCII:    ");
        for (int i = 0; i < Math.min(ms + 16, 80); i++) {
            if (offset + i >= data.length) break;
            int b = data[offset + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();

        // 尝试从偏移处读取 variable text
        // TU格式: length(BS) + char数组(8bit ASCII)
        int textStart = typeStart + 2; // 跳过 type code
        // 但实际上需要先跳过 object common header / entity header
        // 让我逐步检查
        System.out.println("\n尝试不同的偏移位置读取文本:");
        for (int pos : new int[] {2, 4, 6, 10, 14, 18, 22, 26, 30, 34, 38, 42}) {
            int readPos = typeStart + 2 + pos;
            if (readPos >= data.length) break;

            // 读取 potential text length
            int textLen = (data[readPos] & 0xFF) | ((data[readPos+1] & 0xFF) << 8);
            // 注意: BS 可能是大端或小端
            int textLenBE = ((data[readPos] & 0xFF) << 8) | (data[readPos+1] & 0xFF);

            // 检查是否是合理的文本长度
            if (textLen > 0 && textLen < 100) {
                // 尝试读取文本
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < textLen && readPos + 2 + i < data.length; i++) {
                    int c = data[readPos + 2 + i] & 0xFF;
                    if (c >= 32 && c < 127) sb.append((char)c);
                }
                if (sb.length() >= 2) {
                    System.out.println("  [+"+pos+"] len=" + textLen + " (LE): '" + sb + "'");
                }
            }
            if (textLenBE > 0 && textLenBE < 100 && textLenBE != textLen) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < textLenBE && readPos + 2 + i < data.length; i++) {
                    int c = data[readPos + 2 + i] & 0xFF;
                    if (c >= 32 && c < 127) sb.append((char)c);
                }
                if (sb.length() >= 2) {
                    System.out.println("  [+"+pos+"] len=" + textLenBE + " (BE): '" + sb + "'");
                }
            }
        }

        // 另一种方法: 直接找字符串
        System.out.println("\n附近的字符串:");
        for (int start = 2; start < Math.min(100, ms); start++) {
            int readPos = typeStart + 2 + start;
            if (readPos >= data.length - 5) break;

            // 检查是否是 ASCII 字符串
            StringBuilder sb = new StringBuilder();
            int len = 0;
            while (readPos + len < data.length && len < 50) {
                int c = data[readPos + len] & 0xFF;
                if (c >= 32 && c < 127) {
                    sb.append((char)c);
                    len++;
                } else {
                    break;
                }
            }
            if (len >= 3) {
                System.out.println("  [+" + start + "]: '" + sb + "'");
                start += len; // 跳过已读取的文本
            }
        }
    }

    private static int readModularShort(byte[] data, int offset) {
        // R2000 的 MS: 16bit LE short
        // 但也可能是 modular short
        // 先试试 LE16
        int le16 = (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8);
        return le16;
    }

    private static int readBitShort(byte[] data, int offset) {
        // 同样尝试 LE16
        if (offset >= data.length - 1) return -1;
        return (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8);
    }

    private static int getMSByteSize(byte[] data, int offset) {
        // 对 R2000, MS 就是 2 bytes (LE16 short)
        return 2;
    }

    private static void dumpRange(byte[] data, int start, int end) {
        for (int i = start; i < end; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < end; j++) {
                if (i + j >= data.length) break;
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.printf("  %06X: %-48s |%s|%n", i, hex, ascii);
        }
    }
}
