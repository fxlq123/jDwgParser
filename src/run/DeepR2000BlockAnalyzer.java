package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 深入分析 R2000 对象流，找出 BLOCK_HEADER 和 INSERT 对象
 * R2000 对象结构: MS(obj_size) + BS(type_code) + common_data + entity_data
 *
 * 实体类型码:
 *   BLOCK_HEADER = 0x30 (48)
 *   BLOCK_END    = 0x31 (49)
 *   INSERT       = 0x32 (50)
 */
public class DeepR2000BlockAnalyzer {

    private static final int BLOCK_HEADER_TYPE = 0x30;
    private static final int BLOCK_END_TYPE = 0x31;
    private static final int INSERT_TYPE = 0x32;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] fileData = java.nio.file.Files.readAllBytes(Paths.get(filename));
        System.out.println("文件大小: " + fileData.length + " 字节");

        // 1. 解析 header 获取 section 信息
        HeaderInfo header = parseR2000Header(fileData);
        System.out.println("Section 数量: " + header.sectionCount);
        System.out.println();

        // 2. 找到 Objects section (第4个 section)
        long objectsOffset = header.sectionOffsets[3];
        long objectsSize = header.sectionSizes[3];
        System.out.println("Objects Section: offset=0x" + Long.toHexString(objectsOffset) +
            ", size=" + objectsSize);

        byte[] objectsData = new byte[(int) objectsSize];
        System.arraycopy(fileData, (int) objectsOffset, objectsData, 0, (int) objectsSize);

        // 3. 分析对象流的类型码分布
        System.out.println();
        System.out.println("=== 对象类型码分布 ===");
        analyzeObjectStream(objectsData);

        // 4. 详细分析 BLOCK_HEADER 对象
        System.out.println();
        System.out.println("=== BLOCK_HEADER 详细分析 ===");
        analyzeBlockHeaders(objectsData);

        // 5. 详细分析 INSERT 对象
        System.out.println();
        System.out.println("=== INSERT 详细分析 ===");
        analyzeInserts(objectsData);
    }

    private static class HeaderInfo {
        int sectionCount;
        long[] sectionOffsets;
        long[] sectionSizes;
    }

    private static HeaderInfo parseR2000Header(byte[] data) {
        HeaderInfo info = new HeaderInfo();
        // 跳过 version string(6) + reserved(6) + RC(1) + preview RL(4) + RC(1) + RC(1) + RS(2)
        int offset = 6 + 6 + 1 + 4 + 1 + 1 + 2;
        ByteBuffer bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        info.sectionCount = bb.getInt(offset);
        offset += 4;

        info.sectionOffsets = new long[info.sectionCount];
        info.sectionSizes = new long[info.sectionCount];

        for (int i = 0; i < info.sectionCount; i++) {
            int number = data[offset] & 0xFF;
            offset += 1;
            long addr = bb.getInt(offset) & 0xFFFFFFFFL;
            offset += 4;
            long size = bb.getInt(offset) & 0xFFFFFFFFL;
            offset += 4;
            info.sectionOffsets[i] = addr;
            info.sectionSizes[i] = size;
            System.out.println("  Section[" + i + "] #num=" + number +
                " addr=0x" + Long.toHexString(addr) + " size=" + size);
        }
        return info;
    }

    private static void analyzeObjectStream(byte[] data) {
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();
        Map<Integer, String> typeNames = new HashMap<>();

        // 尝试不同策略来解析对象流
        // R2000 对象格式: MS(obj_size) + BS(type_code) + ...
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        int objCount = 0;
        int byteOffset = 0;

        try {
            while (byteOffset < data.length - 4) {
                // 对齐到字节边界
                long currentBit = bbuf.position();
                if (currentBit % 8 != 0) {
                    bbuf.seek((currentBit / 8 + 1) * 8);
                }

                try {
                    int objSize = r.readModularShort();
                    if (objSize <= 0 || objSize > 0x10000) {
                        // 跳过1字节
                        byteOffset++;
                        bbuf.seek((long) byteOffset * 8);
                        continue;
                    }

                    int typeCode = r.readBitShort();
                    if (typeCode < 0 || typeCode > 500) {
                        byteOffset++;
                        bbuf.seek((long) byteOffset * 8);
                        continue;
                    }

                    typeCount.merge(typeCode, 1, Integer::sum);
                    objCount++;

                    // 跳转到下一个对象
                    // 当前位置: MS + BS 消耗的 bits
                    // objSize 以字节为单位，从 type_code 之后开始？
                    // 简单处理: 跳到 byteOffset + 2(MS近似) + 2(BS近似) + objSize
                    // 实际应该从当前bit位置跳 objSize*8 位
                    long afterTypeBits = bbuf.position();
                    // objSize 是从 MS 之后开始的字节数（包含BS）
                    long nextObjStartBit = (long) byteOffset * 8 + (long) objSize * 8;
                    // 但MS本身可能占用多个16位，让我们用更简单的方式：
                    // 从 type_code 之后，跳过 objSize - BS 消耗字节数的数据
                    // 实际 libredwg: object_size 包括从 type_code 字节开始的一切
                    // 所以从 object_size 之后，我们应该位于: size_bytes + objSize
                    // 让我们用更稳健的方法
                    byteOffset = (int) (afterTypeBits / 8);
                    // 剩余字节 = objSize - BS消耗字节
                    // BS = 2位opcode + 0/8/16 位 = 1~3字节
                    // 简单从 afterType 跳到 afterType + (objSize - 3)*8
                    long remainingBytes = objSize - 3; // 粗略估计
                    if (remainingBytes > 0) {
                        bbuf.seek(afterTypeBits + remainingBytes * 8);
                        byteOffset += remainingBytes;
                    }

                    if (objCount % 100 == 0) {
                        System.out.println("  已解析 " + objCount + " 对象, offset=0x" + Integer.toHexString(byteOffset));
                    }

                    if (objCount > 2000) break;
                } catch (Exception e) {
                    byteOffset++;
                    bbuf.seek((long) byteOffset * 8);
                }
            }
        } catch (Exception e) {
            System.out.println("  解析终止于 offset=0x" + Integer.toHexString(byteOffset) + ": " + e.getMessage());
        }

        System.out.println("  共解析 " + objCount + " 个对象");
        System.out.println();

        // 输出类型码分布
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<Integer, Integer> e : sorted) {
            String name = getTypeName(e.getKey());
            System.out.printf("  Type 0x%02X (%3d): %4d 个 - %s%n",
                e.getKey(), e.getKey(), e.getValue(), name);
        }
    }

    private static String getTypeName(int typeCode) {
        return switch (typeCode) {
            case 0x01 -> "TEXT";
            case 0x03 -> "ATTRIB";
            case 0x04 -> "ATTDEF";
            case 0x05 -> "BLOCK / BLOCK_HEADER";
            case 0x06 -> "ENDBLK";
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
            case 0x33 -> "MINSERT (R2000)";
            case 0x36 -> "DICTIONARY";
            case 0x3B -> "LAYOUT";
            case 0x43 -> "OLE2FRAME";
            case 0x4B -> "DICTIONARYVAR";
            case 0x4C -> "PLACEHOLDER";
            case 0x50 -> "LAYER_INDEX";
            case 0x5F -> "HATCH";
            case 0x60 -> "XLINE";
            case 0x61 -> "RAY";
            case 0x65 -> "MESH";
            case 0x6D -> "SUBDIVISIONMESH";
            case 0x6E -> "WIPEOUT";
            case 0x78 -> "LIGHT";
            case 0x7C -> "LEADER";
            case 0x7F -> "TOLERANCE";
            case 0x80 -> "MULTILEADER";
            case 0x82 -> "MULTILEADER";
            case 0x85 -> "ACAD_PROXY_OBJECT";
            case 0x89 -> "DGNUNDERLAY";
            case 0x8F -> "ACAD_TABLE";
            case 0x9B -> "FIELD";
            case 0x9F -> "DYNAMICBLOCK";
            case 0xA9 -> "GEODATA";
            case 0x100 -> "LAYER";
            case 0x101 -> "LAYER_INDEX";
            case 0x102 -> "STYLE";
            case 0x103 -> "LTYPE";
            case 0x104 -> "DIMSTYLE";
            case 0x105 -> "VIEW";
            case 0x106 -> "UCS";
            case 0x107 -> "VIEWPORT";
            case 0x108 -> "APPID";
            case 0x109 -> "DIMSTYLE";
            case 0x110 -> "MLINESTYLE";
            case 0x1F0 -> "BLOCK_RECORD (R2004+)";
            default -> "UNKNOWN";
        };
    }

    private static void analyzeBlockHeaders(byte[] data) {
        // 直接字节搜索: 尝试找出具有 BLOCK_HEADER 类型码的对象
        // 并尝试解析其字段
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        int byteOffset = 0;
        int found = 0;

        while (byteOffset < data.length - 8) {
            bbuf.seek((long) byteOffset * 8);

            try {
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x1000) {
                    byteOffset++;
                    continue;
                }

                int typeCode = r.readBitShort();

                if (typeCode == BLOCK_HEADER_TYPE || typeCode == 5 || typeCode == 0x05) {
                    // 找到可能的 BLOCK_HEADER
                    // 尝试解析字段
                    System.out.println("  offset=0x" + Integer.toHexString(byteOffset) +
                        " size=" + objSize + " type=0x" + Integer.toHexString(typeCode));
                    try {
                        parseBlockHeaderFields(r, byteOffset, objSize);
                    } catch (Exception ex) {
                        System.out.println("    解析失败: " + ex.getMessage());
                    }
                    found++;
                    if (found > 100) break;
                }

                // 跳到下一个对象
                // 简单方式: objSize 字节(从 MS 开始算)
                // 但 MS 可能是多字节, 重新从 byteOffset 读取
                // 简化: byteOffset + objSize + sizeof(MS)
                // 先重定位
                ByteBufferBitInput bbuf2 = new ByteBufferBitInput(ByteBuffer.wrap(data));
                bbuf2.seek((long) byteOffset * 8);
                BitStreamReader r2 = new BitStreamReader(bbuf2, DwgVersion.R2000);
                r2.readModularShort(); // 跳过 MS
                long msBits = bbuf2.position() - (long) byteOffset * 8;
                long totalBits = (long) byteOffset * 8 + msBits + (long) objSize * 8;
                byteOffset = (int) (totalBits / 8);
            } catch (Exception e) {
                byteOffset++;
            }
        }

        System.out.println("  共找到 " + found + " 个可能的 BLOCK_HEADER");
    }

    private static void parseBlockHeaderFields(BitStreamReader r, int baseOffset, int objSize)
            throws Exception {
        // BLOCK_HEADER 对象结构:
        // common: numReactors(BL) + ownerHandle(H) + reactorHandles(H*) + xDictHandle(H)?
        // specific: blockName(T) + blockFlags(BS) + insertPoint(3BD) + xrefPath(T?)

        // 先看 common
        try {
            int numReactors = r.readBitLong();
            System.out.println("    numReactors=" + numReactors);

            long ownerHandle = r.readHandle();
            System.out.println("    ownerHandle=0x" + Long.toHexString(ownerHandle));

            // 实体特殊字段: entity mode (2 bits)
            // BLOCK_HEADER 不是 entity, 跳过

            for (int i = 0; i < numReactors; i++) {
                r.readHandle(); // skip
            }

            // 尝试读取 block name
            try {
                String name = r.readText();
                System.out.println("    blockName='" + name + "'");
            } catch (Exception e) {
                System.out.println("    blockName=(无法读取)");
            }

            // 尝试读取 flags
            try {
                int flags = r.readBitShort();
                System.out.println("    flags=0x" + Integer.toHexString(flags));
            } catch (Exception e) {
                // 跳过
            }

            // 尝试读取基点
            try {
                double x = r.readBitDouble();
                double y = r.readBitDouble();
                double z = r.readBitDouble();
                System.out.println("    basePoint=(" + x + ", " + y + ", " + z + ")");
            } catch (Exception e) {
                System.out.println("    basePoint=(无法读取)");
            }
        } catch (Exception e) {
            System.out.println("    错误: " + e.getMessage());
        }
    }

    private static void analyzeInserts(byte[] data) {
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        int byteOffset = 0;
        int found = 0;

        while (byteOffset < data.length - 8) {
            bbuf.seek((long) byteOffset * 8);

            try {
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x1000) {
                    byteOffset++;
                    continue;
                }

                int typeCode = r.readBitShort();

                if (typeCode == INSERT_TYPE || typeCode == 7 || typeCode == 0x07) {
                    System.out.println("  offset=0x" + Integer.toHexString(byteOffset) +
                        " size=" + objSize + " type=0x" + Integer.toHexString(typeCode));
                    try {
                        parseInsertFields(r);
                    } catch (Exception ex) {
                        System.out.println("    解析失败: " + ex.getMessage());
                    }
                    found++;
                    if (found > 30) break;
                }

                // 跳到下一个对象
                ByteBufferBitInput bbuf2 = new ByteBufferBitInput(ByteBuffer.wrap(data));
                bbuf2.seek((long) byteOffset * 8);
                BitStreamReader r2 = new BitStreamReader(bbuf2, DwgVersion.R2000);
                r2.readModularShort();
                long msBits = bbuf2.position() - (long) byteOffset * 8;
                long totalBits = (long) byteOffset * 8 + msBits + (long) objSize * 8;
                byteOffset = (int) (totalBits / 8);
            } catch (Exception e) {
                byteOffset++;
            }
        }

        System.out.println("  共找到 " + found + " 个可能的 INSERT");
    }

    private static void parseInsertFields(BitStreamReader r) throws Exception {
        // INSERT: entity common + blockHeaderHandle(H) + insertionPoint(3BD)
        //       + scale(3BD) + rotationAngle(BD) + attributes(BS?)

        try {
            int numReactors = r.readBitLong();
            System.out.println("    numReactors=" + numReactors);

            // entity mode (2 bits)
            int entityMode = r.getInput().readBits(2);
            System.out.println("    entityMode=" + entityMode);

            long ownerHandle = r.readHandle();
            System.out.println("    ownerHandle=0x" + Long.toHexString(ownerHandle));

            for (int i = 0; i < numReactors; i++) {
                r.readHandle();
            }

            long blockHeaderHandle = r.readHandle();
            System.out.println("    blockHeaderHandle=0x" + Long.toHexString(blockHeaderHandle));

            double x = r.readBitDouble();
            double y = r.readBitDouble();
            double z = r.readBitDouble();
            System.out.println("    insertPoint=(" + x + ", " + y + ", " + z + ")");

            double sx = r.readBitDouble();
            double sy = r.readBitDouble();
            double sz = r.readBitDouble();
            System.out.println("    scale=(" + sx + ", " + sy + ", " + sz + ")");

            double rot = r.readBitDouble();
            System.out.println("    rotation=" + rot);
        } catch (Exception e) {
            System.out.println("    错误: " + e.getMessage());
        }
    }
}
