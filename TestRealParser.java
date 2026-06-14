import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class TestRealParser {
    static byte[] data;
    static DwgVersion version;

    public static void main(String[] args) throws Exception {
        data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        version = DwgVersion.R2000;

        System.out.println("=== 0x5484: *Paper_Space 附近 ===");
        debugObject(0x5484);

        System.out.println("\n=== 0x5A77: *Model_Space 附近 ===");
        debugObject(0x5A77);

        System.out.println("\n=== 0x54AC: SW_NOTE_0 附近 ===");
        debugObject(0x54AC);

        System.out.println("\n=== 扫描所有对象 ===");
        scanAllObjects();
    }

    static void debugObject(int offset) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(data);
            ByteBufferBitInput input = new ByteBufferBitInput(bb);
            input.seek(offset * 8L);  // 定位到正确的字节偏移量
            BitStreamReader r = new BitStreamReader(input, version);

            System.out.print("  起始字节: ");
            for (int i = 0; i < 10; i++) {
                System.out.printf("%02X ", data[offset + i] & 0xFF);
            }
            System.out.println();

            // 1. objSize (Modular Short)
            int objSize = r.readModularShort();
            System.out.println("  objSize: " + objSize);

            // 2. typeCode (Bit Short)
            int typeCode = r.readBitShort();
            System.out.println("  typeCode: " + typeCode + " (0x" + Integer.toHexString(typeCode) + ")");

            // 3. bitsize (4 raw bytes)
            long bitsize = 0;
            for (int i = 0; i < 4; i++) {
                bitsize |= ((long)input.readBits(8) & 0xFF) << (i * 8);
            }
            System.out.println("  bitsize: " + bitsize + " (0x" + Long.toHexString(bitsize) + ")");

            // 4. handle
            long handle = r.readHandle();
            System.out.println("  handle: 0x" + Long.toHexString(handle));

            // 5. EED loop
            int eedCount = 0;
            while (true) {
                int eedSize = r.readBitShort();
                if (eedSize == 0) break;
                if (eedSize < 0 || eedSize > 5000) { System.out.println("  EED: 无效 eedSize=" + eedSize); break; }
                r.readHandle();
                for (int i = 0; i < eedSize; i++) input.readBits(8);
                eedCount++;
                if (eedCount > 50) break;
            }
            System.out.println("  EED: " + eedCount + " 个条目");

            // 6. numReactors
            int numReactors = r.readBitLong();
            System.out.println("  numReactors: " + numReactors);
            for (int i = 0; i < numReactors && i < 10; i++) r.readHandle();

            // 7. xdict handle
            long xdictHandle = r.readHandle();
            System.out.println("  xdictHandle: 0x" + Long.toHexString(xdictHandle));

            // 8. owner handle
            long ownerHandle = r.readHandle();
            System.out.println("  ownerHandle: 0x" + Long.toHexString(ownerHandle));

            // 尝试 BLOCK_HEADER 字段
            try {
                String blockName = r.readVariableText();
                System.out.println("  blockName: '" + blockName + "'");
                int flags = r.readBitShort();
                System.out.println("  flags: " + flags);
                double[] bp = r.read3RawDouble();
                System.out.printf("  basePoint: (%.2f, %.2f, %.2f)%n", bp[0], bp[1], bp[2]);
                try {
                    String xref = r.readVariableText();
                    System.out.println("  xrefPath: '" + xref + "'");
                } catch (Exception e) {}
            } catch (Exception e) {
                System.out.println("  BLOCK_HEADER 字段: 读取失败: " + e.getMessage());
            }

        } catch (Exception e) {
            System.out.println("  出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static class BlockInfo {
        long handle; String name; int offset; int typeCode;
        int flags; double x, y, z; String xref;
    }

    static class InsertInfo {
        long handle; long blockHandle; String blockName; int offset;
        double x, y, z, rot;
    }

    static void scanAllObjects() {
        List<long[]> handles = parseHandles(data, 0x11943, 5000);
        System.out.println("Handles: " + handles.size() + " 个条目");

        // 先打印 0x5400-0x5600 范围的 handles
        System.out.println("0x5400-0x5600 范围的 handles:");
        for (long[] h : handles) {
            if (h[1] >= 0x5400 && h[1] <= 0x5600) {
                System.out.printf("  handle=0x%x offset=0x%x bytes=%02X %02X %02X %02X%n",
                    h[0], h[1], data[(int)h[1]] & 0xFF, data[(int)h[1]+1] & 0xFF,
                    data[(int)h[1]+2] & 0xFF, data[(int)h[1]+3] & 0xFF);
            }
        }

        // 解析 BLOCK_HEADER
        List<BlockInfo> blocks = new ArrayList<>();
        Map<Long, String> blockNameByHandle = new HashMap<>();

        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 10) continue;

            BlockInfo bi = tryParseBlockHeader((int)offset, handle);
            if (bi != null) {
                blocks.add(bi);
                blockNameByHandle.put(handle, bi.name);
            }
        }

        System.out.println("\n找到 " + blocks.size() + " 个 BLOCK_HEADER:");
        for (BlockInfo bi : blocks) {
            System.out.printf("  handle=0x%x offset=0x%x type=%d name='%s' bp=(%.3f,%.3f,%.3f) flags=%d%n",
                bi.handle, bi.offset, bi.typeCode, bi.name, bi.x, bi.y, bi.z, bi.flags);
        }

        // 解析 INSERT
        List<InsertInfo> inserts = new ArrayList<>();
        for (long[] h : handles) {
            long handle = h[0];
            long offset = h[1];
            if (offset <= 0 || offset > data.length - 10) continue;
            if (blockNameByHandle.containsKey(handle)) continue;

            InsertInfo ii = tryParseInsert((int)offset, handle, blockNameByHandle);
            if (ii != null) inserts.add(ii);
        }

        System.out.println("\n找到 " + inserts.size() + " 个 INSERT:");
        // 引用统计
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (InsertInfo ii : inserts) {
            refCount.merge(ii.blockName, 1, Integer::sum);
        }
        for (BlockInfo bi : blocks) {
            refCount.putIfAbsent(bi.name, 0);
        }

        // 排序
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(refCount.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        System.out.println("\n引用统计:");
        for (Map.Entry<String, Integer> e : sorted) {
            String bar = "";
            for (int i = 0; i < Math.min(40, e.getValue() * 2); i++) bar += "█";
            System.out.printf("  %-45s : %4d  %s%n", e.getKey(), e.getValue(), bar);
        }
    }

    static BlockInfo tryParseBlockHeader(int offset, long handle) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(data);
            ByteBufferBitInput input = new ByteBufferBitInput(bb);
            input.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(input, version);

            int objSize = r.readModularShort();
            if (objSize <= 0 || objSize > 500) return null;

            int typeCode = r.readBitShort();
            if (typeCode < 0 || typeCode > 500) return null;

            // bitsize (4 raw bytes)
            for (int i = 0; i < 4; i++) input.readBits(8);

            // own handle
            r.readHandle();

            // EED loop
            int guard = 0;
            while (true) {
                int eedSize = r.readBitShort();
                if (eedSize == 0) break;
                if (eedSize < 0 || eedSize > 500) return null;
                r.readHandle();
                for (int i = 0; i < eedSize; i++) input.readBits(8);
                if (++guard > 50) return null;
            }

            // numReactors
            int numR = r.readBitLong();
            if (numR < 0 || numR > 100) return null;
            for (int i = 0; i < numR; i++) r.readHandle();

            // xdict + owner
            r.readHandle();
            r.readHandle();

            // BLOCK_HEADER 字段: blockName TV, flags BS, basePoint 3RD, xref TV
            String name = r.readVariableText();
            if (name == null || name.length() == 0 || name.length() > 100) return null;
            for (char c : name.toCharArray()) {
                if (c < 32 || c > 126) return null;
            }

            int flags = r.readBitShort();
            if (flags < 0 || flags > 10000) return null;

            double[] bp = r.read3RawDouble();
            if (!Double.isFinite(bp[0]) || Math.abs(bp[0]) > 1e8) return null;
            if (!Double.isFinite(bp[1]) || Math.abs(bp[1]) > 1e8) return null;
            if (!Double.isFinite(bp[2]) || Math.abs(bp[2]) > 1e8) return null;

            String xref = null;
            try {
                xref = r.readVariableText();
            } catch (Exception e) {}

            BlockInfo bi = new BlockInfo();
            bi.handle = handle; bi.offset = offset; bi.typeCode = typeCode;
            bi.name = name; bi.flags = flags;
            bi.x = bp[0]; bi.y = bp[1]; bi.z = bp[2];
            bi.xref = xref;
            return bi;
        } catch (Exception e) {
            return null;
        }
    }

    static InsertInfo tryParseInsert(int offset, long handle, Map<Long, String> blockNames) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(data);
            ByteBufferBitInput input = new ByteBufferBitInput(bb);
            input.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(input, version);

            int objSize = r.readModularShort();
            if (objSize <= 0 || objSize > 500) return null;

            int typeCode = r.readBitShort();
            if (typeCode < 0 || typeCode > 500) return null;

            for (int i = 0; i < 4; i++) input.readBits(8);  // bitsize
            r.readHandle();  // own handle

            // EED loop
            int guard = 0;
            while (true) {
                int eedSize = r.readBitShort();
                if (eedSize == 0) break;
                if (eedSize < 0 || eedSize > 500) return null;
                r.readHandle();
                for (int i = 0; i < eedSize; i++) input.readBits(8);
                if (++guard > 50) return null;
            }

            int numR = r.readBitLong();
            if (numR < 0 || numR > 100) return null;
            for (int i = 0; i < numR; i++) r.readHandle();

            r.readHandle();  // xdict
            r.readHandle();  // owner

            // INSERT 特有: blockHeader handle (H)
            long blockHandle = r.readHandle();
            if (!blockNames.containsKey(blockHandle)) return null;

            // insertion point (3BD)
            double[] pt = r.read3BitDouble();
            // scale (3BD)
            r.read3BitDouble();
            // rotation (BD)
            double rot = r.readBitDouble();

            InsertInfo ii = new InsertInfo();
            ii.handle = handle; ii.offset = offset;
            ii.blockHandle = blockHandle; ii.blockName = blockNames.get(blockHandle);
            ii.x = pt[0]; ii.y = pt[1]; ii.z = pt[2]; ii.rot = rot;
            return ii;
        } catch (Exception e) {
            return null;
        }
    }

    static List<long[]> parseHandles(byte[] data, int offset, int maxBytes) {
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
            pos += 2;  // CRC
        }
        return result;
    }
}
