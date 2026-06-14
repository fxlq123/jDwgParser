package run;

import io.dwg.core.io.*;
import io.dwg.core.util.*;
import io.dwg.core.version.*;
import io.dwg.format.common.*;
import io.dwg.format.r2018.*;
import java.nio.file.*;
import java.util.*;

/**
 * 分析 R2018 对象数据的实际位级编码
 * 关键问题: blockName 和 basePoint 显示乱码/异常值
 * 需要找到: 对象数据的正确字节/位对齐方式
 */
public class AnalyzeR2018ObjectEncoding {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];

        byte[] data = Files.readAllBytes(Paths.get(path));
        System.out.println("=== R2018 对象编码分析: " + path + " ===");

        // 1. 提取对象 section
        R2018FileStructureHandler handler = new R2018FileStructureHandler();
        BitInput input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data));
        FileHeaderFields header = handler.readHeader(input);

        input = new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data));
        Map<String, SectionInputStream> sections = handler.readSections(input, header);
        SectionInputStream objStream = sections.get("AcDb:AcDbObjects");
        if (objStream == null) {
            System.out.println("ERROR: 没有 AcDb:AcDbObjects section");
            return;
        }

        byte[] objBytes = objStream.rawBytes();
        System.out.println("对象 section 大小: " + objBytes.length + " bytes");
        System.out.println();

        // 2. 分析对象数据 - 尝试不同方式读取
        // 先打印前 256 字节的位模式
        System.out.println("=== 对象数据前 128 字节的详细分析 ===");
        for (int i = 0; i < Math.min(128, objBytes.length); i += 16) {
            System.out.printf("%04X: ", i);
            for (int j = 0; j < 16 && i + j < objBytes.length; j++) {
                System.out.printf("%02X ", objBytes[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < objBytes.length; j++) {
                int b = objBytes[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char)b);
                else System.out.print('.');
            }
            System.out.println("|");
        }
        System.out.println();

        // 3. 尝试作为 R2010+ 对象流读取
        // R2010+ 对象格式:
        //   Object Size (MS)
        //   UMC (handle/reference count)
        //   BOT (2 bits) + Object Type
        //   Common Entity Data (CED)
        //   Specific object data
        System.out.println("=== 尝试以 R2010+ 对象格式解析 ===");
        BitStreamReader reader = new BitStreamReader(
            new ByteBufferBitInput(objBytes), DwgVersion.R2010);

        // 读取前 10 个"对象"
        for (int objIdx = 0; objIdx < 5; objIdx++) {
            try {
                long startPos = reader.position();
                System.out.println("\n--- 对象 #" + objIdx + " @ bit position " + startPos + " (byte " + (startPos/8) + ") ---");

                // 尝试读取 MS (对象大小)
                int objSize = tryReadModularShort(objBytes, (int)(startPos / 8));
                System.out.println("  潜在 MS (对象大小): " + objSize);

                // 用位流方式读取
                int ms = reader.readModularShort();
                System.out.println("  bitstream MS: " + ms);

                // 然后尝试读 UMC
                long umc = reader.readUMC();
                System.out.println("  UMC: " + umc);

                // 然后尝试读 BOT (2 bits + type)
                BitInput bi = reader.getInput();
                int bb = bi.readBits(2);
                int typeCode;
                if (bb == 0) typeCode = bi.readBits(8) & 0xFF;
                else if (bb == 1) typeCode = bi.readBits(8) & 0xFF + 0x1F0;
                else if (bb == 2) typeCode = bi.readBits(16) & 0xFFFF;
                else typeCode = bi.readBits(32);

                System.out.println("  BOT bb=" + bb + ", type=" + typeCode + " (0x" +
                    Integer.toHexString(typeCode) + ")");

                // 查看后续 64 字节作为"对象数据"
                long posAfter = reader.position();
                System.out.println("  后续字节:");
                printByteRange(objBytes, (int)(posAfter/8), Math.min((int)(posAfter/8)+32, objBytes.length));

                // 尝试跳过这个"对象"，看下一个
                if (ms > 0 && ms < 0x10000) {
                    // 按字节跳过
                    reader.seek(startPos + ms * 8L);
                } else {
                    break;
                }

            } catch (Exception e) {
                System.out.println("  ERROR: " + e.getMessage());
                break;
            }
        }
        System.out.println();

        // 4. 让我们尝试用另一种方式: 直接按实体大小数据区域解析
        // 检查是否有重复的模式
        System.out.println("=== 检查数据模式 - 寻找对象边界 ===");
        // R2018 对象的存储方式可能与 R2007 不同，也许是分组的

        // 检查是否数据被划分为大小相似的块
        // 首先查看文件中 0x4163 标记附近的结构 (之前在 0x121fb 找到)
        System.out.println("\n=== 重新检查 0x4163 标记 ===");
        List<Integer> markers = new ArrayList<>();
        for (int i = 0; i < data.length - 2; i++) {
            if ((data[i] & 0xFF) == 0x63 && (data[i+1] & 0xFF) == 0x41) {
                markers.add(i);
                if (markers.size() > 20) break;
            }
        }
        System.out.println("找到前 " + markers.size() + " 个 0x4163 标记:");
        for (int i = 0; i < markers.size(); i++) {
            int pos = markers.get(i);
            // 读取后续的字节
            int le32val = 0;
            if (pos + 10 < data.length) {
                le32val = (data[pos+2] & 0xFF) |
                    ((data[pos+3] & 0xFF) << 8) |
                    ((data[pos+4] & 0xFF) << 16) |
                    ((data[pos+5] & 0xFF) << 24);
            }
            System.out.println("  [" + i + "] @0x" + Integer.toHexString(pos) +
                " 接下来 16 字节: " + bytesToHex(data, pos, 16));
            System.out.println("       LE32(pos+2)=0x" + Integer.toHexString(le32val));
        }

        System.out.println("\n=== 标记之间的距离 ===");
        for (int i = 1; i < markers.size(); i++) {
            int diff = markers.get(i) - markers.get(i-1);
            System.out.println("  " + i + "-" + (i-1) + ": " + diff + " bytes (0x" +
                Integer.toHexString(diff) + ")");
        }

        // 5. 关键分析: 检查对象 section 的数据是不是来自于正确的文件偏移
        // 当前 R2018FileStructureHandler 直接从0x100 后提取数据或者扫描 section 偏移
        // 但正确做法应该是像 R2007 一样提取 page map 和 section map

        // 让我们检查: 如果 0x4163 后面跟着大小信息
        // 那么第一个标记后面的数据应该可以被解码
        if (markers.size() > 0) {
            int firstMarker = markers.get(0);
            System.out.println("\n=== 分析第一个标记附近的数据结构 ===");
            System.out.println("位置: 0x" + Integer.toHexString(firstMarker));
            System.out.println("后续 256 字节:");
            printByteRange(data, firstMarker, firstMarker + 256);
        }

        // 6. 对比一下: R2007 的对象 section 提取方式
        // R2007: header (0x480) → page map (RS+LZ77) → page data → section map → object data
        // 也许 R2018 使用类似的结构，只是 header 大小不同，不需要 RS 编码
    }

    static int tryReadModularShort(byte[] data, int byteOff) {
        if (byteOff >= data.length) return -1;
        int b = data[byteOff] & 0xFF;
        if (b < 128) return b;
        if (byteOff + 1 < data.length) {
            return ((b - 128) << 8) | (data[byteOff + 1] & 0xFF);
        }
        return b;
    }

    static void printByteRange(byte[] data, int start, int end) {
        for (int i = start; i < end; i += 16) {
            System.out.printf("    %04X: ", i);
            for (int j = 0; j < 16 && i + j < end; j++) {
                System.out.printf("%02X ", data[i + j]);
            }
            System.out.print("  |");
            for (int j = 0; j < 16 && i + j < end; j++) {
                int b = data[i + j] & 0xFF;
                if (b >= 32 && b < 127) System.out.print((char)b);
                else System.out.print('.');
            }
            System.out.println("|");
        }
    }

    static String bytesToHex(byte[] data, int start, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && start + i < data.length; i++) {
            sb.append(String.format("%02X ", data[start + i]));
        }
        return sb.toString();
    }
}
