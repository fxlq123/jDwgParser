import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgBlockBegin;
import io.dwg.entities.concrete.DwgBlockEnd;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.sections.objects.ObjectsSectionParser;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class FinalParser {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        // R2000: 对象存储在 section gaps 中
        // 扫描每个 gap，提取对象数据
        System.out.println("=== 扫描 section gaps 中的对象 ===");

        // 已知的 gaps (从之前的分析):
        //   0x50B9 - 0x8067, 0x83F5 - 0xCC3B, 0xCE57 - 0x11943
        // 但我们用更简单的方式: 扫描整个文件

        // 找到 section map (文件从 0x200 处开始有 section 表)
        // 简化: 直接扫描文件中可能的对象数据区域
        // 从 0x5000 到 0x12000

        // 先扫描前几个对象，了解结构
        List<DwgObject> allObjects = new ArrayList<>();
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();

        int scanStart = 0x5000;
        int scanEnd = 0x12000;
        int pos = scanStart;

        ByteBufferBitInput bbuf = new ByteBufferBitInput(data);

        // 用 MS objSizeBytes 的方式扫描
        while (pos < scanEnd - 16) {
            bbuf.seek((long) pos * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, version);

            long startBitOffset = (long) pos * 8L;
            int objSizeBytes;
            try {
                objSizeBytes = r.readModularShort();
            } catch (Exception e) {
                pos++;
                continue;
            }

            if (objSizeBytes <= 0 || objSizeBytes > 0x4000) {
                pos++;
                continue;
            }

            // objDataStartBit
            long objDataStartBit = bbuf.position();

            // typeCode
            int typeCode;
            try {
                typeCode = r.readBitShort();
            } catch (Exception e) {
                pos++;
                continue;
            }

            if (typeCode < 0 || typeCode > 5000) {
                pos++;
                continue;
            }

            // 验证: 这个 objSize 有意义吗？
            long nextBitOffset = objDataStartBit + (long) objSizeBytes * 8L;
            int nextBytePos = (int) ((nextBitOffset + 7) / 8);
            if (nextBytePos > scanEnd) {
                pos++;
                continue;
            }

            // 记录
            typeCount.put(typeCode, typeCount.getOrDefault(typeCode, 0) + 1);

            // 尝试解析 BLOCK_HEADER
            if (typeCode == 48) {
                try {
                    DwgBlockHeader bh = new DwgBlockHeader();
                    bh.setHandle(allObjects.size() + 1L);

                    // 让 reader 从 objDataStartBit 开始解析
                    bbuf.seek(objDataStartBit);
                    BitStreamReader r2 = new BitStreamReader(bbuf, version);

                    // common header + block_header 数据
                    // 简化: 直接跳到 nextBytePos，用原始字节的子集

                    // 提取这个对象的字节
                    byte[] objData = new byte[objSizeBytes];
                    int objDataStartByte = (int) ((objDataStartBit + 7) / 8);
                    // 由于 MS 和 BS 可能不正好在字节边界上，我们需要更精确的方式
                    // 用 jDwgParser 的实际 ObjectsSectionParser

                    // 先做简单的: 从对象的第一个字节 (pos) 到 objSizeBytes 后
                    // 用 parseObjectAt 的方式
                    ByteBufferBitInput bbuf2 = new ByteBufferBitInput(data);
                    bbuf2.seek((long) pos * 8L);
                    BitStreamReader r3 = new BitStreamReader(bbuf2, version);

                    int _objSize = r3.readModularShort();
                    int _tc = r3.readBitShort();

                    // 现在 r3 的位置是在 common data 开始
                    // 用 ObjectsSectionParser 的实际逻辑
                    if (_tc == 48) {
                        bh = parseBlockHeader(r3, version);
                        if (bh != null) {
                            bh.setHandle(allObjects.size() + 1L);
                            bh.setOffset(pos);
                            allObjects.add(bh);
                            System.out.println("  BLOCK_HEADER @ 0x" + Integer.toHexString(pos) +
                                " objSize=" + objSizeBytes +
                                " name='" + safeGetName(bh) + "'");
                        }
                    } else if (_tc == 7) {
                        DwgInsert ins = parseInsert(r3, version);
                        if (ins != null) {
                            ins.setHandle(allObjects.size() + 1L);
                            ins.setOffset(pos);
                            allObjects.add(ins);
                        }
                    }
                } catch (Exception e) {}
            }

            // 跳到下一个对象
            pos = nextBytePos;
        }

        // 类型统计
        System.out.println("\n=== 类型统计 ===");
        List<Map.Entry<Integer, Integer>> sortedTypes = new ArrayList<>(typeCount.entrySet());
        sortedTypes.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int shown = 0;
        for (Map.Entry<Integer, Integer> e : sortedTypes) {
            System.out.printf("  type=%4d (0x%02X): %5d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
            if (++shown >= 25) break;
        }

        // 总结 BLOCK_HEADER
        System.out.println("\n=== BLOCK_HEADER 列表 ===");
        int bhCount = 0;
        for (DwgObject obj : allObjects) {
            if (obj instanceof DwgBlockHeader) {
                DwgBlockHeader bh = (DwgBlockHeader) obj;
                System.out.printf("  [%d] h=0x%x offset=0x%x name='%s' base=(%.2f,%.2f,%.2f)%n",
                    bhCount++, bh.getHandle(), bh.getOffset(),
                    bh.getName() != null ? bh.getName() : "",
                    bh.getBasePoint() != null ? bh.getBasePoint().x() : 0,
                    bh.getBasePoint() != null ? bh.getBasePoint().y() : 0,
                    bh.getBasePoint() != null ? bh.getBasePoint().z() : 0);
            }
        }

        // 统计 INSERT 引用
        System.out.println("\n=== INSERT 引用统计 ===");
        Map<String, Integer> insertRefs = new LinkedHashMap<>();
        Map<Long, String> handleToName = new HashMap<>();
        for (DwgObject obj : allObjects) {
            if (obj instanceof DwgBlockHeader) {
                DwgBlockHeader bh = (DwgBlockHeader) obj;
                handleToName.put(bh.getHandle(), bh.getName() != null ? bh.getName() : "");
            }
        }

        int insCount = 0;
        for (DwgObject obj : allObjects) {
            if (obj instanceof DwgInsert) {
                insCount++;
                DwgInsert ins = (DwgInsert) obj;
                long bhHandle = ins.getBlockHeaderHandle() != null ? ins.getBlockHeaderHandle() : 0;
                String bname = handleToName.getOrDefault(bhHandle, "UNKNOWN(0x" + Long.toHexString(bhHandle) + ")");
                insertRefs.put(bname, insertRefs.getOrDefault(bname, 0) + 1);
            }
        }

        System.out.println("共 " + insCount + " 个 INSERT");
        for (Map.Entry<String, Integer> e : insertRefs.entrySet()) {
            System.out.printf("  '%s': %d 次%n", e.getKey(), e.getValue());
        }
    }

    static String safeGetName(DwgBlockHeader bh) {
        try { return bh.getName() != null ? bh.getName() : ""; }
        catch (Exception e) { return ""; }
    }

    static DwgBlockHeader parseBlockHeader(BitStreamReader r, DwgVersion version) {
        try {
            DwgBlockHeader bh = new DwgBlockHeader();

            // Entity common data
            // bitsize (BL)
            try { r.readBitLong(); } catch (Exception e) {}
            // entity handle
            try { r.readHandle(); } catch (Exception e) {}
            // EED size (MS)
            try {
                int eedSize = r.readModularShort();
                if (eedSize > 0 && eedSize < 1000) {
                    // skip
                    r.getInput().seek(r.getInput().position() + eedSize * 8L);
                }
            } catch (Exception e) {}
            // owner handle
            try { r.readHandle(); } catch (Exception e) {}
            // num reactors
            try {
                int numReactors = r.readBitLong();
                for (int i = 0; i < Math.min(numReactors, 100); i++) {
                    try { r.readHandle(); } catch (Exception ex) { break; }
                }
            } catch (Exception e) {}
            // xdict handle
            try { r.readHandle(); } catch (Exception e) {}

            // BLOCK_HEADER 特有
            // block name: 1-byte length + ASCII
            String name = readRawByteText(r);
            bh.setName(name);

            // flags (BS)
            try { bh.setFlags(r.readBitShort()); } catch (Exception e) {}
            // base point (3 BD)
            try {
                double x = r.readBitDouble();
                double y = r.readBitDouble();
                double z = r.readBitDouble();
                bh.setBasePoint(new io.dwg.geometry.Point3D(x, y, z));
            } catch (Exception e) {}
            // xref path (1-byte length + ASCII)
            try {
                String xref = readRawByteText(r);
                bh.setXrefPath(xref);
            } catch (Exception e) {}

            return bh;
        } catch (Exception e) {
            return null;
        }
    }

    static DwgInsert parseInsert(BitStreamReader r, DwgVersion version) {
        try {
            DwgInsert ins = new DwgInsert();

            // Entity common data
            try { r.readBitLong(); } catch (Exception e) {}
            try { r.readHandle(); } catch (Exception e) {}
            try {
                int eedSize = r.readModularShort();
                if (eedSize > 0 && eedSize < 1000) {
                    r.getInput().seek(r.getInput().position() + eedSize * 8L);
                }
            } catch (Exception e) {}
            try { r.readHandle(); } catch (Exception e) {}
            try {
                int numReactors = r.readBitLong();
                for (int i = 0; i < Math.min(numReactors, 100); i++) {
                    try { r.readHandle(); } catch (Exception ex) { break; }
                }
            } catch (Exception e) {}
            try { r.readHandle(); } catch (Exception e) {}

            // INSERT 特有
            try {
                long bhHandle = r.readHandle();
                ins.setBlockHeaderHandle(bhHandle);
            } catch (Exception e) {}
            try {
                double x = r.readBitDouble();
                double y = r.readBitDouble();
                double z = r.readBitDouble();
                ins.setInsertionPoint(new io.dwg.geometry.Point3D(x, y, z));
            } catch (Exception e) {}
            try {
                double sx = r.readBitDouble();
                double sy = r.readBitDouble();
                double sz = r.readBitDouble();
                ins.setScale(new io.dwg.geometry.Point3D(sx, sy, sz));
            } catch (Exception e) {}
            try {
                double rot = r.readBitDouble();
                ins.setRotationAngle(rot);
            } catch (Exception e) {}

            return ins;
        } catch (Exception e) {
            return null;
        }
    }

    static String readRawByteText(BitStreamReader r) {
        try {
            // 对齐到字节
            long bitPos = r.getInput().position();
            long alignedByte = (bitPos + 7) / 8;
            r.getInput().seek(alignedByte * 8L);

            // 1-byte length
            int len = 0;
            for (int i = 0; i < 8; i++) len = (len << 1) | (r.getInput().readBit() ? 1 : 0);
            if (len <= 0 || len > 200) return "";

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int ch = 0;
                for (int j = 0; j < 8; j++) ch = (ch << 1) | (r.getInput().readBit() ? 1 : 0);
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
