package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 解析 R2000 的 Handles section，然后使用句柄偏移读取对象数据
 */
public class R2000HandleBasedParser {

    private static class HandleOffset {
        long handle;
        long offset;

        HandleOffset(long h, long o) {
            this.handle = h;
            this.offset = o;
        }
    }

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // Section #2 (Handles) @ 0x11943, size=2000
        int handlesOffset = 0x11943;
        int handlesSize = 2000;

        System.out.println("=== 解析 Handles Section ===");
        List<HandleOffset> handleOffsets = parseHandlesSection(data, handlesOffset, handlesSize);

        System.out.println("找到 " + handleOffsets.size() + " 个句柄条目");
        System.out.println();

        // 打印前20个 handle-offset 对
        System.out.println("前20个句柄条目:");
        for (int i = 0; i < Math.min(20, handleOffsets.size()); i++) {
            HandleOffset ho = handleOffsets.get(i);
            System.out.printf("  #%d: handle=0x%X offset=0x%X (%d)%n",
                i, ho.handle, ho.offset, ho.offset);
        }
        System.out.println();

        // 打印一些关键句柄范围
        System.out.println("句柄范围:");
        if (!handleOffsets.isEmpty()) {
            long minHandle = Long.MAX_VALUE, maxHandle = Long.MIN_VALUE;
            long minOff = Long.MAX_VALUE, maxOff = Long.MIN_VALUE;
            for (HandleOffset ho : handleOffsets) {
                minHandle = Math.min(minHandle, ho.handle);
                maxHandle = Math.max(maxHandle, ho.handle);
                minOff = Math.min(minOff, ho.offset);
                maxOff = Math.max(maxOff, ho.offset);
            }
            System.out.println("  Handle 范围: 0x" + Long.toHexString(minHandle) +
                " - 0x" + Long.toHexString(maxHandle));
            System.out.println("  Offset 范围: 0x" + Long.toHexString(minOff) +
                " - 0x" + Long.toHexString(maxOff));
        }
        System.out.println();

        // 现在读取每个对象并解析其类型
        System.out.println("=== 根据 Handles 读取对象 ===");
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();
        Map<Long, Integer> handleToType = new HashMap<>();

