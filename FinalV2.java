import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class FinalV2 {
    static class BlockHeaderInfo {
        int offset;
        int typeCode;
        int objSize;
        String name;
        int flags;
        double baseX, baseY, baseZ;
        String xrefPath;
    }

    static class InsertInfo {
        int offset;
        int typeCode;
        int objSize;
        long blockHeaderHandle;
        double insX, insY, insZ;
        double scaleX, scaleY, scaleZ;
        double rotation;
    }

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        System.out.println("=== 扫描 section gaps 中的对象 (MS objSize + BS typeCode) ===");

        List<BlockHeaderInfo> blockHeaders = new ArrayList<>();
        List<InsertInfo> inserts = new ArrayList<>();
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();

        int scanStart = 0x5000;
        int scanEnd = 0x12000;
        int pos = scanStart;

        ByteBufferBitInput bbuf = new ByteBufferBitInput(data);

        while (pos < scanEnd - 16) {
            bbuf.seek((long) pos * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, version);

            int objSizeBytes;
            try {
                objSizeBytes = r.readModularShort();
            } catch (Exception e) { pos++; continue; }

            if (objSizeBytes <= 0 || objSizeBytes > 0x4000) { pos++; continue; }

            long objDataStartBit = bbuf.position();

            int typeCode;
            try {
                typeCode = r.readBitShort();
            } catch (Exception e) { pos++; continue; }

            if (typeCode < 0 || typeCode > 5000) { pos++; continue; }

            // 验证: 下一个对象的位置
            long nextBitOffset = objDataStartBit + (long) objSizeBytes * 8L;
            int nextBytePos = (int) ((nextBitOffset + 7) / 8);
            if (nextBytePos > scanEnd || nextBytePos <= pos) { pos++; continue; }

            typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);

            // 详细解析 BLOCK_HEADER
            if (typeCode == 48) {
                try {
                    ByteBufferBitInput bb2 = new ByteBufferBitInput(data);
                    bb2.seek((long) pos * 8L);
                    BitStreamReader r2 = new BitStreamReader(bb2, version);

                    int _objSize = r2.readModularShort();
                    int _tc = r2.readBitShort();

                    BlockHeaderInfo bh = new BlockHeaderInfo();
                    bh.offset = pos;
                    bh.typeCode = _tc;
                    bh.objSize = _objSize;

                    // entity common data
                    try { r2.readBitLong(); } catch (Exception e) {}  // bitsize
                    try { r2.readHandle(); } catch (Exception e) {}  // entity handle
                    try {
                        int eedSize = r2.readModularShort();
                        if (eedSize > 0 && eedSize < 200) bb2.seek(bb2.position() + eedSize * 8L);
                    } catch (Exception e) {}
                    try { r2.readHandle(); } catch (Exception e) {}  // owner
                    try {
                        int numReactors = r2.readBitLong();
                        for (int i = 0; i < Math.min(numReactors, 50); i++) {
                            try { r2.readHandle(); } catch (Exception ex) { break; }
                        }
                    } catch (Exception e) {}
                    try { r2.readHandle(); } catch (Exception e) {}  // xdict

                    // BLOCK_HEADER 特有: name (1-byte length + ASCII)
                    bh.name = readByteAlignedText(r2);
                    try { bh.flags = r2.readBitShort(); } catch (Exception e) {}
                    try { bh.baseX = r2.readBitDouble(); } catch (Exception e) {}
                    try { bh.baseY = r2.readBitDouble(); } catch (Exception e) {}
                    try { bh.baseZ = r2.readBitDouble(); } catch (Exception e) {}
                    try { bh.xrefPath = readByteAlignedText(r2); } catch (Exception e) {}

                    blockHeaders.add(bh);
                } catch (Exception e) {}
            }

            // 详细解析 INSERT
            if (typeCode == 7) {
                try {
                    ByteBufferBitInput bb2 = new ByteBufferBitInput(data);
                    bb2.seek((long) pos * 8L);
                    BitStreamReader r2 = new BitStreamReader(bb2, version);

                    int _objSize = r2.readModularShort();
                    int _tc = r2.readBitShort();

                    InsertInfo ins = new InsertInfo();
                    ins.offset = pos;
                    ins.typeCode = _tc;
                    ins.objSize = _objSize;

                    try { r2.readBitLong(); } catch (Exception e) {}  // bitsize
                    try { r2.readHandle(); } catch (Exception e) {}  // entity handle
                    try {
                        int eedSize = r2.readModularShort();
                        if (eedSize > 0 && eedSize < 200) bb2.seek(bb2.position() + eedSize * 8L);
                    } catch (Exception e) {}
                    try { r2.readHandle(); } catch (Exception e) {}  // owner
                    try {
                        int numReactors = r2.readBitLong();
                        for (int i = 0; i < Math.min(numReactors, 50); i++) {
                            try { r2.readHandle(); } catch (Exception ex) { break; }
                        }
                    } catch (Exception e) {}
                    try { r2.readHandle(); } catch (Exception e) {}  // xdict

                    // INSERT 特有
                    try { ins.blockHeaderHandle = r2.readHandle(); } catch (Exception e) {}
                    try { ins.insX = r2.readBitDouble(); } catch (Exception e) {}
                    try { ins.insY = r2.readBitDouble(); } catch (Exception e) {}
                    try { ins.insZ = r2.readBitDouble(); } catch (Exception e) {}
                    try { ins.scaleX = r2.readBitDouble(); } catch (Exception e) {}
                    try { ins.scaleY = r2.readBitDouble(); } catch (Exception e) {}
                    try { ins.scaleZ = r2.readBitDouble(); } catch (Exception e) {}
                    try { ins.rotation = r2.readBitDouble(); } catch (Exception e) {}

                    inserts.add(ins);
                } catch (Exception e) {}
            }

            pos = nextBytePos;
        }

        // 类型统计
        System.out.println("\n=== 类型统计 (前 25 个) ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCount.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int shown = 0;
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            System.out.printf("  type=%4d (0x%02X): %5d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
            if (++shown >= 25) break;
        }

        // BLOCK_HEADER
        System.out.println("\n=== BLOCK_HEADER (type=48) ===");
        System.out.println("共找到 " + blockHeaders.size() + " 个:");
        for (int i = 0; i < blockHeaders.size(); i++) {
            BlockHeaderInfo bh = blockHeaders.get(i);
            System.out.printf("  [%d] @ 0x%x size=%d  name='%s'  flags=%d  base=(%.3f,%.3f,%.3f)  xref='%s'%n",
                i, bh.offset, bh.objSize, bh.name, bh.flags, bh.baseX, bh.baseY, bh.baseZ, bh.xrefPath);
        }

        // INSERT 引用统计
        System.out.println("\n=== INSERT (type=7) - 引用统计 ===");
        System.out.println("共找到 " + inserts.size() + " 个:");
        Map<String, Integer> refCount = new LinkedHashMap<>();
        Map<String, List<InsertInfo>> byBlockName = new LinkedHashMap<>();

        // 构建 offset→block name 映射
        Map<Integer, String> offsetNameMap = new HashMap<>();
        for (BlockHeaderInfo bh : blockHeaders) {
            offsetNameMap.put(bh.offset, bh.name);
        }

        // 用 handle 在 0x5000-0x6000 范围的 block header
        // 先尝试 blockHeaderHandle 映射
        for (InsertInfo ins : inserts) {
            String blockName = offsetNameMap.getOrDefault((int)(ins.blockHeaderHandle & 0xFFFF),
                "UNKNOWN(h=0x" + Long.toHexString(ins.blockHeaderHandle) + ")");
            refCount.put(blockName, refCount.getOrDefault(blockName, 0) + 1);
            byBlockName.computeIfAbsent(blockName, k -> new ArrayList<>()).add(ins);
        }

        System.out.println("\n 引用统计:");
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            System.out.printf("    '%s': %d 次%n", e.getKey(), e.getValue());
        }

        // 显示每个 INSERT 的详细信息
        int maxShown = Math.min(30, inserts.size());
        System.out.println("\n  前 " + maxShown + " 个 INSERT 详情:");
        for (int i = 0; i < maxShown; i++) {
            InsertInfo ins = inserts.get(i);
            String bname = offsetNameMap.getOrDefault((int)(ins.blockHeaderHandle & 0xFFFF),
                "0x" + Long.toHexString(ins.blockHeaderHandle));
            System.out.printf("    [%d] @ 0x%x  block='%s'  pos=(%.2f,%.2f)  scale=(%.3f,%.3f)  rot=%.4f%n",
                i, ins.offset, bname, ins.insX, ins.insY, ins.scaleX, ins.scaleY, ins.rotation);
        }

        // 最终输出摘要
        System.out.println("\n==================================================");
        System.out.println("  FINAL RESULT: Block Definitions and References");
        System.out.println("==================================================");
        System.out.println("\n【块定义】共 " + blockHeaders.size() + " 个:");
        for (int i = 0; i < blockHeaders.size(); i++) {
            BlockHeaderInfo bh = blockHeaders.get(i);
            System.out.printf("  %d. '%s' (基点: %.3f, %.3f, %.3f, 大小: %d 字节)%n",
                i + 1, bh.name, bh.baseX, bh.baseY, bh.baseZ, bh.objSize);
        }
        System.out.println("\n【引用统计】:");
        int totalRefs = 0;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            totalRefs += e.getValue();
            System.out.printf("  - '%s': %d 次%n", e.getKey(), e.getValue());
        }
        System.out.println("\n总计: " + blockHeaders.size() + " 个块定义, " + totalRefs + " 个 INSERT 引用");
    }

    static String readByteAlignedText(BitStreamReader r) {
        try {
            ByteBufferBitInput input = (ByteBufferBitInput) r.getInput();
            long bitPos = input.position();
            long alignedByte = (bitPos + 7) / 8;
            input.seek(alignedByte * 8L);

            int len = 0;
            for (int i = 0; i < 8; i++) len = (len << 1) | (input.readBit() ? 1 : 0);
            if (len <= 0 || len > 200) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int ch = 0;
                for (int j = 0; j < 8; j++) ch = (ch << 1) | (input.readBit() ? 1 : 0);
                sb.append((char) ch);
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
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
