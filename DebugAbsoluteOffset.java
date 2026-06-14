import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugAbsoluteOffset {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        // 解析 handles (无符号累积)
        List<long[]> handles = parseHandles(data, 0x11943, 8192);
        System.out.println("解析到 " + handles.size() + " 个 handle 条目");

        // 测试: offset 是文件中的绝对偏移
        System.out.println("\n=== 扫描对象 (offset 作为绝对地址) ===");
        Map<Integer, Integer> typeCounts = new LinkedHashMap<>();
        int scanned = 0, invalidSize = 0;
        long minOff = Long.MAX_VALUE, maxOff = 0;

        for (long[] h : handles) {
            long offset = h[1];
            if (offset < 0 || offset > data.length - 4) continue;
            minOff = Math.min(minOff, offset);
            maxOff = Math.max(maxOff, offset);

            try {
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(input, version);
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 2000) { invalidSize++; continue; }

                int typeCode = r.readBitShort();
                typeCounts.put(typeCode, typeCounts.getOrDefault(typeCode, 0) + 1);
                scanned++;
            } catch (Exception e) {}
        }

        System.out.println("offset 范围: 0x" + Long.toHexString(minOff) + " - 0x" + Long.toHexString(maxOff));
        System.out.println("成功扫描: " + scanned + "  无效 objSize: " + invalidSize);
        System.out.println("\n类型分布:");
        for (Map.Entry<Integer, Integer> e : typeCounts.entrySet()) {
            System.out.printf("  type=%3d (0x%02X): %4d 个  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
        }

        // 现在详细解析 BLOCK_HEADER 和 INSERT
        System.out.println("\n=== 详细解析 BLOCK_HEADER (type=48) ===");
        List<BlockHeader> blockHeaders = new ArrayList<>();
        for (long[] h : handles) {
            try {
                long offset = h[1];
                if (offset < 0 || offset > data.length - 10) continue;
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(input, version);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 2000) continue;

                int typeCode = r.readBitShort();
                if (typeCode != 48) continue;

                BlockHeader bh = new BlockHeader();
                bh.handle = h[0];

                // entity common
                try {
                    int bitsize = r.readBitLong();
                    long entHandle = r.readHandle();
                    // EED
                    try {
                        int eedSize = r.readModularShort();
                        if (eedSize > 0) r.seek(r.position() + eedSize * 8L);
                    } catch (Exception ex) {}
                    // owner object handle
                    try { r.readHandle(); } catch (Exception ex) {}
                    // number of reactors
                    try {
                        int numReactors = r.readBitLong();
                        for (int i = 0; i < numReactors; i++) {
                            try { r.readHandle(); } catch (Exception ex) { break; }
                        }
                    } catch (Exception ex) {}
                    // xdic obj handle
                    try { r.readHandle(); } catch (Exception ex) {}

                    // BLOCK_HEADER 特有字段
                    String name = read1ByteLenText(r);
                    bh.name = name;

                    try { int flags = r.readBitShort(); bh.flags = flags; } catch (Exception ex) {}
                    try {
                        double[] pt = r.read3BitDouble();
                        bh.baseX = pt[0]; bh.baseY = pt[1]; bh.baseZ = pt[2];
                    } catch (Exception ex) {}
                    try { bh.xrefPath = read1ByteLenText(r); } catch (Exception ex) {}

                    blockHeaders.add(bh);
                } catch (Exception ex) {}
            } catch (Exception ex) {}
        }

        System.out.println("找到 " + blockHeaders.size() + " 个 BLOCK_HEADER");
        for (BlockHeader bh : blockHeaders) {
            System.out.printf("  h=0x%x  name='%s'  flags=%d  base=(%.2f, %.2f, %.2f)  xref='%s'%n",
                bh.handle, bh.name, bh.flags, bh.baseX, bh.baseY, bh.baseZ, bh.xrefPath);
        }

        // INSERT
        System.out.println("\n=== 详细解析 INSERT (type=7) ===");
        List<Insert> inserts = new ArrayList<>();
        for (long[] h : handles) {
            try {
                long offset = h[1];
                if (offset < 0 || offset > data.length - 10) continue;
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(input, version);

                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 2000) continue;

                int typeCode = r.readBitShort();
                if (typeCode != 7) continue;

                Insert ins = new Insert();
                ins.handle = h[0];

                // entity common
                try {
                    int bitsize = r.readBitLong();
                    long entHandle = r.readHandle();
                    try {
                        int eedSize = r.readModularShort();
                        if (eedSize > 0) r.seek(r.position() + eedSize * 8L);
                    } catch (Exception ex) {}
                    try { r.readHandle(); } catch (Exception ex) {}
                    try {
                        int numReactors = r.readBitLong();
                        for (int i = 0; i < numReactors; i++) {
                            try { r.readHandle(); } catch (Exception ex) { break; }
                        }
                    } catch (Exception ex) {}
                    try { r.readHandle(); } catch (Exception ex) {}

                    // INSERT 特有
                    try { ins.blockHandle = r.readHandle(); } catch (Exception ex) {}
                    try { double[] pt = r.read3BitDouble(); ins.x = pt[0]; ins.y = pt[1]; ins.z = pt[2]; } catch (Exception ex) {}
                    try { double[] sc = r.read3BitDouble(); ins.sx = sc[0]; ins.sy = sc[1]; ins.sz = sc[2]; } catch (Exception ex) {}
                    try { ins.rotation = r.readBitDouble(); } catch (Exception ex) {}

                    inserts.add(ins);
                } catch (Exception ex) {}
            } catch (Exception ex) {}
        }

        System.out.println("找到 " + inserts.size() + " 个 INSERT");
        // 构建 block handle -> name 映射
        Map<Long, String> blockNameMap = new HashMap<>();
        for (BlockHeader bh : blockHeaders) blockNameMap.put(bh.handle, bh.name);

        // 统计引用
        Map<String, Integer> refCounts = new LinkedHashMap<>();
        for (Insert ins : inserts) {
            String name = blockNameMap.getOrDefault(ins.blockHandle, "UNKNOWN(h=0x" + Long.toHexString(ins.blockHandle) + ")");
            refCounts.put(name, refCounts.getOrDefault(name, 0) + 1);
        }

        System.out.println("\n引用统计:");
        for (Map.Entry<String, Integer> e : refCounts.entrySet()) {
            System.out.printf("  '%s' : %d 次%n", e.getKey(), e.getValue());
        }

        // 显示几个 INSERT 详细信息
        int shown = 0;
        for (Insert ins : inserts) {
            if (shown++ >= 15) break;
            String bname = blockNameMap.getOrDefault(ins.blockHandle, "?");
            System.out.printf("  h=0x%x block='%s' pos=(%.2f, %.2f) scale=(%.3f, %.3f) rot=%.4f%n",
                ins.handle, bname, ins.x, ins.y, ins.sx, ins.sy, ins.rotation);
        }
    }

    static String read1ByteLenText(BitStreamReader r) throws Exception {
        // 读取 1 byte length + ASCII
        long pos = r.position();
        // 对齐到 byte 边界
        long byteAlignedPos = ((pos + 7) / 8) * 8;
        r.seek(byteAlignedPos);
        // 现在读取 length
        int len = 0;
        // 直接从 input 读取 8 位
        for (int i = 0; i < 8; i++) len = (len << 1) | (r.getInput().readBit() ? 1 : 0);
        if (len <= 0 || len > 100) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int ch = 0;
            for (int j = 0; j < 8; j++) ch = (ch << 1) | (r.getInput().readBit() ? 1 : 0);
            sb.append((char)ch);
        }
        return sb.toString();
    }

    static class BlockHeader {
        long handle;
        String name = "";
        int flags;
        double baseX, baseY, baseZ;
        String xrefPath = "";
    }

    static class Insert {
        long handle;
        long blockHandle;
        double x, y, z;
        double sx, sy, sz;
        double rotation;
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
                    oDelta = oByte;
                } else {
                    int lo = oByte & 0x7F;
                    int hi = data[pos++] & 0xFF;
                    pairsRead++;
                    oDelta = lo | ((long)hi << 7);
                }
                lastOffset += oDelta;
                result.add(new long[]{ lastHandle, lastOffset });
            }
            pos += 2;
        }
        return result;
    }

    static String typeName(int code) {
        String[] names = {
            "UNUSED", "TEXT", "ATTDEF", "ATTRIB", "SEQEND", "ENDBLK", "", "INSERT", "MINSERT",
            "", "V2D", "V3D", "VMESH", "VPF", "VPFF",
            "PL2D", "PL3D", "ARC", "CIRCLE", "LINE",
            "DIMO", "DIML", "DIMA", "DIM3",
            "DIM2", "DIMR", "DIMD", "POINT", "FACE",
            "PLPF", "PLPM", "SOLID", "TRACE", "SHAPE", "VPORT",
            "EL", "SPL", "REG", "S3D", "BODY", "RAY", "XLINE", "DICT",
            "", "MTEXT", "LEAD", "TOL", "MLINE", "BLKH", "BLKE",
            "LTYPE", "LAYER", "STYLE"
        };
        if (code >= 0 && code < names.length && names[code] != null && !names[code].isEmpty()) return names[code];
        switch(code) {
            case 0x3C: return "GROUP";
            case 0x4B: return "LWPLINE";
            case 0x4C: return "HATCH";
            case 0x4D: return "XRECORD";
            case 0x50: return "LAYOUT";
            default: return "?";
        }
    }
}
