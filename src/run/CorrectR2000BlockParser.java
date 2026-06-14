package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import io.dwg.sections.classes.*;
import io.dwg.sections.objects.*;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.*;
import io.dwg.core.type.*;
import java.nio.file.Paths;
import java.util.*;

/**
 * R2000 正确的 BLOCK 和 INSERT 解析器
 *
 * R2000 格式的关键区别:
 *
 * OBJECT (BLOCK_HEADER = 0x30, 不是实体):
 *   object_common_data: owner_handle(H) + num_reactors(BS/BL)
 *                       + reactor_handles(H * num)
 *                       + xdict_handle(H) [only if hasXDic flag, R2004+]
 *     → 注意 R2000 的 object common 与 entity common 完全不同
 *
 * ENTITY (INSERT = 0x07, BLOCK = 0x31, LINE = 0x1F, 等等):
 *   entity_header: bitsize(RL, 32bit, 非对齐)
 *                 + entity_handle(H) 
 *                 + EED_size(BS) - 扩展实体数据大小
 *   entity_common_data (R2000):
 *     preview_exists_bit(1)
 *     ent_mode(2)
 *     num_reactors(BS)
 *     reactor_handles(H * num)
 *     xdict_handle(H)
 *     layer_handle(H)
 *     linetype_handle(H)
 *     color(BS)
 *     ltype_scale(BD)
 *     ltype_flags(2bits) + plotstyle_flags(2bits)
 *     invisible(BS)
 *     linewt(RC=8bits)
 */
public class CorrectR2000BlockParser {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        // 1. Parse sections
        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        // R2000: objects stored at offsets relative to file
        // 直接使用文件的原始 bytes，handles offset 是文件中的偏移量
        byte[] objData = data;
        System.out.println("File data size: " + objData.length + " bytes\n");

        // 2. 扫描对象区域，找到所有 BLOCK_HEADER(0x30), BLOCK(0x31), INSERT(0x07)
        System.out.println("=== 扫描对象区域 ===\n");

        // 用 handle offsets 定位对象
        List<ObjectInfo> objects = new ArrayList<>();
        for (long h : registry.allHandles()) {
            java.util.Optional<Long> offsetOpt = registry.offsetFor(h);
            if (!offsetOpt.isPresent()) continue;
            long off = offsetOpt.get();
            if (off < 0 || off >= objData.length - 4) continue;

            ByteBufferBitInput bb = new ByteBufferBitInput(objData);
            bb.seek(off * 8L);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

            int objSize = r.readModularShort();
            if (objSize <= 0 || objSize > 0x40000) continue;

            int type = r.readBitShort();
            if (type == 0x30 || type == 0x31 || type == 0x07) {
                objects.add(new ObjectInfo(h, off, objSize, type));
            }
        }

        // Sort by offset
        objects.sort((a, b) -> Long.compare(a.offset, b.offset));

        // 3. 按类型分组并解析
        Map<Integer, List<ObjectInfo>> byType = new HashMap<>();
        for (ObjectInfo oi : objects) {
            byType.computeIfAbsent(oi.type, k -> new ArrayList<>()).add(oi);
        }

        // 4. BLOCK_HEADER (0x30) - OBJECT, not ENTITY
        System.out.println("=== BLOCK_HEADER (0x30) 数量: " +
            byType.getOrDefault(0x30, new ArrayList<>()).size() + " ===\n");
        Map<String, Long> blockNameToHandle = new HashMap<>();
        for (ObjectInfo oi : byType.getOrDefault(0x30, new ArrayList<>())) {
            try {
                parseBlockHeader(objData, oi, blockNameToHandle);
            } catch (Exception e) {
                System.out.println("  解析失败: " + e.getMessage());
            }
        }

