package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.sections.handles.*;
import java.nio.file.Paths;
import java.util.*;

/**
 * 深度分析 R2000 对象格式，找出正确的字段位置
 * 方法: 对已知对象做逐位/逐字节的渐进读取测试
 *
 * R2000 对象格式 (根据 libredwg/src/dwg.spec):
 *
 * OBJECT (非实体):
 *   object_size (MS)
 *   type (BS)
 *   common:
 *     num_reactors (BS)
 *     handles (H) [仅当 num_reactors > 0]
 *     xdict_handle (H)
 *     owner_handle (H)  -- 注意: 顺序可能不同!
 *
 * ENTITY (实体):
 *   object_size (MS)
 *   type (BS)
 *   entity_header:
 *     bitsize (RL)
 *     entity_handle (H)
 *     EED_size (BS)
 *   entity_common (R2000):
 *     preview_exists (1)
 *     ent_mode (2)
 *     num_reactors (BS)
 *     [reactors (H * num)]
 *     xdict_handle (H)
 *     layer_handle (H)
 *     prev_entity_handle (H)
 *     next_entity_handle (H)
 *     color (BS)
 *     ltype_scale (BD)
 *     ltype_flags + plotstyle_flags (2+2)
 *     invisible (BS)
 *     linewt (RC=8)
 */
public class R2000DeepAnalyze {

    static byte[] data;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        io.dwg.format.common.DwgFileStructureHandler handler = io.dwg.format.common.DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        io.dwg.format.common.FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        java.util.Map<String, SectionInputStream> sections = handler.readSections(input, header);
        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        // 找到一些典型对象
        // 1. BLOCK_HEADER @ offset 21177 - OBJECT type
        // 2. BLOCK @ 23203 - ENTITY? 或 object?
        // 3. INSERT @ 50409?
        //
        // 之前 AnalyzeR2000 的观察:
        // BLOCK_HEADER @21177: bytes 0-3 = 72 00 4C 12, size=114, type=0x30
        // BLOCK @23203: bytes 0-3 = 25 00 4C 71, size=37, type=0x31

        System.out.println("=== 1. BLOCK_HEADER @ 21177 (OBJECT, size=114, type=0x30) ===");
        analyzeObject(21177, true);

        System.out.println("\n=== 2. BLOCK @ 23203 (size=37, type=0x31) ===");
        analyzeObject(23203, false);

        System.out.println("\n=== 3. BLOCK @ 21677 (size=38, type=0x31) -- 有 ASCII 名字 ===");
        analyzeObject(21677, false);

