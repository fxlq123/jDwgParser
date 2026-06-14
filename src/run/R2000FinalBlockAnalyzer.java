package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.version.DwgVersion;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.*;

/**
 * 最终的 R2000 块解析器
 * 解析 Handles section -> 读取 BLOCK_HEADER 和 INSERT -> 输出详细报告
 */
public class R2000FinalBlockAnalyzer {

    private static class HandleEntry {
        long handle;
        long offset;

        HandleEntry(long h, long o) {
            this.handle = h;
            this.offset = o;
        }
    }

    private static class BlockInfo {
        long handle;
        String name;
        double baseX, baseY, baseZ;
        int flags;

        BlockInfo(long h, String n, double x, double y, double z, int f) {
            this.handle = h;
            this.name = n;
            this.baseX = x;
            this.baseY = y;
            this.baseZ = z;
            this.flags = f;
        }
    }

    private static class InsertInfo {
        long handle;
        long blockHandle;
        double x, y, z;
        double scaleX, scaleY, scaleZ;
        double rotation;
    }

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        System.out.println("=========================================================");
        System.out.println("  文件: " + filename);
        System.out.println("  格式: R2000 (AC1015)");
        System.out.println("=========================================================");
        System.out.println();

        // 1. 解析 Handles section
        List<HandleEntry> handles = parseHandlesSection(data, 0x11943, 2000);
        System.out.println("[1/4] Handles Section: 找到 " + handles.size() + " 个句柄条目");

        // 2. 构建句柄 -> 偏移映射
        Map<Long, Long> handleToOffset = new HashMap<>();
        for (HandleEntry he : handles) {
            handleToOffset.put(he.handle, he.offset);
        }

        // 3. 读取对象类型
        Map<Long, Integer> handleToType = new HashMap<>();
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();

