package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 分析 R2000 文件中 section 间隙的数据 - 这是实际的对象存储位置
 */
public class R2000GapAnalyzer {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 从前面的分析: section locations (按偏移排序)
        // Section #5: 0x61 - 0xdc (123 bytes)
        // Section #0: 0x49bf - 0x4bd5 (534 bytes)
        // Section #1: 0x4ed0 - 0x50b9 (489 bytes)
        // Section #2: 0x11943 - 0x12113 (2000 bytes)
        // Section #3: 0x12113 - 0x12148 (53 bytes)
        // Section #4: 0x121e2 - 0x121e6 (4 bytes)

        // 间隙:
        // Gap A: 0xDC - 0x49BF (~18KB)
        // Gap B: 0x4BD5 - 0x4ED0 (~760 bytes)
        // Gap C: 0x50B9 - 0x11943 (~51KB) - 最大的间隙！
        // Gap D: 0x12148 - 0x121E2 (154 bytes)

        long[][] sections = {
            {5, 0x61, 123},
            {0, 0x49bf, 534},
            {1, 0x4ed0, 489},
            {2, 0x11943, 2000},
            {3, 0x12113, 53},
            {4, 0x121e2, 4}
        };

        // 找出所有间隙
        System.out.println("=== Section 间隙分析 ===");
        Arrays.sort(sections, (a, b) -> Long.compare(a[1], b[1]));

        for (int i = 0; i < sections.length - 1; i++) {
            long gapStart = sections[i][1] + sections[i][2];
            long gapEnd = sections[i+1][1];
            long gapSize = gapEnd - gapStart;
            System.out.println("Gap " + (char)('A' + i) + ": 0x" +
                Long.toHexString(gapStart) + " - 0x" + Long.toHexString(gapEnd) +
                " (" + gapSize + " bytes)");
        }

        // 分析最大的间隙: Gap C: 0x50B9 - 0x11943 (~51KB)
        // 这里应该包含对象数据
        System.out.println();
        System.out.println("=== Gap C (0x50B9 - 0x11943) 分析 - 最大的间隙 (~51KB) ===");
        analyzeGap(data, 0x50B9, (int) (0x11943 - 0x50B9), "Gap C");

        // 分析 Gap A: 0xDC - 0x49BF
        System.out.println();
        System.out.println("=== Gap A (0xDC - 0x49BF) 分析 (~18KB) ===");
        analyzeGap(data, 0xDC, (int) (0x49bf - 0xDC), "Gap A");

        // 分析 Gap B: 0x4BD5 - 0x4ED0
        System.out.println();
        System.out.println("=== Gap B (0x4BD5 - 0x4ED0) 分析 (~760B) ===");
        analyzeGap(data, 0x4BD5, (int) (0x4ed0 - 0x4BD5), "Gap B");

        // 分析 Gap D: 0x12148 - 0x121E2
        System.out.println();
        System.out.println("=== Gap D (0x12148 - 0x121E2) 分析 (~154B) ===");
        analyzeGap(data, 0x12148, (int) (0x121e2 - 0x12148), "Gap D");