        for (HandleOffset ho : handleOffsets) {
            if (ho.offset < 0 || ho.offset > data.length - 4) continue;

            try {
                ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                bbuf.seek(ho.offset * 8);
                BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x8000) continue;

                int typeCode = r.readBitShort();

                typeCount.merge(typeCode, 1, Integer::sum);
                handleToType.put(ho.handle, typeCode);
            } catch (Exception e) {
                // skip
            }
        }

        System.out.println("成功解析 " + handleToType.size() + " 个对象");
        System.out.println();
        System.out.println("类型分布:");
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<Integer, Integer> e : sorted) {
            String tname = getTypeName(e.getKey());
            System.out.printf("  Type 0x%02X (%3d): %4d 个 - %s%n",
                e.getKey(), e.getKey(), e.getValue(), tname);
        }

        // 特别查找 BLOCK_HEADER (type 0x05 或 0x30) 和 INSERT (type 0x07 或 0x32)
        System.out.println();
        System.out.println("=== 查找 BLOCK_HEADER 和 INSERT ===");
        for (HandleOffset ho : handleOffsets) {
            Integer type = handleToType.get(ho.handle);
            if (type != null && (type == 0x05 || type == 0x30 || type == 0x06 || type == 0x31 ||
                                  type == 0x07 || type == 0x32)) {
                System.out.printf("  handle=0x%X offset=0x%X type=0x%02X (%s)%n",
                    ho.handle, ho.offset, type, getTypeName(type));
            }
        }

        // 详细解析 BLOCK_HEADER 对象
        System.out.println();
        System.out.println("=== 详细解析 BLOCK_HEADER 对象 ===");
        for (HandleOffset ho : handleOffsets) {
            Integer type = handleToType.get(ho.handle);
            if (type != null && (type == 0x05 || type == 0x30)) {
                System.out.println("  BLOCK_HEADER @ offset 0x" + Long.toHexString(ho.offset) +
                    " handle=0x" + Long.toHexString(ho.handle));
                try {
                    parseBlockHeader(data, (int) ho.offset);
                } catch (Exception ex) {
                    System.out.println("    解析错误: " + ex.getMessage());
                }
                System.out.println();
            }
        }

        // 详细解析 INSERT 对象
        System.out.println();
        System.out.println("=== 详细解析 INSERT 对象 ===");
        for (HandleOffset ho : handleOffsets) {
            Integer type = handleToType.get(ho.handle);
            if (type != null && (type == 0x07 || type == 0x32)) {
                System.out.println("  INSERT @ offset 0x" + Long.toHexString(ho.offset) +
                    " handle=0x" + Long.toHexString(ho.handle));
                try {
                    parseInsert(data, (int) ho.offset);
                } catch (Exception ex) {
                    System.out.println("    解析错误: " + ex.getMessage());
                }
                System.out.println();
            }
        }
    }

    private static List<HandleOffset> parseHandlesSection(byte[] data, int offset, int size) {
        List<HandleOffset> result = new ArrayList<>();

        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        long lastHandle = 0;
        long lastOffset = 0;
        int totalBytesRead = 0;

        int pageNum = 0;
        while (totalBytesRead < size - 4) {
            try {
                int pageSize = r.readBigEndianShort();
                totalBytesRead += 2;

                if (pageSize <= 2 || pageSize > 2040) {
                    System.out.println("  终止解析: pageSize=" + pageSize);
                    break;
                }

                pageNum++;
                // Per libredwg: lastOffset resets per page
                lastOffset = 0;

                int pairsDataSize = pageSize - 2; // minus CRC
                int bytesReadInPage = 0;

                while (bytesReadInPage < pairsDataSize) {
                    long beforePos = r.position();

                    int handleDelta = r.readUnsignedModularChar();
                    if (handleDelta == 0) {
                        break;
                    }

                    int offsetDelta = r.readModularChar();

                    long afterPos = r.position();
                    bytesReadInPage += (int) ((afterPos - beforePos) / 8);
                    totalBytesRead += (int) ((afterPos - beforePos) / 8);

                    lastHandle += handleDelta;
                    lastOffset += offsetDelta;

                    result.add(new HandleOffset(lastHandle, lastOffset));
                }

                // align to byte
                long currentPos = r.position();
                if ((currentPos % 8) != 0) {
                    int paddingBits = 8 - (int) (currentPos % 8);
                    r.getInput().readBits(paddingBits);
                    totalBytesRead += paddingBits / 8;
                }

                // skip CRC
                r.readBigEndianShort();
                totalBytesRead += 2;

                if (pageNum <= 3) {
                    System.out.println("  Page " + pageNum + ": size=" + pageSize +
                        ", lastHandle=0x" + Long.toHexString(lastHandle) +
                        ", lastOffset=0x" + Long.toHexString(lastOffset));
                }
            } catch (Exception e) {
                System.out.println("  Handles 解析异常: " + e.getMessage());
                break;
            }
        }

        System.out.println("  共解析 " + pageNum + " 个页面");
        return result;
    }

    private static void parseBlockHeader(byte[] data, int objFileOffset) {
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) objFileOffset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        try {
            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();
            System.out.println("    objSize=" + objSize + " typeCode=0x" + Integer.toHexString(typeCode));

            // Object common data (R2000):
            // - numReactors (BL)
            // - ownerHandle (H)
            // - reactor handles (H * numReactors)
            // - xDic handle (H) if non-zero... no, for entities there are more fields

            // BLOCK_HEADER is not an entity, it's an object
            // Object common: numReactors (BL) + ownerHandle (H) + xDict handle?

            int numReactors = r.readBitLong();
            System.out.println("    numReactors=" + numReactors);

            long ownerHandle = r.readHandle();
            System.out.println("    ownerHandle=0x" + Long.toHexString(ownerHandle));

            // 读取 reactor handles
            for (int i = 0; i < numReactors; i++) {
                r.readHandle();
            }

            // xDict object 可能有也可能没有 - 尝试直接读取 blockName
            // 可能需要先读取 xDict handle
            try {
                long xdictHandle = r.readHandle();
                System.out.println("    xdictHandle=0x" + Long.toHexString(xdictHandle));
            } catch (Exception e) {
                System.out.println("    (no xdictHandle)");
            }

            // BLOCK_HEADER specific: blockName (T), block flags (BS), etc.
            // Try reading text
            try {
                String blockName = r.readText();
                System.out.println("    blockName='" + blockName + "'");
            } catch (Exception e) {
                System.out.println("    blockName=无法读取: " + e.getMessage());
            }

            // block flags
            try {
                int flags = r.readBitShort();
                System.out.println("    flags=0x" + Integer.toHexString(flags));
            } catch (Exception e) {
                System.out.println("    flags=无法读取");
            }

            // basePoint (3BD)
            try {
                double x = r.readBitDouble();
                double y = r.readBitDouble();
                double z = r.readBitDouble();
                System.out.println("    basePoint=(" + x + ", " + y + ", " + z + ")");
            } catch (Exception e) {
                System.out.println("    basePoint=无法读取");
            }

        } catch (Exception e) {
            System.out.println("    解析失败: " + e.getMessage());
        }
    }

    private static void parseInsert(byte[] data, int objFileOffset) {
        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) objFileOffset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        try {
            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();
            System.out.println("    objSize=" + objSize + " typeCode=0x" + Integer.toHexString(typeCode));

            // INSERT is an ENTITY
            // Entity common for R2000:
            // - entityMode (2 bits) + ltypeScale (2 bits?)... let me check actual order
            // Actually: numReactors (BL) + entityMode(2) + ltypeFlags(2) + ownerHandle(H)
            //   + reactorHandles(H*) + [entity specific:]
            //   For INSERT: blockHeaderHandle(H) + insertionPoint(3BD) + scale(3BD)
            //                + rotationAngle(BD) + columnCount(BS?) + rowCount(BS?)
            //                + columnSpacing(BD) + rowSpacing(BD)
            //   + attributes?

            int numReactors = r.readBitLong();
            System.out.println("    numReactors=" + numReactors);

            // entity mode flags
            int entityMode = r.getInput().readBits(2);
            System.out.println("    entityMode=" + entityMode);

            int ltypeFlags = r.getInput().readBits(2);
            System.out.println("    ltypeFlags=" + ltypeFlags);

            long ownerHandle = r.readHandle();
            System.out.println("    ownerHandle=0x" + Long.toHexString(ownerHandle));

            // Reactor handles
            for (int i = 0; i < numReactors; i++) {
                r.readHandle();
            }

            // xDic? maybe... let's try block header handle
            // Try reading xdic handle first
            try {
                long xdicHandle = r.readHandle();
                System.out.println("    xdicHandle=0x" + Long.toHexString(xdicHandle));
            } catch (Exception e) {
                System.out.println("    (no xdictHandle)");
            }

            // block header handle
            long blockHeaderHandle = r.readHandle();
            System.out.println("    blockHeaderHandle=0x" + Long.toHexString(blockHeaderHandle));

            // insertion point
            double x = r.readBitDouble();
            double y = r.readBitDouble();
            double z = r.readBitDouble();
            System.out.println("    insertionPoint=(" + x + ", " + y + ", " + z + ")");

            // scale
            double sx = r.readBitDouble();
            double sy = r.readBitDouble();
            double sz = r.readBitDouble();
            System.out.println("    scale=(" + sx + ", " + sy + ", " + sz + ")");

            // rotation
            double rot = r.readBitDouble();
            System.out.println("    rotation=" + rot + " rad (" + (rot * 180.0 / Math.PI) + "°)");

        } catch (Exception e) {
            System.out.println("    解析失败: " + e.getMessage());
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
            case 0x30 -> "BLOCK_HEADER (alt)";
            case 0x31 -> "BLOCK_END (alt)";
            case 0x32 -> "INSERT (alt)";
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
