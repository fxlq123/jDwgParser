import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class BlockParserFinal {
    static byte[] data;
    static DwgVersion version = DwgVersion.R2000;

    static class BlockInfo {
        long handle;
        String name;
        int offset;
        double x, y, z;
        int flags;
        String xref;
    }

    static class InsertInfo {
        long handle;
        long blockHandle;
        String blockName;
        int offset;
        double x, y, z;
        double rot;
    }

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println();
        System.out.println("=== 扫描 Blocks 区域 (Section 间隙) ===");

        // 解析 Handles section，获取 handle -> offset 映射
        List<long[]> handles = parseHandlesSection(data, 0x11943, 4096);
        System.out.println("Handles: " + handles.size() + " 个条目");

        // 构建 handle 映射
        Map<Long, Long> handleToOffset = new HashMap<>();
        for (long[] h : handles) {
            handleToOffset.put(h[0], h[1]);
        }

        // 扫描每个对象，找出 BLOCK_HEADER (type=48), BLOCK_END (type=49), INSERT (type=7)
        List<BlockInfo> blocks = new ArrayList<>();
        List<InsertInfo> inserts = new ArrayList<>();
        Map<Long, String> handleToName = new HashMap<>();

        // 先扫描所有可能的对象位置
        // 扫描方法: 对每个 handle 的偏移，解析 MS objSize，BS typeCode
        System.out.println("\n--- 扫描对象类型分布 ---");
        int[] typeCount = new int[128];
        int scanned = 0;
        int badObjSize = 0;

        for (long[] h : handles) {
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 20) continue;

            try {
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(input, version);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 500) {
                    badObjSize++;
                    continue;
                }

                int typeCode = r.readBitShort();
                if (typeCode >= 0 && typeCode < 128) typeCount[typeCode]++;
                scanned++;
            } catch (Exception e) {}
        }
        System.out.println("成功扫描: " + scanned + " 个 (无效 objSize: " + badObjSize + ")");

        // 显示各类型
        System.out.println("\n类型分布 (前20个):");
        for (int i = 0; i < 128; i++) {
            if (typeCount[i] > 0) {
                String name = typeName(i);
                System.out.printf("  type=%3d (0x%02X): %4d 个  %s%n", i, i, typeCount[i], name);
            }
        }

        // 详细解析 BLOCK_HEADER
        System.out.println("\n--- 解析 BLOCK_HEADER (type=48) ---");
        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 20) continue;

            try {
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(input, version);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 500) continue;

                int typeCode = r.readBitShort();
                if (typeCode != 48) continue;

                // Entity header: bitsize (4 bytes) + handle + EED
                for (int i = 0; i < 4; i++) input.readBits(8);
                r.readHandle();
                int eedSize = r.readBitShort();
                while (eedSize != 0 && eedSize > 0) {
                    r.readHandle();
                    for (int i = 0; i < eedSize; i++) input.readBits(8);
                    eedSize = r.readBitShort();
                }

                // Common entity data
                int numR = r.readBitLong();
                for (int i = 0; i < numR; i++) r.readHandle();
                r.readHandle();  // xdict
                r.readHandle();  // owner

                // BLOCK_HEADER 特有字段
                String name = r.readVariableText();
                int flags = r.readBitShort();
                double[] pt = r.read3RawDouble();
                String xref = r.readVariableText();

                BlockInfo bi = new BlockInfo();
                bi.handle = handle;
                bi.offset = (int)offset;
                bi.name = name;
                bi.flags = flags;
                bi.x = pt[0]; bi.y = pt[1]; bi.z = pt[2];
                bi.xref = xref;
                blocks.add(bi);
                handleToName.put(handle, name);

                System.out.printf("  handle=0x%02X offset=0x%04X name='%s' base=(%.2f,%.2f,%.2f) flags=%d xref='%s'%n",
                    handle, (int)offset, name, bi.x, bi.y, bi.z, flags, xref);
            } catch (Exception e) {}
        }
        System.out.println("找到 " + blocks.size() + " 个 BLOCK_HEADER");

        // 解析 INSERT
        System.out.println("\n--- 解析 INSERT (type=7) ---");
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (BlockInfo bi : blocks) refCount.put(bi.name, 0);

        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 30) continue;
            if (handleToName.containsKey(handle)) continue;

            try {
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(input, version);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 500) continue;

                int typeCode = r.readBitShort();
                if (typeCode != 7) continue;

                // Entity header
                for (int i = 0; i < 4; i++) input.readBits(8);
                r.readHandle();
                int eedSize = r.readBitShort();
                while (eedSize != 0 && eedSize > 0) {
                    r.readHandle();
                    for (int i = 0; i < eedSize; i++) input.readBits(8);
                    eedSize = r.readBitShort();
                }

                // Common entity data
                int numR = r.readBitLong();
                for (int i = 0; i < numR; i++) r.readHandle();
                r.readHandle();  // xdict
                r.readHandle();  // owner

                // INSERT 特有字段
                long blockHandle = r.readHandle();
                double[] pt = r.read3BitDouble();
                // scale factors 3BD
                double[] scale = r.read3BitDouble();
                // rotation BD
                double rot = r.readBitDouble();

                String blockName = handleToName.get(blockHandle);
                if (blockName == null) blockName = "handle=0x" + Long.toHexString(blockHandle);

                InsertInfo ii = new InsertInfo();
                ii.handle = handle;
                ii.offset = (int)offset;
                ii.blockHandle = blockHandle;
                ii.blockName = blockName;
                ii.x = pt[0]; ii.y = pt[1]; ii.z = pt[2];
                ii.rot = rot;
                inserts.add(ii);

                refCount.merge(blockName, 1, Integer::sum);

                System.out.printf("  handle=0x%02X offset=0x%04X block='%s'(0x%02X) pos=(%.2f,%.2f,%.2f) rot=%.4f%n",
                    handle, (int)offset, blockName, blockHandle, ii.x, ii.y, ii.z, rot);
            } catch (Exception e) {}
        }
        System.out.println("找到 " + inserts.size() + " 个 INSERT");

        // 显示最终汇总
        System.out.println("\n==================================================");
        System.out.println("  FINAL RESULT: Block Definitions and References");
        System.out.println("==================================================");

        System.out.println("\n【块定义】共 " + blocks.size() + " 个:");
        for (BlockInfo bi : blocks) {
            System.out.printf("  - %-40s handle=0x%02X  base=(%.2f, %.2f, %.2f)%n",
                "'" + bi.name + "'", bi.handle, bi.x, bi.y, bi.z);
        }

        System.out.println("\n【引用统计】:");
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(refCount.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        int totalRefs = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            String bar = repeat("█", Math.min(50, e.getValue() * 2));
            totalRefs += e.getValue();
            System.out.printf("  %-45s : %4d  %s%n", e.getKey(), e.getValue(), bar);
        }

        System.out.println("\n  总计: " + blocks.size() + " 个块定义, " + totalRefs + " 个 INSERT 引用");
        System.out.println();
    }

    static String typeName(int code) {
        String[] names = {
            "UNUSED", "TEXT", "ATTDEF", "ATTRIB", "SEQEND", "ENDBLK", "", "INSERT", "MINSERT",
            "", "VERTEX_2D", "VERTEX_3D", "VERTEX_MESH", "VERTEX_PFACE", "VERTEX_PFACE_FACE",
            "POLYLINE_2D", "POLYLINE_3D", "ARC", "CIRCLE", "LINE",
            "DIMENSION_ORDINATE", "DIMENSION_LINEAR", "DIMENSION_ALIGNED", "DIMENSION_ANG_3PT",
            "DIMENSION_ANG_2LN", "DIMENSION_RADIUS", "DIMENSION_DIAMETER", "POINT", "FACE3D",
            "POLYLINE_PFACE", "POLYLINE_MESH", "SOLID", "TRACE", "SHAPE", "VIEWPORT",
            "ELLIPSE", "SPLINE", "REGION", "SOLID3D", "BODY", "RAY", "XLINE", "DICTIONARY",
            "", "MTEXT", "LEADER", "TOLERANCE", "MLINE", "BLOCK_HEADER", "BLOCK_END",
            "LTYPE", "LAYER", "STYLE", "STYLE_ALT", "VIEW", "UCS", "VPORT", "APPID", "DIMSTYLE"
        };
        if (code < names.length && names[code] != null && !names[code].isEmpty()) return names[code];
        switch(code) {
            case 0x3B: return "VP_ENT_HDR";
            case 0x3C: return "GROUP";
            case 0x4B: return "LWPLINE";
            case 0x4C: return "HATCH";
            case 0x4D: return "XRECORD";
            case 0x50: return "LAYOUT";
            default: return "";
        }
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static List<long[]> parseHandlesSection(byte[] data, int offset, int maxBytes) {
        List<long[]> result = new ArrayList<>();
        int pos = offset;
        while (pos < offset + maxBytes && pos < data.length - 4) {
            int pageSize = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pageSize <= 2 || pageSize > 2040) break;
            int pairsSize = pageSize - 2;
            int pairsRead = 0;
            long lastHandle = 0;
            long lastOffset = 0;
            while (pairsRead < pairsSize && pos < data.length - 2) {
                int hByte = data[pos++] & 0xFF;
                pairsRead++;
                long hDelta;
                if (hByte < 0x80) {
                    if (hByte == 0) break;
                    hDelta = hByte;
                } else {
                    int lo = hByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    hDelta = lo | ((long)hi << 7);
                    if (hDelta == 0) break;
                }
                lastHandle += hDelta;
                int oByte = data[pos++] & 0xFF;
                pairsRead++;
                long oDelta;
                if (oByte < 0x80) {
                    oDelta = (oByte > 63) ? (long)oByte - 128 : oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    int combined = lo | (hi << 7);
                    oDelta = (combined > 16383) ? (long)combined - 32768 : combined;
                }
                lastOffset += oDelta;
                result.add(new long[]{ lastHandle, lastOffset });
            }
            pos += 2;
        }
        return result;
    }
}
