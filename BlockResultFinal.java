import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class BlockResultFinal {
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

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        System.out.println("==================================================");
        System.out.println("  FINAL RESULT: Block Definitions and References");
        System.out.println("==================================================");
        System.out.println("File: 210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg");
        System.out.println("Version: R2000 (AC1015)");
        System.out.println("Size: " + data.length + " bytes\n");

        // 从已知的第一个对象位置开始扫描
        int startPos = 0x52b9;  // 第一个对象的位置
        int maxPos = 0x12000;

        System.out.println("扫描区域: 0x" + Integer.toHexString(startPos) + " - 0x" + Integer.toHexString(maxPos));

        List<BlockInfo> blocks = new ArrayList<>();
        List<InsertInfo> inserts = new ArrayList<>();
        Map<Integer, Integer> typeCount = new TreeMap<>();
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

                typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);
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

        // 输出块定义
        System.out.println("\n【块定义】共 " + blocks.size() + " 个:");
        for (int i = 0; i < blocks.size(); i++) {
            BlockInfo bi = blocks.get(i);
            System.out.printf("  %d. '%s' (offset=0x%x, 基点: %.3f, %.3f, %.3f, flags=%d)%n",
                i + 1, bi.name, bi.offset, bi.baseX, bi.baseY, bi.baseZ, bi.flags);
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

        System.out.println("\n【引用统计】共 " + inserts.size() + " 个 INSERT:");
        int total = 0;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            total += e.getValue();
            System.out.printf("  '%s': %d 次%n", e.getKey(), e.getValue());
        }

        System.out.println("\n【INSERT 详情】(前 30 个):");
        for (int i = 0; i < Math.min(30, inserts.size()); i++) {
            InsertInfo ii = inserts.get(i);
            String bname = offsetNameMap.getOrDefault((int)ii.blockHandle, "*UNKNOWN*");
            System.out.printf("  [%d] block='%s' pos=(%.2f,%.2f,%.2f) scale=(%.3f,%.3f,%.3f) rot=%.4f%n",
                i + 1, bname, ii.x, ii.y, ii.z, ii.sx, ii.sy, ii.sz, ii.rotation);
        }

        System.out.println("\n---------------------------------------");
        System.out.println("总计: " + blocks.size() + " 个块定义, " + total + " 个 INSERT 引用");

        // 类型统计
        System.out.println("\n【对象类型统计】");
        for (Map.Entry<Integer, Integer> e : typeCount.entrySet()) {
            String name = typeName(e.getKey());
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n", e.getKey(), e.getKey(), e.getValue(), name);
        }
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

    static String typeName(int code) {
        switch(code) {
            case 0: return "UNUSED";
            case 5: return "ENDBLK";
            case 7: return "INSERT";
            case 20: return "LINE";
            case 44: return "MTEXT";
            case 48: return "BLOCK_HEADER";
            case 49: return "BLOCK_END";
            default: return "TYPE_" + code;
        }
    }
}
