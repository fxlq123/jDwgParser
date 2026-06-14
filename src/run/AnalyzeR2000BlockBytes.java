package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import java.nio.file.Paths;
import java.util.*;

/**
 * 深入分析 R2000 BLOCK (0x31) 定义对象的字节级结构
 * 目标：找出 block_name 字段的确切位置和编码方式
 */
public class AnalyzeR2000BlockBytes {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        // 收集 BLOCK (0x31) handles
        List<Long> blockHandles = new ArrayList<>();
        for (long h : registry.allHandles()) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);
            int objSize = r.readModularShort();
            int type = r.readBitShort();
            if (type == 0x31) blockHandles.add(h);
        }

        Collections.sort(blockHandles);
        System.out.println("=== BLOCK (0x31) 对象总数: " + blockHandles.size() + " ===");
        System.out.println();

        // 分析前 6 个 BLOCK 对象
        for (long h : blockHandles.subList(0, Math.min(6, blockHandles.size()))) {
            long offset = registry.offsetFor(h).orElse(-1L);
            analyzeOneBlock(h, offset);
        }

        // 同时分析 INSERT
        System.out.println("\n=== INSERT (0x07) 对象分析 ===");
        int shown = 0;
        for (long h : registry.allHandles()) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);
            int objSize = r.readModularShort();
            int type = r.readBitShort();
            if (type == 0x07) {
                analyzeOneInsert(h, offset, objSize);
                if (++shown > 2) break;
            }
        }
    }

    private static void analyzeOneBlock(long handle, long offset) {
        System.out.println("--- BLOCK handle=0x" + Long.toHexString(handle) + " @offset=" + offset + " ---");

        // 完整 dump 对象数据（前 48 字节）
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

        int objSize = r.readModularShort();
        int type = r.readBitShort();
        System.out.println("obj_size=" + objSize + ", type=0x" + Integer.toHexString(type));

        // Dump 对象数据字节
        System.out.print("bytes 0-47: ");
        for (int i = 0; i < Math.min(48, objSize); i++) {
            int b = data[(int)offset + i] & 0xFF;
            System.out.printf("%02X ", b);
        }
        System.out.println();
        System.out.print("ascii  :    ");
        for (int i = 0; i < Math.min(48, objSize); i++) {
            int b = data[(int)offset + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();

        // 关键: 假设 bytes[4..] 是 object common data
        // libredwg 格式: object common:
        //   owner_handle (H)
        //   reactors -  num (BS) + H * num
        //   xdict_handle (H)

        // 测试方案 A: 使用标准 readHandle + 后续字段
        // readHandle: first byte code(4bits) + counter(4bits). If counter>0, counter bytes.
        // Block 记录中的 owner_handle 应该是 BLOCK_HEADER 的 handle (0x1)
        try {
            long owner = r.readHandle();
            System.out.println("方案A owner_handle: 0x" + Long.toHexString(owner));

            int numReactors = r.readBitShort();
            System.out.println("方案A num_reactors: " + numReactors);
            if (numReactors > 0 && numReactors < 20) {
                for (int i = 0; i < numReactors; i++) r.readHandle();
            }
            long xdict = r.readHandle();
            System.out.println("方案A xdict_handle: 0x" + Long.toHexString(xdict));

            // 现在 block 字段
            // block_name (TU): BS length + UTF-16LE chars OR (R2000 sometimes BS length + ASCII)
            // flags (BS)
            // base_point (3RD - 3 doubles)
            // xref_path (TU)

            long pos1 = bb.position();
            System.out.println("当前 bit_pos: " + pos1 + " (byte=" + pos1/8 + ", extra=" + (pos1%8) + ")");

            // 尝试 BS length 读取 name
            int nameLen1 = r.readBitShort();
            System.out.println("方案A BS nameLen: " + nameLen1);
            if (nameLen1 > 0 && nameLen1 < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen1; i++) {
                    int b = r.getInput().readBits(8) & 0xFF;
                    sb.append((b >= 32 && b < 127) ? (char)b : '?');
                }
                System.out.println("方案A ASCII name: '" + sb.toString() + "'");
            }
            bb.seek(pos1);

            // 尝试 TU: BS length + UTF-16 chars (每字符 2 字节)
            int nameLen2 = r.readBitShort();
            System.out.println("方案B BS nameLen: " + nameLen2);
            if (nameLen2 > 0 && nameLen2 < 200) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nameLen2; i++) {
                    int lo = r.getInput().readBits(8) & 0xFF;
                    int hi = r.getInput().readBits(8) & 0xFF;
                    int cp = lo | (hi << 8);
                    sb.append((cp >= 32 && cp < 127) ? (char)cp : '?');
                }
                System.out.println("方案B UTF-16 name: '" + sb.toString() + "'");
            }
            bb.seek(pos1);

            // 尝试直接从当前位置读取 H handles (假设 block 是一系列 handle 指向 entities)
            // 也许 BLOCK 对象不像普通对象那样有 object_common 数据
            // 让我试试: bytes 4+ 直接是 block_name
            // 或者: BLOCK 记录 = block_name(TV) + block_end(H) + first_entity(H)?

            System.out.println();
        } catch (Exception e) {
            System.out.println("parse error: " + e.getMessage());
        }
    }

    private static void analyzeOneInsert(long handle, long offset, int objSize) {
        System.out.println("--- INSERT handle=0x" + Long.toHexString(handle) + " @offset=" + offset + " obj_size=" + objSize + " ---");
        System.out.print("bytes 0-47: ");
        for (int i = 0; i < Math.min(48, objSize); i++) {
            int b = data[(int)offset + i] & 0xFF;
            System.out.printf("%02X ", b);
        }
        System.out.println();
        System.out.print("ascii  :    ");
        for (int i = 0; i < Math.min(48, objSize); i++) {
            int b = data[(int)offset + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();

        // INSERT 实体: entity_header + common_entity_data + entity_specific
        // entity_header: bitsize(RL) + ent_handle(H) + EED(loop BS)
        // common_entity_data varies by version
        //   R2000: preview_exists(1bit) + entmode(2bit) + num_reactors(BS) + xdict_handle(H) + ...
        //          layer_handle(H) + linetype_handle(H) + color(BS) + ...
        //   entity specific (INSERT):
        //     block_header_handle(H) + scale(3BD) + rotation(BD) + insertion_pt(3BD) + attribs_follow(BS)
        ByteBufferBitInput bb = new ByteBufferBitInput(data);
        bb.seek(offset * 8L);
        BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);
        int size = r.readModularShort();
        int tc = r.readBitShort();

        try {
            int bitsize = readRawLong(r);
            System.out.println("bitsize(RL): " + bitsize);
            long entHandle = r.readHandle();
            System.out.println("ent_handle: 0x" + Long.toHexString(entHandle));

            // EED loop
            int eedSize = r.readBitShort();
            int count = 0;
            while (eedSize != 0 && eedSize > 0 && eedSize < 0x7FFF && count < 20) {
                long eedHandle = r.readHandle();
                for (int i = 0; i < eedSize; i++) r.getInput().readBits(8);
                eedSize = r.readBitShort();
                count++;
            }
            System.out.println("EED loops: " + count);

            long posAfterHeader = bb.position();
            System.out.println("after header bit_pos: " + posAfterHeader);

            // common entity data
            boolean previewExists = r.getInput().readBit();
            System.out.println("preview_exists: " + previewExists);
            if (previewExists) {
                int ps = readRawLong(r);
                bb.seek(bb.position() + ps * 8L);
                System.out.println("preview size: " + ps);
            }

            int entMode = r.getInput().readBits(2);
            System.out.println("ent_mode: " + entMode);

            int numReactors = r.readBitShort();
            System.out.println("num_reactors: " + numReactors);
            if (numReactors > 0 && numReactors < 20) {
                for (int i = 0; i < numReactors; i++) r.readHandle();
            }

            long xdict = r.readHandle();
            System.out.println("xdict_handle: 0x" + Long.toHexString(xdict));

            // R2000: nolinks? has bit following num_reactors
            // 让我们试跳过 1 bit (nolinks)
            //r.getInput().readBit();

            long layer = r.readHandle();
            System.out.println("layer_handle: 0x" + Long.toHexString(layer));

            long ltype = r.readHandle();
            System.out.println("ltype_handle: 0x" + Long.toHexString(ltype));

            int color = r.readBitShort();
            System.out.println("color: " + color);

            // Now INSERT specific
            long blockHeaderHandle = r.readHandle();
            System.out.println("** block_header_handle: 0x" + Long.toHexString(blockHeaderHandle) + " **");

            double sx = r.readBitDouble();
            double sy = r.readBitDouble();
            double sz = r.readBitDouble();
            System.out.printf("scale: (%.4f, %.4f, %.4f)%n", sx, sy, sz);

            double rot = r.readBitDouble();
            System.out.printf("rotation: %.6f rad (%.4f deg)%n", rot, rot * 180.0 / Math.PI);

            double ix = r.readBitDouble();
            double iy = r.readBitDouble();
            double iz = r.readBitDouble();
            System.out.printf("insertion: (%.4f, %.4f, %.4f)%n", ix, iy, iz);

        } catch (Exception e) {
            System.out.println("insert parse error: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println();
    }

    private static int readRawLong(BitStreamReader r) {
        BitInput input = r.getInput();
        long b0 = input.readBits(8) & 0xFF;
        long b1 = input.readBits(8) & 0xFF;
        long b2 = input.readBits(8) & 0xFF;
        long b3 = input.readBits(8) & 0xFF;
        return (int)(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24));
    }
}
