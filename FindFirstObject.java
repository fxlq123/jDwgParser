import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class FindFirstObject {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        System.out.println("=== 找到第一个对象 ===");
        int start = 0x5000;
        int end = 0x6000;

        for (int pos = start; pos < end; pos++) {
            if (pos >= data.length - 4) break;
            if (data[pos] == 0) continue;

            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, DwgVersion.R2000);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) continue;

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) continue;

                System.out.printf("第一个对象 @ 0x%x: MS=%d, type=%d%n", pos, objSize, typeCode);
                System.out.println("从 " + pos + " 开始扫描...");

                // 从这里开始扫描
                scanFrom(data, DwgVersion.R2000, pos, 0x12000);
                break;
            } catch (Exception e) {}
        }
    }

    static void scanFrom(byte[] data, DwgVersion version, int startPos, int maxPos) {
        List<BlockInfo> blocks = new ArrayList<>();
        List<InsertInfo> inserts = new ArrayList<>();
        int pos = startPos;
        int scanned = 0;

        while (pos < maxPos && scanned < 2000) {
            if (pos >= data.length - 4) break;
            if (data[pos] == 0) { pos++; continue; }

            try {
                byte[] sub = Arrays.copyOfRange(data, pos, data.length);
                ByteBufferBitInput buf = new ByteBufferBitInput(ByteBuffer.wrap(sub));
                BitStreamReader r = new BitStreamReader(buf, version);

                int objSize = r.readModularShort();
                if (objSize <= 2 || objSize > 2000) { pos++; continue; }

                int typeCode = r.readBitShort();
                if (typeCode < 0 || typeCode > 255) { pos++; continue; }

                scanned++;

                if (typeCode == 48) {
                    BlockInfo bi = parseBlockHeader(r);
                    bi.offset = pos;
                    bi.objSize = objSize;
                    blocks.add(bi);
                }

                if (typeCode == 7) {
                    InsertInfo ii = parseInsert(r);
                    ii.offset = pos;
                    inserts.add(ii);
                }

                pos += 2 + objSize;
            } catch (Exception e) {
                pos++;
            }
        }

        System.out.println("扫描了 " + scanned + " 个对象");
        System.out.println("\n【块定义】" + blocks.size() + " 个:");
        for (int i = 0; i < blocks.size(); i++) {
            BlockInfo bi = blocks.get(i);
            System.out.printf("  %d. '%s' (offset=0x%x, 基点: %.3f, %.3f, %.3f)%n",
                i + 1, bi.name, bi.offset, bi.baseX, bi.baseY, bi.baseZ);
        }

        // 构建 offset -> block name 映射
        Map<Integer, String> offsetNameMap = new HashMap<>();
        for (BlockInfo bi : blocks) {
            offsetNameMap.put(bi.offset, bi.name);
        }

        // 引用统计
        Map<String, Integer> refCount = new LinkedHashMap<>();
        for (InsertInfo ii : inserts) {
            String bname = offsetNameMap.getOrDefault((int)ii.blockHandle, "*UNKNOWN*");
            refCount.put(bname, refCount.getOrDefault(bname, 0) + 1);
        }

        System.out.println("\n【引用统计】" + inserts.size() + " 个 INSERT:");
        int total = 0;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            total += e.getValue();
            System.out.printf("  '%s': %d 次%n", e.getKey(), e.getValue());
        }

        System.out.println("\n【INSERT 详情】(前 20 个):");
        for (int i = 0; i < Math.min(20, inserts.size()); i++) {
            InsertInfo ii = inserts.get(i);
            String bname = offsetNameMap.getOrDefault((int)ii.blockHandle, "*UNKNOWN*");
            System.out.printf("  [%d] block='%s' pos=(%.2f,%.2f,%.2f) rot=%.4f%n",
                i + 1, bname, ii.x, ii.y, ii.z, ii.rotation);
        }

        System.out.println("\n总计: " + blocks.size() + " 个块定义, " + total + " 个 INSERT 引用");
    }

    static BlockInfo parseBlockHeader(BitStreamReader r) {
        BlockInfo bi = new BlockInfo();
        try {
            int bitsize = r.readBitLong();
            long entH = r.readHandle();
            int eedSize = r.readModularShort();
            if (eedSize > 0 && eedSize < 200) r.getInput().seek(r.getInput().position() + eedSize * 8L);
            long owner = r.readHandle();
            int numReactors = r.readBitLong();
            for (int i = 0; i < Math.min(numReactors, 10); i++) {
                try { r.readHandle(); } catch (Exception e) { break; }
            }
            try { r.readHandle(); } catch (Exception e) {}

            // name: 1-byte length + ASCII
            ByteBufferBitInput buf = (ByteBufferBitInput) r.getInput();
            long namePos = buf.position();
            buf.seek(((namePos + 7) / 8) * 8);
            int nameLen = 0;
            for (int i = 0; i < 8; i++) nameLen = (nameLen << 1) | (buf.readBit() ? 1 : 0);
            if (nameLen > 0 && nameLen < 100) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int ch = 0;
                    for (int j = 0; j < 8; j++) ch = (ch << 1) | (buf.readBit() ? 1 : 0);
                    sb.append((char)ch);
                }
                bi.name = sb.toString();
            }

            bi.flags = r.readBitShort();
            bi.baseX = r.readBitDouble();
            bi.baseY = r.readBitDouble();
            bi.baseZ = r.readBitDouble();
        } catch (Exception e) {}
        return bi;
    }

    static InsertInfo parseInsert(BitStreamReader r) {
        InsertInfo ii = new InsertInfo();
        try {
            int bitsize = r.readBitLong();
            long entH = r.readHandle();
            int eedSize = r.readModularShort();
            if (eedSize > 0 && eedSize < 200) r.getInput().seek(r.getInput().position() + eedSize * 8L);
            long owner = r.readHandle();
            int numReactors = r.readBitLong();
            for (int i = 0; i < Math.min(numReactors, 10); i++) {
                try { r.readHandle(); } catch (Exception e) { break; }
            }
            try { r.readHandle(); } catch (Exception e) {}

            ii.blockHandle = r.readHandle();
            ii.x = r.readBitDouble();
            ii.y = r.readBitDouble();
            ii.z = r.readBitDouble();
            ii.sx = r.readBitDouble();
            ii.sy = r.readBitDouble();
            ii.sz = r.readBitDouble();
            ii.rotation = r.readBitDouble();
        } catch (Exception e) {}
        return ii;
    }

    static class BlockInfo {
        int offset;
        int objSize;
        String name = "";
        double baseX, baseY, baseZ;
        int flags;
    }

    static class InsertInfo {
        int offset;
        long blockHandle;
        double x, y, z;
        double sx, sy, sz;
        double rotation;
    }
}
