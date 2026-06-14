import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugScan {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        // 扫描 0x5000-0x8000 区域，按 MS objSize + BS typeCode 模式
        System.out.println("=== 扫描 object data 区域 ===");
        scanRegion(data, version, 0x5000, 0x3000);

        // 扫描 0x8000-0xB000
        System.out.println("\n=== 扫描 0x8000-0xB000 ===");
        scanRegion(data, version, 0x8000, 0x3000);

        // 扫描 0xB000-0x11943
        System.out.println("\n=== 扫描 0xB000-0x11943 ===");
        scanRegion(data, version, 0xB000, 0x6943);
    }

    static void scanRegion(byte[] data, DwgVersion version, int start, int length) {
        int pos = start;
        int end = Math.min(start + length, data.length);
        Map<Integer, Integer> typeCounts = new LinkedHashMap<>();
        int found = 0, invalid = 0;
        int firstBHeader = -1, lastBHeader = -1;
        int firstBEnd = -1, lastBEnd = -1;
        int firstInsert = -1, lastInsert = -1;
        int firstMText = -1, lastMText = -1;

        while (pos < end - 4) {
            // 先读 MS objSize
            int firstB = data[pos] & 0xFF;
            int objSize;
            int sizeBytes;
            if (firstB < 0x80) {
                // 1-byte MS
                if (firstB == 0) { pos++; continue; }
                objSize = firstB;
                sizeBytes = 1;
            } else {
                // 2-byte MS
                if (pos + 1 >= end) break;
                int secondB = data[pos+1] & 0xFF;
                objSize = (firstB & 0x7F) | (secondB << 7);
                sizeBytes = 2;
            }

            if (objSize <= 2 || objSize > 2000 || pos + objSize >= end) {
                pos++;
                continue;
            }

            // 然后读 BS typeCode
            int b1 = data[pos + sizeBytes] & 0xFF;
            int opcode = (b1 >> 6) & 0x3;
            int typeCode;
            int codeBits;
            if (opcode == 1) {
                int b2 = data[pos + sizeBytes + 1] & 0xFF;
                typeCode = ((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3);
                codeBits = 2;
            } else if (opcode == 0) {
                int b2 = data[pos + sizeBytes + 1] & 0xFF;
                int b3 = data[pos + sizeBytes + 2] & 0xFF;
                typeCode = (((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3))
                         | ((((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3)) << 8);
                codeBits = 3;
            } else {
                pos++;
                continue;
            }

            // 记录
            typeCounts.put(typeCode, typeCounts.getOrDefault(typeCode, 0) + 1);
            found++;

            // 记录首次/末次位置
            if (typeCode == 48 && firstBHeader < 0) firstBHeader = pos;
            if (typeCode == 48) lastBHeader = pos;
            if (typeCode == 49 && firstBEnd < 0) firstBEnd = pos;
            if (typeCode == 49) lastBEnd = pos;
            if (typeCode == 7 && firstInsert < 0) firstInsert = pos;
            if (typeCode == 7) lastInsert = pos;
            if (typeCode == 44 && firstMText < 0) firstMText = pos;
            if (typeCode == 44) lastMText = pos;

            // 跳到下一个
            pos += objSize;
        }

        System.out.println("找到 " + found + " 个对象, " + invalid + " 跳过");
        System.out.println("类型分布:");
        for (Map.Entry<Integer, Integer> e : typeCounts.entrySet()) {
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
        }
        if (firstBHeader >= 0) System.out.println("BLOCK_HEADER 范围: 0x" + Integer.toHexString(firstBHeader) + " - 0x" + Integer.toHexString(lastBHeader));
        if (firstBEnd >= 0) System.out.println("BLOCK_END 范围: 0x" + Integer.toHexString(firstBEnd) + " - 0x" + Integer.toHexString(lastBEnd));
        if (firstInsert >= 0) System.out.println("INSERT 范围: 0x" + Integer.toHexString(firstInsert) + " - 0x" + Integer.toHexString(lastInsert));
        if (firstMText >= 0) System.out.println("MTEXT 范围: 0x" + Integer.toHexString(firstMText) + " - 0x" + Integer.toHexString(lastMText));

        // 找到 BLOCK_HEADER (type=48) 后，详细解析几个
        if (firstBHeader >= 0) {
            System.out.println("\n=== 解析 BLOCK_HEADER ===");
            List<BlockHeader> bhs = parseBlockHeaders(data, version, 0x5000, end);
            System.out.println("共 " + bhs.size() + " 个 BLOCK_HEADER");
            for (BlockHeader bh : bhs) {
                System.out.printf("  offset=0x%x  h=0x%x  name='%s'  flags=%d  base=(%.2f, %.2f, %.2f)%n",
                    bh.offset, bh.handle, bh.name, bh.flags, bh.baseX, bh.baseY, bh.baseZ);
            }

            // 解析 INSERT
            System.out.println("\n=== 解析 INSERT ===");
            List<Insert> inserts = parseInserts(data, version, 0x5000, end);
            System.out.println("共 " + inserts.size() + " 个 INSERT");

            Map<Long, String> bhMap = new HashMap<>();
            for (BlockHeader bh : bhs) bhMap.put(bh.handle, bh.name);

            Map<String, Integer> refCount = new LinkedHashMap<>();
            int shown = 0;
            for (Insert ins : inserts) {
                String bname = bhMap.getOrDefault(ins.blockHandle, "UNKNOWN(h=0x" + Long.toHexString(ins.blockHandle) + ")");
                refCount.put(bname, refCount.getOrDefault(bname, 0) + 1);
                if (shown++ < 20) {
                    System.out.printf("  offset=0x%x  h=0x%x  block='%s'  pos=(%.2f, %.2f)  scale=(%.3f, %.3f)  rot=%.4f%n",
                        ins.offset, ins.handle, bname, ins.x, ins.y, ins.sx, ins.sy, ins.rotation);
                }
            }

            System.out.println("\n引用统计:");
            for (Map.Entry<String, Integer> e : refCount.entrySet()) {
                System.out.printf("  '%s' : %d 次%n", e.getKey(), e.getValue());
            }
        }
    }

    static List<BlockHeader> parseBlockHeaders(byte[] data, DwgVersion version, int start, int end) {
        List<BlockHeader> result = new ArrayList<>();
        int pos = start;
        while (pos < end - 4) {
            int firstB = data[pos] & 0xFF;
            int objSize, sizeBytes;
            if (firstB < 0x80) {
                if (firstB == 0) { pos++; continue; }
                objSize = firstB; sizeBytes = 1;
            } else {
                if (pos + 1 >= end) break;
                int secondB = data[pos+1] & 0xFF;
                objSize = (firstB & 0x7F) | (secondB << 7);
                sizeBytes = 2;
            }
            if (objSize <= 2 || objSize > 2000 || pos + objSize >= end) { pos++; continue; }

            // BS typeCode
            int b1 = data[pos + sizeBytes] & 0xFF;
            int opcode = (b1 >> 6) & 0x3;
            int typeCode;
            if (opcode == 1) {
                int b2 = data[pos + sizeBytes + 1] & 0xFF;
                typeCode = ((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3);
            } else if (opcode == 0) {
                int b2 = data[pos + sizeBytes + 1] & 0xFF;
                int b3 = data[pos + sizeBytes + 2] & 0xFF;
                typeCode = (((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3))
                         | ((((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3)) << 8);
            } else { pos++; continue; }

            if (typeCode != 48) { pos += objSize; continue; }

            // 详细解析
            try {
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(pos * 8L);
                BitStreamReader r = new BitStreamReader(input, version);

                int _objSize = r.readModularShort();
                int _tc = r.readBitShort();
                if (_tc != 48) { pos += objSize; continue; }

                BlockHeader bh = new BlockHeader();
                bh.offset = pos;

                try {
                    int bitsize = r.readBitLong();
                    long entHandle = r.readHandle();
                    bh.handle = entHandle;
                    try {
                        int eedSize = r.readModularShort();
                        if (eedSize > 0) r.seek(r.position() + eedSize * 8L);
                    } catch (Exception ex) {}
                    try { r.readHandle(); } catch (Exception ex) {}
                    try {
                        int n = r.readBitLong();
                        for (int i = 0; i < n; i++) { try { r.readHandle(); } catch (Exception ex) { break; } }
                    } catch (Exception ex) {}
                    try { r.readHandle(); } catch (Exception ex) {}

                    // 1-byte length text for block name
                    bh.name = readByteText(r);

                    try { bh.flags = r.readBitShort(); } catch (Exception ex) {}
                    try { double[] pt = r.read3BitDouble(); bh.baseX = pt[0]; bh.baseY = pt[1]; bh.baseZ = pt[2]; } catch (Exception ex) {}
                    try { bh.xrefPath = readByteText(r); } catch (Exception ex) {}

                    result.add(bh);
                } catch (Exception ex) {}
            } catch (Exception ex) {}

            pos += objSize;
        }
        return result;
    }

    static List<Insert> parseInserts(byte[] data, DwgVersion version, int start, int end) {
        List<Insert> result = new ArrayList<>();
        int pos = start;
        while (pos < end - 4) {
            int firstB = data[pos] & 0xFF;
            int objSize, sizeBytes;
            if (firstB < 0x80) {
                if (firstB == 0) { pos++; continue; }
                objSize = firstB; sizeBytes = 1;
            } else {
                if (pos + 1 >= end) break;
                int secondB = data[pos+1] & 0xFF;
                objSize = (firstB & 0x7F) | (secondB << 7);
                sizeBytes = 2;
            }
            if (objSize <= 2 || objSize > 2000 || pos + objSize >= end) { pos++; continue; }

            int b1 = data[pos + sizeBytes] & 0xFF;
            int opcode = (b1 >> 6) & 0x3;
            int typeCode;
            if (opcode == 1) {
                int b2 = data[pos + sizeBytes + 1] & 0xFF;
                typeCode = ((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3);
            } else if (opcode == 0) {
                int b2 = data[pos + sizeBytes + 1] & 0xFF;
                int b3 = data[pos + sizeBytes + 2] & 0xFF;
                typeCode = (((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3))
                         | ((((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3)) << 8);
            } else { pos++; continue; }

            if (typeCode != 7) { pos += objSize; continue; }

            try {
                ByteBuffer bb = ByteBuffer.wrap(data);
                ByteBufferBitInput input = new ByteBufferBitInput(bb);
                input.seek(pos * 8L);
                BitStreamReader r = new BitStreamReader(input, version);

                int _objSize = r.readModularShort();
                int _tc = r.readBitShort();
                if (_tc != 7) { pos += objSize; continue; }

                Insert ins = new Insert();
                ins.offset = pos;

                try {
                    int bitsize = r.readBitLong();
                    long entHandle = r.readHandle();
                    ins.handle = entHandle;
                    try {
                        int eedSize = r.readModularShort();
                        if (eedSize > 0) r.seek(r.position() + eedSize * 8L);
                    } catch (Exception ex) {}
                    try { r.readHandle(); } catch (Exception ex) {}
                    try {
                        int n = r.readBitLong();
                        for (int i = 0; i < n; i++) { try { r.readHandle(); } catch (Exception ex) { break; } }
                    } catch (Exception ex) {}
                    try { r.readHandle(); } catch (Exception ex) {}

                    try { ins.blockHandle = r.readHandle(); } catch (Exception ex) {}
                    try { double[] pt = r.read3BitDouble(); ins.x = pt[0]; ins.y = pt[1]; ins.z = pt[2]; } catch (Exception ex) {}
                    try { double[] sc = r.read3BitDouble(); ins.sx = sc[0]; ins.sy = sc[1]; ins.sz = sc[2]; } catch (Exception ex) {}
                    try { ins.rotation = r.readBitDouble(); } catch (Exception ex) {}

                    result.add(ins);
                } catch (Exception ex) {}
            } catch (Exception ex) {}

            pos += objSize;
        }
        return result;
    }

    static String readByteText(BitStreamReader r) {
        try {
            // 对齐到 byte
            long pos = r.position();
            long byteAligned = ((pos + 7) / 8) * 8;
            r.seek(byteAligned);

            int len = 0;
            for (int i = 0; i < 8; i++) len = (len << 1) | (r.getInput().readBit() ? 1 : 0);
            if (len <= 0 || len > 100) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int ch = 0;
                for (int j = 0; j < 8; j++) ch = (ch << 1) | (r.getInput().readBit() ? 1 : 0);
                sb.append((char)ch);
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    static class BlockHeader {
        long offset;
        long handle;
        String name = "";
        int flags;
        double baseX, baseY, baseZ;
        String xrefPath = "";
    }

    static class Insert {
        long offset;
        long handle;
        long blockHandle;
        double x, y, z;
        double sx, sy, sz;
        double rotation;
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
            default: return "UNKNOWN";
        }
    }
}