        for (HandleEntry he : handles) {
            if (he.offset < 0 || he.offset > data.length - 4) continue;
            try {
                ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
                bbuf.seek(he.offset * 8);
                BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x8000) continue;

                int typeCode = r.readBitShort();
                handleToType.put(he.handle, typeCode);
                typeCount.merge(typeCode, 1, Integer::sum);
            } catch (Exception e) {
                // skip
            }
        }

        System.out.println("[2/4] 成功解析 " + handleToType.size() + " 个对象的类型");
        System.out.println();

        // 4. 提取 BLOCK_HEADER (type 0x05)
        System.out.println("=========================================================");
        System.out.println("  BLOCK 定义详情 (类型码: 0x05, 共 " +
            typeCount.getOrDefault(0x05, 0) + " 个)");
        System.out.println("=========================================================");

        List<BlockInfo> blocks = new ArrayList<>();
        int blockIdx = 1;
        for (HandleEntry he : handles) {
            Integer type = handleToType.get(he.handle);
            if (type != null && type == 0x05) {
                BlockInfo bi = parseBlockHeader(data, (int) he.offset, he.handle);
                if (bi != null) {
                    blocks.add(bi);
                    String flagDesc = String.format(
                        "匿名=%s, 有属性=%s, XRef=%s",
                        (bi.flags & 0x01) != 0 ? "是" : "否",
                        (bi.flags & 0x02) != 0 ? "是" : "否",
                        (bi.flags & 0x04) != 0 ? "是" : "否"
                    );
                    System.out.printf("  [%d] 名称: %s%n", blockIdx, bi.name);
                    System.out.printf("       句柄: 0x%X, 偏移: 0x%X%n", bi.handle, he.offset);
                    System.out.printf("       基点: (%.6f, %.6f, %.6f)%n", bi.baseX, bi.baseY, bi.baseZ);
                    System.out.printf("       标志: 0x%04X [%s]%n", bi.flags, flagDesc);
                    System.out.println();
                    blockIdx++;
                }
            }
        }

        // 5. 提取 INSERT (type 0x07)
        System.out.println("=========================================================");
        System.out.println("  INSERT 块引用详情 (类型码: 0x07, 共 " +
            typeCount.getOrDefault(0x07, 0) + " 个)");
        System.out.println("=========================================================");

        // 构建块名映射
        Map<Long, String> handleToBlockName = new HashMap<>();
        for (BlockInfo bi : blocks) {
            handleToBlockName.put(bi.handle, bi.name);
        }

        List<InsertInfo> inserts = new ArrayList<>();
        Map<String, Integer> blockUsage = new LinkedHashMap<>();
        int insertIdx = 1;
        for (HandleEntry he : handles) {
            Integer type = handleToType.get(he.handle);
            if (type != null && type == 0x07) {
                InsertInfo ii = parseInsert(data, (int) he.offset, he.handle);
                if (ii != null) {
                    inserts.add(ii);
                    String refBlockName = handleToBlockName.getOrDefault(ii.blockHandle,
                        "未知块(handle=0x" + Long.toHexString(ii.blockHandle) + ")");
                    blockUsage.merge(refBlockName, 1, Integer::sum);

                    System.out.printf("  [%d] 引用块: %s (块句柄: 0x%X)%n",
                        insertIdx, refBlockName, ii.blockHandle);
                    System.out.printf("       句柄: 0x%X, 偏移: 0x%X%n", ii.handle, he.offset);
                    System.out.printf("       插入点: (%.6f, %.6f, %.6f)%n", ii.x, ii.y, ii.z);
                    System.out.printf("       缩放  : (%.6f, %.6f, %.6f)%n", ii.scaleX, ii.scaleY, ii.scaleZ);
                    System.out.printf("       旋转  : %.6f rad (%.4f 度)%n", ii.rotation, ii.rotation * 180.0 / Math.PI);
                    System.out.println();
                    insertIdx++;
                }
            }
        }

        // 6. 汇总报告
        System.out.println("=========================================================");
        System.out.println("  汇总报告");
        System.out.println("=========================================================");
        System.out.println("  文件大小: " + data.length + " 字节");
        System.out.println("  对象总数: " + handleToType.size());
        System.out.println("  块定义数: " + blocks.size() + " (BLOCK_HEADER type=0x05)");
        System.out.println("  块引用数: " + inserts.size() + " (INSERT type=0x07)");
        System.out.println();

        System.out.println("--- 按块名统计引用 ---");
        List<Map.Entry<String, Integer>> sortedUsage = new ArrayList<>(blockUsage.entrySet());
        sortedUsage.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<String, Integer> e : sortedUsage) {
            System.out.printf("  %-45s : %d 次%n", e.getKey(), e.getValue());
        }

        System.out.println();
        System.out.println("--- 系统块与用户块分类 ---");
        int sysBlocks = 0, userBlocks = 0;
        System.out.println("  系统块 (* 开头):");
        for (BlockInfo bi : blocks) {
            if (bi.name.startsWith("*") || bi.name.startsWith("*Model_Space")) {
                System.out.println("    • " + bi.name);
                sysBlocks++;
            }
        }
        System.out.println("  用户块:");
        for (BlockInfo bi : blocks) {
            if (!bi.name.startsWith("*") && !bi.name.startsWith("*Model_Space")) {
                System.out.println("    • " + bi.name);
                userBlocks++;
            }
        }
        System.out.println("  (系统块: " + sysBlocks + " 个, 用户块: " + userBlocks + " 个)");

        System.out.println();
        System.out.println("--- 对象类型统计 ---");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCount.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            if (e.getValue() < 2) continue; // 只显示数量>=2的
            String tname = getTypeName(e.getKey());
            System.out.printf("  Type 0x%02X (%3d): %4d 个 - %s%n",
                e.getKey(), e.getKey(), e.getValue(), tname);
        }
    }

    // ========== Handles section 解析 ==========
    private static List<HandleEntry> parseHandlesSection(byte[] data, int offset, int size) {
        List<HandleEntry> result = new ArrayList<>();

        ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
        bbuf.seek((long) offset * 8);
        BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

        long lastHandle = 0;
        long lastOffset = 0;
        int totalBytesRead = 0;

        while (totalBytesRead < size - 4) {
            try {
                int pageSize = r.readBigEndianShort();
                totalBytesRead += 2;

                if (pageSize <= 2 || pageSize > 2040) break;

                lastOffset = 0; // 每页重置
                int pairsDataSize = pageSize - 2;
                int bytesReadInPage = 0;

                while (bytesReadInPage < pairsDataSize) {
                    long beforePos = r.position();

                    int handleDelta = r.readUnsignedModularChar();
                    if (handleDelta == 0) break;

                    int offsetDelta = r.readModularChar();

                    long afterPos = r.position();
                    bytesReadInPage += (int) ((afterPos - beforePos) / 8);
                    totalBytesRead += (int) ((afterPos - beforePos) / 8);

                    lastHandle += handleDelta;
                    lastOffset += offsetDelta;

                    result.add(new HandleEntry(lastHandle, lastOffset));
                }

                // align to byte
                long currentPos = r.position();
                if ((currentPos % 8) != 0) {
                    int padding = 8 - (int) (currentPos % 8);
                    r.getInput().readBits(padding);
                }

                // skip CRC
                r.readBigEndianShort();
                totalBytesRead += 2;

            } catch (Exception e) {
                break;
            }
        }
        return result;
    }

    // ========== BLOCK_HEADER 解析 ==========
    private static BlockInfo parseBlockHeader(byte[] data, int objFileOffset, long objHandle) {
        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            bbuf.seek((long) objFileOffset * 8);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();

            if (typeCode != 0x05) return null;

            // Object common data for R2000:
            // numReactors (BL) + ownerHandle (H) + reactorHandles (H * numReactors)
            // BLOCK_HEADER is a TABLE-style object, also has:
            //   blockName (T)
            //   flags (BS)
            //   basePoint (3BD)
            //   xrefPath (T) optional

            int numReactors = r.readBitLong();
            long ownerHandle = r.readHandle();

            for (int i = 0; i < numReactors; i++) {
                r.readHandle();
            }

            // 读取块名
            String blockName = "";
            try {
                blockName = r.readText();
            } catch (Exception e) {
                blockName = "(无法读取)";
            }

            // 读取标志
            int flags = 0;
            try {
                flags = r.readBitShort();
            } catch (Exception e) {
                // skip
            }

            // 读取基点
            double x = 0, y = 0, z = 0;
            try {
                x = r.readBitDouble();
                y = r.readBitDouble();
                z = r.readBitDouble();
            } catch (Exception e) {
                // skip
            }

            return new BlockInfo(objHandle, blockName, x, y, z, flags);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== INSERT 解析 ==========
    private static InsertInfo parseInsert(byte[] data, int objFileOffset, long objHandle) {
        try {
            ByteBufferBitInput bbuf = new ByteBufferBitInput(ByteBuffer.wrap(data));
            bbuf.seek((long) objFileOffset * 8);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();

            if (typeCode != 0x07) return null;

            // INSERT entity common data for R2000:
            // numReactors (BL)
            // entity mode flags (2 bits) + ltype flags (2 bits)
            // ownerHandle (H)
            // reactor handles (H * numReactors)
            // [entity specific:]
            //   blockHeaderHandle (H)
            //   insertionPoint (3BD)
            //   scale (3BD)
            //   rotationAngle (BD)
            //   [optional: columnCount, rowCount, columnSpacing, rowSpacing]

            int numReactors = r.readBitLong();

            // entity mode flags
            r.getInput().readBits(2); // entityMode
            r.getInput().readBits(2); // ltypeFlags

            long ownerHandle = r.readHandle();

            for (int i = 0; i < numReactors; i++) {
                r.readHandle();
            }

            // block header handle
            long blockHeaderHandle = r.readHandle();

            // insertion point
            double x = r.readBitDouble();
            double y = r.readBitDouble();
            double z = r.readBitDouble();

            // scale factors
            double sx = r.readBitDouble();
            double sy = r.readBitDouble();
            double sz = r.readBitDouble();

            // rotation angle
            double rot = r.readBitDouble();

            InsertInfo ii = new InsertInfo();
            ii.handle = objHandle;
            ii.blockHandle = blockHeaderHandle;
            ii.x = x;
            ii.y = y;
            ii.z = z;
            ii.scaleX = sx;
            ii.scaleY = sy;
            ii.scaleZ = sz;
            ii.rotation = rot;
            return ii;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getTypeName(int typeCode) {
        return switch (typeCode) {
            case 0x01 -> "TEXT";
            case 0x03 -> "ATTRIB";
            case 0x04 -> "ATTDEF";
            case 0x05 -> "BLOCK_HEADER";
            case 0x06 -> "BLOCK_ENDBLK";
            case 0x07 -> "INSERT";
            case 0x08 -> "MINSERT";
            case 0x0F -> "LINE";
            case 0x11 -> "CIRCLE";
            case 0x12 -> "ARC";
            case 0x13 -> "LINE (alt)";
            case 0x14 -> "SPLINE";
            case 0x15 -> "ELLIPSE";
            case 0x1A -> "POINT";
            case 0x1F -> "SOLID";
            case 0x20 -> "TRACE";
            case 0x25 -> "LWPOLYLINE";
            case 0x27 -> "HATCH";
            case 0x28 -> "XRECORD";
            case 0x2B -> "MTEXT";
            case 0x2C -> "MTEXT (alt)";
            case 0x2E -> "LEADER";
            case 0x2F -> "TOLERANCE";
            case 0x30 -> "BLOCK_HEADER (R2000)";
            case 0x31 -> "BLOCK_END";
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
            case 0x1F7 -> "DIMENSION_LINEAR";
            default -> "UNKNOWN(0x" + Integer.toHexString(typeCode) + ")";
        };
    }
}
