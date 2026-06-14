package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;

import java.nio.file.Paths;
import java.util.*;

/**
 * R2000 BLOCK_HEADER (0x30) 和 BLOCK (0x31) 字段结构分析
 *
 * BLOCK_HEADER 对象结构 (R2000, 非实体 - 没有 entity header):
 *   obj_size (MS) + type_code (BS, 0x30) + object_common_data + block_header_specific
 *
 * BLOCK 对象结构 (R2000):
 *   obj_size (MS) + type_code (BS, 0x31) + object_common_data + block_specific
 */
public class AnalyzeR2000BlockStructure {

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);

        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        System.out.println("=== R2000 BLOCK 对象结构分析 ===");
        System.out.println("总对象数 (by handles): " + registry.allHandles().size());
        System.out.println();

        // 按 type code 分组: 0x30 = BLOCK_HEADER, 0x31 = BLOCK/ENDBLK
        Map<Integer, List<Long>> byType = groupByType(data, registry);

        for (int typeCode : new int[]{0x30, 0x31}) {
            List<Long> handles = byType.getOrDefault(typeCode, new ArrayList<>());
            System.out.println("类型 0x" + Integer.toHexString(typeCode) + ": " + handles.size() + " 个对象");
        }
        System.out.println();

        // 分析 0x30 (BLOCK_HEADER)
        List<Long> bhList = byType.getOrDefault(0x30, new ArrayList<>());
        if (!bhList.isEmpty()) {
            System.out.println("========== BLOCK_HEADER (0x30) 详细分析 ==========");
            analyzeBlockHeader(data, registry, bhList);
        }

        // 分析 0x31 (BLOCK) - 前几个
        List<Long> blockList = byType.getOrDefault(0x31, new ArrayList<>());
        if (!blockList.isEmpty()) {
            System.out.println("\n========== BLOCK (0x31) 详细分析 ==========");
            int limit = Math.min(5, blockList.size());
            analyzeBlockObjects(data, registry, blockList.subList(0, limit));
        }

        // INSERT 实体
        List<Long> insertList = byType.getOrDefault(0x07, new ArrayList<>());
        if (!insertList.isEmpty()) {
            System.out.println("\n========== INSERT (0x07) 详细分析 ==========");
            int limit = Math.min(3, insertList.size());
            analyzeInsertEntities(data, registry, insertList.subList(0, limit));
        }
    }

    private static Map<Integer, List<Long>> groupByType(byte[] data, HandleRegistry registry) {
        Map<Integer, List<Long>> result = new HashMap<>();
        for (long handle : registry.allHandles()) {
            long offset = registry.offsetFor(handle).orElse(-1L);
            if (offset < 0 || offset >= data.length - 6) continue;

            try {
                ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                bbuf.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
                r.readModularShort(); // obj_size
                int type = r.readBitShort();
                result.computeIfAbsent(type, k -> new ArrayList<>()).add(handle);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static void analyzeBlockHeader(byte[] data, HandleRegistry registry, List<Long> handles) {
        for (long h : handles) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;

            System.out.println("\n[BLOCK_HEADER handle=0x" + Long.toHexString(h) + " @offset=" + offset + "]");

            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();
            System.out.println("  obj_size: " + objSize + " bytes");
            System.out.println("  type_code: 0x" + Integer.toHexString(typeCode));
            System.out.println("  start_bit_pos: " + bbuf.position());

            // 尝试几种解析方式，看哪种能得到合理的 block_name
            // 方案 A: 直接读取 object_common_data + block_name
            // 方案 B: R2000 非实体对象只有 owner_handle + xdict_handle 没有 entity 数据

            System.out.println("  ---- 尝试方案 A (object_common + block_name) ----");
            long savePos = bbuf.position();

            // Object non-entity header: owner_handle (H) + num_reactors (BS) + reactors + xdict_handle (H)
            try {
                long ownerHandle = readHandle(r);
                System.out.println("  owner_handle: 0x" + Long.toHexString(ownerHandle));

                int numReactors = r.readBitShort();
                System.out.println("  num_reactors: " + numReactors);
                if (numReactors > 0 && numReactors < 20) {
                    for (int i = 0; i < numReactors; i++) {
                        readHandle(r); // skip
                    }
                }

                long xdictHandle = readHandle(r);
                System.out.println("  xdict_handle: 0x" + Long.toHexString(xdictHandle));

                // 现在开始 block-specific: block_name (TU)
                try {
                    String name = r.readVariableText();
                    System.out.println("  block_name: '" + name + "'");

                    int flags = r.readBitShort();
                    System.out.println("  flags: 0x" + Integer.toHexString(flags));

                    double[] pt = r.read3RawDouble();
                    System.out.printf("  base_point: (%.4f, %.4f, %.4f)%n", pt[0], pt[1], pt[2]);

                    String xref = r.readVariableText();
                    System.out.println("  xref_path: '" + xref + "'");
                } catch (Exception e) {
                    System.out.println("  block-specific parse failed: " + e.getMessage());
                }
            } catch (Exception e) {
                System.out.println("  common parse failed: " + e.getMessage());
            }

            // 方案 C: 不读取 common，直接从 type_code 后读取 block_name
            bbuf.seek(savePos);
            System.out.println("  ---- 尝试方案 C (直接 block_name) ----");
            try {
                String name = r.readVariableText();
                System.out.println("  direct block_name: '" + name + "'");
            } catch (Exception e) {
                System.out.println("  failed: " + e.getMessage());
            }

            // 方案 D: 十六进制 dump
            System.out.println("  ---- 原始字节 (从 type_code 后开始) ----");
            int byteStart = (int)(bbuf.position() / 8);
            int showLen = Math.min(64, (int)objSize);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < showLen && byteStart + i < data.length; i++) {
                sb.append(String.format("%02X ", data[byteStart + i] & 0xFF));
            }
            System.out.println("  hex: " + sb.toString());

            // 可打印 ASCII
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < showLen && byteStart + i < data.length; i++) {
                int b = data[byteStart + i] & 0xFF;
                ascii.append((b >= 32 && b < 127) ? (char)b : '.');
            }
            System.out.println("  txt: " + ascii.toString());
        }
    }

    private static void analyzeBlockObjects(byte[] data, HandleRegistry registry, List<Long> handles) {
        for (long h : handles) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;

            System.out.println("\n[BLOCK handle=0x" + Long.toHexString(h) + " @offset=" + offset + "]");

            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();
            System.out.println("  obj_size: " + objSize + " bytes");
            System.out.println("  type_code: 0x" + Integer.toHexString(typeCode));

            // 尝试直接读取 object common (owner, reactors, xdict) + block_name
            try {
                long ownerHandle = readHandle(r);
                int numReactors = r.readBitShort();
                if (numReactors > 0 && numReactors < 20) {
                    for (int i = 0; i < numReactors; i++) readHandle(r);
                }
                long xdictHandle = readHandle(r);
                System.out.println("  owner: 0x" + Long.toHexString(ownerHandle) +
                    ", reactors: " + numReactors +
                    ", xdict: 0x" + Long.toHexString(xdictHandle));

                String name = r.readVariableText();
                System.out.println("  block_name: '" + name + "'");

                int flags = r.readBitShort();
                System.out.println("  flags: 0x" + Integer.toHexString(flags));

                double[] pt = r.read3RawDouble();
                System.out.printf("  base_point: (%.4f, %.4f, %.4f)%n", pt[0], pt[1], pt[2]);
            } catch (Exception e) {
                System.out.println("  parse failed: " + e.getMessage());
            }

            // 原始字节
            int byteStart = (int)(bbuf.position() / 8);
            int showLen = Math.min(48, (int)objSize);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < showLen && byteStart + i < data.length; i++) {
                sb.append(String.format("%02X ", data[byteStart + i] & 0xFF));
            }
            System.out.println("  hex: " + sb.toString());
        }
    }

    private static void analyzeInsertEntities(byte[] data, HandleRegistry registry, List<Long> handles) {
        for (long h : handles) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;

            System.out.println("\n[INSERT handle=0x" + Long.toHexString(h) + " @offset=" + offset + "]");

            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);

            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();
            System.out.println("  obj_size: " + objSize + " bytes, type_code: 0x" + Integer.toHexString(typeCode));

            // INSERT 是实体: 需要 entity header + common_entity_data
            // entity header for R2000: bitsize(RL) + obj_handle(H) + EED_loop
            // common_entity_data: preview_exists + bitsize(RL) + entmode(BB) + num_reactors(BL)
            //   + color(BS) + ltype_scale(BD) + ltype_flags(BB) + plotstyle_flags(BB)
            //   + invisible(BS) + linewt(RC) + layer_handle(H) + ltype_handle(H)
            // block_header_handle(H) + scale(3BD) + rotation(BD) + ins_pt(3BD) + attribs_follow(BS)
            try {
                // Entity Header
                readRawLong(r); // bitsize
                long entHandle = readHandle(r);
                System.out.println("  entity_handle: 0x" + Long.toHexString(entHandle));

                // EED loop
                int eedSize;
                do {
                    eedSize = r.readBitShort();
                    if (eedSize > 0 && eedSize < 0x7FFF) {
                        readHandle(r); // appid handle
                        for (int i = 0; i < eedSize; i++) r.getInput().readBits(8);
                    } else break;
                } while (eedSize != 0);

                // Common Entity Data
                boolean previewExists = r.getInput().readBit();
                if (previewExists) {
                    long previewSize = readRawLong(r);
                    r.getInput().seek(r.getInput().position() + previewSize * 8L);
                }
                // bitsize for non-preview (R13) - skip if in wrong format
                // Actually in R2000 there's no bitsize after preview in common data

                // entmode (BB=2bits)
                r.getInput().readBits(2);
                // num_reactors (BL)
                int numReactors = r.readBitLong();
                if (numReactors > 0 && numReactors < 20) {
                    for (int i = 0; i < numReactors; i++) readHandle(r);
                }

                // is_xdic_missing
                // For R2000, no is_xdic_missing bit (that's R2004+).
                // R2000 has: nolinks (B) - but actually spec says R2000 is between 13 and 2004
                // Let's check:
                // common_entity_data R13: preview_exists, bitsize, entmode, num_reactors, isbylayerlt
                // R2000: probably similar to R13 but no isbylayerlt

                // Let's try a different approach - just read key fields
                // Skip rest of common_entity_data by reading layer_handle
                // Actually let's try to find block_header_handle by reading handle candidates

                // color (BS, 16bit) - for R2000 it's CMC BS
                int color = r.readBitShort();
                System.out.println("  color: 0x" + Integer.toHexString(color));

                // ltype_scale (BD)
                double ltypeScale = r.readBitDouble();
                System.out.printf("  ltype_scale: %.4f%n", ltypeScale);

                // ltype_flags + plotstyle_flags (BB each)
                r.getInput().readBits(2);
                r.getInput().readBits(2);

                // invisible (BS)
                int invisible = r.readBitShort();
                System.out.println("  invisible: " + invisible);

                // linewt (RC)
                r.getInput().readBits(8);

                // layer handle (H)
                long layerHandle = readHandle(r);
                System.out.println("  layer_handle: 0x" + Long.toHexString(layerHandle));

                // ltype handle (H)
                long ltypeHandle = readHandle(r);
                System.out.println("  ltype_handle: 0x" + Long.toHexString(ltypeHandle));

                // block_header_handle (H) - key!
                long blockHeaderHandle = readHandle(r);
                System.out.println("  ** block_header_handle: 0x" + Long.toHexString(blockHeaderHandle));

                // scale (3BD)
                double sx = r.readBitDouble();
                double sy = r.readBitDouble();
                double sz = r.readBitDouble();
                System.out.printf("  scale: (%.4f, %.4f, %.4f)%n", sx, sy, sz);

                // rotation (BD)
                double rot = r.readBitDouble();
                System.out.printf("  rotation: %.4f rad (%.4f deg)%n", rot, rot * 180.0 / Math.PI);

                // ins_pt (3BD)
                double ix = r.readBitDouble();
                double iy = r.readBitDouble();
                double iz = r.readBitDouble();
                System.out.printf("  insertion: (%.4f, %.4f, %.4f)%n", ix, iy, iz);

            } catch (Exception e) {
                System.out.println("  parse failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static long readHandle(BitStreamReader r) {
        BitInput input = r.getInput();
        int firstByte = input.readBits(8) & 0xFF;
        int code = firstByte >> 4;
        int count = firstByte & 0x0F;
        long handle = 0;
        for (int i = 0; i < count; i++) {
            handle = (handle << 8) | (input.readBits(8) & 0xFF);
        }
        return handle;
    }

    private static long readRawLong(BitStreamReader r) {
        BitInput input = r.getInput();
        long b0 = input.readBits(8) & 0xFF;
        long b1 = input.readBits(8) & 0xFF;
        long b2 = input.readBits(8) & 0xFF;
        long b3 = input.readBits(8) & 0xFF;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }
}