        System.out.println("\n=== 4. INSERT @ 50409 (size=38, type=0x07) ===");
        analyzeObject(50409, false);
    }

    private static void analyzeObject(int offset, boolean isObjectHeader) throws Exception {
        // Print hex dump
        int size = (data[offset] & 0xFF) | ((data[offset+1] & 0xFF) << 8);
        size = size & 0x7FFF;  // MS = value & 0x7FFF
        System.out.println("Object size (MS): " + size + " bytes");
        System.out.println("Type bytes (BS opcode area): " + String.format("%02X %02X", data[offset+2] & 0xFF, data[offset+3] & 0xFF));

        System.out.print("HEX: ");
        for (int i = 0; i < Math.min(size, 64); i++) {
            System.out.print(String.format("%02X ", data[offset + i] & 0xFF));
        }
        System.out.println();

        System.out.print("ASC: ");
        for (int i = 0; i < Math.min(size, 64); i++) {
            int b = data[offset + i] & 0xFF;
            System.out.print((b >= 32 && b < 127) ? (char)b : '.');
        }
        System.out.println();

        // 渐进读取测试 - 多种可能格式
        // 格式A: obj_size(MS 2 bytes) + type_code(BS 10 bits) + data from bit 26
        // 格式B: obj_size(MS 2 bytes) + type_code(BS 10 bits) + pad to byte (6 bits) + data from bit 32
        // 格式C: obj_size(MS 2 bytes) + type_code(BS 10 bits) + RL bitsize from bit 26
        // ...

        // 测试多种起始位置读取 H (handle)
        System.out.println("\n  测试不同位置读取 Handle (H = code + counter + data bytes):");
        for (int startBit = 16; startBit < 60; startBit += 2) {
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek((long)offset * 8 + startBit);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);
            try {
                long h = r.readHandle();
                if (h > 0 && h < 0xFFFFL) {  // 小值，合理的 handle
                    System.out.println("  bit" + startBit + " -> H = 0x" + Long.toHexString(h));
                }
            } catch (Exception ignored) {}
        }

        // 测试不同位置读取 BS (BitShort)
        System.out.println("\n  测试不同位置读取 BS (小值可能是 flags/num_reactors/length):");
        for (int startBit = 16; startBit < 96; startBit += 2) {
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek((long)offset * 8 + startBit);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);
            try {
                int bs = r.readBitShort();
                if (bs >= 0 && bs < 128) {
                    System.out.println("  bit" + startBit + " -> BS = " + bs);
                }
            } catch (Exception ignored) {}
        }

        // 从字节 4 开始的字节级读取
        System.out.println("\n  从字节 4 开始的字节级结构:");
        for (int b = 4; b < Math.min(size, 20); b++) {
            int v = data[offset + b] & 0xFF;
            System.out.println("  byte" + b + ": 0x" + Integer.toHexString(v)
                + " = " + String.format("%8s", Integer.toBinaryString(v)).replace(' ', '0')
                + " = ASCII '" + (v >= 32 && v < 127 ? (char)v : '.') + "'");
        }

        // 完整的 object common 和 block 特定字段测试
        System.out.println("\n  测试: owner_handle(H) + num_reactors(BS) + xdict(H) + block_name(TV)...");
        for (int startBit : new int[]{16, 18, 20, 24, 26, 32, 40, 48}) {
            try {
                ByteBufferBitInput bb = new ByteBufferBitInput(data);
                bb.seek((long)offset * 8 + startBit);
                BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

                long owner = r.readHandle();
                int numR = r.readBitShort();
                long xdict = 0;
                String name = null;
                try {
                    // skip reactors if any
                    if (numR > 0 && numR < 50) {
                        for (int i = 0; i < numR; i++) r.readHandle();
                    }
                    xdict = r.readHandle();
                    int nameLen = r.readBitShort();
                    if (nameLen > 0 && nameLen < 100) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < nameLen; i++) {
                            int c = r.getInput().readBits(8) & 0xFF;
                            sb.append((c >= 32 && c < 127) ? (char)c : '?');
                        }
                        name = sb.toString();
                    }
                } catch (Exception ignored) {}

                if (owner > 0 && owner < 0xFFFF || (name != null && !name.isEmpty())) {
                    System.out.println("  bit" + startBit
                        + ": owner=0x" + Long.toHexString(owner)
                        + " numR=" + numR
                        + " xdict=0x" + Long.toHexString(xdict)
                        + " name='" + (name == null ? "" : name) + "'");
                }
            } catch (Exception ignored) {}
        }

        // 测试 entity header 格式 (RL bitsize + H entity_handle + BS eed_size)
        System.out.println("\n  测试 entity header: RL bitsize + H entity_handle + BS EED...");
        for (int startBit : new int[]{16, 18, 20, 24, 26, 32}) {
            try {
                ByteBufferBitInput bb = new ByteBufferBitInput(data);
                bb.seek((long)offset * 8 + startBit);
                BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);

                int bitsize = 0;
                for (int i = 0; i < 4; i++) {
                    bitsize |= (r.getInput().readBits(8) & 0xFF) << (i * 8);
                }
                long entH = r.readHandle();
                int eedSize = r.readBitShort();

                if (bitsize > 0 && bitsize < 1000 && entH > 0 && entH < 0xFFFFL) {
                    System.out.println("  bit" + startBit
                        + ": bitsize=" + bitsize
                        + " entH=0x" + Long.toHexString(entH)
                        + " eed=" + eedSize);

                    // 继续测试 entity common: preview(1) + ent_mode(2) + numR(BS)
                    boolean preview = (r.getInput().readBits(1) & 1) == 1;
                    int entMode = r.getInput().readBits(2) & 3;
                    int nR = r.readBitShort();
                    System.out.println("    ... preview=" + preview + " entMode=" + entMode + " numR=" + nR);
                }
            } catch (Exception ignored) {}
        }
    }
}