        // 5. BLOCK (0x31) - BLOCK 实体/表记录
        System.out.println("\n=== BLOCK (0x31) 数量: " +
            byType.getOrDefault(0x31, new ArrayList<>()).size() + " ===\n");
        for (ObjectInfo oi : byType.getOrDefault(0x31, new ArrayList<>())) {
            try {
                parseBlockEntity(objData, oi);
            } catch (Exception e) {
                System.out.println("  解析失败: " + e.getMessage());
            }
        }

        // 6. INSERT (0x07) - ENTITY
        System.out.println("\n=== INSERT (0x07) 数量: " +
            byType.getOrDefault(0x07, new ArrayList<>()).size() + " ===\n");
        for (ObjectInfo oi : byType.getOrDefault(0x07, new ArrayList<>())) {
            try {
                parseInsertEntity(objData, oi);
            } catch (Exception e) {
                System.out.println("  解析失败: " + e.getMessage());
            }
        }

        // 7. 总结
        System.out.println("\n=== 总结 ===\n");
        System.out.println("BLOCK_HEADER 定义的块名: " + blockNameToHandle.size() + " 个");
        for (Map.Entry<String, Long> e : blockNameToHandle.entrySet()) {
            System.out.println("  '" + e.getKey() + "' (handle=0x" + Long.toHexString(e.getValue()) + ")");
        }
    }

    /**
     * 解析 BLOCK_HEADER 对象 (type 0x30) - 这是 OBJECT，不是 ENTITY
     * 结构: obj_size(MS) + type_code(BS) + object_common_data + block_header_specific
     *
     * object_common_data (R2000):
     *   num_reactors(BS)
     *   owner_handle(H)
     *   H * num_reactors
     *   xdict_handle(H) - R2000 中, 实际上在 num_reactors 之后
     * 注意: R2000 对象格式的 object common 没有 layer_handle 等实体字段
     */
    private static void parseBlockHeader(byte[] raw, ObjectInfo oi, Map<String, Long> nameMap) throws Exception {
        ByteBufferBitInput bb = new ByteBufferBitInput(raw);
        bb.seek(oi.offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        int objSize = r.readModularShort();
        int type = r.readBitShort();

        // object common data - 根据实际规范
        int numReactors = r.readBitShort();
        if (numReactors < 0 || numReactors > 1000) numReactors = 0;

        long ownerHandle = r.readHandle();

        // skip reactors
        for (int i = 0; i < numReactors; i++) {
            r.readHandle();
        }

        long xdictHandle = r.readHandle();

        // BLOCK_HEADER 特有字段
        // block_name (TU/R2000: TV - ASCII)
        String blockName = null;
        try {
            // TV (R2000): BS length + ASCII chars
            int nameLen = r.readBitShort();
            if (nameLen > 0 && nameLen < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int c = r.getInput().readBits(8) & 0xFF;
                    sb.append((char)c);
                }
                blockName = sb.toString();
            }
        } catch (Exception e) {
            // Try fallback: first bit from common end + seek to ASCII
            // skip
        }

        // flags (BS)
        int flags = 0;
        try {
            flags = r.readBitShort();
        } catch (Exception ignored) {}

        // base_point (3RD)
        double bx = 0, by = 0, bz = 0;
        try {
            double[] pt = r.read3RawDouble();
            bx = pt[0]; by = pt[1]; bz = pt[2];
        } catch (Exception ignored) {}

        // xref_path (TV)
        String xrefPath = null;
        try {
            int pathLen = r.readBitShort();
            if (pathLen > 0 && pathLen < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pathLen; i++) {
                    int c = r.getInput().readBits(8) & 0xFF;
                    sb.append((char)c);
                }
                xrefPath = sb.toString();
            }
        } catch (Exception ignored) {}

        System.out.println("  [BLOCK_HEADER handle=0x" + Long.toHexString(oi.handle)
            + " offset=" + oi.offset + " size=" + objSize + "]");
        System.out.println("    owner_handle=0x" + Long.toHexString(ownerHandle)
            + " num_reactors=" + numReactors
            + " xdict=0x" + Long.toHexString(xdictHandle));
        System.out.println("    block_name='" + (blockName == null ? "(空)" : blockName) + "'");
        System.out.println("    flags=" + flags
            + String.format(" base_point=(%.2f, %.2f, %.2f)", bx, by, bz));
        if (xrefPath != null && !xrefPath.isEmpty()) {
            System.out.println("    xref_path='" + xrefPath + "'");
        }

        if (blockName != null && !blockName.isEmpty()) {
            nameMap.put(blockName, oi.handle);
        }
    }

    /**
     * 解析 BLOCK 实体 (type 0x31) - 这是 BLOCK 的实体记录
     * 注意：libredwg 中 0x31 是 BLOCK，是"块定义的第一条实体"
     * 它的结构: entity_header + entity_common_data + block_specific
     *
     * entity_header: bitsize(RL) + entity_handle(H) + EED_size(BS)
     * entity_common_data (R2000): preview(1bit) + ent_mode(2bits) + num_reactors(BS)
     *   + reactors(H*num) + xdict_handle(H) + layer_handle(H)
     *   + ltype_handle(H) + prev_handle(H) + next_handle(H)
     *   + color(BS) + ltype_scale(BD) + ltype_flags(2) + plotstyle_flags(2)
     *   + invisible(BS) + linewt(8 bits)
     *
     * block_specific: block_name(TV) + flags(BS) + base_point(3RD) + xref_path(TV)
     */
    private static void parseBlockEntity(byte[] raw, ObjectInfo oi) throws Exception {
        ByteBufferBitInput bb = new ByteBufferBitInput(raw);
        bb.seek(oi.offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        int objSize = r.readModularShort();
        int type = r.readBitShort();

        // entity header
        int bitsize = r.readBitLong();  // 4 bytes
        long entityHandle = r.readHandle();
        int eedSize = r.readBitShort();
        if (eedSize > 0 && eedSize < 0x8000) {
            // skip EED data
            bb.seek(bb.position() + (long)eedSize * 8L);
        }

        // entity common (partial, minimal)
        boolean preview = (r.getInput().readBits(1) & 1) == 1;
        int entMode = r.getInput().readBits(2) & 3;
        int numReactors = r.readBitShort();
        if (numReactors < 0 || numReactors > 1000) numReactors = 0;

        for (int i = 0; i < numReactors; i++) {
            r.readHandle();
        }

        long xdictHandle = r.readHandle();
        long layerHandle = r.readHandle();
        long ltypeHandle = r.readHandle();

        // prev & next handles
        long prevHandle = r.readHandle();
        long nextHandle = r.readHandle();

        int color = r.readBitShort();

        // ltype_scale
        r.readBitDouble();

        // ltype_flags(2) + plotstyle_flags(2)
        r.getInput().readBits(4);  // skip 4 bits

        // invisible(BS)
        r.readBitShort();

        // linewt(RC=8)
        r.getInput().readBits(8);

        // block specific
        // block_name (TV)
        String blockName = null;
        try {
            int nameLen = r.readBitShort();
            if (nameLen > 0 && nameLen < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen; i++) {
                    int c = r.getInput().readBits(8) & 0xFF;
                    sb.append((char)c);
                }
                blockName = sb.toString();
            }
        } catch (Exception ignored) {}

        // block_flags (BS)
        int flags = 0;
        try { flags = r.readBitShort(); } catch (Exception ignored) {}

        // base_point (3RD)
        double bx = 0, by = 0, bz = 0;
        try {
            double[] pt = r.read3RawDouble();
            bx = pt[0]; by = pt[1]; bz = pt[2];
        } catch (Exception ignored) {}

        System.out.println("  [BLOCK handle=0x" + Long.toHexString(oi.handle)
            + " offset=" + oi.offset + " size=" + objSize + "]");
        System.out.println("    entity_handle=0x" + Long.toHexString(entityHandle)
            + " layer=0x" + Long.toHexString(layerHandle)
            + " ltype=0x" + Long.toHexString(ltypeHandle));
        System.out.println("    prev=0x" + Long.toHexString(prevHandle)
            + " next=0x" + Long.toHexString(nextHandle)
            + " color=" + color);
        System.out.println("    block_name='" + (blockName == null ? "(空)" : blockName) + "'");
        System.out.println(String.format("    flags=%d base_point=(%.2f, %.2f, %.2f)", flags, bx, by, bz));
    }

    /**
     * 解析 INSERT 实体 (type 0x07)
     */
    private static void parseInsertEntity(byte[] raw, ObjectInfo oi) throws Exception {
        ByteBufferBitInput bb = new ByteBufferBitInput(raw);
        bb.seek(oi.offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        int objSize = r.readModularShort();
        int type = r.readBitShort();

        // entity header
        int bitsize = r.readBitLong();
        long entityHandle = r.readHandle();
        int eedSize = r.readBitShort();
        if (eedSize > 0 && eedSize < 0x8000) {
            bb.seek(bb.position() + (long)eedSize * 8L);
        }

        // entity common
        boolean preview = (r.getInput().readBits(1) & 1) == 1;
        int entMode = r.getInput().readBits(2) & 3;
        int numReactors = r.readBitShort();
        if (numReactors < 0 || numReactors > 1000) numReactors = 0;

        for (int i = 0; i < numReactors; i++) r.readHandle();

        long xdictHandle = r.readHandle();
        long layerHandle = r.readHandle();
        long ltypeHandle = r.readHandle();
        long prevHandle = r.readHandle();
        long nextHandle = r.readHandle();
        int color = r.readBitShort();
        r.readBitDouble();  // ltype_scale
        r.getInput().readBits(4);  // ltype_flags(2) + plotstyle_flags(2)
        r.readBitShort();  // invisible
        r.getInput().readBits(8);  // linewt

        // INSERT specific
        long blockHeaderHandle = r.readHandle();  // references a BLOCK_HEADER
        double[] insPt = r.read3BitDouble();  // 3BD
        int scaleFlags = r.getInput().readBits(2) & 3;
        double sx, sy, sz;
        if (scaleFlags == 3) {
            sx = sy = sz = 1.0;
        } else if (scaleFlags == 1) {
            double s = r.readRawDouble();  // shared RD
            sx = sy = sz = s;
        } else {
            sx = r.readBitDouble();
            sy = r.readBitDouble();
            sz = r.readBitDouble();
        }

        double rotAngle = r.readBitDouble();  // rotation angle
        double[] extrusion = r.readBitExtrusion();  // extrusion direction
        boolean hasAttribs = (r.getInput().readBits(1) & 1) == 1;

        System.out.println("  [INSERT handle=0x" + Long.toHexString(oi.handle)
            + " offset=" + oi.offset + " size=" + objSize + "]");
        System.out.println("    layer=0x" + Long.toHexString(layerHandle)
            + " ltype=0x" + Long.toHexString(ltypeHandle)
            + " color=" + color);
        System.out.println("    block_header_handle=0x" + Long.toHexString(blockHeaderHandle)
            + " (引用块的 handle)");
        System.out.println(String.format("    insert_point=(%.3f, %.3f, %.3f)", insPt[0], insPt[1], insPt[2]));
        System.out.println(String.format("    scale=(%.3f, %.3f, %.3f)", sx, sy, sz));
        System.out.println(String.format("    rotation=%.6f rad (%.3f deg)", rotAngle, rotAngle * 180.0 / Math.PI));
        System.out.println(String.format("    extrusion=(%.3f, %.3f, %.3f) has_attribs=%b",
            extrusion[0], extrusion[1], extrusion[2], hasAttribs));
    }

    static class ObjectInfo {
        long handle;
        long offset;
        int size;
        int type;

        ObjectInfo(long h, long o, int s, int t) {
            handle = h; offset = o; size = s; type = t;
        }
    }
}