        // 使用位级解析器在最大间隙中查找对象
        System.out.println();
        System.out.println("=== Gap C 的位级对象解析 ===");
        parseObjectsInGap(data, 0x50B9, (int) (0x11943 - 0x50B9));
    }

    private static void analyzeGap(byte[] data, int offset, int size, String name) {
        if (size <= 0) {
            System.out.println("  空间隙");
            return;
        }
        System.out.println("  " + name + ": offset=0x" + Integer.toHexString(offset) +
            " size=" + size + " bytes");

        // 打印前64字节的十六进制
        System.out.println("  前64字节:");
        int printSize = Math.min(size, 64);
        for (int i = 0; i < printSize; i++) {
            if (i % 16 == 0) System.out.print("    " + String.format("%04X: ", i));
            System.out.print(String.format("%02X ", data[offset + i]));
            if (i % 16 == 15) System.out.println();
        }
        System.out.println();

        // 检查中间32字节
        if (size > 200) {
            System.out.println("  中间32字节 @ offset " + (size/2) + ":");
            int mid = offset + size/2;
            for (int i = 0; i < Math.min(32, size - size/2); i++) {
                if (i % 16 == 0) System.out.print("    " + String.format("%04X: ", i));
                System.out.print(String.format("%02X ", data[mid + i]));
                if (i % 16 == 15) System.out.println();
            }
            System.out.println();
        }

        // 检查末尾32字节
        if (size > 32) {
            System.out.println("  最后32字节:");
            int end = offset + size - 32;
            for (int i = 0; i < 32; i++) {
                if (i % 16 == 0) System.out.print("    " + String.format("%04X: ", i));
                System.out.print(String.format("%02X ", data[end + i]));
                if (i % 16 == 15) System.out.println();
            }
        }
    }

    private static void parseObjectsInGap(byte[] data, int startOffset, int size) {
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        Map<Integer, Integer> typeCount = new LinkedHashMap<>();
        int objCount = 0;
        int byteOffset = startOffset;
        int endOffset = startOffset + size;

        // 尝试多种起始位置/偏移解析
        // R2000 对象通常对齐到字节边界
        while (byteOffset < endOffset - 4) {
            bbuf.seek((long) byteOffset * 8);

            try {
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x8000) {
                    byteOffset++;
                    continue;
                }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 500) {
                    byteOffset++;
                    continue;
                }

                // 有效的对象！
                typeCount.merge(typeCode, 1, Integer::sum);
                objCount++;

                // 跳到下一个对象
                // objSize 是从 MS 之后开始的字节数
                // 重新从起点计算
                ByteBufferBitInput bbuf2 = new ByteBufferBitInput(ByteBuffer.wrap(data));
                bbuf2.seek((long) byteOffset * 8);
                BitStreamReader r2 = new BitStreamReader(bbuf2, DwgVersion.R2000);
                r2.readModularShort(); // 读取 MS 以获取其字节大小
                long msBits = bbuf2.position() - (long) byteOffset * 8;
                long nextStartBit = (long) byteOffset * 8 + msBits + (long) objSize * 8;
                int nextByteOffset = (int) ((nextStartBit + 7) / 8);

                if (nextByteOffset <= byteOffset || nextByteOffset > endOffset) {
                    byteOffset++;
                    continue;
                }

                byteOffset = nextByteOffset;

            } catch (Exception e) {
                byteOffset++;
            }
        }

        System.out.println("  共找到 " + objCount + " 个对象");
        System.out.println("  类型分布:");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<Integer, Integer> e : sorted) {
            String tname = getTypeName(e.getKey());
            System.out.printf("    Type 0x%02X (%3d): %4d 个 - %s%n",
                e.getKey(), e.getKey(), e.getValue(), tname);
        }
    }

    private static String getTypeName(int typeCode) {
        return switch (typeCode) {
            case 0x01 -> "TEXT";
            case 0x03 -> "ATTRIB";
            case 0x04 -> "ATTDEF";
            case 0x05 -> "BLOCK_HEADER";
            case 0x06 -> "BLOCK_END";
            case 0x07 -> "INSERT";
            case 0x08 -> "MINSERT";
            case 0x0F -> "LINE";
            case 0x11 -> "CIRCLE";
            case 0x12 -> "ARC";
            case 0x14 -> "SPLINE";
            case 0x15 -> "ELLIPSE";
            case 0x1A -> "POINT";
            case 0x1F -> "SOLID";
            case 0x20 -> "TRACE";
            case 0x25 -> "LWPOLYLINE";
            case 0x27 -> "HATCH";
            case 0x28 -> "XRECORD";
            case 0x2B -> "MTEXT";
            case 0x2E -> "LEADER";
            case 0x2F -> "TOLERANCE";
            case 0x30 -> "BLOCK_HEADER (R2000)";
            case 0x31 -> "BLOCK_END (R2000)";
            case 0x32 -> "INSERT (R2000)";
            case 0x36 -> "DICTIONARY";
            case 0x3B -> "LAYOUT";
            case 0x43 -> "OLE2FRAME";
            case 0x4B -> "DICTIONARYVAR";
            case 0x4C -> "PLACEHOLDER";
            case 0x100 -> "LAYER";
            case 0x102 -> "STYLE";
            case 0x103 -> "LTYPE";
            case 0x104 -> "DIMSTYLE";
            case 0x107 -> "VIEWPORT";
            case 0x108 -> "APPID";
            case 0x110 -> "MLINESTYLE";
            default -> "UNKNOWN";
        };
    }
}
